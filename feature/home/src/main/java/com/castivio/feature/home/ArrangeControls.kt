package com.castivio.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.components.ButtonWeight
import com.castivio.core.design.components.CastivioButton
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Sizing

/**
 * What a row is told in reorder mode: whether it is the one picked up, whether it is carried with it or
 * ticked to be, and what to do when it is moved or ticked.
 *
 * Null on a row outside the mode, which is every row on every board that is not being arranged:
 * a row that is not told draws what it always drew.
 */
internal data class RowArrange(
    /** This row is the one picked up -- the one under the finger or the remote, which the arrows move. */
    val held: Boolean,
    val onMove: (up: Boolean) -> Unit,
    /** This row is carried with the one that is held: it moves when the arrows do, and has no taps of its own. */
    val carried: Boolean = false,
    /** This row is ticked, to be moved with the others that are, and nothing is picked up yet. */
    val marked: Boolean = false,
    /**
     * The long press, or OK held: tick this row, or take its tick off. Null where the row is not told one, and
     * the row is then a plain press as it was.
     */
    val onMark: (() -> Unit)? = null,
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
 * The end of a row in reorder mode: a handle on a row that can be picked up, a tick on one that is ticked
 * or carried with the one that is picked up, and on the one that is, the two taps a thumb moves it -- and
 * its group -- with.
 *
 * The taps are not focus targets. A remote moves a held row with the arrow keys, and a button
 * the focus could land on would be one more place for the arrow to go instead of moving it.
 */
@Composable
internal fun ArrangeTail(arrange: RowArrange, ink: Color, size: Dp) {
    if (arrange.carried || arrange.marked) {
        // A row of the group: ticked to be moved, or carried along with the one picked up. A mark and not a
        // handle, and not taps -- the taps are the held row's, and a group is moved from one place.
        Icon(
            Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(Sizing.iconSm),
        )
    } else if (arrange.held) {
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
 * The band over the board while it is in reorder mode: the word, [hint]'s one sentence for the state, and Cancel and Save --
 * all of it [ModeBand]'s, which the hide mode's band is too.
 *
 * It said everything that was true in five lines, 109dp of a 385dp screen, and the board under it lost two channels and a
 * bouquet for the sake of sentences that were mostly not about the viewer's next press. Now it says one, and a failed save is
 * that one, in the colour of a failure, in the place the eye already is. Save and Cancel keep the size they have.
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
    val hint = mode.hint()

    ModeBand(
        label = stringResource(R.string.reorder_entry),
        hint = when (hint) {
            ReorderHint.Failed -> stringResource(R.string.reorder_failed)
            ReorderHint.Move -> stringResource(R.string.reorder_hint_move)
            is ReorderHint.Marked -> stringResource(R.string.reorder_hint_marked, hint.count)
            ReorderHint.AllOpen -> stringResource(R.string.reorder_hint_all)
            ReorderHint.Pick -> stringResource(R.string.reorder_hint_pick)
        },
        hintInk = when (hint) {
            ReorderHint.Failed -> colors.danger
            ReorderHint.AllOpen -> colors.onBackgroundMuted
            else -> colors.onBackground
        },
        m = m,
        modifier = modifier,
    ) {
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

/** A tap on a held row, as a share of the row: big enough for a thumb, small enough to stay inside it. */
internal const val ARROW_OF_ROW = 0.8f
