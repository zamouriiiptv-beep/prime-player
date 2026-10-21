package com.castivio.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.core.platform.NetworkState
import com.castivio.domain.CatalogRepository
import com.castivio.domain.MediaKind
import com.castivio.domain.ProviderSource
import com.castivio.domain.ProviderStatusCatalogue
import com.castivio.domain.Recorded
import com.castivio.domain.RefreshProvider
import com.castivio.domain.Refreshed
import com.castivio.domain.SectionCatalogue
import com.castivio.domain.SectionLoads
import com.castivio.domain.SectionRun
import com.castivio.domain.SourceKind
import com.castivio.domain.SourceRepository
import com.castivio.domain.entitlement.EntitlementRepository
import com.castivio.domain.entitlement.EntitlementState
import com.castivio.domain.identity.DeviceIdentity
import com.castivio.domain.time.TrustedTime
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Home: which provider is showing, how much of each kind it carries, and a first
 * taste of each.
 *
 * Counts and rows are separate reads for a reason that is a rule rather than a
 * preference — the count comes from an indexed `COUNT` and the row from a bounded
 * window, and neither is derived from the other. Counting by loading is the defect
 * this data layer is shaped to make impossible, and Home is where a "just take the
 * size of the list" would be most tempting.
 */
data class HomeState(
    /**
     * What the user named the active playlist. Null before activation, and **empty
     * when they named nothing** — the two are not the same and [hasSource] turns on
     * the first, so this is deliberately not narrowed to null-when-blank the way
     * `BrowseState.providerLabel` is. Nothing on Home prints it; [playlists] is what
     * the menu draws, with its own placeholder for the unnamed.
     */
    val provider: String? = null,
    /** Xtream, an M3U link, a file — shown so a user with several knows which is live. */
    val sourceKind: SourceKind? = null,
    /** What the last import wrote, which is not the sum of the four counts below. */
    val importedCount: Int = 0,
    val liveCount: Int = 0,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
    val radioCount: Int = 0,
    /**
     * Castivio's own licence, not the provider's subscription.
     *
     * The two are separate systems and Home says so by showing this beside the
     * provider's name rather than merged into it. Null only before the first answer
     * arrives; the gate has already established one by the time Home is drawn.
     */
    val entitlement: EntitlementState? = null,
    /**
     * What the provider last said about the subscription, or null if it has never
     * been asked — which is a different fact from "expired" and reads differently.
     */
    val subscription: Recorded? = null,
    /**
     * Which sections are on this device, and when each arrived.
     *
     * A tile with no entry here has never been fetched, which is a different thing
     * from a section that was fetched and turned out to be empty. Deriving it from
     * the count would collapse the two and re-download an empty section forever.
     */
    val sections: Map<MediaKind, Long> = emptyMap(),
    /**
     * What each section's card says about its load: waiting, arriving, arrived a moment
     * ago, failed -- or nothing, beside its count. See [CardLoad]; decided in [cardLoad].
     */
    val loads: Map<MediaKind, CardLoad> = emptyMap(),
    /**
     * This device's address, which is what a provider activating by MAC asks for.
     *
     * Read once and carried in the state rather than looked up where it is drawn: it
     * is derived from a seed the operating system keeps and cannot change while the
     * app is running, so re-deriving it per composition would be work with a
     * guaranteed identical answer.
     */
    val mac: String = "",
    /**
     * The short code beside the address, for a provider whose panel asks for one.
     *
     * Derived from the same seed as [mac] and carried here for the same reason: it
     * cannot change while the app is running, so deriving it where it is drawn would
     * be work with a guaranteed identical answer. Empty only before the first state
     * arrives, and the footer draws nothing for an empty one rather than an empty box.
     */
    val deviceKey: String = "",
    /**
     * Every playlist on this device, in the order they were added, and which one is
     * showing.
     *
     * Home draws these in the menu behind its "change list" control. They are carried
     * in the state rather than read where the menu is built for the reason every other
     * field here is: a composable that opened its own collector would re-read the
     * table on each recomposition of a menu that is usually closed.
     *
     * The active one is identified by [activePlaylistId] rather than by a flag on the
     * item, because the repository is the only thing that decides which is active and
     * a second copy of that fact is a second thing to get wrong.
     */
    val playlists: List<Playlist> = emptyList(),
    val activePlaylistId: String? = null,
    /**
     * A refresh is in flight.
     *
     * Its own field and not derived from anything, because the whole point of the
     * control is that the user can see it working. Pressing it used to launch a
     * coroutine and throw the answer away: a provider that could not be reached and a
     * provider whose answer was identical produced the same screen — nothing — and the
     * button was indistinguishable from one that did not work.
     */
    val refreshing: Boolean = false,
    /**
     * Why the last refresh failed, or null when it did not fail.
     *
     * Said on the refresh card itself, until the next press of it -- which is the retry.
     * It was a notice floating over the footer, where it covered the device's address:
     * the one value on the screen a user is asked to read out.
     */
    val refreshFault: RefreshFault? = null,
    /**
     * A refresh answered moments ago: the card says so for a few seconds, the way a
     * section's card says it has just loaded.
     *
     * A changed answer moves the header on its own, but an unchanged one moves nothing,
     * and a press that ends in nothing visible reads as a button that does not work --
     * the defect [refreshing] was added for, one step later.
     */
    val refreshed: Boolean = false,
    /**
     * What is wrong with the subscription, or null when nothing is known to be.
     *
     * Said on the last line, in place of the disclaimer, while it lasts -- see
     * [SubscriptionTrouble]. The header's card keeps its date beside it: a subscription
     * paid for until next year and a server that does not answer are both true at once.
     */
    val trouble: SubscriptionTrouble? = null,
    val loading: Boolean = true,
) {
    /** True once a provider has been configured, whatever it did or did not carry. */
    val hasSource: Boolean get() = provider != null

    /** True when there is a choice to offer. One playlist is not a menu. */
    val hasPlaylistChoice: Boolean get() = playlists.size > 1

    /** True when nothing has been counted — which is the normal state before any
     * section has been opened, and says nothing about the provider. */
    val isEmpty: Boolean get() =
        liveCount == 0 && movieCount == 0 && seriesCount == 0 && radioCount == 0

    /** True once every section has been asked for at least once. */
    val everySectionFetched: Boolean get() = MediaKind.entries.all { it in sections }

    /** The indexed `COUNT` for [kind]. */
    fun countOf(kind: MediaKind): Int = when (kind) {
        MediaKind.LIVE -> liveCount
        MediaKind.MOVIE -> movieCount
        MediaKind.SERIES -> seriesCount
        MediaKind.RADIO -> radioCount
    }

    /**
     * Whether [kind] is here to open: it has rows, or it was loaded and the provider sent
     * none -- which opens to its own empty state rather than to nothing.
     */
    fun onDevice(kind: MediaKind): Boolean = countOf(kind) > 0 || sections[kind] != null

    /**
     * The provider really does carry nothing, as opposed to nothing having been
     * fetched yet.
     *
     * Both look like four zeroes, and before sections were fetched lazily they could
     * not be told apart — so Home said "nothing imported" to every user who had just
     * activated and not yet opened a section. The marks are what separate them:
     * four zeroes *after* all four sections have been asked for is a genuinely empty
     * provider, and four zeroes before that is a user who has not pressed anything.
     */
    val carriesNothing: Boolean get() = everySectionFetched && isEmpty
}

