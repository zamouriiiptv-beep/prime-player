package com.castivio.feature.player

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How the transport's three groups share their row: the list's pill at one end, the steps and the play control
 * in the middle, the screen's own controls at the other.
 *
 * ## What it is for
 *
 * The groups share the row by weight, the two outer ones equal, so the play control is on the centre line
 * whatever the ends hold -- and that is the whole of the design while the row is wide. It was never asked to be
 * narrow: the film had the window, and a row of 748dp holds the widest end with room to spare.
 *
 * With the list of channels beside the film the row is the film's region, 458dp on a handset and 484 on a
 * television, and the equal shares are each too small for the end that holds the screen's two controls: 96dp for
 * a pair of 56dp targets and the gap between them. A row does not refuse a child that will not fit; it lays the
 * second one over the first, and the fill and the lock were drawn one on the other.
 *
 * So the row is planned before it is laid out, from the sizes the controls are declared at, in three steps:
 *
 *  1. **As drawn.** Both ends get the same share and the wider of them fits in it: weights equal, the gaps as
 *     they were. Every surface the player had before the list had a column of its own is here.
 *  2. **Unequal.** The wider end takes what it asks for and the other keeps the rest, which is more than it asks
 *     for. The play control leaves the centre line by half the difference -- about 35dp on a television -- and no
 *     control is laid over another.
 *  3. **Closer.** Where even that is not enough -- the pill that takes the viewer back to the live edge is one
 *     more control at that end -- the gaps close, between the groups and between the circles, down to [minGap].
 *     Each end then has exactly what it asks for.
 *
 * Under that there is nothing left to take and the ends are laid out as they ask: the same overlap a row has
 * always had where it was asked for more than it could hold, and not a new one.
 *
 * ## A little more than it asks
 *
 * Each end that has anything in it is planned for [TRANSPORT_SLACK] more than its controls need. The plan is made
 * in dp and the row is laid out in whole pixels -- the gaps, the shares of the weights, the gap inside the group --
 * and a row measures its controls one after the other, each given what is left after the one before: a share a pixel
 * short of two targets and the gap between them left the second at 47dp under a floor of 48. That was the one
 * failure of the first build (#623, on a handset, by a pixel) and the reason the slack is in the plan and not in the
 * test.
 *
 * Pure, so the three steps are asserted with numbers and not photographed: `TransportPlanTest`.
 */
@Immutable
internal data class TransportPlan(
    /** The start group's weight in the row. Weights are relative: only the ratio matters. */
    val startWeight: Float,
    /** The end group's weight in the row. */
    val endWeight: Float,
    /** The gap between the groups, and between the controls of the middle one. */
    val gap: Dp,
)

/**
 * The plan for a transport [room] wide.
 *
 * [middle] is the width of the middle group's controls added together, **gaps not counted**, and [count] how many
 * controls that is; [start] and [end] are the least each outer group asks for -- its controls at their targets,
 * labels gone -- and zero for a group with nothing in it. [gap] is the gap the design draws and [minGap] the least
 * one the controls may be brought to.
 */
internal fun transportPlan(
    room: Dp,
    middle: Dp,
    count: Int,
    start: Dp,
    end: Dp,
    gap: Dp,
    minGap: Dp,
): TransportPlan {
    // The gaps in the row: one each side of the middle group, and one between every two controls inside it.
    val gaps = count + 1

    // What each end is planned for: what it asks, and a little more where it asks for anything.
    val wantStart = if (start > 0.dp) start + TRANSPORT_SLACK else start
    val wantEnd = if (end > 0.dp) end + TRANSPORT_SLACK else end

    // What the two ends share, at a gap.
    fun spare(at: Dp): Dp = room - middle - at * gaps

    val shared = spare(gap)
    val half = shared / 2

    // 1. As drawn.
    if (half >= wantStart && half >= wantEnd) return TransportPlan(EQUAL, EQUAL, gap)

    // 2. Unequal: the wider end has what it asks for, the other the rest.
    if (shared >= wantStart + wantEnd) {
        val atEnd = if (wantEnd > half) wantEnd else shared - wantStart
        return TransportPlan(weight(shared - atEnd), weight(atEnd), gap)
    }

    // 3. Closer: the gap at which the two ends have exactly what they ask for, not under [minGap], not over [gap].
    val fit = (room - middle - wantStart - wantEnd) / gaps
    return TransportPlan(weight(wantStart), weight(wantEnd), minOf(gap, maxOf(fit, minGap)))
}

/**
 * What an end is planned for beyond what its controls need, for the whole pixels the row is laid out in: its gaps
 * (four, each up to half a pixel out), the weights' shares (up to a pixel) and the gap inside the end group, which
 * together are under four pixels on a screen of one pixel to the dp and fewer on every other. Three dp keeps the
 * labelled playlist pill on an 851dp handset, which has 0.95dp to spare for it.
 */
internal val TRANSPORT_SLACK = 3.dp

/** A weight in a row has to be above zero, and an end with nothing in it is asked for nothing. */
private fun weight(share: Dp): Float = maxOf(share.value, MIN_WEIGHT)

private const val EQUAL = 1f
private const val MIN_WEIGHT = 0.01f
