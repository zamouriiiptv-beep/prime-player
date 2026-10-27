package com.castivio.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.domain.CatalogPager
import com.castivio.domain.ContinueTarget
import com.castivio.domain.EpisodeMark
import com.castivio.domain.EpisodesLoad
import com.castivio.domain.ProgressRepository
import com.castivio.domain.Season
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
) : ViewModel() {

    private val shown = MutableStateFlow<String?>(null)
    private val load = MutableStateFlow(ShowLoad.Loading)

    /** Bumped to read what the viewer has watched again: the player has been on top of the page since. */
    private val rereads = MutableStateFlow(0)

    private val seasons = shown.filterNotNull().flatMapLatest { pager.seasons(it) }

    val state: StateFlow<ShowState> = combine(seasons, load, rereads) { held, loadState, _ -> held to loadState }
        .mapLatest { (held, loadState) ->
            val positions = progress.progressOf(held.flatMap { it.episodes }.map { it.id })
            ShowState(
                seasons = held,
                // Nothing is waited for once something is held, whatever the provider is doing behind it.
                load = if (held.isNotEmpty()) ShowLoad.Ready else loadState,
                marks = positions.mapValues { (_, position) -> episodeMark(position) },
                target = continueTarget(held, positions),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), ShowState())

    /** Opens [seriesId]'s page: asks its provider for the episodes, once for each show shown. */
    fun open(seriesId: String) {
        if (shown.value == seriesId) return
        shown.value = seriesId
        load.value = ShowLoad.Loading
        fetch(seriesId)
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