/**
 * One playlist as the menu needs it: what to call it, and what to switch to.
 *
 * ## Why the name can be absent, and who supplies the placeholder
 *
 * [name] is what the user typed when they added the subscription, and empty when they
 * typed nothing — `RoomSourceRepository` stores that fact rather than replacing it
 * with the host, so the screen is free to offer its own placeholder instead of being
 * handed one it cannot recognise as invented.
 *
 * The placeholder itself is *not* here. "Playlist 1" is a translated string, and a
 * state holder that reached for a `Context` to resolve one would be a state holder
 * that cannot be tested without an emulator. So this carries the name and the
 * [position], and the screen composes `Playlist ${position}` when the name is empty —
 * which is also why the position is one-based: it is a number a reader counts with,
 * not an index.
 */
data class Playlist(
    val id: String,
    /** What the user called it. Empty when they named nothing. */
    val name: String,
    /** Its one-based place in the order they were added. */
    val position: Int,
)

/**
 * Why a refresh did not produce an answer.
 *
 * Two cases, kept apart because they need opposite sentences — one asks the user to
 * add a subscription, the other to try again — and collapsing them would let a
 * network blip read as a missing provider. The same split [Refreshed] already draws;
 * this is that decision arriving at the screen rather than being re-taken there.
 */
enum class RefreshFault {

