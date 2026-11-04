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
import com.castivio.domain.time.TrustedTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private var loading: Job? = null
    private var opening: Job? = null
    private var target: CatchUpChannel? = null
    private var shown: Channel? = null

    /** Asks for [channel]'s archive. The one call that reaches a provider; a second while one is out replaces it. */
    fun open(channel: Channel) {
        val hours = channel.catchUpHours ?: return
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
        /** A programme the guide gave no name: the card still has to say something, and it is not a string to translate. */
        const val UNTITLED = "-"
    }
}

enum class CatchUpPhase { IDLE, LOADING, READY, EMPTY, UNSUPPORTED, FAILED }

enum class DayLabel { TODAY, YESTERDAY, DATE }

/** One programme, ready to draw: its text made once, with the programme it came from for the choice. */
data class CatchUpCard(val programme: CatchUpProgramme, val title: String, val timeText: String)

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
