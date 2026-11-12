package com.castivio.feature.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.Sizing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the transport shares its row, asserted with the numbers the player's own sizes give.
 *
 * ## The claim
 *
 * No control is laid over another. A row does not refuse a child that will not fit, it lays it over the one before,
 * and the screen's two controls -- the fill and the lock -- were drawn one on the other on a television the moment
 * the list of channels took a column of its own and the row became the film's region.
 *
 * The rows below are the player's real ones, read off [playerMetricsFor] and the target for the device, so a
 * change to a share that would bring a control back over its neighbour fails here and not on a television.
 */
class TransportPlanTest {

    /**
     * One transport, as `PlayerStage` lays it out: the controls at the sizes they are declared at.
     *
     * [room] is the width of the safe-area column the transport is in: the film's region less two insets.
     */
    private class Row(
        val name: String,
        val tv: Boolean,
        val window: Pair<Dp, Dp>,
        val listBeside: Boolean,
        val timeshift: Boolean = false,
        val circles: Int = 2,
    ) {
        private val metrics = playerMetricsFor(tv, window.first, window.second)
        val target: Dp = Sizing.minTarget(tv)
        val room: Dp = (if (listBeside) window.first - metrics.playlist else window.first) - metrics.inset * 2
        val circle: Dp = maxOf(metrics.step, target)
        val middle: Dp = circle * circles + metrics.play
        val count: Int = circles + 1
        val start: Dp = target
        val pills: Int = 2 + if (timeshift) 1 else 0
        val end: Dp = target * pills + metrics.barGap * (pills - 1)
        val gap: Dp = metrics.barGapLarge
        val plan: TransportPlan = transportPlan(room, middle, count, start, end, gap, MIN_GAP)

        /** What each outer group is given: its weight's share of what the gaps leave. */
        private val spare: Dp = room - middle - plan.gap * (count + 1)
        val startShare: Dp = spare * (plan.startWeight / (plan.startWeight + plan.endWeight))
        val endShare: Dp = spare - startShare

        override fun toString() = name
    }

    private val handset = 827.dp to 393.dp
    private val shortest = 800.dp to 360.dp
    private val narrow = 640.dp to 360.dp
    private val television = 960.dp to 540.dp

    /**
     * As drawn, wherever the row is the whole window: weights equal, the gaps the design's, the play control on the
     * centre line. Every surface the player had before the list took a column is here, and none of them moves.
     */
    @Test
    fun `a row the whole window wide is as it was drawn`() {
        val rows = listOf(
            Row("handset", tv = false, handset, listBeside = false),
            Row("shortest handset", tv = false, shortest, listBeside = false),
            Row("narrow handset, behind the live edge", tv = false, narrow, listBeside = false, timeshift = true),
            Row("television", tv = true, television, listBeside = false),
            Row("television, behind the live edge", tv = true, television, listBeside = false, timeshift = true),
            Row("a film's, with its jumps", tv = false, handset, listBeside = false, circles = 4),
        )
        for (row in rows) {
            assertEquals("$row: the start group's weight", 1f, row.plan.startWeight, 0f)
            assertEquals("$row: the end group's weight", 1f, row.plan.endWeight, 0f)
            assertEquals("$row: the gap", row.gap.value, row.plan.gap.value, 0f)
        }
    }

    /**
     * Beside the list the end group is wider than half of what is left, on a television by a good deal: it takes
     * what it asks for -- and the slack, for the pixels -- the start group keeps the rest, and the gaps stay as drawn.
     */
    @Test
    fun `beside the list the wider end takes what it asks for`() {
        val handsetRow = Row("handset", tv = false, handset, listBeside = true)
        val tvRow = Row("television", tv = true, television, listBeside = true)

        for (row in listOf(handsetRow, tvRow)) {
            assertEquals("$row: the gap is the design's", row.gap.value, row.plan.gap.value, 0.001f)
            assertTrue("$row: the weights are still equal", row.plan.startWeight != row.plan.endWeight)
            assertEquals(
                "$row: the end group has what it asks for and the slack",
                (row.end + TRANSPORT_SLACK).value,
                row.endShare.value,
                0.01f,
            )
            assertTrue("$row: the start group ${row.startShare} is under its ${row.start}", row.startShare >= row.start + TRANSPORT_SLACK)
        }

        // The play control leaves the centre line by half the difference between the ends: about 35dp on a television.
        val shift = (tvRow.endShare - tvRow.startShare) / 2
        assertTrue("the television's play control moved ${shift.value}dp", shift.value in 32f..38f)
    }

    /**
     * The pill that takes the viewer back to the live edge is one more control at the end, and unequal shares are
     * not enough: the gaps close, to exactly the width at which every group has what it asks for and the slack.
     */
    @Test
    fun `behind the live edge the gaps close to what is needed`() {
        val tvRow = Row("television, behind the live edge", tv = true, television, listBeside = true, timeshift = true)
        val handsetRow = Row("shortest handset, behind the live edge", tv = false, shortest, listBeside = true, timeshift = true)

        for (row in listOf(tvRow, handsetRow)) {
            assertTrue("$row: the gap ${row.plan.gap} did not close", row.plan.gap < row.gap)
            assertTrue("$row: the gap ${row.plan.gap} closed under the floor", row.plan.gap >= MIN_GAP)
            assertEquals("$row: the end group has what it asks for", (row.end + TRANSPORT_SLACK).value, row.endShare.value, 0.01f)
            assertEquals("$row: the start group has what it asks for", (row.start + TRANSPORT_SLACK).value, row.startShare.value, 0.01f)
        }
    }

