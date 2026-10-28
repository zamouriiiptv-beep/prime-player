package com.castivio.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Spacing
import com.castivio.domain.Channel
import com.castivio.domain.StreamQuality

/**
 * The rows of the list of channels in the large player: the board's own rows over a window-at-a-time list.
 *
 * Everything it needs is handed in, so it draws the same from a view model and from a fixture: the state
 * (`ChannelListState`), how to look a row up by index, what to ask for when a row is looked at, and the
 * three facts a row says about a channel -- whether it is the one playing, a favourite, locked -- from the
 * sets the shell already holds.
 *
 * ## One row is the board's row
 *
 * [ChannelRow]: the number plate (the channel's place in this list, one-based, as on the board), the
 * channel's logo, its name -- which scrolls under the remote -- the quality badge, the star, the lock, and
 * the green disc on the one that is playing. Violet is the remote and the disc is the player, and the two
 * never swap, as on the board. A row not yet read is a placeholder of exactly its height, so nothing moves
 * when the window lands.
 *
 * ## Where it opens, and where the remote is
 *
 * Scrolled so the playing channel is the first row (`ChannelListState.start`), with the remote on it: the
 * viewer opened the list from a channel and is going somewhere from there.
 */
@Composable
internal fun ChannelListRows(
    state: ChannelListState,
    channelAt: (Int) -> Channel?,
    want: (Int) -> Unit,
    playingId: String,
    favorites: Set<String>,
    locked: Set<String>,
    qualities: Map<String, StreamQuality>,
    m: ChannelsMetrics,
    onPick: (channel: Channel, position: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    if (!state.ready) {
        // The first window is arriving: placeholders at the rows' own height, as many as fill the panel.
        LazyColumn(modifier.fillMaxSize().testTag(ChannelListTags.LOADING), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            items(LOADING_ROWS) { Placeholder(m, colors.glassFill) }
        }
        return
    }

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.start)
    val first = remember { FocusRequester() }
    // The remote goes to the row the list opened on. A lazy list composes its rows during layout, after the
    // effects of the frame that composed it, so the row's requester is attached a frame or two later than the
    // list is: asked once a frame until it takes, and not at all more than a few times.
    LaunchedEffect(state.total > 0) {
        if (state.total == 0) return@LaunchedEffect
        repeat(FOCUS_FRAMES) {
            withFrameNanos { }
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    // What is on screen is what is asked for: the window of the first row shown and of the last, so a
    // scroll that crosses into the next window finds it read -- `want` reads the neighbour near the edge.
    LaunchedEffect(listState) {
        snapshotFlow {
            val shown = listState.layoutInfo.visibleItemsInfo
            (shown.firstOrNull()?.index ?: 0) to (shown.lastOrNull()?.index ?: 0)
        }.collect { (top, bottom) ->
            want(top)
            want(bottom)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().testTag(ChannelListTags.LIST),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        items(count = state.total, key = { index -> index }) { index ->
            val channel = channelAt(index)
            if (channel == null) {
                LaunchedEffect(index) { want(index) }
                Placeholder(m, colors.glassFill)
            } else {
                ChannelRow(
                    channel = channel,
                    number = index + 1,
                    playing = channel.id == playingId,
                    m = m,
                    onClick = { onPick(channel, index) },
                    modifier = if (index == state.start) Modifier.focusRequester(first) else Modifier,
                    quality = qualities[channel.id],
                    locked = channel.id in locked,
                    favorite = channel.id in favorites,
                )
            }
        }
    }
}

/** A row not read yet: a quiet box of the row's own height, so the list does not move when it lands. */
@Composable
private fun Placeholder(m: ChannelsMetrics, fill: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(m.rowMin)
            .background(fill, RoundedCornerShape(m.previewRadius)),
    )
}

/** How many frames the remote is asked to go to the first row for, before it is left where it is. */
private const val FOCUS_FRAMES = 5

/** How many placeholders fill the panel while the first window is arriving. */
private const val LOADING_ROWS = 10

internal object ChannelListTags {
    const val LIST = "channellist.rows"
    const val LOADING = "channellist.loading"
}
