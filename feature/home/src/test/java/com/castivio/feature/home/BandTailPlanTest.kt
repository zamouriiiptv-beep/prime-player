package com.castivio.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the end of a board's band puts its pieces: the field, the section's control, the clock and the way back, the
 * same space between each of them. The figures are the ones of the mockup the owner approved
 * (`channels-band-back-ar.html`, strip B), on its 833dp-wide band.
 */
class BandTailPlanTest {

    /** The mockup's band: the section's name ends at 290 and the arrow's trailing edge is at 817. */
    private val room = 817 - 290
    private val field = 179
    private val order = 138
    private val clock = 46
    private val arrow = 34

    private fun spaces(plan: BandTailPlan, widths: List<Int>): List<Int> =
        widths.indices.map { i ->
            val before = if (i == 0) 0 else plan.positions[i - 1] + widths[i - 1]
            plan.positions[i] - before
        }

    @Test
    fun `the space between every piece is the same, the first one included`() {
        val plan = planBandTail(room, hasField = true, fieldWanted = field, fieldMin = 40, others = listOf(order, clock, arrow), minGap = 8)

        val gaps = spaces(plan, listOf(plan.fieldWidth, order, clock, arrow))

        assertEquals(32.5f, plan.gap, 0.001f)
        gaps.forEach { assertTrue("a space of $it in $gaps", it in 32..33) }
    }

    @Test
    fun `the last piece ends where the band says it ends`() {
        val plan = planBandTail(room, hasField = true, fieldWanted = field, fieldMin = 40, others = listOf(order, clock, arrow), minGap = 8)

        assertEquals(room.toFloat(), (plan.positions.last() + arrow).toFloat(), 1f)
    }

    @Test
    fun `the field asks for what it needs and is given it when there is room`() {
        val plan = planBandTail(room, hasField = true, fieldWanted = field, fieldMin = 40, others = listOf(order, clock, arrow), minGap = 8)

        assertEquals(field, plan.fieldWidth)
    }

    @Test
    fun `the channels' band, with no control, leaves the field where it always stood`() {
        // The channels' board has three pieces. Equal spaces put its field at about 380 -- 90 from the section's name --
        // where the band drew it before the arrow, so that screen's field does not move.
        val plan = planBandTail(room, hasField = true, fieldWanted = field, fieldMin = 40, others = listOf(clock, arrow), minGap = 8)

        assertEquals(380f - 290f, plan.positions.first().toFloat(), 1f)
    }

    @Test
    fun `the films' band moves its field toward the section's name, as the drawing shows`() {
        val plan = planBandTail(room, hasField = true, fieldWanted = field, fieldMin = 40, others = listOf(order, clock, arrow), minGap = 8)

        // 290 + 32.5, drawn at about 322 in the mockup.
        assertEquals(322.5f - 290f, plan.positions.first().toFloat(), 1f)
    }

    @Test
    fun `the pieces stand in the order they were given, left to right`() {
        val plan = planBandTail(room, hasField = true, fieldWanted = field, fieldMin = 40, others = listOf(order, clock, arrow), minGap = 8)

        assertEquals(plan.positions.sorted(), plan.positions)
    }

    @Test
    fun `a narrow band shrinks the field and keeps the others whole`() {
        // 300 of room: the order, the clock and the arrow take 218, and the spaces want 8 each.
        val plan = planBandTail(300, hasField = true, fieldWanted = field, fieldMin = 40, others = listOf(order, clock, arrow), minGap = 8)

        assertEquals(300 - 218 - 8 * 4, plan.fieldWidth)
        assertEquals(8f, plan.gap, 0.001f)
        assertEquals(300f, (plan.positions.last() + arrow).toFloat(), 1f)
    }

    @Test
    fun `the field is never made smaller than its mark and the room round it`() {
        val plan = planBandTail(250, hasField = true, fieldWanted = field, fieldMin = 40, others = listOf(order, clock, arrow), minGap = 8)

        assertEquals(40, plan.fieldWidth)
        // What is lost then is the space between the pieces, not the pieces.
        assertTrue("a space of ${plan.gap}", plan.gap < 8f)
        assertTrue(plan.gap >= 0f)
    }

    @Test
    fun `a band with no room for anything has no negative space and no negative edge`() {
        val plan = planBandTail(100, hasField = true, fieldWanted = field, fieldMin = 40, others = listOf(order, clock, arrow), minGap = 8)

        assertEquals(0f, plan.gap, 0.001f)
        plan.positions.forEach { assertTrue("an edge at $it", it >= 0) }
    }

    @Test
    fun `a field that asks for less than its least is given what it asked for`() {
        val plan = planBandTail(room, hasField = true, fieldWanted = 30, fieldMin = 40, others = listOf(clock, arrow), minGap = 8)

        assertEquals(30, plan.fieldWidth)
    }

    @Test
    fun `without a field the others share the space equally`() {
        val plan = planBandTail(room, hasField = false, fieldWanted = field, fieldMin = 40, others = listOf(clock, arrow), minGap = 8)

        assertEquals(0, plan.fieldWidth)
        assertEquals(2, plan.positions.size)
        assertEquals((room - clock - arrow) / 2f, plan.gap, 0.001f)
        assertEquals(room.toFloat(), (plan.positions.last() + arrow).toFloat(), 1f)
    }

    @Test
    fun `nothing to place is nothing placed`() {
        val plan = planBandTail(room, hasField = false, fieldWanted = field, fieldMin = 40, others = emptyList(), minGap = 8)

        assertEquals(emptyList<Int>(), plan.positions)
        assertEquals(0, plan.fieldWidth)
    }

    @Test
    fun `one piece stands at the band's end, with the whole of the space before it`() {
        // The way back alone: the last piece's trailing edge is the band's, so every space there is is before it.
        val plan = planBandTail(room, hasField = false, fieldWanted = 0, fieldMin = 0, others = listOf(arrow), minGap = 8)

        assertEquals(room - arrow, plan.positions.single())
    }
}
