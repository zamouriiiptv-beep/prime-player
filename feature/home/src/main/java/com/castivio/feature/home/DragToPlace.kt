package com.castivio.feature.home

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** How near the top or the bottom of the list a dragging finger makes it scroll. */
internal val DragEdge = 56.dp

/** How far the list scrolls in one frame at most, with the finger at its very edge. */
internal val DragScrollFastest = 14.dp

/** How thick the line is that shows where what is dragged will go. */
internal val DropLine = 2.dp

/** A row of a list as it is laid out now: its place in the list, and where it is on screen. */
internal data class ShownRow(val index: Int, val offset: Int, val size: Int)

/** The rows [this] list has laid out now, top to bottom. */
internal fun LazyListState.shownRows(): List<ShownRow> =
    layoutInfo.visibleItemsInfo.map { ShownRow(it.index, it.offset, it.size) }

/**
 * Where what is let go at [y] goes: before the first row shown whose middle is below [y], or -- below
 * all of them -- after the last row shown. Null when no row is shown.
 */
internal fun dropIndexAt(rows: List<ShownRow>, y: Float): Int? {
    if (rows.isEmpty()) return null
    val inOrder = rows.sortedBy { it.index }
    return inOrder.firstOrNull { y < it.offset + it.size / 2f }?.index ?: (inOrder.last().index + 1)
}

/**
 * Where the line that says where it goes is drawn, for a drop before row [index]: that row's top, or --
 * after the last row shown -- that row's bottom. Null when the place is not on screen.
 */
internal fun dropLineAt(rows: List<ShownRow>, index: Int): Int? {
    rows.firstOrNull { it.index == index }?.let { return it.offset }
    val last = rows.maxByOrNull { it.index } ?: return null
    return if (index == last.index + 1) last.offset + last.size else null
}

/** What the channels carried are put before when they are let go. */
internal sealed interface DropTarget {
    /** Before this channel. */
    data class Before(val id: String) : DropTarget

    /** After every other channel of the bouquet. */
    data object End : DropTarget
}

/**
 * What the channels in [carried] go before when they are let go before row [index] of a list of [count]
 * rows read with [idAt]: the first row from there that is not carried itself -- let go on the group, they
 * gather where its first one stood -- or the end of the bouquet when no such row follows and the list is
 * [complete]. Null when nothing should move: a row on the way is not loaded, so its place is not known, or
 * the list goes on past what is loaded and "the end" would be further than the thumb meant.
 */
internal fun dropTarget(
    index: Int,
    count: Int,
    idAt: (Int) -> String?,
    carried: Set<String>,
    complete: Boolean,
): DropTarget? {
    var at = index.coerceAtLeast(0)
    while (at < count) {
        val id = idAt(at) ?: return null
        if (id !in carried) return DropTarget.Before(id)
        at++
    }
    return if (complete) DropTarget.End else null
}

/**
 * How far a list is scrolled, this frame, while a thumb drags at [y] in a list [height] tall: up -- a
 * negative step -- within [edge] of the top, down within [edge] of the bottom, faster the nearer the edge,
 * up to [fastest]; nothing in between. So a drag reaches a place that is not on screen.
 */
internal fun edgeScrollStep(y: Float, height: Float, edge: Float, fastest: Float): Float {
    if (height <= 0f || edge <= 0f) return 0f
    val reach = edge.coerceAtMost(height / 2f)
    return when {
        y < reach -> -fastest * ((reach - y) / reach).coerceAtMost(1f)
        y > height - reach -> fastest * ((y - (height - reach)) / reach).coerceAtMost(1f)
        else -> 0f
    }
}

/**
 * **A thumb drags what is carried, and lets it go where it should go.**
 *
 * Reorder mode's other hand, beside the arrows: a finger put on a row that is carried -- the one picked
 * up, or a ticked one picked up with it -- and moved further than a tap drags them all, and lifting it
 * gives [onDrop] the row they go before. A tap is still a tap, and every other row still scrolls the
 * list: the drag takes only the gesture that began on a carried row and moved, and it takes it before
 * the list does, so the list does not scroll under it. [onMove] says where the finger is, in the list's
 * own coordinates, while it drags -- and null once it has let go -- for the line that shows the place
 * and for scrolling at the edges.
 *
 * Touch only. A remote has no pointer, and moves with the arrows as before.
 */
@Composable
internal fun Modifier.dragToPlace(
    listState: LazyListState,
    enabled: Boolean,
    carried: (index: Int) -> Boolean,
    onMove: (y: Float?) -> Unit,
    onDrop: (beforeIndex: Int) -> Unit,
): Modifier {
    val isCarried by rememberUpdatedState(carried)
    val move by rememberUpdatedState(onMove)
    val drop by rememberUpdatedState(onDrop)
    if (!enabled) return this
    return pointerInput(listState) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val start = down.position.y
            val under = listState.shownRows().firstOrNull { start >= it.offset && start < it.offset + it.size }
            if (under == null || !isCarried(under.index)) return@awaitEachGesture
            var dragging = false
            var y = start
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    if (dragging) {
                        change.consume()
                        move(null)
                        dropIndexAt(listState.shownRows(), y)?.let { drop(it) }
                    }
                    return@awaitEachGesture
                }
                y = change.position.y
                if (!dragging && abs(y - start) > viewConfiguration.touchSlop) dragging = true
                if (dragging) {
                    change.consume()
                    move(y)
                }
            }
            // The gesture went away without a lift -- another finger, or the list was left: nothing moves.
            if (dragging) move(null)
        }
    }
}