    /**
     * Where there is nothing left to take, the ends are laid out as they ask and the gap is the floor: the overlap a
     * row has always had where it was asked for more than it could hold, and not a new one.
     */
    @Test
    fun `under the floor the gap is the floor`() {
        val plan = transportPlan(room = 300.dp, middle = 200.dp, count = 3, start = 56.dp, end = 200.dp, gap = 24.dp, minGap = MIN_GAP)
        assertEquals("the gap", MIN_GAP.value, plan.gap.value, 0f)
        assertEquals("the start group's weight", 56f + TRANSPORT_SLACK.value, plan.startWeight, 0.001f)
        assertEquals("the end group's weight", 200f + TRANSPORT_SLACK.value, plan.endWeight, 0.001f)
    }

    /** A group with nothing in it asks for nothing, and the slack is not asked of it either. */
    @Test
    fun `an empty group is not given slack`() {
        // 120dp is what the end group needs with its slack, and is all there is: the gaps stay as drawn.
        val plan = transportPlan(room = 300.dp, middle = 100.dp, count = 3, start = 0.dp, end = 117.dp, gap = 20.dp, minGap = MIN_GAP)
        assertEquals("the gaps closed for a group that is not there", 20f, plan.gap.value, 0f)
    }

    /** A weight has to be above zero, and a group with nothing in it asks for nothing. */
    @Test
    fun `an empty end is given a weight above zero`() {
        val plan = transportPlan(room = 400.dp, middle = 200.dp, count = 3, start = 0.dp, end = 160.dp, gap = 24.dp, minGap = MIN_GAP)
        assertTrue("start ${plan.startWeight}", plan.startWeight > 0f)
        assertTrue("end ${plan.endWeight}", plan.endWeight > 0f)
    }

    /**
     * The slack is the whole pixels the row is laid out in and not more, and the two frames that fix it say so.
     *
     * Not less: on the 827dp handset build #623 gave the end group 109px for two 48dp targets and the gap between
     * them, which is 110, and the second was laid out at 47 -- so the end group is planned for two dp over what it
     * needs. Not more: on the 851dp handset the drawing was made on, the list's pill is shown with its word, which
     * it is when its group has the target and 64dp of room for the label, and that group has under a dp to spare.
     */
    @Test
    fun `the slack covers a pixel and does not cost the playlist pill its word`() {
        val squeezed = Row("827 handset", tv = false, handset, listBeside = true)
        assertTrue(
            "the end group ${squeezed.endShare} has under two dp over the ${squeezed.end} it needs",
            squeezed.endShare >= squeezed.end + 2.dp,
        )

        val drawn = Row("851 handset", tv = false, 851.dp to 393.dp, listBeside = true)
        assertTrue(
            "the playlist pill's group is ${drawn.startShare}: under the ${drawn.target + LABEL_ROOM} a word needs",
            drawn.startShare >= drawn.target + LABEL_ROOM,
        )
    }

    /**
     * Every width from a sliver to a wide screen, for every kind of transport the player draws, on both devices.
     *
     * Wherever the controls can be fitted at all -- the floor on the gap, every group at its target -- they are:
     * each outer group has at least what it asks for, and the gap is neither wider than the design's nor under the
     * floor. And wherever the shares are equal, the row is wide enough for the wider end to have what it asks for.
     */
    @Test
    fun `every width that can hold the controls holds them`() {
        var checked = 0
        var width = 300.dp
        while (width <= 1400.dp) {
            for (tv in listOf(false, true)) {
                val window = width to (width * 9f / 16f)
                for (timeshift in listOf(false, true)) {
                    for (circles in listOf(0, 2, 4)) {
                        val row = Row("$width tv=$tv timeshift=$timeshift circles=$circles", tv, window, listBeside = false, timeshift = timeshift, circles = circles)
                        val fits = row.room - row.middle - MIN_GAP * (row.count + 1) >=
                            row.start + row.end + TRANSPORT_SLACK * 2
                        val plan = row.plan

                        assertTrue("$row: the weights ${plan.startWeight}/${plan.endWeight}", plan.startWeight > 0f && plan.endWeight > 0f)
                        assertTrue("$row: the gap ${plan.gap} is over the design's ${row.gap}", plan.gap <= row.gap)
                        assertTrue("$row: the gap ${plan.gap} is under the floor", plan.gap >= minOf(MIN_GAP, row.gap))
                        if (fits) {
                            assertTrue(
                                "$row: the start group ${row.startShare} is under its ${row.start} and the slack",
                                row.startShare >= row.start + TRANSPORT_SLACK - 0.01.dp,
                            )
                            assertTrue(
                                "$row: the end group ${row.endShare} is under its ${row.end} and the slack",
                                row.endShare >= row.end + TRANSPORT_SLACK - 0.01.dp,
                            )
                            checked++
                        }
                    }
                }
            }
            width += 1.dp
        }
        assertTrue("only $checked rows were checked", checked > 1000)
    }

}

/** The least gap the controls may be brought to. */
private val MIN_GAP = 4.dp

/** What a pill's word asks for beyond its target: `PILL_LABEL_ROOM` in `PlayerControls`, which is private. */
private val LABEL_ROOM = 64.dp