    /** Nothing was active to ask. */
    NoProvider,

    /** The provider did not answer. Whatever was recorded before still stands. */
    Unreachable,
}

/**
 * Every saved subscription, as the menu needs it.
 *
 * A free function rather than a method so it can be tested without building a view
 * model and the eight collaborators one needs — which is the same reason `licenceTerm`
 * is one. There is nothing here a fake repository would prove that a list of sources
 * does not.
 *
 * The position is the source's place in the repository's order, which is `created_at`:
 * one-based because it is read aloud as a number, and taken from the order rather than
 * from any stored counter so that adding a playlist never renumbers the ones already
 * on the device.
 */
internal fun playlistsOf(sources: List<ProviderSource>): List<Playlist> =
    sources.mapIndexed { index, source ->
        // `label` is what the user typed, or empty when they typed nothing — the
        // repository keeps that distinction rather than filling it with the host. The
        // placeholder is the screen's, because it is a translated string.
        Playlist(id = source.id, name = source.label, position = index + 1)
    }

/** The four `COUNT`s, carried together so the outer combine stays within its arity. */
private data class Counts(
    val live: Int = 0,
    val movies: Int = 0,
    val series: Int = 0,
    val radio: Int = 0,
)

/**
 * What the cards say about loads, carried together for the same reason as [Counts]: the
 * queue's latest answers, the sections that arrived a moment ago, the card last pressed
 * while it was on its way.
 */
private data class CardInputs(
    val runs: Map<MediaKind, SectionRun>,
    val arrived: Set<MediaKind>,
    val hinted: MediaKind?,
)

