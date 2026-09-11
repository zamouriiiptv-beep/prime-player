package com.castivio.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.tracing.Trace
import com.castivio.core.platform.CastivioTrace
import com.castivio.domain.CatalogPager
import com.castivio.domain.CatalogQuery
import com.castivio.domain.CatalogRepository
import com.castivio.domain.LoadSection
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaItem
import com.castivio.domain.Season
import com.castivio.domain.SectionLoad
import com.castivio.domain.SeriesSummary
import com.castivio.domain.SortOrder
import com.castivio.domain.SourceRepository
import com.castivio.domain.time.TrustedTime
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the category pane and the header need, which is everything except the rows.
 *
 * The rows are not in here on purpose. They arrive as [PagingData], which is a stream
 * of windows rather than a value, and folding one into a state object is how a screen
 * ends up holding a list it promised never to hold.
 */
data class BrowseState(
    val section: CatalogSection = CatalogSection.Live,
    val groups: List<MediaGroup> = emptyList(),
    /**
     * The same groups by id, built once here rather than in the screen.
     *
     * A channel row shows the category it came from, and deriving that map inside
     * composition would rebuild it on every recomposition — the sort of quiet
     * per-frame work `PERFORMANCE.md` forbids, on a list that is hundreds long.
     */
    val categoryNames: Map<String, String> = emptyMap(),
    val selectedGroup: String? = null,
    /**
     * The order the rows are read in.
     *
     * Part of the state rather than kept in the screen, because it is half of the
     * query: a control that owned it locally would be a control whose value and the
     * rows on screen could disagree for a frame after a section change.
     */
    val sort: SortOrder = SortOrder.PROVIDER,
    /** From an indexed `COUNT`, never from measuring a list. */
    val total: Int = 0,
    val providerLabel: String? = null,
    /** True only until the first answer arrives; a category change is not a reload. */
    val loading: Boolean = true,
    /**
     * Whether this section is on the device yet, and how far along if it is arriving.
     *
     * Null before the question has been asked. Distinct from [loading], which is
     * about this screen's own queries: a section can have answered every query it has
     * and still be empty because nobody has fetched it.
     */
    val fetch: SectionLoad? = null,
)

