package com.castivio.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.castivio.core.design.icons.CastivioIcons
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing

/**
 * What the shell gives the player to draw its list of channels with.
 *
 * The player holds a stream and must not learn what a catalogue is, so it cannot read the list itself: the
 * shell, which knows both, hands it the rows already made. The player draws the panel they sit in -- its
 * width, its ground, the title, how many there are and the way out -- and this draws what is inside it.
 *
 *  - [rowHeight] is the floor a row may not go under, the player's own target for the device.
 *  - [onTotal] is how long the list is, said as soon as it is known; the panel under the title writes it.
 *  - [onClose] is for the press that has chosen: the panel goes, and the channel is what is on screen.
 *
 * Absent for a film and for anything that was not pressed in a list, which is what keeps the playlist pill
 * from being drawn: Castivio does not draw a control that cannot act.
 */
typealias ChannelListContent = @Composable (
    rowHeight: Dp,
    onTotal: (Int) -> Unit,
    onClose: () -> Unit,
) -> Unit

/**
 * The list of channels, over the end edge of the picture -- `design/mockups/player-channels.html`.
 *
 * A panel and not a sheet: the film goes on playing behind it, the bars stay, and it is as narrow as a
 * number, a logo and a name allow. The title says what it is, the line under it how many there are, and the
 * mark at its end is one of the three ways out (the other two are Back and the pill that opened it).
 *
 * A list of one -- or of none, when the channel has left the catalogue -- says so in a line instead of
 * showing its single row: the viewer asked a question and the answer is that there is nowhere else to go.
 */
@Composable
internal fun BoxScope.PlaylistPanel(content: ChannelListContent, actions: PlayerActions) {
    val colors = CastivioTheme.colors
    val m = LocalPlayerMetrics.current
    val tv = CastivioTheme.device.isTv
    val target = Sizing.minTarget(tv)
    val pad = if (tv) Spacing.lg else Spacing.md
    // Said by the rows once the first window is read. Null while it is arriving, and the line stays out
    // until it is true -- a count that went from nothing to a figure is a count that moved.
    var total by remember { mutableStateOf<Int?>(null) }

    Column(
        Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(m.playlist)
            // Over a ground of its own, nearly solid: the bars behind the panel are the controls of a screen it
            // has covered, and a ghost of the gear under a row is a thing nobody can press.
            .background(colors.background.copy(alpha = PANEL_ALPHA))
            .testTag(PlayerTags.PLAYLIST_PANEL),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = pad + Spacing.xs, top = pad, end = pad, bottom = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.player_playlist),
                    style = CastivioType.titleMedium.ltr(),
                    color = colors.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                total?.let {
                    Text(
                        // The figure is built here and handed over as text, so it is Western digits whatever the
                        // language -- the same rule as the clock.
                        text = stringResource(R.string.player_playlist_count, it.toString()),
                        style = CastivioType.bodySmall.ltr(),
                        color = colors.onBackgroundVariant,
                        maxLines = 1,
                        modifier = Modifier.testTag(PlayerTags.PLAYLIST_COUNT),
                    )
                }
            }
            Box(
                Modifier
                    .size(target)
                    .clip(CircleShape)
                    .background(colors.glassFill)
                    .border(HAIRLINE, colors.glassBorder, CircleShape)
                    .clickable(role = Role.Button) { actions.onSheet(null) }
                    .testTag(PlayerTags.PLAYLIST_CLOSE),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = CastivioIcons.Close,
                    contentDescription = stringResource(R.string.player_close),
                    tint = colors.onBackground,
                    modifier = Modifier.size(Sizing.iconMd),
                )
            }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = pad, end = pad, bottom = pad),
        ) {
            if (total?.let { it <= 1 } == true) {
                Text(
                    text = stringResource(R.string.player_playlist_alone),
                    style = CastivioType.bodyLarge.ltr(),
                    color = colors.onBackgroundVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = pad)
                        .testTag(PlayerTags.PLAYLIST_ALONE),
                )
            } else {
                content(target, { total = it }, { actions.onSheet(null) })
            }
        }
    }
}

/** How much of the film shows through the panel: almost none, so what is under it never reads as a control. */
private const val PANEL_ALPHA = 0.96f
