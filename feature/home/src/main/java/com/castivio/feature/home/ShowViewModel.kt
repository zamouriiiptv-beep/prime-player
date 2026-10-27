package com.castivio.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.domain.CatalogPager
import com.castivio.domain.ContinueTarget
import com.castivio.domain.EpisodeMark
import com.castivio.domain.EpisodesLoad
import com.castivio.domain.FavoritesRepository
import com.castivio.domain.ProgressRepository
import com.castivio.domain.Season
import com.castivio.domain.SeriesDetails
import com.castivio.domain.SeriesDetailsStore
import com.castivio.domain.SeriesEpisodes
import com.castivio.domain.continueTarget
import com.castivio.domain.episodeMark
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How asking the provider for the show's episodes stands, as the page says it. */
enum class ShowLoad {
    /** The episodes are being asked for, and none is held yet. */
    Loading,

    /** The page has what there is to show: the episodes held, or none because the provider lists none. */
    Ready,

    /** The provider could not be asked, and no episode is held to show instead. */
    Failed,
}

/** What the page draws for one show. */
data class ShowState(
    val seasons: List<Season> = emptyList(),
    val load: ShowLoad = ShowLoad.Loading,
    /** What each episode carries -- watched, part-watched -- by episode id. */
    val marks: Map<String, EpisodeMark> = emptyMap(),
    /** The episode Play starts; null while the show has none. */
    val target: ContinueTarget? = null,
    /**
     * What the provider said of the show with its episodes; null while that is on its way, and for good where
     * the provider said nothing. The page draws what is here and nothing else.
     */
    val details: SeriesDetails? = null,
    /** The plot of each episode that has one, by the episode's id. */
    val plots: Map<String, String> = emptyMap(),
    /** Whether the viewer has marked the show as a favourite. */
    val favorite: Boolean = false,
)

/**
 * One show's page: its seasons as the catalogue holds them, the episodes asked of its provider when the page
 * opens, and what the viewer has watched of them.
 *
 * The episodes already held are shown at once and the provider is asked again behind them, because a show that
 * is still running has new ones; a failure of that second asking costs the page nothing. Only a show with no
 * episode held waits on the provider, and says so.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ShowViewModel @Inject constructor(
    private val pager: CatalogPager,
    private val loader: SeriesEpisodes,
    private val progress: ProgressRepository,
    private val details: SeriesDetailsStore,
    private val favorites: FavoritesRepository,
) : ViewModel() {

    private val shown = MutableStateFlow<String?>(null)
    private val load = MutableStateFlow(ShowLoad.Loading)

    /** Bumped to read what the viewer has watched again: the player has been on top of the page since. */
    private val rereads = MutableStateFlow(0)

    private val seasons = shown.filterNotNull().flatMapLatest { pager.seasons(it) }

    /** What the provider said of the show, and of its episodes: read as it is written, after the page is up. */
    private val said = shown.filterNotNull().flatMapLatest { details.details(it) }
    private val episodePlots = shown.filterNotNull().flatMapLatest { details.episodePlots(it) }

    /** The show's own row, which a favourite of the show is kept under; null until it has been looked up. */
    private val rowId = MutableStateFlow<String?>(null)
    private val favorite = rowId.flatMapLatest { id -> if (id == null) flowOf(false) else favorites.isFavorite(id) }

    private val content = combine(seasons, load, rereads, said, episodePlots) { held, loadState, _, info, plots ->
        Held(held, loadState, info, plots)
    }
        .mapLatest { (held, loadState, info, plots) ->
            val positions = progress.progressOf(held.flatMap { it.episodes }.map { it.id })
            ShowState(
                seasons = held,
                // Nothing is waited for once something is held, whatever the provider is doing behind it.
                load = if (held.isNotEmpty()) ShowLoad.Ready else loadState,
                marks = positions.mapValues { (_, position) -> episodeMark(position) },
                target = continueTarget(held, positions),
                details = info,
                plots = plots,
            )
        }

    val state: StateFlow<ShowState> = combine(content, favorite) { shownState, marked -> shownState.copy(favorite = marked) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), ShowState())

    private data class Held(
        val seasons: List<Season>,
        val load: ShowLoad,
        val details: SeriesDetails?,
        val plots: Map<String, String>,
    )

    /** Opens [seriesId]'s page: asks its provider for the episodes, once for each show shown. */
    fun open(seriesId: String) {
        if (shown.value == seriesId) return
        shown.value = seriesId
        load.value = ShowLoad.Loading
        rowId.value = null
        viewModelScope.launch {
            val row = try {
                pager.seriesShell(seriesId)?.mediaId
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (shown.value == seriesId) rowId.value = row
        }
        fetch(seriesId)
    }

    /** Marks the show as a favourite, or takes the mark away. A show with no row of its own cannot be marked. */
    fun toggleFavorite() {
        val id = rowId.value ?: return
        viewModelScope.launch {
            try {
                favorites.toggle(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not marked: the heart stays as it was, which is what the page shows.
            }
        }
    }

    /** Asks the provider again, after a failure the viewer chose to retry. */
    fun retry() {
        val id = shown.value ?: return
        load.value = ShowLoad.Loading
        fetch(id)
    }

    /** Reads again what the viewer has watched. */
    fun reread() {
        rereads.value += 1
    }

    /** An episode was started from the page: it is the one Play continues from next time. */
    fun started(episodeId: String) {
        viewModelScope.launch {
            try {
                progress.watched(episodeId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not recording it costs the page nothing but where Play continues from.
            }
        }
    }

    private fun fetch(seriesId: String) {
        viewModelScope.launch {
            val answer = try {
                loader.load(seriesId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EpisodesLoad.Failed(e)
            }
            if (shown.value != seriesId) return@launch
            load.value = if (answer is EpisodesLoad.Failed) ShowLoad.Failed else ShowLoad.Ready
        }
    }

    private companion object {
        /** Long enough that turning the screen does not ask again. */
        const val SUBSCRIPTION_GRACE_MS = 5_000L
    }
}
