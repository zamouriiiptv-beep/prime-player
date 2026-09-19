package com.castivio.feature.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.domain.CatalogRepository
import com.castivio.domain.Channel
import com.castivio.domain.EpgRepository
import com.castivio.domain.FavoritesRepository
import com.castivio.domain.NowNext
import com.castivio.domain.NowNextRefresher
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
 * It never makes the panel wait. Selecting a row draws from what is on the device — one
 * indexed read for the guide, one for the favourite — and nothing on that path touches
 * the network. A channel whose guide is genuinely absent is the single exception: one
 * `get_short_epg` for that one channel is issued behind the panel, throttled by
 * [ASK_AGAIN_MS], and the panel fills when it lands. Opening the board asks for nothing,
 * walking the list asks for nothing, and playback never waits on any of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChannelsViewModel @Inject constructor(
    private val epg: EpgRepository,
    /**
     * The provider's own stream id for a channel, which `Channel` does not carry.
     *
     * A guide request is addressed by that id while the catalogue is keyed by ours, so the
     * ref has to be fetched even though two of its three fields are already in hand. One
     * indexed read of three columns for one row — see `MediaDao.refsFor`.
     */
    private val catalog: CatalogRepository,
    /**
     * The cheap guide path: `get_short_epg`, about two kilobytes for one channel.
     *
     * Injected as the domain interface, so this module gains nothing from `:data:epg` —
     * both contracts already live in `:domain`, which `:feature:home` already depends on.
     */
    private val nowNext: NowNextRefresher,
    private val favorites: FavoritesRepository,
    private val clock: TrustedTime,
) : ViewModel() {

    /**
     * When this channel was last asked of the network, by media id.
     *
     * **A throttle, not a cache.** Room is the store and it is consulted first; this map is
     * only reached when Room had nothing, and its job is to stop a provider that answers
     * with an empty guide from being asked again on every press. Losing it on a process
     * death costs one request, which is why it is allowed to be in memory at all.
     */
    private val asked = HashMap<String, Long>()

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

    /**
     * What is on this channel: from storage if it is there, from the provider if it is not.
     *
     * ## Why there was nothing to read
     *
     * The guide's whole write path exists — importer, refresher, parser, writer — and
     * nothing called it, so the `programme` table was empty on every device and the panel
     * drew its identity row and stopped. This is the call that was missing.
     *
     * ## The order, which is the performance contract
     *
     * 1. **Storage first.** A channel whose guide is already down costs one indexed read
     *    and no network at all.
     * 2. **One request, for this channel.** `NowNextRefresher` will take forty channels;
     *    it is handed one, because the panel shows one. Forty would be thirty-nine
     *    responses nobody looks at.
     * 3. **Read it back.** The refresher reports how many rows it wrote, not what they
     *    say, so the panel is filled from the store either way — one path to the screen
     *    rather than two that can disagree.
     *
     * Nothing here is on the way to anything. Opening the board issues no request, moving
     * the remote issues no request, and playback never waits on this: the stream is opened
     * from the catalogue by the press that also lands here.
     */
    private fun loadGuide(channel: Channel) {
        viewModelScope.launch {
            val nowMs = clock.nowMs()
            if (show(channel, nowMs)) return@launch

            // Stale rather than absent is the same answer here: a provider that returned an
            // empty guide a minute ago will return one now, and asking it per press is how
            // a quiet failure becomes a busy one.
            val last = asked[channel.id]
            if (last != null && nowMs - last < ASK_AGAIN_MS) return@launch
            asked[channel.id] = nowMs

            val refs = runCatching { catalog.channelRefs(listOf(channel.id)) }
                .getOrDefault(emptyList())
            if (refs.isEmpty()) return@launch

            // A failure is one channel's guide, not an error state: the panel keeps what it
            // has and the stream is untouched.
            val wrote = runCatching { nowNext.refresh(refs) }.getOrDefault(0)
            // Ids and counts only. The provider's host, user and password are not here and
            // must never be: this line exists to answer "does our panel answer
            // get_short_epg at all", and a credential in logcat answers nothing.
            val hasStreamId = !refs.first().providerRef.isNullOrBlank()
            Log.i(
                TAG,
                "short epg: media=${channel.id} guide=${channel.epgChannelId ?: "-"} " +
                    "streamId=$hasStreamId wrote=$wrote",
            )

            // The viewer may have moved on while the request was out. Re-read anyway --
            // the rows are stored and will serve the next visit -- but let `show` decide
            // whether they may touch the panel.
            show(channel, clock.nowMs())
        }
    }

    /**
     * Publish what storage holds for this channel, and say whether it held anything.
     *
     * ## The two keys, and why this is not a second identifier scheme
     *
     * `XtreamNowNextRefresher` files a programme under the channel's guide id, or the id
     * the response carried, or the media id — in that order. Those are the *same two ids
     * this channel already carries*: `ChannelRef.epgChannelId` and `ChannelRef.mediaId` are
     * read from the very row `Channel` came from. So reading both is following the writer's
     * own order, not inventing one.
     *
     * The middle branch is the one this cannot follow: if our catalogue has no guide id and
     * the provider's *response* carries one, the row is filed under a string this side
     * never sees. `refresh` returns a count, not the keys it used, so the reader cannot
     * learn it without changing `:data:epg` — which is out of scope here and is recorded as
     * the one uncovered case.
     *
     * This replaces `channel.epgChannelId ?: return`, which abandoned every channel whose
     * provider ships no guide id before it ever reached a query.
     */
    private suspend fun show(channel: Channel, nowMs: Long): Boolean {
        val keys = listOfNotNull(
            channel.epgChannelId?.takeIf { it.isNotBlank() },
            channel.id,
        ).distinct()

        for (key in keys) {
            val coming = runCatching {
                epg.programmes(key, nowMs, nowMs + SCHEDULE_HORIZON_MS)
            }.getOrDefault(emptyList())
            if (coming.isEmpty()) continue

            // Checked against the current selection rather than assumed: a viewer holding
            // the down key moves faster than a query returns, and without this a slow read
            // for row 4 would overwrite the panel showing row 11. The rows stay in storage
            // either way, so nothing is lost by declining to draw them.
            if (_selected.value?.id != channel.id) return true

            val live = coming.firstOrNull { it.isLiveAt(nowMs) }
            schedule.value = coming.take(SCHEDULE_ROWS)
            // `now` is whichever entry actually contains this instant -- asked of the
            // programme rather than assumed of the first row, because a channel with a
            // gap in its schedule has a next without having a now, and drawing the
            // upcoming programme as the current one is the kind of small lie a viewer
            // catches immediately.
            guide.value = NowNext(now = live, next = coming.firstOrNull { it.startMs > nowMs })
            Log.i(TAG, "stored epg: key=$key rows=${coming.size} now=${live != null}")

            // A schedule with no entry covering this instant is a gap, and a gap is worth
            // one more ask -- the provider may simply not have sent the current hour.
            return live != null
        }
        return false
    }

    /** The blue key, and the star in the row. Returns nothing: the flow reports the result. */
    fun toggleFavorite() {
        val channel = _selected.value ?: return
        viewModelScope.launch { favorites.toggle(channel.id) }
    }

    // `internal` rather than private: `ChannelsGuideTest` asserts the throttle against this
    // constant rather than against a copy of the number, so the two cannot drift apart.
    internal companion object {
        /** Matches `BrowseViewModel`: long enough to survive a rotation, short enough to let go. */
        const val GRACE_MS = 5_000L

        /** Far enough ahead to fill three rows on any channel that has a guide at all. */
        const val SCHEDULE_HORIZON_MS = 8 * 60 * 60 * 1000L

        /** What the board draws. More would be the guide screen, which this is not. */
        const val SCHEDULE_ROWS = 3

        /**
         * How long a fruitless ask stands before the provider is asked again.
         *
         * Shorter than any programme, so a channel that gains a guide shows it within the
         * half hour; long enough that walking a list and coming back does not re-ask. It
         * governs only the *empty* answer — a channel whose rows are in storage never
         * reaches this at all.
         */
        const val ASK_AGAIN_MS = 30 * 60 * 1000L

        const val TAG = "CastivioEpg"
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
