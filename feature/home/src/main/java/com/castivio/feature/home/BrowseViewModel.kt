package com.castivio.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.castivio.domain.CatalogOrder
import com.castivio.domain.CatalogPager
import com.castivio.domain.CatalogQuery
import com.castivio.domain.CatalogRepository
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaItem
import com.castivio.domain.ProviderSource
import com.castivio.domain.Season
import com.castivio.domain.SectionLoad
import com.castivio.domain.SectionLoads
import com.castivio.domain.SectionRun
import com.castivio.domain.SeriesSummary
import com.castivio.domain.SortOrder
import com.castivio.domain.SourceRepository
import com.castivio.domain.searchTermOf
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    /**
     * From an indexed `COUNT`, never from measuring a list. What the scope holds -- the open
     * category, or the whole section while a search is on -- and not how many rows match: whether
     * the section has anything is one question, and whether this search found any is the list's.
     */
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
     * What the header's field is asking for, as the rows reflect it: the typed words folded by
     * [searchTermOf], or empty when the list is not narrowed. Not the field's own text -- that
     * is [BrowseViewModel.searchText], which is what was typed this instant -- but what the
     * query in force was built from, so "Nothing matched" names the search the rows answer.
     */
    val search: String = "",
    /**
     * The board is in reorder mode, or null. While it is, the rail lists the bouquets in the order
     * being set -- [shownGroups] is that order, whole, and the rail's own filter is set aside --
     * and the channels of the open bouquet are its draft. See [Arranging].
     */
    val arranging: Arranging? = null,
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
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val pager: CatalogPager,
    private val loads: SectionLoads,
    private val sources: SourceRepository,
    private val customOrder: CatalogOrder,
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

    /**
     * What the header's field holds, exactly as typed.
     *
     * Kept here and read straight off the flow rather than through [state]: a field whose text
     * made a round trip through the combine below, and the pager's own work, would lag the
     * keys under it. It is the holder's, not the composition's, so a trip to the player and back
     * finds the field and the list agreeing about what was asked.
     */
    private val typed = MutableStateFlow("")

    val searchText: StateFlow<String> = typed

    /**
     * What the rows are narrowed by: [typed], folded, held back until the viewer pauses.
     *
     * Folded *before* the pause, so a trailing space or a capital does not restart it, and
     * distinct, so those typings are not a second query. Cleared at once -- there is nothing to
     * wait for when the answer is the list that was there before.
     */
    private val term: Flow<String> = typed
        .map(::searchTermOf)
        .distinctUntilChanged()
        .debounce { if (it.isEmpty()) 0L else SEARCH_DEBOUNCE_MS }
        .distinctUntilChanged()

    /**
     * The board in reorder mode, or null -- see [Arranging]. Not [state]'s: the query below reads
     * it too, to ask for the draft of the channels being arranged and not their saved order.
     */
    private val arrangement = MutableStateFlow<Arranging?>(null)

    /** Moves and saves are written one at a time, in the order they were pressed. */
    private val orderLock = Mutex()

    private val groups: Flow<List<MediaGroup>> =
        section.flatMapLatest { catalog.groups(it.kind) }

    /**
     * The query the rows are read with.
     *
     * Derived rather than stored, and the selection is passed through [surviving] on
     * the way — so a category that vanished in a re-import cannot leave the screen
     * querying a group id that no longer exists.
     *
     * Distinct, because [groups] answers again every time a category's count changes --
     * about once a second while a section arrives -- and every answer here is a new pager
     * whose first page starts over. The rows are asked for again only when the query is.
     */
    private val query: Flow<CatalogQuery> =
        combine(
            section,
            chosen,
            groups,
            order,
            combine(term, arrangement.map { it?.channelsOf }) { search, arranged -> search to arranged },
        ) { current, selected, available, sort, narrowing ->
            val (search, arrangedGroup) = narrowing
            val groupId = if (search.isEmpty()) surviving(selected, available) else null
            CatalogQuery(
                kind = current.kind,
                // **A search is of the whole section.** A viewer who types a name wants the
                // channel, not the channel if it happens to be in the bouquet that was open --
                // and an answer of "nothing" from one bouquet is indistinguishable from the
                // channel not existing. So while there are words, no category is asked for, the
                // rail's "all" is the one lit, and the category chosen is only set aside:
                // clearing the field gives it back, on the list it was.
                groupId = groupId,
                sort = sort,
                search = search,
                arranging = groupId != null && groupId == arrangedGroup,
            )
        }.distinctUntilChanged()

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
            arrangement,
        ) { provider, state, filter, arranged -> Surroundings(provider, state, filter, arranged) },
    ) { current, available, asked, counts, around ->
        val provider = around.provider
        val run = around.run
        val filter = around.filter
        val arranged = around.arranging
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
            // Reorder mode lists every bouquet in the order being set; the rail's own filter
            // is set aside, because a list with bouquets filtered out is not one to move in.
            shownGroups = arranged?.let { listed.arrangedAs(it.bouquets) } ?: listed.filtered(filter),
            search = asked.search,
            arranging = arranged,
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
        // A draft an earlier arrangement left behind -- the app closed before Save or Cancel -- is
        // cleared once a board is open, off the way to its first frame. See `CatalogOrder.sweepDrafts`.
        viewModelScope.launch {
            try {
                customOrder.sweepDrafts()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Left for the next sweep, or the next arrangement, which clears it as it begins.
            }
        }
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
            cancel()
            section.value = current
            chosen.value = null
            userChose = false
            typed.value = ""
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

    /**
     * What the header's field was typed into: the channels whose names match, as the viewer
     * types. Empty -- or nothing but spaces and punctuation -- is the list as it was.
     */
    fun search(text: String) {
        // Reorder mode is of the lists as they are: a search in it would show rows nobody can move.
        if (arrangement.value != null) return
        typed.value = text
    }

    /** What the rail's field was typed into. Empty shows every category again. */
    fun filterGroups(text: String) {
        if (arrangement.value != null) return
        railFilter.value = text
    }

    /**
     * Null is the "all" pseudo-category, which is a selection like any other.
     *
     * A choice ends a search that was on: the rail's press would otherwise change nothing the
     * viewer can see, the rows being those of the search and not of the category they asked for.
     */
    fun choose(groupId: String?) {
        // A press on the rail in reorder mode picks a bouquet up; it does not open one.
        if (arrangement.value != null) return
        userChose = true
        chosen.value = groupId
        typed.value = ""
    }

    // ------------------------------------------------------------------------- reorder mode

    private var starting = false

    /**
     * Enter reorder mode on the bouquets, and on the channels of the bouquet that is open.
     *
     * The draft of that bouquet's channels is made first, and the mode is announced after, so the
     * list is never asked for a draft that is not there yet. A search is cleared: what it shows is
     * not a list that can be arranged. "All" has no order of its own, so with it open only the
     * bouquets can be moved.
     */
    fun arrange() {
        if (arrangement.value != null || starting) return
        val now = state.value
        val ids = listedOf(now.groups).map { it.id }
        val group = surviving(chosen.value, now.groups)
        typed.value = ""
        starting = true
        viewModelScope.launch {
            try {
                if (group != null) orderLock.withLock { customOrder.begin(group) }
                arrangement.value = Arranging(bouquets = ids, initial = ids, channelsOf = group)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The draft could not be made, so there is nothing to arrange: the board stays as it was.
            } finally {
                starting = false
            }
        }
    }

    /** OK on a row in reorder mode: pick it up, or -- if it is the one held -- put it down. */
    fun press(row: Held) {
        val now = arrangement.value ?: return
        // A bouquet's channels can be moved only when there is a bouquet open to hold them.
        if (row is Held.Channel && now.channelsOf == null) return
        arrangement.value = now.pressed(row)
    }

    /** Put down whatever is held. */
    fun drop() {
        arrangement.value = arrangement.value?.dropped()
    }

    /** One place up or down for what is held. The arrow keys, and the two buttons a thumb has. */
    fun moveHeld(up: Boolean) {
        val now = arrangement.value ?: return
        when (val held = now.held) {
            is Held.Bouquet -> arrangement.value = now.withHeldBouquetMoved(up)
            is Held.Channel -> now.channelsOf?.let { group ->
                viewModelScope.launch {
                    try {
                        orderLock.withLock {
                            // Told inside the lock, so a save pressed after this move finds it said.
                            if (customOrder.move(group, held.id, up)) {
                                arrangement.value = arrangement.value?.copy(channelsMoved = true)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        arrangement.value = arrangement.value?.copy(failed = true)
                    }
                }
            }
            null -> Unit
        }
    }

    /**
     * Keep what was moved, and leave the mode.
     *
     * The channels' draft becomes their order -- if a channel was moved; otherwise it is dropped,
     * and no order is stored that the viewer did not make -- and the bouquets are written as they
     * stand, if they were moved. Every move already pressed is written first. If something does
     * not reach the database the mode stays open and says so, with everything moved still where
     * it was put.
     */
    fun save() {
        val now = arrangement.value ?: return
        if (now.saving) return
        arrangement.value = now.copy(saving = true, failed = false)
        viewModelScope.launch {
            val written = try {
                orderLock.withLock {
                    if (now.bouquetsMoved) customOrder.saveBouquets(section.value.kind, now.bouquets)
                    now.channelsOf?.let { group ->
                        // Read here, after every move pressed before this save has been written.
                        if (arrangement.value?.channelsMoved == true) customOrder.save(group)
                        else customOrder.discard(group)
                    }
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            arrangement.value = if (written) null else arrangement.value?.copy(saving = false, failed = true)
        }
    }

    /**
     * Leave the mode and put everything back as it was.
     *
     * The lists are read as they were saved the moment this returns; the draft is thrown away
     * behind them, after any move still being written.
     */
    fun cancel() {
        val now = arrangement.value ?: return
        arrangement.value = null
        now.channelsOf?.let { group ->
            viewModelScope.launch {
                try {
                    orderLock.withLock { customOrder.discard(group) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A draft left behind is read by nothing: the next time this bouquet is arranged it starts over.
                }
            }
        }
    }

    /**
     * The screen is gone for good. An arrangement still open has a draft nobody will save or
     * cancel; the next sweep throws it away. Nothing is written here: this scope is ending.
     */
    override fun onCleared() {
        if (arrangement.value != null) customOrder.draftAbandoned()
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

        /** The data rule's number -- see `SearchViewModel`, which states why it is one number. */
        const val SEARCH_DEBOUNCE_MS = 120L
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

/** What [BrowseViewModel.state] reads beside the section's own flows, in one value. */
private data class Surroundings(
    val provider: ProviderSource?,
    val run: SectionRun?,
    val filter: String,
    val arranging: Arranging?,
)