/**
 * Reads Home, and re-reads it when the catalogue underneath changes.
 *
 * The counts are flows, so an import finishing behind this screen moves the numbers
 * without anyone asking — which is the whole of what "the content appears after the
 * subscription succeeds" needs to be.
 *
 * It reads no rows. It used to read twenty items of each kind to draw a sample row on
 * Home, and those rows are gone: three bounded queries on every visit to a screen that
 * now shows counts, not content. The section screens page properly and are one press
 * away, which is where a row belongs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    catalog: CatalogRepository,
    private val sources: SourceRepository,
    entitlement: EntitlementRepository,
    marks: SectionCatalogue,
    statuses: ProviderStatusCatalogue,
    identity: DeviceIdentity,
    private val refresher: RefreshProvider,
    private val clock: TrustedTime,
    private val loads: SectionLoads,
    private val network: NetworkState,
) : ViewModel() {

    private val mac: String = identity.current().macAddress.value
    private val deviceKey: String = identity.key().value

    /**
     * Ask the provider again, and let the recorded answer move the header.
     *
     * Nothing is returned and nothing is held: the store is a flow this screen is
     * already collecting, so a new answer arrives the same way an import's counts do
     * — the numbers simply change. A result carried back through the state would be a
     * second path to the same fact, and the two would drift.
     *
     * It downloads no catalogue. See [RefreshProvider] for why that is the point
     * rather than a limitation.
     */
    fun refresh() {
        // Guarded: a second press while one is in flight would start a second network
        // call whose answer would be recorded over the first for no benefit, and would
        // let the spinner stop while a request was still running.
        if (_refresh.value.busy) return
        // A press inside the last answer's few seconds is a refresh of its own: the earlier
        // answer's timer must not end the new one's early.
        answered?.cancel()
        viewModelScope.launch {
            _refresh.value = RefreshUi(busy = true)
            // `nowMs` read here, not held: the answer is stamped with the instant it
            // was judged at, which is what the recorded status is compared against.
            val outcome = refresher.refresh(clock.nowMs())
            _refresh.value = RefreshUi(
                busy = false,
                fault = when (outcome) {
                    is Refreshed.Updated -> null
                    Refreshed.NoProvider -> RefreshFault.NoProvider
                    is Refreshed.Unreachable -> RefreshFault.Unreachable
                },
                done = outcome is Refreshed.Updated,
            )
            contactOf(outcome)?.let { contact.value = it }
            // A failure stays until the card is pressed again: one that went on a timer is
            // one a viewer looks up to find already gone. Only an answer goes by itself.
            if (outcome is Refreshed.Updated) {
                answered = viewModelScope.launch {
                    delay(REFRESHED_MS)
                    _refresh.update { it.copy(done = false) }
                }
            }
        }
    }

    /** The timer that ends the card's "refreshed", while one is running. */
    private var answered: Job? = null

    /**
     * What the refresh control is doing, and what the last attempt produced.
     *
     * Held here rather than derived, because it is the *only* thing on this screen
     * that is not a projection of the database. Everything else — the counts, the
     * status, the playlists — is a flow the store owns, and this is a fact about a
     * button press that no store knows about.
     */
    private val _refresh = MutableStateFlow(RefreshUi())

    /**
     * How the last asking of the provider went: at Home's opening, or at a press of refresh.
     *
     * Held here for the reason [_refresh] is: no store knows it. The recorded answer is a
     * store's, and outlives a failure on purpose -- the date from before stays true -- so
     * the failure itself has to be kept somewhere, or the screen could not say it.
     */
    private val contact = MutableStateFlow<Contact?>(null)

    /**
     * An asking's outcome, as the trouble line reads it. The network is read now, at the
     * failure, because the question is why this attempt failed. Null for "nobody to ask".
     */
    private fun contactOf(outcome: Refreshed?): Contact? = when (outcome) {
        is Refreshed.Updated -> Contact.Answered
        is Refreshed.Unreachable -> Contact.Failed(outcome.error, network.isOnline())
        Refreshed.NoProvider, null -> null
    }

    /**
     * Show a different playlist.
     *
     * One line, and deliberately so. `SourceRepository.setActive` already exists, is
     * already the only writer of that flag, and already does it in one transaction so
     * there is never a moment with two active sources or none — `SavedSourcesViewModel`
     * has forwarded to it since that screen shipped, and this is the same forward from
     * a second place, not a second mechanism.
     *
     * Nothing is flipped optimistically and nothing is reloaded here. [provider] is
     * `sources.active()`, and the sections and the subscription status hang off it
     * through `flatMapLatest`, so the store re-emitting is what moves Home — the tick
     * moves because the database said so, not because this assumed it would. No
     * import is triggered: the catalogue rows of every playlist are already on the
     * device, keyed by source.
     */
    fun choosePlaylist(id: String) {
        viewModelScope.launch { sources.setActive(id) }
    }

    /**
     * A section's card was pressed. True when the screen should open the section now.
     *
     * A section on the device opens at once, with what it holds -- and one whose mark a
     * provider refresh forgot is fetched again behind it, the import a press of it always
     * started, now without a screen in front of the rows. A section that is not here is
     * asked for, and the viewer stays on Home to watch its card; pressed again while it is
     * on its way, the card says when it will open. A failed one is asked again: one press,
     * one attempt, and nothing retries on its own.
     *
     * Live TV does not wait for its load to end: it opens by itself with its first
     * category -- see [opens] -- and a press before that changes nothing on its card. Any
     * press ends the wait for a section pressed before it: the newest press is the one the
     * viewer means.
     */
    fun press(section: CatalogSection): Boolean {
        val kind = section.kind
        val current = state.value
        val onDevice = current.onDevice(kind)
        val press = pressOf(kind, onDevice, marked = current.sections[kind] != null, run = loads.runs.value[kind])
        awaited.value = null
        return when (press) {
            Press.Open -> true
            Press.OpenAndFetch -> {
                loads.request(kind)
                true
            }
            Press.Hint -> {
                hint(kind)
                false
            }
            Press.Await -> {
                awaited.value = kind
                false
            }
            Press.Fetch -> {
                // Asked before it is awaited, so the wait starts on an answer of its own
                // rather than on no answer at all -- which it would take for a dropped load.
                loads.request(kind, first = !onDevice)
                if (opensWithFirstCategory(kind)) awaited.value = kind
                false
            }
        }
    }

    /**
     * The section Home is waiting to open by itself: Live TV, pressed with nothing of it on
     * the device, until its first category is listed. Ended by any other press, by its load
     * failing, and by the viewer leaving Home -- see [leftHome].
     */
    private val awaited = MutableStateFlow<MediaKind?>(null)

    private val _opens = MutableSharedFlow<CatalogSection>(extraBufferCapacity = 1)

    /**
     * A section to open now, without a press: Live TV with its first category listed, the
     * rest of it still arriving. Told only to a Home that is collecting it -- nothing is
     * kept for later, so a viewer who has left Home is not taken anywhere on the way back.
     */
    val opens: SharedFlow<CatalogSection> = _opens.asSharedFlow()

    /** Home is no longer what the viewer is looking at: nothing opens by itself any more. */
    fun leftHome() {
        awaited.value = null
    }

    /** The card pressed while its section is on its way: [HINT_MS] of "opens when loaded". */
    private fun hint(kind: MediaKind) {
        hintJob?.cancel()
        hinted.value = kind
        hintJob = viewModelScope.launch {
            delay(HINT_MS)
            hinted.value = null
        }
    }

    private val hinted = MutableStateFlow<MediaKind?>(null)
    private var hintJob: Job? = null

    /**
     * Sections whose load ended moments ago, for the card's [LOADED_MS] of "loaded".
     *
     * Watched for the holder's whole life rather than while Home is on screen, and only
     * from the second answer on: a load that ended before anybody was watching is simply
     * a count, not news.
     */
    private val arrived = MutableStateFlow<Set<MediaKind>>(emptySet())

    init {
        // The subscription is asked about, quietly, when this holder starts -- each time
        // Castivio is opened -- and again for every subscription the menu switches to: the
        // expiry date belongs on the card before any section is loaded, and whether the
        // server answers *now* is what the last line says when it does not. The same
        // question as [refresh], without letting the sections go stale -- see
        // [RefreshProvider.ask]. A switch clears the previous subscription's trouble first:
        // it was that one's, not this one's.
        viewModelScope.launch {
            // `sources.active()` and not [provider]: this runs before the properties below
            // it are initialised, and [provider] is one of them.
            sources.active().map { it?.id }.distinctUntilChanged().collectLatest { id ->
                contact.value = null
                if (id == null) return@collectLatest
                try {
                    contact.value = contactOf(refresher.ask(clock.nowMs()))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A question that broke rather than failed says nothing about the
                    // subscription, so nothing is said about it: the line stays the
                    // disclaimer, and refresh asks again.
                }
            }
        }
        viewModelScope.launch {
            var before: Map<MediaKind, SectionRun>? = null
            loads.runs.collect { runs ->
                val previous = before
                before = runs
                if (previous != null) arrivedBetween(previous, runs).forEach(::arrive)
            }
        }
        // The section Home waits on, opened when its first category is listed -- see
        // [awaitOf] -- or let go when there is nothing to open.
        viewModelScope.launch {
            combine(awaited, loads.runs) { kind, runs -> kind?.let { it to awaitOf(it, runs) } }
                .collect { waiting ->
                    val (kind, next) = waiting ?: return@collect
                    when (next) {
                        Await.Keep -> Unit
                        Await.Open -> {
                            awaited.value = null
                            _opens.tryEmit(CatalogSection.entries.first { it.kind == kind })
                        }
                        Await.Drop -> awaited.value = null
                    }
                }
        }
    }

    private fun arrive(kind: MediaKind) {
        arrived.update { it + kind }
        viewModelScope.launch {
            delay(LOADED_MS)
            arrived.update { it - kind }
        }
    }

    private val cardInputs: Flow<CardInputs> =
        combine(loads.runs, arrived, hinted) { runs, done, pressed -> CardInputs(runs, done, pressed) }

    private val counts: Flow<Counts> = combine(
        catalog.count(MediaKind.LIVE),
        catalog.count(MediaKind.MOVIE),
        catalog.count(MediaKind.SERIES),
        catalog.count(MediaKind.RADIO),
    ) { live, movies, series, radio -> Counts(live, movies, series, radio) }

    private val provider: Flow<ProviderSource?> = sources.active()

    /**
     * Every playlist on the device, numbered in the order they were added.
     *
     * The order is the repository's, which is `created_at` — so the number beside an
     * unnamed playlist is stable: adding a fourth does not renumber the first three,
     * and a user who was told "Playlist 2" yesterday still sees Playlist 2 today.
     * Deleting one does renumber the ones after it, which is the same thing any
     * ordinal placeholder does and the reason a user who cares is invited to name it.
     */
    private val playlists: Flow<List<Playlist>> = sources.sources().map(::playlistsOf)

    /**
     * The marks belonging to whichever provider is active.
     *
     * `flatMapLatest` rather than a join, because the marks are keyed by source: on a
     * box with three subscriptions, switching provider must not leave the tiles
     * showing the previous one's dates for a frame.
     */
    private val sections: Flow<Map<MediaKind, Long>> = provider.flatMapLatest { active ->
        if (active == null) flowOf(emptyMap()) else marks.loaded(active.id)
    }

    /** The active provider's own last answer, keyed the same way and for the same reason. */
    private val subscription: Flow<Recorded?> = provider.flatMapLatest { active ->
        if (active == null) flowOf(null) else statuses.of(active.id)
    }

    /**
     * The active source and the whole shelf it came off, carried together.
     *
     * The same reason [Counts] exists: `combine` is typed up to five flows and this
     * screen now has six facts to assemble. Pairing the two that are about *which
     * playlist* keeps the outer combine within its arity without an array overload
     * that would lose every parameter name.
     */
    private val library: Flow<Library> = combine(provider, playlists) { active, all ->
        Library(active, all)
    }

    val state: StateFlow<HomeState> = combine(
        library,
        counts,
        entitlement.state,
        // The marks and the cards' inputs together: both say what each section is doing,
        // and `combine` is typed to five.
        sections.combine(cardInputs) { marks, inputs -> marks to inputs },
        // Together because all three are answers about the same question — what the
        // provider says — and because `combine` is typed to five.
        combine(subscription, _refresh, contact) { recorded, refresh, asked -> Answer(recorded, refresh, asked) },
    ) { shelf, tally, licence, fetchedAndCards, answer ->
        val (status, refresh, asked) = answer
        val (fetched, cards) = fetchedAndCards
        val source = shelf.active
        val base = HomeState(
            provider = source?.label,
            sourceKind = source?.kind,
            importedCount = source?.sync?.itemCount ?: 0,
            liveCount = tally.live,
            movieCount = tally.movies,
            seriesCount = tally.series,
            radioCount = tally.radio,
            entitlement = licence,
            sections = fetched,
            subscription = status,
            mac = mac,
            deviceKey = deviceKey,
            playlists = shelf.all,
            activePlaylistId = source?.id,
            refreshing = refresh.busy,
            refreshFault = refresh.fault,
            refreshed = refresh.done,
            trouble = subscriptionTrouble(asked, status),
            loading = false,
        )
        base.copy(
            loads = MediaKind.entries.associateWith { kind ->
                cardLoad(
                    run = cards.runs[kind],
                    onDevice = base.onDevice(kind),
                    justArrived = kind in cards.arrived,
                    hinted = cards.hinted == kind,
                    opensWithFirst = opensWithFirstCategory(kind),
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), HomeState())

    /** See [library]. */
    private data class Library(val active: ProviderSource?, val all: List<Playlist>)

    /** What the provider has said, what the refresh card is doing, and how the last asking went. */
    private data class Answer(val recorded: Recorded?, val refresh: RefreshUi, val asked: Contact?)

    /** See [_refresh]. */
    private data class RefreshUi(val busy: Boolean = false, val fault: RefreshFault? = null, val done: Boolean = false)

    private companion object {
        const val SUBSCRIPTION_GRACE_MS = 5_000L

        /** How long a card says its section has just loaded before it goes back to its count. */
        const val LOADED_MS = 3_000L

        /** How long the refresh card says the provider answered: as long as a section's card. */
        const val REFRESHED_MS = LOADED_MS

        /** How long a card pressed on its way says when it will open. */
        const val HINT_MS = 2_000L
    }
}
