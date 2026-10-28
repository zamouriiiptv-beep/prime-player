package com.castivio.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.domain.CatalogPager
import com.castivio.domain.ContinueTarget
import com.castivio.domain.EpisodeMark
import com.castivio.domain.EpisodesLoad
import com.castivio.domain.EpisodesReady
import com.castivio.domain.FavoritesRepository
import com.castivio.domain.ProgressRepository
import com.castivio.domain.RequestTimes
import com.castivio.domain.Season
import com.castivio.domain.SeriesDetails
import com.castivio.domain.SeriesDetailsStore
import com.castivio.domain.SeriesEpisodes
import com.castivio.domain.SeriesSummary
import com.castivio.domain.continueTarget
import com.castivio.domain.episodeMark
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
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
    /**
     * The show this state is of, null before any is opened. A page is given the state of whichever show the model
     * held last, and draws it only when it is its own: a show opened after another starts from nothing, not from the
     * other's episodes.
     */
    val seriesId: String? = null,
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
    /** Other shows of the same category, for the page to offer; empty where there are none. */
    val similar: List<SeriesSummary> = emptyList(),
    /**
     * The provider is being asked for the show's episodes and what it says of them, so whatever is not here yet may
     * still come: the page keeps quiet placeholders for it until this is false, and then draws only what there is.
     */
    val asking: Boolean = false,
    /**
     * Why the provider could not be asked, as one line safe to print -- see `failureReason`. The page prints it in a
     * debuggable build only: a viewer is told the episodes could not be loaded, and a tester is told why.
     */
    val failure: String? = null,
    /**
     * The provider answered with no episode and is being asked again. A provider that has not built a show yet answers
     * empty the first time and fully a moment later, so the page says it is being prepared and does not give up.
     */
    val preparing: Boolean = false,
    /** What the last request cost, for a debuggable build to print: where the wait went. */
    val times: RequestTimes? = null,
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
    private val preparing = MutableStateFlow(false)
    private val times = MutableStateFlow<RequestTimes?>(null)

    /** The asking that is under way, so that opening another show -- or asking again -- ends it. */
    private var fetching: Job? = null

    /** Whether the last asking ended with nothing to show, so that opening the same show again asks once more. */
    private var needsAsking = false

    /** Bumped to read what the viewer has watched again: the player has been on top of the page since. */
    private val rereads = MutableStateFlow(0)

    /** A value read for one show, so that what was read for the show before it is never taken for this one's. */
    private data class Of<T>(val seriesId: String, val value: T)

    /**
     * What the provider sent of the show the moment it was read -- before the catalogue had kept it -- and for which show.
     * The page draws it until the catalogue has the episodes to give back, so it does not wait for them to be written
     * and read again.
     */
    private val fresh = MutableStateFlow<Of<EpisodesReady>?>(null)

    /** When the page was opened, and when it first had something to draw, in nanoseconds on the monotonic clock. */
    @Volatile private var openedAt = 0L
    @Volatile private var shownAfterMs = 0L

    // Each read starts with nothing for the show, so the page's state is of the show from the moment it is opened and not
    // from the moment the catalogue first answers -- which is what a page waiting on what the provider sent needs.
    private val seasons = shown.filterNotNull().flatMapLatest { id ->
        pager.seasons(id).map { Of(id, it) }.onStart { emit(Of(id, emptyList())) }
    }

    /** What the provider said of the show, and of its episodes: read as it is written, after the page is up. */
    private val said = shown.filterNotNull().flatMapLatest { id ->
        details.details(id).map { Of(id, it) }.onStart { emit(Of(id, null)) }
    }
    private val episodePlots = shown.filterNotNull().flatMapLatest { id ->
        details.episodePlots(id).map { Of(id, it) }.onStart { emit(Of(id, emptyMap())) }
    }

    /** The show's own row, which a favourite of the show is kept under; null until it has been looked up. */
    private val rowId = MutableStateFlow<String?>(null)
    private val favorite = rowId.flatMapLatest { id -> if (id == null) flowOf(false) else favorites.isFavorite(id) }

    private val similar = MutableStateFlow<List<SeriesSummary>>(emptyList())

    /** Why the last asking failed; null while it has not, and again once another asking is begun. */
    private val failure = MutableStateFlow<String?>(null)

    private val read = combine(seasons, said, episodePlots, fresh) { held, info, plots, early ->
        val sent = early?.takeIf { it.seriesId == held.seriesId }?.value
        Held(
            seriesId = held.seriesId,
            // What the catalogue holds is the page's; until it holds the episodes, what the provider just sent is.
            seasons = held.value.ifEmpty { sent?.seasons.orEmpty() },
            load = ShowLoad.Loading,
            details = info.takeIf { it.seriesId == held.seriesId }?.value ?: sent?.details,
            plots = plots.takeIf { it.seriesId == held.seriesId }?.value.orEmpty().ifEmpty { sent?.plots.orEmpty() },
        )
    }

    private val content = combine(read, load, rereads) { held, loadState, _ -> held.copy(load = loadState) }
        .mapLatest { held ->
            val positions = progress.progressOf(held.seasons.flatMap { it.episodes }.map { it.id })
            ShowState(
                seriesId = held.seriesId,
                seasons = held.seasons,
                // Nothing is waited for once something is held, whatever the provider is doing behind it.
                load = if (held.seasons.isNotEmpty()) ShowLoad.Ready else held.load,
                marks = positions.mapValues { (_, position) -> episodeMark(position) },
                target = continueTarget(held.seasons, positions),
                details = held.details,
                plots = held.plots,
                asking = held.load == ShowLoad.Loading,
            )
        }

    private val extras = combine(favorite, similar, failure, preparing, times) { marked, others, why, again, cost ->
        Extras(marked, others, why, again, cost)
    }

    val state: StateFlow<ShowState> = combine(content, extras) { shownState, extra ->
        shownState.copy(
            favorite = extra.favorite,
            similar = extra.similar,
            failure = extra.failure,
            preparing = extra.preparing,
            times = extra.times,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), ShowState())

    private class Extras(
        val favorite: Boolean,
        val similar: List<SeriesSummary>,
        val failure: String?,
        val preparing: Boolean,
        val times: RequestTimes?,
    )

    private data class Held(
        val seriesId: String,
        val seasons: List<Season>,
        val load: ShowLoad,
        val details: SeriesDetails?,
        val plots: Map<String, String>,
    )

    /** Opens [seriesId]'s page: asks its provider for the episodes, once for each show shown. */
    fun open(seriesId: String) {
        if (shown.value == seriesId && !needsAsking) return
        fetching?.cancel()
        openedAt = System.nanoTime()
        shownAfterMs = 0L
        fresh.value = null
        shown.value = seriesId
        load.value = ShowLoad.Loading
        preparing.value = false
        times.value = null
        rowId.value = null
        similar.value = emptyList()
        failure.value = null
        viewModelScope.launch {
            val row = try {
                pager.seriesShell(seriesId)?.mediaId
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (shown.value == seriesId) rowId.value = row
            val others = try {
                pager.similarTo(seriesId, SIMILAR_LIMIT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (shown.value == seriesId) similar.value = others
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
        failure.value = null
        preparing.value = false
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

    /**
     * Asks the provider, and believes its answer unless it is empty for a show that holds nothing: a provider that has
     * not yet built a show answers with no episode the first time and with all of them a moment later, so an empty
     * answer is asked again after a few seconds -- and the page says it is being prepared -- before the page says the
     * show has none.
     */
    private fun fetch(seriesId: String) {
        fetching?.cancel()
        needsAsking = false
        fetching = viewModelScope.launch {
            var tries = 0
            while (true) {
                tries++
                val answer = try {
                    loader.load(seriesId) { sent ->
                        // Read, and not yet kept: the page draws it now, and the figures say when that was.
                        if (shown.value == seriesId) {
                            if (shownAfterMs == 0L) {
                                shownAfterMs = elapsedMs()
                                times.value = (times.value ?: RequestTimes(0, 0)).copy(shownMs = shownAfterMs)
                            }
                            fresh.value = Of(seriesId, sent)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    EpisodesLoad.Failed(e)
                }
                if (shown.value != seriesId) return@launch
                val loaded = answer as? EpisodesLoad.Loaded
                loaded?.times?.let { times.value = it.copy(tries = tries, shownMs = shownAfterMs, totalMs = elapsedMs()) }
                failure.value = (answer as? EpisodesLoad.Failed)?.reason
                val empty = loaded != null && loaded.episodes == 0 && !holdsEpisodes(seriesId)
                if (empty && tries <= EMPTY_RETRY_DELAYS_MS.size) {
                    preparing.value = true
                    delay(EMPTY_RETRY_DELAYS_MS[tries - 1])
                    if (shown.value != seriesId) return@launch
                    continue
                }
                preparing.value = false
                needsAsking = empty || answer is EpisodesLoad.Failed
                load.value = if (answer is EpisodesLoad.Failed) ShowLoad.Failed else ShowLoad.Ready
                return@launch
            }
        }
    }

    /** Milliseconds since the page was opened. */
    private fun elapsedMs(): Long = (System.nanoTime() - openedAt) / NANOS_PER_MS

    /** Whether the catalogue already holds episodes of the show: an empty answer behind them costs the page nothing. */
    private suspend fun holdsEpisodes(seriesId: String): Boolean = try {
        pager.seasons(seriesId).first().isNotEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L

        /** Long enough that turning the screen does not ask again. */
        const val SUBSCRIPTION_GRACE_MS = 5_000L

        /**
         * How long an empty answer is left before the provider is asked again, one wait for each asking: two, four and
         * seven seconds, and the page believes the fourth answer. Spaced out because the provider is likely still
         * building the show, and asking it harder would not hurry it.
         */
        val EMPTY_RETRY_DELAYS_MS = longArrayOf(2_000, 4_000, 7_000)

        /** How many other shows of the category the page offers: a row, not a wall. */
        const val SIMILAR_LIMIT = 12
    }
}
