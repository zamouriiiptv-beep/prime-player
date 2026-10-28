package com.castivio.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.domain.CatalogQuery
import com.castivio.domain.Channel
import com.castivio.domain.StreamQuality

/**
 * The rows of the list of channels the large player opens -- handed to the player by the shell, which is
 * the one place that knows both what a channel is and what the player is.
 *
 * [list] is the list the channel on screen was pressed in, or null -- its own bouquet then -- and
 * [channelId] is the channel on screen: the row the list opens on and the one marked as playing. [surface]
 * is what the player is drawn into, because the rows are sized from it as the board's are from the board;
 * [rowHeight] is the floor the player asks of a row. [onTotal] says how long the list is as soon as the
 * first window is read, so the player can write it under its title, and [onPick] is a press on a row: the
 * shell changes channel, and the list then closes through [onClose].
 *
 * A thin holder in front of [ChannelListRows], which is where the drawing is and which a fixture can feed.
 */
@Composable
fun PlayerChannelList(
    list: CatalogQuery?,
    channelId: String,
    surface: DpSize,
    rowHeight: Dp,
    favorites: Set<String>,
    locked: Set<String>,
    qualities: Map<String, StreamQuality>,
    onTotal: (Int) -> Unit,
    onPick: (channel: Channel, position: Int, total: Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    model: ChannelListViewModel = hiltViewModel(),
) {
    LaunchedEffect(list, channelId) { model.open(list, channelId) }
    val state by model.state.collectAsStateWithLifecycle()

    // Said once the list is known, and said as it is: nothing before, the length after.
    LaunchedEffect(state.ready, state.total) { if (state.ready) onTotal(state.total) }

    val tv = CastivioTheme.device.isTv
    val metrics = channelsMetricsFor(tv, surface.width, surface.height).let { it.copy(rowMin = maxOf(it.rowMin, rowHeight)) }

    ChannelListRows(
        state = state,
        channelAt = model::channelAt,
        want = model::want,
        playingId = channelId,
        favorites = favorites,
        locked = locked,
        qualities = qualities,
        m = metrics,
        onPick = { channel, position ->
            onPick(channel, position, state.total)
            onClose()
        },
        modifier = modifier,
    )
}
