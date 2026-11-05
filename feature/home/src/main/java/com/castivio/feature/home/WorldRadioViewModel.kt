package com.castivio.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.castivio.core.common.AppDispatchers
import com.castivio.core.common.PlayOutcome
import com.castivio.domain.DirectoryResult
import com.castivio.domain.NetworkTroubleGuard
import com.castivio.domain.StationHealth
import com.castivio.domain.Standing
import com.castivio.domain.WorldListing
import com.castivio.domain.WorldRadioDirectory
import com.castivio.domain.WorldRadioLibrary
import com.castivio.domain.WorldStation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

/**
 * The world radio's board: which list is open, what was typed, the rail's countries and tags, and the stations -- all of it read from the
 * directory and from what the viewer keeps of it, and none of it from the subscription.
 *
 * ## The lists
 *
 * The top, a country and a tag are pages of the directory, read as the viewer scrolls and at most [MAX_IN_MEMORY] rows held at once, as the
 * catalogue's own lists are ([WorldStationsSource]). The favourites and the stations listened to last are the viewer's own handful and are
 * held whole, from the world radio's own store. Typing in the band's field searches the directory by name over whichever list is open,
 * after [SEARCH_DEBOUNCE_MS] without a key, and a query that a later key replaced is cancelled and not waited for.
 *
 * ## What it writes
 *
 * What listening says about a station -- [heard] -- goes to the store, and a failure only if it is the station's: several stations failing
 * within a couple of minutes is the network, and is not held against any of them ([NetworkTroubleGuard]).
 */
