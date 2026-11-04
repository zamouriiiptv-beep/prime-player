package com.castivio.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.core.common.Outcome
import com.castivio.core.common.format.CastivioDates
import com.castivio.domain.CatalogRepository
import com.castivio.domain.CatchUpChannel
import com.castivio.domain.CatchUpGateway
import com.castivio.domain.CatchUpListing
import com.castivio.domain.CatchUpPolicy
import com.castivio.domain.CatchUpProgramme
import com.castivio.domain.Channel
import com.castivio.domain.searchTermOf
import com.castivio.domain.time.TrustedTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.TimeZone
import javax.inject.Inject

/**
 * The catch-up screen's state: one channel's archive, in days, and the choice of a programme to watch.
 *
 * ## Nothing is asked until the screen opens
 *
 * Which channels have an archive is the catalogue's own value, read by [CatchUpHubViewModel] a page at a time, and never
 * touches a network. [open] is the one call that reaches a provider and is made when a channel is opened in Catch-up;
 * [choose] reaches it only through a gateway that builds an address and asks nothing. **[close] cancels whatever is
 * out**, and the screen calls it when it is left, so a request for an archive that nobody is looking at is not left
 * running.
 *
 * ## One request, seven days
 *
 * The listing is a single reply for the channel, so moving between days is [selectDay] and reads nothing: the days were
 * made when the reply arrived. Everything the screen draws -- the day headings, the times -- is formatted here, once, and
 * not while the list scrolls.
 */
