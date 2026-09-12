package com.castivio.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.domain.Channel
import com.castivio.domain.EpgRepository
import com.castivio.domain.FavoritesRepository
import com.castivio.domain.NowNext
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
     * What the preview panel shows, as one value.
     *
     * Combined here rather than collected as three flows in the composition, because a
     * screen that collects three flows draws three times for one selection change — and
     * on a television that is three frames of a panel disagreeing with itself.
     */
    val preview: StateFlow<ChannelPreview> = combine(
        _selected,
        guide,
        _selected.flatMapLatest { channel ->
            // `isFavorite` is an indexed EXISTS, and it is a flow because the remote's
            // blue key toggles it from this very screen: a star that only refreshed on
            // navigation would be a star that lies for as long as the user stays.
            channel?.let { favorites.isFavorite(it.id) } ?: flowOf(false)
        },
    ) { channel, nowNext, favorite ->
        ChannelPreview(channel = channel, guide = nowNext, favorite = favorite)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(GRACE_MS), ChannelPreview())

    /**
     * A row was focused or pressed.
     *
     * Idempotent on the same channel, because focus is re-reported for reasons that are
     * not a move — a recomposition, a window regaining focus — and re-reading the guide
     * for each of those would be a query per frame on the list a D-pad is held down on.
     */
    fun select(channel: Channel) {
        if (_selected.value?.id == channel.id) return
        _selected.value = channel
        // Cleared first, and deliberately: the previous channel's programme must never
        // be left under the new channel's name, not even for the frame it takes to read
        // the guide. An absent programme is a sentence the panel already has.
        guide.value = null
        loadGuide(channel)
    }

    private fun loadGuide(channel: Channel) {
        val epgId = channel.epgChannelId ?: return
        viewModelScope.launch {
            val answer = runCatching { epg.nowNext(listOf(epgId), clock.nowMs()) }.getOrNull()
            // Checked against the current selection rather than assumed: a viewer
            // holding the down key moves faster than a query returns, and without this
            // a slow read for row 4 would overwrite the panel showing row 11.
            if (_selected.value?.id == channel.id) guide.value = answer?.get(epgId)
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
