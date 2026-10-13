package com.castivio.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.tracing.Trace
import com.castivio.core.platform.CastivioTrace
import com.castivio.core.platform.PerfMode
import com.castivio.core.platform.PerformanceLog
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
import com.castivio.domain.identity.DeviceIdentity
import com.castivio.domain.time.TrustedTime
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
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
     * [groups] stays whole, because the channel rows look a category's name up by id and
     * a filtered map would leave them blank.
     */
    val groupFilter: String = "",
    val shownGroups: List<MediaGroup> = emptyList(),
    val providerLabel: String? = null,
    /**
     * The active subscription's id, null when there is none.
     *
     * Not drawn. The gate keys on it, so a screen still open while the subscription
     * changes asks for its section again — see [SectionFetch].
     */
    val sourceId: String? = null,
    /**
     * This device's address, for the loading gate to print while the section arrives.
     *
     * Derived once per holder rather than asked for per frame: [DeviceIdentity.current]
     * is a pure function of a seed the operating system keeps, so it is the same six
     * octets on every read and there is nothing to observe.
     */
    val mac: String = "",
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
    private val loadSection: LoadSection,
    private val clock: TrustedTime,
    private val sources: SourceRepository,
    identity: DeviceIdentity,
) : ViewModel() {

    /** Read once, for the reason [BrowseState.mac] gives. */
    private val mac: String = identity.current().macAddress.value

    private val section = MutableStateFlow(CatalogSection.Live)
    private val chosen = MutableStateFlow<String?>(null)

    /**
     * Whether this section is on the device for the active subscription, and the one
     * fetch allowed to ask. The rules — a failure waits for the user, one press is one
     * attempt, a new subscription forgets the previous one's answer — are
     * [SectionFetch]'s, where they are tested without a device.
     */
    private val fetch = SectionFetch(
        scope = viewModelScope,
        load = { force ->
            // The outermost boundary Phase A named. `LoadSection` itself is pure Kotlin
            // and may not import `androidx` -- the invariant script fails the build on
            // that -- so the section is opened around its collection, which begins and
            // ends at the same two instants the loader does.
            loadSection.load(section.value.kind, clock.nowMs(), force = force)
                .onStart { Trace.beginSection(CastivioTrace.FETCH) }
                .onCompletion { Trace.endSection() }
        },
        onRunStarted = { PerformanceLog.begin(section.value.perf) },
        onAnswer = ::report,
    )

    init {
        // Watched for the holder's whole life, not only while a screen is open: the
        // holder outlives its screen, because the shell has no back stack to clear it.
        // A subscription switched from Home would otherwise leave this section holding
        // the previous one's fetch, still running, and its answer, still on show.
        viewModelScope.launch {
            sources.active()
                .map { it?.id }
                .distinctUntilChanged()
                .collect { fetch.sourceChanged(it) }
        }
    }

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
            fetch.state,
            railFilter,
        ) { provider, state, filter -> Triple(provider, state, filter) },
    ) { current, available, asked, counts, providerAndFetch ->
        val provider = providerAndFetch.first
        val filter = providerAndFetch.third
        BrowseState(
            section = current,
            groups = available,
            categoryNames = available.associate { it.id to it.name },
            selectedGroup = asked.groupId,
            sort = asked.sort,
            total = counts.first,
            sectionTotal = counts.second,
            groupFilter = filter,
            shownGroups = available.filtered(filter),
            // `takeIf`, because a playlist the user did not name now stores no name
            // rather than the host: absent and empty mean the same thing to every
            // reader of this, and only one of them has a fallback. Without it the
            // empty-section title reads " carries no Films".
            providerLabel = provider?.label?.takeIf(String::isNotBlank),
            sourceId = provider?.id,
            loading = false,
            fetch = providerAndFetch.second,
            fetchReadout = (providerAndFetch.second as? SectionLoad.Failed)?.diagnosis?.let(::fetchReadout),
            mac = mac,
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
     * the category the user has open, and must not ask again for a section whose fetch
     * failed — that is the user's to ask, with the retry button. See [SectionFetch].
     */
    fun show(current: CatalogSection) {
        if (section.value != current) {
            section.value = current
            chosen.value = null
            fetch.forget()
        }
        // The active subscription is read at the moment of asking rather than taken from
        // the watcher in `init`. The two are separate queries, and a screen that saw a
        // switch before the watcher did must not be answered about the previous one.
        viewModelScope.launch { fetch.ensure(sources.activeNow()?.id) }
    }

    /**
     * Tells the stopwatch how this load ended, from what the loader actually said.
     *
     * Nothing is inferred. `Ready` means `LoadSection` found a mark for this source and
     * kind and returned without fetching, so the rows were already here -- warm, and
     * with no import to have an end time for. `Done` means the importer reached the end
     * of the provider's categories and committed, which is a genuine full load. The
     * counts come from `CallMetrics`, which the importer resets at the start of the same
     * window, so they belong to this section and not to the app's whole session.
     *
     * A cache hit is only claimed when OkHttp said so for **every** call. A partly
     * cached import is a download.
     */
    private fun report(load: SectionLoad) {
        when (load) {
            // Nothing was imported, so there is no import to have finished. The honest
            // report is the first frame and nothing beside it.
            is SectionLoad.Ready -> PerformanceLog.settled(PerfMode.WARM, fullLoad = false)

            // COLD here is a claim about *this* screen: a fetch ran. Whether the
            // provider or its cache answered is the network layer's fact, and
            // `PerformanceLog` turns it into CACHE_HIT from the counts the importer
            // published -- which is why that is not decided in a view model.
            is SectionLoad.Done -> PerformanceLog.settled(PerfMode.COLD, fullLoad = true)

            is SectionLoad.Failed, is SectionLoad.NoSource ->
                PerformanceLog.settled(PerfMode.UNKNOWN, fullLoad = false)

            is SectionLoad.Loading -> Unit
        }
    }

    /**
     * After a failure, or when the user asks for this section again deliberately.
     *
     * One press is one attempt: a press while an attempt is running is not a second
     * question. A deliberate retry is a new experiment, so it gets a new run rather
     * than overwriting the failed one's numbers into the same row.
     */
    fun retryFetch(force: Boolean = false) {
        fetch.retry(force)
    }

    /** What the rail's field was typed into. Empty shows every category again. */
    fun filterGroups(text: String) {
        railFilter.value = text
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