/**
 * One section of the catalogue: its categories, its rows, and which category is open.
 *
 * There is one of these per section rather than one shared between them, keyed by the
 * section in the composition. That is what makes leaving Movies and coming back land
 * on the category the user had open — the alternative is a single holder that has to
 * remember four selections and re-derive which one is current on every read.
 *
 * Everything here is a query. Nothing loads a list to count it, nothing asks for more
 * rows than a window, and the paging configuration is the pager's, decided once next
 * to the performance budgets rather than restated per screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val pager: CatalogPager,
    private val loadSection: LoadSection,
    private val clock: TrustedTime,
    sources: SourceRepository,
) : ViewModel() {

    private val fetch = MutableStateFlow<SectionLoad?>(null)
    private var fetching: Job? = null

    private val section = MutableStateFlow(CatalogSection.Live)
    private val chosen = MutableStateFlow<String?>(null)

    /**
     * The order, which the provider's own is the default for.
     *
     * A playlist arrives in the order the provider wrote it, and for live television
     * that order *is* the channel numbering — the thing every remote user navigates
     * by. Sorting it alphabetically by default would throw away the one piece of
     * structure an IPTV catalogue reliably has.
     */
    private val order = MutableStateFlow(SortOrder.PROVIDER)

    private val groups: Flow<List<MediaGroup>> =
        section.flatMapLatest { catalog.groups(it.kind) }

    /**
     * The query the rows are read with.
     *
     * Derived rather than stored, and the selection is passed through [surviving] on
     * the way — so a category that vanished in a re-import cannot leave the screen
     * querying a group id that no longer exists.
     */
    private val query: Flow<CatalogQuery> =
        combine(section, chosen, groups, order) { current, selected, available, sort ->
            CatalogQuery(
                kind = current.kind,
                groupId = surviving(selected, available),
                sort = sort,
            )
        }

    val state: StateFlow<BrowseState> = combine(
        section,
        groups,
        // The query rather than the raw selection: it has already resolved a category
        // that did not survive a re-import, and it carries the order, so the control
        // and the rows cannot disagree about either.
        query,
        // A count per section and category, answered by SQL. It is a flow because an
        // import running behind the screen changes it, and a number that only
        // refreshes when the user navigates away and back is a number nobody trusts.
        // It is also how a section being fetched right now fills in front of the user.
        query.flatMapLatest { catalog.count(it.kind, it.groupId) },
        combine(sources.active().map { it?.label }, fetch) { provider, state -> provider to state },
    ) { current, available, asked, total, providerAndFetch ->
        BrowseState(
            section = current,
            groups = available,
            categoryNames = available.associate { it.id to it.name },
            selectedGroup = asked.groupId,
            sort = asked.sort,
            total = total,
            providerLabel = providerAndFetch.first,
            loading = false,
            fetch = providerAndFetch.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), BrowseState())

    /** Channels, films and episodes. Empty for [CatalogSection.Series], which pages shows. */
    val items: Flow<PagingData<MediaItem>> =
        query.flatMapLatest(pager::items).cachedIn(viewModelScope)

    /** Shows, aggregated by SQL from their episodes — never by grouping rows here. */
    val shows: Flow<PagingData<SeriesSummary>> =
        query.flatMapLatest(pager::series).cachedIn(viewModelScope)

    /**
     * Called by the screen on first composition, so the holder is told what it is for.
     *
     * Idempotent, because composition is: re-declaring the same section must not clear
     * the category the user has open.
     */
    fun show(current: CatalogSection) {
        if (section.value != current) {
            section.value = current
            chosen.value = null
            fetch.value = null
        }
        ensureFetched(current, force = false)
    }

    /**
     * Fetch this section if it is not already on the device.
     *
     * Guarded by the running job rather than by the state, because composition calls
     * [show] again for reasons that are not a navigation — a rotation, a theme switch,
     * a recomposition after the count moved — and a second import of the same kind
     * racing the first would interleave writes to the same rows.
     *
     * `LoadSection` decides whether there is anything to do; this only decides not to
     * ask twice at once.
     */
    private fun ensureFetched(current: CatalogSection, force: Boolean) {
        if (fetching?.isActive == true) return
        fetching = viewModelScope.launch {
            // The outermost boundary Phase A named. `LoadSection` itself is pure Kotlin
            // and may not import `androidx` -- the invariant script fails the build on
            // that -- so the section is opened around its collection, which begins and
            // ends at the same two instants the loader does.
            Trace.beginSection(CastivioTrace.FETCH)
            try {
                loadSection.load(current.kind, clock.nowMs(), force = force)
                    .collect { fetch.value = it }
            } finally {
                Trace.endSection()
            }
        }
    }

    /** After a failure, or when the user asks for this section again deliberately. */
    fun retryFetch(force: Boolean = false) {
        fetching?.cancel()
        fetching = null
        fetch.value = null
        ensureFetched(section.value, force = force)
    }

    /** Null is the "all" pseudo-category, which is a selection like any other. */
    fun choose(groupId: String?) {
        chosen.value = groupId
    }

    /**
     * Re-reads the section in another order.
     *
     * Changes the query, so the pager starts a new stream and the list returns to the
     * top — which is what a re-sort means, and is why this is not a client-side sort
     * of the rows that happen to be loaded.
     */
    fun sortBy(value: SortOrder) {
        order.value = value
    }

    /** One show's seasons, small enough to read whole because it is bounded by the show. */
    fun seasons(seriesId: String): Flow<List<Season>> = pager.seasons(seriesId)

    private companion object {
        /**
         * How long the flows stay alive with nobody watching.
         *
         * Long enough that rotating a phone or opening the player over a list does not
         * re-run the category query and the count; short enough that a section the user
         * has left stops holding a database cursor.
         */
        const val SUBSCRIPTION_GRACE_MS = 5_000L
    }
}