@HiltViewModel
class CatchUpViewModel @Inject constructor(
    private val gateway: CatchUpGateway,
    /** The provider's own stream id for a channel, which `Channel` does not carry: see [ChannelsViewModel]. */
    private val catalog: CatalogRepository,
    private val clock: TrustedTime,
) : ViewModel() {

    private val _state = MutableStateFlow(CatchUpState())
    val state: StateFlow<CatchUpState> = _state.asStateFlow()

    /** What the search box holds, exactly as typed: read straight off the flow, so the field never lags the keys. */
    private val typed = MutableStateFlow("")

    val searchText: StateFlow<String> = typed.asStateFlow()

    /** [typed], folded and held back until the viewer pauses; empty is no search. */
    @OptIn(FlowPreview::class)
    private val term = typed
        .map(::searchTermOf)
        .distinctUntilChanged()
        .debounce { if (it.isEmpty()) 0L else CATCHUP_SEARCH_PAUSE_MS }
        .distinctUntilChanged()

    /**
     * The programmes of the day that is chosen, narrowed by the search when there is one.
     *
     * Only that day's: the search does not touch the days, which are the viewer's to move between, and a word that
     * matches nothing on this day is told as such, with the days left where they are.
     */
    val view: StateFlow<CatchUpView> = combine(_state, term) { state, words ->
        val cards = state.days.getOrNull(state.dayIndex)?.cards.orEmpty()
        if (words.isEmpty()) CatchUpView(cards, "") else CatchUpView(matchingCards(cards, words), words)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), CatchUpView(emptyList(), ""))

    private var loading: Job? = null
    private var opening: Job? = null
    private var target: CatchUpChannel? = null
    private var shown: Channel? = null

    /** The viewer typed in the search box. */
    fun search(text: String) {
        typed.value = text
    }

    /** Closes a search that holds something, and says whether there was one: the first Back is the search's. */
    fun clearSearch(): Boolean {
        if (typed.value.isEmpty()) return false
        typed.value = ""
        return true
    }

    /** Asks for [channel]'s archive. The one call that reaches a provider; a second while one is out replaces it. */
    fun open(channel: Channel) {
        val hours = channel.catchUpHours ?: return
        typed.value = ""
        loading?.cancel()
        opening?.cancel()
        shown = channel
        _state.value = CatchUpState(phase = CatchUpPhase.LOADING)
        loading = viewModelScope.launch {
            val ref = runCatching { catalog.channelRefs(listOf(channel.id)) }.getOrDefault(emptyList()).firstOrNull()
            val request = CatchUpChannel(
                mediaId = channel.id,
                providerRef = ref?.providerRef,
                title = channel.title,
                archiveHours = hours,
            )
            target = request
            val nowMs = clock.nowMs()
            _state.value = when (val listing = gateway.listing(request, nowMs)) {
                is CatchUpListing.Programmes ->
                    if (listing.items.isEmpty()) CatchUpState(phase = CatchUpPhase.EMPTY) else ready(listing.items, nowMs)

                is CatchUpListing.NotSupported -> CatchUpState(phase = CatchUpPhase.UNSUPPORTED)
                is CatchUpListing.Failed -> CatchUpState(phase = CatchUpPhase.FAILED)
            }
        }
    }

    /** The viewer left the screen: nothing it started goes on, and nothing of it is kept on screen. */
    fun close() {
        typed.value = ""
        loading?.cancel()
        opening?.cancel()
        loading = null
        opening = null
        target = null
        shown = null
        _state.value = CatchUpState()
    }

    /** Again, after a failure. */
    fun retry() {
        shown?.let(::open)
    }

    /** Another day of the archive already in hand: no request. */
    fun selectDay(index: Int) {
        _state.update { it.copy(dayIndex = index.coerceIn(0, (it.days.size - 1).coerceAtLeast(0)), openFailed = false) }
    }

    /**
     * The viewer chose [card]: its address is built and handed to [onAddress], or the failure is shown.
     *
     * Nothing is asked of the provider here -- see [CatchUpGateway.playbackUrl] -- and a second press while the first is
     * being resolved is ignored rather than started twice.
     */
    fun choose(card: CatchUpCard, onAddress: (address: String, channelTitle: String) -> Unit) {
        val request = target ?: return
        if (_state.value.opening) return
        _state.update { it.copy(opening = true, openFailed = false) }
        opening = viewModelScope.launch {
            when (val result = gateway.playbackUrl(request, card.programme)) {
                is Outcome.Success -> {
                    _state.update { it.copy(opening = false) }
                    onAddress(result.value, request.title)
                }

                is Outcome.Failure -> _state.update { it.copy(opening = false, openFailed = true) }
            }
        }
    }

    private fun ready(items: List<CatchUpProgramme>, nowMs: Long): CatchUpState {
        val offsetAt = { atMs: Long -> TimeZone.getDefault().getOffset(atMs).toLong() }
        val todayOrdinal = (nowMs + offsetAt(nowMs)).floorDiv(CatchUpPolicy.DAY_MS)
        val days = CatchUpPolicy.days(items, offsetAt).map { day ->
            val ordinal = (day.dayStartMs + offsetAt(day.dayStartMs)).floorDiv(CatchUpPolicy.DAY_MS)
            CatchUpDayView(
                label = when (todayOrdinal - ordinal) {
                    0L -> DayLabel.TODAY
                    1L -> DayLabel.YESTERDAY
                    else -> DayLabel.DATE
                },
                date = CastivioDates.date(day.dayStartMs),
                cards = day.programmes.map { p ->
                    CatchUpCard(
                        programme = p,
                        title = p.title.ifBlank { UNTITLED },
                        timeText = "${CastivioDates.time(p.startMs)} – ${CastivioDates.time(p.stopMs)}",
                    )
                },
            )
        }
        return CatchUpState(phase = CatchUpPhase.READY, days = days)
    }

    private companion object {
        /** How long the programmes are kept after the screen is left, for a quick return. */
        const val STOP_MS = 5_000L

        /** A programme the guide gave no name: the card still has to say something, and it is not a string to translate. */
        const val UNTITLED = "-"
    }
}

enum class CatchUpPhase { IDLE, LOADING, READY, EMPTY, UNSUPPORTED, FAILED }

enum class DayLabel { TODAY, YESTERDAY, DATE }

/** One programme, ready to draw: its text made once, with the programme it came from for the choice. */
data class CatchUpCard(val programme: CatchUpProgramme, val title: String, val timeText: String)

/** What the grid draws: the chosen day's programmes, narrowed by the search, and the search's words -- empty for none. */
data class CatchUpView(val cards: List<CatchUpCard>, val term: String) {
    val searching: Boolean get() = term.isNotEmpty()
}

data class CatchUpDayView(val label: DayLabel, val date: String, val cards: List<CatchUpCard>)

data class CatchUpState(
    val phase: CatchUpPhase = CatchUpPhase.IDLE,
    /** Newest day first. */
    val days: List<CatchUpDayView> = emptyList(),
    val dayIndex: Int = 0,
    /** A chosen programme's address is being made. */
    val opening: Boolean = false,
    /** The last choice could not be turned into an address. */
    val openFailed: Boolean = false,
)
