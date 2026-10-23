package com.castivio.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.castivio.core.design.components.ButtonWeight
import com.castivio.core.design.components.CastivioButton
import com.castivio.core.design.components.castivioBodyStyle
import com.castivio.core.design.components.castivioChipStyle
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Sizing

/**
 * What a row is told in reorder mode: whether it is the one picked up, and what to do when it is
 * moved.
 *
 * Null on a row outside the mode, which is every row on every board that is not being arranged:
 * a row that is not told draws what it always drew.
 */
internal data class RowArrange(
    val held: Boolean,
    val onMove: (up: Boolean) -> Unit,
)

/**
 * The arrow keys move the row that is held, and are taken from the focus search while it is.
 *
 * Both directions and both halves of the press are consumed, so the remote does not also walk
 * the focus off the row it is carrying. Left and right are not: they go where they always went,
 * which is how a viewer gets from one list to the other with a row in hand. A row that is not
 * held leaves the keys alone, which is how the arrows move *between* rows until one is picked up.
 */
internal fun Modifier.movedByArrows(arrange: RowArrange?): Modifier =
    if (arrange == null || !arrange.held) {
        this
    } else {
        onPreviewKeyEvent { event ->
            val up = event.key == Key.DirectionUp
            val down = event.key == Key.DirectionDown
            if (up || down) {
                if (event.type == KeyEventType.KeyDown) arrange.onMove(up)
                true
            } else {
                false
            }
        }
    }

/**
 * The end of a row in reorder mode: a handle on a row that can be picked up, and on the one that
 * is, the two taps a thumb moves it with.
 *
 * The taps are not focus targets. A remote moves a held row with the arrow keys, and a button
 * the focus could land on would be one more place for the arrow to go instead of moving it.
 */
@Composable
internal fun ArrangeTail(arrange: RowArrange, ink: Color, size: Dp) {
    if (arrange.held) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            ArrowTap(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.reorder_move_up), ink, size) {
                arrange.onMove(true)
            }
            ArrowTap(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.reorder_move_down), ink, size) {
                arrange.onMove(false)
            }
        }
    } else {
        Icon(
            Icons.Rounded.DragHandle,
            contentDescription = null,
            tint = CastivioTheme.colors.onBackgroundMuted,
            modifier = Modifier.size(Sizing.iconSm),
        )
    }
}

@Composable
private fun ArrowTap(icon: ImageVector, description: String, ink: Color, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(Sizing.iconMd))
    }
}

/**
 * Keeps the row being carried on screen as it moves -- asked of the list that holds it.
 *
 * It was a modifier on the row, which asked to be brought into view each time the row was laid
 * out. A move that takes the row past the edge of a lazy list leaves it not laid out at all, so
 * nothing asked: the list keeps the row that was first on screen where it was, and a bouquet
 * moved to the top of the rail went above it -- the owner had to scroll back up to find it.
 *
 * So the list is asked, once the move has been laid out: a row off the top becomes the first on
 * screen, one off the bottom one of the last, and one cut by either edge is scrolled just into
 * view. A row already in view is left where it is.
 *
 * @param index where the held row now is in this list; negative when nothing is held.
 */
internal suspend fun LazyListState.keepInView(index: Int) {
    if (index < 0) return
    // The frame the move is laid out in: until then the list still describes the old places.
    withFrameNanos { }
    val info = layoutInfo
    val shown = info.visibleItemsInfo
    if (shown.isEmpty()) return
    val row = shown.firstOrNull { it.index == index }
    when {
        row == null && index < shown.first().index -> scrollToItem(index)
        row == null -> scrollToItem((index - shown.size + 2).coerceAtLeast(0))
        row.offset < 0 -> scrollBy(row.offset.toFloat())
        row.offset + row.size > info.viewportEndOffset - info.afterContentPadding ->
            scrollBy((row.offset + row.size - info.viewportEndOffset + info.afterContentPadding).toFloat())
    }
}

/**
 * The band over the board while it is in reorder mode: what the mode is, what to press, and the
 * two ways out.
 *
 * It says which of the two things is happening -- nothing picked up, or something -- because the
 * same arrow keys do different things in each, and a mode whose keys change meaning without a
 * word is not one a viewer can use from the sofa. Save and Cancel are always here, in the
 * system's own buttons, and neither is more than one press from anywhere on the board. A failed
 * save is said in the same place the hint is, where the eye already is.
 *
 * **Laid out left to right in every language, like the band above it and the columns under it.**
 * It sits between the two, and it used to follow the reader's direction while they did not: in
 * Arabic the buttons went to the far left, over the bouquets, and the hint to the right, over the
 * picture -- the one strip of the board that moved when the language did. Now nothing moves; the
 * hint is shaped by its own script inside its cell, as every other line on the board is, and Save
 * and Cancel stay at the trailing end, over the list and the picture they act on.
 */
@Composable
internal fun ReorderBar(
    mode: Arranging,
    m: ChannelsMetrics,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.previewRadius)

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier
                .fillMaxWidth()
                .padding(bottom = m.headerGap)
                .clip(shape)
                .background(colors.glassFill)
                .border(1.dp, colors.selectedBorder, shape)
                .padding(horizontal = m.controlPadH, vertical = m.badgePadV * 2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.factGap),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.reorder_entry),
                    style = castivioChipStyle(m.frame.fsLabel),
                    color = colors.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(if (mode.held == null) R.string.reorder_hint_pick else R.string.reorder_hint_move),
                    style = castivioBodyStyle(m.frame.fsBody),
                    color = colors.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // With "all" open there is no bouquet whose channels could be moved, and the bouquets
                // are the only thing that can: said once, while nothing is held, so it is read.
                if (mode.channelsOf == null && mode.held == null) {
                    Text(
                        text = stringResource(R.string.reorder_hint_all),
                        style = castivioBodyStyle(m.frame.fsBody),
                        color = colors.onBackgroundMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (mode.failed) {
                    Text(
                        text = stringResource(R.string.reorder_failed),
                        style = castivioBodyStyle(m.frame.fsBody),
                        color = colors.danger,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            CastivioButton(
                text = stringResource(R.string.reorder_cancel),
                onClick = onCancel,
                enabled = !mode.saving,
            )
            CastivioButton(
                text = stringResource(R.string.reorder_save),
                onClick = onSave,
                weight = ButtonWeight.Primary,
                busy = mode.saving,
            )
        }
    }
}

/** A tap on a held row, as a share of the row: big enough for a thumb, small enough to stay inside it. */
internal const val ARROW_OF_ROW = 0.8f