@HiltViewModel
class WorldRadioViewModel @Inject constructor(
    private val directory: WorldRadioDirectory,
    private val library: WorldRadioLibrary,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    /** The clock, replaced by a test's. */
    internal var clock: () -> Long = System::currentTimeMillis

    /** The language the rail's countries are named in, replaced by a test's. */
    internal var locale: Locale = Locale.getDefault()

    private val guard = NetworkTroubleGuard()
    private var favoritesRefreshed = false

    private val _choice = MutableStateFlow<WorldChoice>(WorldChoice.Top)

    /** The list that is open. A search covers it while there is text in the band's field, and the choice is kept under it. */
    val choice: StateFlow<WorldChoice> = _choice

    private val _typed = MutableStateFlow("")

    /** The two together, changed in one write, so a list chosen and a field emptied are one change and not two with a wrong query between. */
    private data class Open(val choice: WorldChoice, val text: String)

    private val open = MutableStateFlow(Open(WorldChoice.Top, ""))

    /** What the band's field holds, exactly as typed: read straight off the flow, so the field never lags the keys. */
    val typed: StateFlow<String> = _typed

    private val _railFilter = MutableStateFlow("")
    private val _tagsShown = MutableStateFlow(false)
    private val countries = MutableStateFlow<RailLoad>(RailLoad.Loading)
    private val tags = MutableStateFlow<RailLoad>(RailLoad.Loading)

    /** The rail: the countries, or the tags, narrowed by what its own field holds. */
    val rail: StateFlow<WorldRailState> = combine(_tagsShown, _railFilter, countries, tags) { showTags, filter, c, t ->
        val load = if (showTags) t else c
        WorldRailState(
            tags = showTags,
            filter = filter,
            load = if (load is RailLoad.Ready) load.copy(entries = load.entries.matching(filter)) else load,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(GRACE_MS), WorldRailState())

    /** The favourites' uuids, as they change, so a row asks whether it is one by a set lookup. */
    val favoriteIds: StateFlow<Set<String>> = library.favoriteIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(GRACE_MS), emptySet())

    /** The favourites, the one added last first, with the ones resting dimmed. Never left out: a favourite is the viewer's. */
    val favorites: StateFlow<SavedStations?> = library.favorites().map { saved(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(GRACE_MS), null)

    /** The stations listened to last, the latest first. */
    val recents: StateFlow<SavedStations?> = library.recents(RECENTS_LIMIT).map { saved(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(GRACE_MS), null)

    /** What is asked of the directory: the search if there is one, else the open list -- or null for the two lists the directory has no part in. */
    @OptIn(FlowPreview::class)
    internal val listing: Flow<WorldListing?> = open
        // A key is waited for; the field being emptied, or a list chosen, is not.
        .debounce { if (it.text.isBlank()) 0L else SEARCH_DEBOUNCE_MS }
        .map { (list, text) ->
            val query = text.trim()
            when {
                query.isNotEmpty() -> WorldListing.Search(query)
                list is WorldChoice.Top -> WorldListing.Top
                list is WorldChoice.Country -> WorldListing.OfCountry(list.code)
                list is WorldChoice.Tag -> WorldListing.OfTag(list.name)
                else -> null
            }
        }
        .distinctUntilChanged()

    /** The open list's stations, a page at a time. Empty for the favourites and the recents, which are [favorites] and [recents]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val stations: Flow<PagingData<WorldStation>> = listing
        .flatMapLatest { asked ->
            if (asked == null) {
                emptyFlow()
            } else {
                Pager(
                    config = PagingConfig(
                        pageSize = PAGE_SIZE,
                        prefetchDistance = PREFETCH,
                        initialLoadSize = PAGE_SIZE,
                        maxSize = MAX_IN_MEMORY,
                        enablePlaceholders = false,
                    ),
                    pagingSourceFactory = { WorldStationsSource(directory, library, asked, clock) },
                ).flow
            }
        }
        .cachedIn(viewModelScope)

    init {
        loadRail(force = false)
    }

    /** Opens a list. The search that was covering the last one is cleared: a list chosen is the list wanted. */
    fun choose(next: WorldChoice) {
        _typed.value = ""
        _choice.value = next
        open.value = Open(next, "")
        if (next is WorldChoice.Favorites) refreshFavorites()
    }

    fun search(text: String) {
        _typed.value = text
        open.update { it.copy(text = text) }
    }

    fun filterRail(text: String) {
        _railFilter.value = text
    }

    /** The rail shows the tags, or goes back to the countries. The field's text belongs to the list it was typed over, and is cleared. */
    fun showTags(shown: Boolean) {
        _railFilter.value = ""
        _tagsShown.value = shown
    }

    /** Asks the directory for the countries and the tags again, ignoring what is kept. */
    fun retryRail() = loadRail(force = true)

    private fun loadRail(force: Boolean) {
        countries.value = RailLoad.Loading
        tags.value = RailLoad.Loading
        viewModelScope.launch {
            val answer = directory.countries(force)
            countries.value = withContext(dispatchers.default) {
                when (answer) {
                    is DirectoryResult.Fresh -> DirectoryResult.Fresh(countryEntries(answer.value, locale))
                    is DirectoryResult.Stale -> DirectoryResult.Stale(countryEntries(answer.value, locale), answer.ageMs)
                    is DirectoryResult.Unavailable -> answer
                }.asRail()
            }
        }
        viewModelScope.launch {
            val answer = directory.tags(force)
            tags.value = withContext(dispatchers.default) {
                when (answer) {
                    is DirectoryResult.Fresh -> DirectoryResult.Fresh(tagEntries(answer.value, locale))
                    is DirectoryResult.Stale -> DirectoryResult.Stale(tagEntries(answer.value, locale), answer.ageMs)
                    is DirectoryResult.Unavailable -> answer
                }.asRail()
            }
        }
    }

    /** The favourites kept are refreshed with what the directory says now, once a visit: a station that moved is found where it moved. */
    private fun refreshFavorites() {
        if (favoritesRefreshed) return
        favoritesRefreshed = true
        viewModelScope.launch {
            val uuids = library.favorites().first().map { it.uuid }
            if (uuids.isEmpty()) return@launch
            val answer = directory.stationsByUuid(uuids)
            if (answer is DirectoryResult.Fresh) answer.value.forEach { library.refreshSaved(it) }
        }
    }

    /** Favourite [station], or let it go: the copy kept is the one given, so it is there when the directory is not. */
    fun toggleFavorite(station: WorldStation) {
        viewModelScope.launch { library.setFavorite(station, favorite = station.uuid !in favoriteIds.value) }
    }

    /** [station] sounded: it is the latest of the recents, and the directory is told it was listened to, as it asks to be. */
    fun listened(station: WorldStation) {
        viewModelScope.launch { library.recordListened(station, clock()) }
        viewModelScope.launch { directory.registerListen(station.uuid) }
    }

    /** What a listen of [station] proved. A failure is written only if it is the station's, and the other three as they are. */
    fun heard(station: WorldStation, outcome: PlayOutcome) {
        val now = clock()
        if (outcome == PlayOutcome.FAILED && !guard.countsAgainst(station.uuid, now)) return
        viewModelScope.launch { library.recordOutcome(station.uuid, outcome, now) }
    }

    private suspend fun saved(stations: List<WorldStation>): SavedStations {
        val records = library.records(stations.map { it.uuid })
        val now = clock()
        val dimmed = stations.filter {
            StationHealth.standing(it, records[it.uuid], now).let { standing -> standing == Standing.RESTING || standing == Standing.SCREENED_OUT }
        }.mapTo(HashSet()) { it.uuid }
        return SavedStations(stations, dimmed)
    }

    private companion object {
        /** The catalogue's own paging, here too: a page of sixty, thirty rows ahead of the one in view, and never more than three hundred held. */
        const val PAGE_SIZE = 60
        const val PREFETCH = 30
        const val MAX_IN_MEMORY = 300

        /** The recents are a short list: what was listened to lately, not a history. */
        const val RECENTS_LIMIT = 60

        const val SEARCH_DEBOUNCE_MS = 120L
        const val GRACE_MS = 5_000L
    }
}
