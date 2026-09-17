package com.castivio.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.domain.Channel
import com.castivio.domain.EpgRepository
import com.castivio.domain.FavoritesRepository
import com.castivio.domain.NowNext
import com.castivio.domain.Programme
import com.castivio.domain.time.TrustedTime
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The half of the Channels board that is about **one** channel: the one the preview is
 * showing.
 *
 * ## Why this is beside `BrowseViewModel` rather than inside it
 *
 * `BrowseViewModel` answers the questions all four sections ask — categories, counts,
 * the paged rows, the sort, whether the section has been fetched — and Channels asks
 * every one of them unchanged. Adding the guide and the favourite to it would put two
 * dependencies into the holder Movies, Series and Radio also run on, for a panel only
 * this screen draws. So the board composes both: `BrowseViewModel` for the catalogue,
 * this for the selection. Nothing in `BrowseViewModel` was modified.
 *
 * ## What it will not do
 *
 * It does not fetch. Selecting a row must not cost a round trip — the reference draws
 * the preview the instant focus lands, and a panel that waits on the network would make
 * arrowing down the list feel like the slowest part of the application. Everything here
 * is either already on the device (the guide) or a single indexed read (the favourite).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChannelsViewModel @Inject constructor(
    private val epg: EpgRepository,
    private val favorites: FavoritesRepository,
    private val clock: TrustedTime,
) : ViewModel() {

    private val _selected = MutableStateFlow<Channel?>(null)

    /** The channel the preview is showing, or null before a row has been focused. */
    val selected: StateFlow<Channel?> = _selected.asStateFlow()

    private val guide = MutableStateFlow<NowNext?>(null)

    /**
     * What is on next, and after that.
     *
     * [NowNext] carries exactly two programmes and the approved board draws three rows,
     * so the schedule is read with [EpgRepository.programmes] over a bounded window
     * instead. One query, not two: `now` and `next` are the first entries of the same
     * list, so the panel and the guide cannot disagree about what is on.
     *
     * Bounded by [SCHEDULE_HORIZON_MS] rather than unbounded, because this is a preview
     * beside a list and not the guide screen: reading a channel's whole week to draw
     * three rows is the kind of query the data rules exist to prevent.
     */
    private val schedule = MutableStateFlow<List<Programme>>(emptyList())

    /**
     * Where the focused channel sits in the list as it is currently ordered.
     *
     * Held here rather than read off [Channel], because a channel does not carry one.
     * `Channel.number` is `null` for every row from every provider -- `Mappers.kt` wrote
     * the literal -- so the plate on the board and the figure on the display were `----`
     * by construction rather than because a provider had numbered nothing.
     *
     * The list's own position is the honest answer and the one the reference shows: its
     * badges run 1, 2, 3 down the visible order. It follows the sort, which is correct --
     * re-ordered A to Z, the number is where the channel now is.
     */
    private val position = MutableStateFlow<Int?>(null)

    /**
     * What the preview panel shows, as one value.
     *
     * Combined here rather than collected as three flows in the composition, because a
     * screen that collects three flows draws three times for one selection change — and
     * on a television that is three frames of a panel disagreeing with itself.
     */
    val preview: StateFlow<ChannelPreview> = combine(
        _selected,
        guide,
        schedule,
        position,
        _selected.flatMapLatest { channel ->
            // `isFavorite` is an indexed EXISTS, and it is a flow because the remote's
            // blue key toggles it from this very screen: a star that only refreshed on
            // navigation would be a star that lies for as long as the user stays.
            channel?.let { favorites.isFavorite(it.id) } ?: flowOf(false)
        },
    ) { channel, nowNext, coming, number, favorite ->
        ChannelPreview(
            channel = channel,
            guide = nowNext,
            schedule = coming,
            number = number,
            favorite = favorite,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(GRACE_MS), ChannelPreview())

    /**
     * A row was focused or pressed.
     *
     * Idempotent on the same channel, because focus is re-reported for reasons that are
     * not a move — a recomposition, a window regaining focus — and re-reading the guide
     * for each of those would be a query per frame on the list a D-pad is held down on.
     */
    fun select(channel: Channel, number: Int?) {
        // Outside the guard, because this one *can* change without the channel
        // changing: a re-sort moves every row without moving the selection.
        position.value = number
        if (_selected.value?.id == channel.id) return
        _selected.value = channel
        // Cleared first, and deliberately: the previous channel's programme must never
        // be left under the new channel's name, not even for the frame it takes to read
        // the guide. An absent programme is a sentence the panel already has.
        guide.value = null
        schedule.value = emptyList()
        loadGuide(channel)
    }

    private fun loadGuide(channel: Channel) {
        val epgId = channel.epgChannelId ?: return
        viewModelScope.launch {
            val nowMs = clock.nowMs()
            val coming = runCatching {
                epg.programmes(epgId, nowMs, nowMs + SCHEDULE_HORIZON_MS)
            }.getOrDefault(emptyList())

            // Checked against the current selection rather than assumed: a viewer
            // holding the down key moves faster than a query returns, and without this
            // a slow read for row 4 would overwrite the panel showing row 11.
            if (_selected.value?.id != channel.id) return@launch

            schedule.value = coming.take(SCHEDULE_ROWS)
            // `now` is whichever entry actually contains this instant -- asked of the
            // programme rather than assumed of the first row, because a channel with a
            // gap in its schedule has a next without having a now, and drawing the
            // upcoming programme as the current one is the kind of small lie a viewer
            // catches immediately.
            guide.value = NowNext(
                now = coming.firstOrNull { it.isLiveAt(nowMs) },
                next = coming.firstOrNull { it.startMs > nowMs },
            )
        }
    }

    /** The blue key, and the star in the row. Returns nothing: the flow reports the result. */
    fun toggleFavorite() {
        val channel = _selected.value ?: return
        viewModelScope.launch { favorites.toggle(channel.id) }
    }

    private companion object {
        /** Matches `BrowseViewModel`: long enough to survive a rotation, short enough to let go. */
        const val GRACE_MS = 5_000L

        /** Far enough ahead to fill three rows on any channel that has a guide at all. */
        const val SCHEDULE_HORIZON_MS = 8 * 60 * 60 * 1000L

        /** What the board draws. More would be the guide screen, which this is not. */
        const val SCHEDULE_ROWS = 3
    }
}

/**
 * Everything the preview panel draws, resolved.
 *
 * Nullable throughout on purpose. A channel with no guide id, a guide that has not been
 * imported, and a programme that has ended are three different absences, and all three
 * end here as `guide == null` — which the panel renders as the reference's own
 * "No Information" rather than as an invented programme.
 */
data class ChannelPreview(
    val channel: Channel? = null,
    val guide: NowNext? = null,
    /** What is on, then what follows, in order. Empty when the guide has nothing. */
    val schedule: List<Programme> = emptyList(),
    /** The channel's position in the list as ordered. See `ChannelsViewModel.position`. */
    val number: Int? = null,
    val favorite: Boolean = false,
)

/**
 * How far through the current programme we are, from 0 to 1, or null when there is no
 * programme to be through.
 *
 * Pure arithmetic on two timestamps the guide already carries, kept out of the
 * composition because `PERFORMANCE.md` forbids work in composition and because a
 * function is something a test can hold to account. A programme whose stop is not after
 * its start yields null rather than a division by zero or a bar pinned at either end.
 */
internal fun NowNext.progressAt(nowMs: Long): Float? {
    val programme = now ?: return null
    val span = programme.stopMs - programme.startMs
    if (span <= 0L) return null
    return ((nowMs - programme.startMs).toFloat() / span).coerceIn(0f, 1f)
}
