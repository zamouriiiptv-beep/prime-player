package com.castivio.feature.home

import kotlin.math.roundToInt

/**
 * Where the things at the end of a board's band stand: the field, the section's own control (the order, on the
 * films', series' and radio's), the clock and the way back -- with the **same space between each of them and the one
 * before it**, so the band reads as one piece.
 *
 * Drawn first in `design/mockups/channels-band-back-ar.html`, as strip B: the owner asked that the space between what
 * is in the band be equal, in all four boards, and that is what this is. Without it the arrow added to the end would
 * have left the space after the section's name large and the three after it small and unlike each other.
 *
 * Pure, in pixels, so the arithmetic is tested without a screen. The layout that uses it ([BandTail]) measures
 * the pieces and hands their widths here.
 *
 * ## What gives when the band is narrow
 *
 * The field is the one piece that may shrink -- a search field with its mark and less of its hint still does its whole
 * job, where a clock without its date or an arrow without its edge does not. It gives up width until the spaces
 * are down to [minGap], and no further than [fieldMin]; below that the spaces give up what is left, to nothing. What
 * is then still too wide runs past the band's end rather than back over the section's name, which is the worse of the
 * two to read.
 *
 * @property fieldWidth the width the field is to be measured at, the same as it asked for when nothing had to give.
 * @property positions the left edge of each piece, in the order they were handed in -- the field first when there is
 *   one -- measured from where the tail begins.
 * @property gap the space between one piece and the one before it, which is the same for all of them. For the first
 *   piece it is the space from the tail's beginning.
 */
internal class BandTailPlan(val fieldWidth: Int, val positions: List<Int>, val gap: Float)

/**
 * @param room the tail's width: from where the section's name ends to where the last piece's trailing edge is to stand.
 * @param fieldWanted the width the field asks for. Ignored where there is no field ([hasField] is false).
 * @param fieldMin the least the field may be given: its mark and the room round it.
 * @param others the widths of the other pieces in the order they stand, left to right: the control (if any), the
 *   clock, the arrow (if any).
 * @param minGap the least space the field gives up width to keep.
 */
internal fun planBandTail(
    room: Int,
    hasField: Boolean,
    fieldWanted: Int,
    fieldMin: Int,
    others: List<Int>,
    minGap: Int,
): BandTailPlan {
    val pieces = others.size + if (hasField) 1 else 0
    if (pieces == 0) return BandTailPlan(fieldWidth = 0, positions = emptyList(), gap = 0f)

    val fixed = others.sum()
    val field = if (hasField) {
        // What is left once the others are down and the spaces are at their least. It is never more than the field
        // asked for and never less than it can do with -- and where the band is too narrow for even that, the spaces
        // are what is lost.
        val most = room - fixed - minGap * pieces
        most.coerceAtMost(fieldWanted).coerceAtLeast(minOf(fieldMin, fieldWanted)).coerceAtLeast(0)
    } else {
        0
    }

    val widths = if (hasField) listOf(field) + others else others
    val gap = ((room - field - fixed).toFloat() / pieces).coerceAtLeast(0f)

    // Each piece's edge from the exact figure, rounded on its own: a gap of 32.5 stays 32.5 along the band rather than
    // becoming 32 for every piece and leaving the odd pixel at the end.
    var before = 0
    val positions = widths.mapIndexed { i, w ->
        val x = (gap * (i + 1) + before).roundToInt().coerceAtLeast(0)
        before += w
        x
    }
    return BandTailPlan(fieldWidth = field, positions = positions, gap = gap)
}
