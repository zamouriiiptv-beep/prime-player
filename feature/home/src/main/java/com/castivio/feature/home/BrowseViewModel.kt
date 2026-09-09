package com.castivio.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.castivio.domain.CatalogPager
import com.castivio.domain.CatalogQuery
import com.castivio.domain.CatalogRepository
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaItem
import com.castivio.domain.Season
import com.castivio.domain.SeriesSummary
import com.castivio.domain.SortOrder
import com.castivio.domain.SourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

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
    sources: SourceRepository,
) : ViewModel() {

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
        query.flatMapLatest { catalog.count(it.kind, it.groupId) },
        sources.active().map { it?.label },
    ) { current, available, asked, total, provider ->
        BrowseState(
            section = current,
            groups = available,
            categoryNames = available.associate { it.id to it.name },
            selectedGroup = asked.groupId,
            sort = asked.sort,
            total = total,
            providerLabel = provider,
            loading = false,
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
        if (section.value == current) return
        section.value = current
        chosen.value = null
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
