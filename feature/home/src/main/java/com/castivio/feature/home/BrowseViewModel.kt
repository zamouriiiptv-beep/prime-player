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
import com.castivio.domain.SectionLoad
import com.castivio.domain.SectionLoads
import com.castivio.domain.SectionRun
import com.castivio.domain.SeriesSummary
import com.castivio.domain.SortOrder
import com.castivio.domain.SourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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
    /**
     * Everything this section holds, whatever category is open.
     *
     * Distinct from [total], which follows the query and therefore the selection. The
     * rail's first entry says **All Channels** and has to mean all of them: it was
     * reading [total], so choosing a bouquet of 80 made the All row say 80 as well —
     * the one row on the screen whose number is supposed not to move.
     *
     * It is the section's own count, so in Movies it is the number of films and in
     * Series the number of shows' episodes. Same `COUNT`, no group.
     */
    val sectionTotal: Int = 0,
    /**
     * What the rail's own field has been typed into, and the categories that survive it.
     *
     * Filtered here rather than in composition: `CLAUDE.md` puts filtering in the state
     * holder or in SQL, and a provider with four hundred categories would otherwise be
     * re-filtered on every frame the rail recomposed.
     *
     * Only the categories with something in them are shown -- see [listedOf] -- which is
     * also what lets the rail grow while the section arrives: a category is counted when it
     * is complete, and appears then, in its place.
     *
     * [groups] stays whole, because the channel rows look a category's name up by id and
     * a filtered map would leave them blank.
     */
    val groupFilter: String = "",
    val shownGroups: List<MediaGroup> = emptyList(),
    /**
     * The rail lists no category and none is on its way: what `RailEmpty` says instead of a
     * list. Not while any load of the section waits or runs, nor after one failed -- see
     * [saysNoCategories].
     */
    val noCategories: Boolean = false,
    /**
     * This section's first load is running: the board opened with its first category and
     * the rest join the rail as they complete. Nothing on the board says so -- the owner's
     * rule is that the viewer does not feel the load -- so the notice a refresh draws is not
     * drawn for it. A failure is still said.
     */
    val firstLoad: Boolean = false,
    val providerLabel: String? = null,
    /** True only until the first answer arrives; a category change is not a reload. */
    val loading: Boolean = true,
    /**
     * This section's latest load, as [SectionLoads] tells it: waiting, arriving, or how it
     * ended. Null when nothing was asked of it since the subscription became the active one.
     *
     * A screen is opened over rows that are already here, so a load it sees is a refresh
     * running behind it, or the end of one. Distinct from [loading], which is about this
     * screen's own queries.
     */
    val fetch: SectionLoad? = null,
    /**
     * [fetch]'s figures when it failed with some, formatted once here — see [fetchReadout].
     *
     * Null when the fetch did not fail, or failed with nothing to show. Whether it is
     * drawn is the screen's to decide: only a debuggable build prints it.
     */
    val fetchReadout: String? = null,
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
    private val loads: SectionLoads,
    private val sources: SourceRepository,
) : ViewModel() {

    private val section = MutableStateFlow(CatalogSection.Live)
    private val chosen = MutableStateFlow<String?>(null)

    /**
     * This section's latest load. The queue owns the rules -- one load at a time, a failure
     * waits for somebody to ask again, a new subscription forgets the old one's answers --
     * and this only reads the entry for the section on screen.
     */
    private val fetch: Flow<SectionRun?> =
        combine(section, loads.runs) { current, runs -> runs[current.kind] }

    /**
     * The order, which the provider's own is the default for.
     *
     * A playlist arrives in the order the provider wrote it, and for live television
     * that order *is* the channel numbering — the thing every remote user navigates
     * by. Sorting it alphabetically by default would throw away the one piece of
     * structure an IPTV catalogue reliably has.
     */
    private val order = MutableStateFlow(SortOrder.PROVIDER)

    /** What the rail's search field holds. See [BrowseState.groupFilter]. */
    private val railFilter = MutableStateFlow("")

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
        // Two counts, answered by SQL. They are flows because an import running behind
        // the screen changes them, and a number that only refreshes when the user
        // navigates away and back is a number nobody trusts -- it is also how a section
        // being fetched right now fills in front of the user.
        //
        // The first follows the query and is what the category that is open holds. The
        // second ignores the selection and is what the whole section holds; the rail's
        // All entry needs that one and was reading the first.
        combine(
            query.flatMapLatest { catalog.count(it.kind, it.groupId) },
            section.flatMapLatest { catalog.count(it.kind, null) },
        ) { shown, whole -> shown to whole },
        combine(
            sources.active(),
            fetch,
            railFilter,
        ) { provider, state, filter -> Triple(provider, state, filter) },
    ) { current, available, asked, counts, providerAndFetch ->
        val provider = providerAndFetch.first
        val run = providerAndFetch.second
        val filter = providerAndFetch.third
        val listed = listedOf(available)
        BrowseState(
            section = current,
            groups = available,
            categoryNames = available.associate { it.id to it.name },
            selectedGroup = asked.groupId,
            sort = asked.sort,
            total = counts.first,
            sectionTotal = counts.second,
            groupFilter = filter,
            shownGroups = listed.filtered(filter),
            noCategories = saysNoCategories(listed, run?.load),
            firstLoad = run.isFirstLoad(),
            // `takeIf`, because a playlist the user did not name now stores no name
            // rather than the host: absent and empty mean the same thing to every
            // reader of this, and only one of them has a fallback. Without it the
            // empty-section title reads " carries no Films".
            providerLabel = provider?.label?.takeIf(String::isNotBlank),
            loading = false,
            fetch = run?.load,
            fetchReadout = (run?.load as? SectionLoad.Failed)?.diagnosis?.let(::fetchReadout),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), BrowseState())

    /**
     * Whether the viewer chose a category on this screen -- "all" included. Until then, a
     * board opened over its section's first load opens on the first category listed, not
     * on "all": the whole section is what is still being written, and its list would wait
     * behind every commit with a spinner where the channels should be.
     */
    private var userChose = false

    init {
        viewModelScope.launch {
            combine(fetch, groups) { run, available -> run to available }.collect { (run, available) ->
                if (userChose || chosen.value != null || !run.isFirstLoad()) return@collect
                listedOf(available).firstOrNull()?.let { chosen.value = it.id }
            }
        }
    }

    /** Channels, films and episodes. Empty for [CatalogSection.Series], which pages shows. */
    val items: Flow<PagingData<MediaItem>> =
        query.flatMapLatest(pager::items).cachedIn(viewModelScope)

    /** Shows, aggregated by SQL from their episodes — never by grouping rows here. */
    val shows: Flow<PagingData<SeriesSummary>> =
        query.flatMapLatest(pager::series).cachedIn(viewModelScope)

    /**
     * Called by the screen on first composition, so the holder is told what it is for.
     *
     * Idempotent, because composition is: re-declaring the same section must not clear the
     * category the user has open. It asks for nothing -- Home asked before it opened the
     * section, and the section is on the device by the time this screen draws: whole, or,
     * for Live TV, its first category with the rest on their way.
     */
    fun show(current: CatalogSection) {
        if (section.value != current) {
            section.value = current
            chosen.value = null
            userChose = false
        }
    }

    /**
     * Fetch this section again, deliberately: the rail's refresh, or another try after a
     * refresh that failed. The rows on screen stay while it runs; the queue says when it has
     * ended, and a press while it waits or runs is the same press.
     */
    fun refresh() {
        loads.request(section.value.kind, force = true)
    }

    /** What the rail's field was typed into. Empty shows every category again. */
    fun filterGroups(text: String) {
        railFilter.value = text
    }

    /** Null is the "all" pseudo-category, which is a selection like any other. */
    fun choose(groupId: String?) {
        userChose = true
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

/**
 * The categories whose names contain [filter], or all of them when nothing is typed.
 *
 * Case-insensitive and a plain `contains` rather than the FTS index, and deliberately:
 * this is a list of hundreds already in memory, the field is answering a keystroke, and
 * putting a query on the database for it would be slower than the scan as well as more
 * code. `Locale.ROOT` so a Turkish device does not fold `I` differently from every
 * other one and hide a category from its own name.
 */
private fun List<MediaGroup>.filtered(typed: String): List<MediaGroup> {
    val needle = typed.trim()
    if (needle.isEmpty()) return this
    val lowered = needle.lowercase(Locale.ROOT)
    return filter { it.name.lowercase(Locale.ROOT).contains(lowered) }
}
