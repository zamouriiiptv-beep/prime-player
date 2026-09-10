package com.castivio.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.domain.CatalogRepository
import com.castivio.domain.MediaKind
import com.castivio.domain.ProviderSource
import com.castivio.domain.ProviderStatusCatalogue
import com.castivio.domain.Recorded
import com.castivio.domain.RefreshProvider
import com.castivio.domain.SectionCatalogue
import com.castivio.domain.SourceKind
import com.castivio.domain.SourceRepository
import com.castivio.domain.entitlement.EntitlementRepository
import com.castivio.domain.entitlement.EntitlementState
import com.castivio.domain.identity.DeviceIdentity
import com.castivio.domain.time.TrustedTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    /** What the user named their provider, or what its host is. Null before activation. */
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
     * This device's address, which is what a provider activating by MAC asks for.
     *
     * Read once and carried in the state rather than looked up where it is drawn: it
     * is derived from a seed the operating system keeps and cannot change while the
     * app is running, so re-deriving it per composition would be work with a
     * guaranteed identical answer.
     */
    val mac: String = "",
    val loading: Boolean = true,
) {
    /** True once a provider has been configured, whatever it did or did not carry. */
    val hasSource: Boolean get() = provider != null

    /** True when nothing has been counted — which is the normal state before any
     * section has been opened, and says nothing about the provider. */
    val isEmpty: Boolean get() =
        liveCount == 0 && movieCount == 0 && seriesCount == 0 && radioCount == 0

    /** True once every section has been asked for at least once. */
    val everySectionFetched: Boolean get() = MediaKind.entries.all { it in sections }

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

/** The four `COUNT`s, carried together so the outer combine stays within its arity. */
private data class Counts(
    val live: Int = 0,
    val movies: Int = 0,
    val series: Int = 0,
    val radio: Int = 0,
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
    sources: SourceRepository,
    entitlement: EntitlementRepository,
    marks: SectionCatalogue,
    statuses: ProviderStatusCatalogue,
    identity: DeviceIdentity,
    private val refresher: RefreshProvider,
    private val clock: TrustedTime,
) : ViewModel() {

    private val mac: String = identity.current().macAddress.value

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
        viewModelScope.launch { refresher.refresh(clock.nowMs()) }
    }

    private val counts: Flow<Counts> = combine(
        catalog.count(MediaKind.LIVE),
        catalog.count(MediaKind.MOVIE),
        catalog.count(MediaKind.SERIES),
        catalog.count(MediaKind.RADIO),
    ) { live, movies, series, radio -> Counts(live, movies, series, radio) }

    private val provider: Flow<ProviderSource?> = sources.active()

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

    val state: StateFlow<HomeState> = combine(
        provider,
        counts,
        entitlement.state,
        sections,
        subscription,
    ) { source, tally, licence, fetched, status ->
        HomeState(
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
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), HomeState())

    private companion object {
        const val SUBSCRIPTION_GRACE_MS = 5_000L
    }
}
