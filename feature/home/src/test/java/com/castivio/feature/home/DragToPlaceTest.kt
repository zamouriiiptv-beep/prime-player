package com.castivio.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where a dragged group goes when a finger lets it go, worked out from the rows on screen, without one:
 * the row it goes before, the line that shows the place, the channel that answers for it -- the first not
 * carried itself -- and how fast the list scrolls with the finger at an edge.
 */
class DragToPlaceTest {

    /** Three rows a hundred pixels tall, the list's tenth to twelfth, the first at the very top. */
    private val shown = listOf(ShownRow(10, 0, 100), ShownRow(11, 100, 100), ShownRow(12, 200, 100))

    // ---------------------------------------------------------------------------- the row it goes before

    @Test
    fun `above the middle of a row, it goes before that row`() {
        assertEquals(10, dropIndexAt(shown, 10f))
        assertEquals(10, dropIndexAt(shown, 49f))
        assertEquals(11, dropIndexAt(shown, 149f))
    }

    @Test
    fun `past the middle of a row, it goes before the next`() {
        assertEquals(11, dropIndexAt(shown, 51f))
        assertEquals(12, dropIndexAt(shown, 150f))
    }

    @Test
    fun `below the middle of the last row shown, it goes after it`() {
        assertEquals(13, dropIndexAt(shown, 260f))
        assertEquals(13, dropIndexAt(shown, 900f))
    }

    @Test
    fun `above the list, it goes before the first row shown`() {
        assertEquals(10, dropIndexAt(shown, -40f))
    }

    @Test
    fun `a row partly scrolled away still has its middle where it is`() {
        val scrolled = listOf(ShownRow(5, -60, 100), ShownRow(6, 40, 100))

        assertEquals(6, dropIndexAt(scrolled, 0f))
        assertEquals(5, dropIndexAt(scrolled, -20f))
    }

    @Test
    fun `the rows are read in the list's order, whatever order they come in`() {
        assertEquals(11, dropIndexAt(shown.reversed(), 120f))
        assertEquals(13, dropIndexAt(shown.reversed(), 280f))
    }

    @Test
    fun `with no row shown there is nowhere to go`() {
        assertNull(dropIndexAt(emptyList(), 50f))
    }

    // ---------------------------------------------------------------------------------- the line

    @Test
    fun `the line is drawn at the top of the row it goes before`() {
        assertEquals(0, dropLineAt(shown, 10))
        assertEquals(100, dropLineAt(shown, 11))
        assertEquals(200, dropLineAt(shown, 12))
    }

    @Test
    fun `after the last row shown, the line is drawn at its bottom`() {
        assertEquals(300, dropLineAt(shown, 13))
    }

    @Test
    fun `a place that is not on screen has no line`() {
        assertNull(dropLineAt(shown, 9))
        assertNull(dropLineAt(shown, 20))
        assertNull(dropLineAt(emptyList(), 0))
    }

    // ------------------------------------------------------------------------ the channel it answers to

    private val ids = listOf("a", "b", "c", "d")
    private val idAt = { index: Int -> ids.getOrNull(index) }

    @Test
    fun `let go before a row that is not carried, it goes before that row`() {
        assertEquals(DropTarget.Before("a"), dropTarget(0, ids.size, idAt, setOf("b", "c"), complete = true))
        assertEquals(DropTarget.Before("d"), dropTarget(3, ids.size, idAt, setOf("b", "c"), complete = true))
    }

    @Test
    fun `let go on the group itself, it answers to the first row after it that is not carried`() {
        assertEquals(DropTarget.Before("d"), dropTarget(1, ids.size, idAt, setOf("b", "c"), complete = true))
        assertEquals(DropTarget.Before("d"), dropTarget(2, ids.size, idAt, setOf("b", "c"), complete = true))
        assertEquals(DropTarget.Before("c"), dropTarget(1, ids.size, idAt, setOf("b", "d"), complete = true))
    }

    @Test
    fun `after the last row of a list read to its end, it goes to the end`() {
        assertEquals(DropTarget.End, dropTarget(4, ids.size, idAt, setOf("b"), complete = true))
        assertEquals(DropTarget.End, dropTarget(2, ids.size, idAt, setOf("c", "d"), complete = true))
    }

    @Test
    fun `after the last row loaded of a list that goes on, nothing moves`() {
        assertNull(dropTarget(4, ids.size, idAt, setOf("b"), complete = false))
        assertNull(dropTarget(2, ids.size, idAt, setOf("c", "d"), complete = false))
    }

    @Test
    fun `a row on the way that is not loaded leaves its place unknown, and nothing moves`() {
        val gap = { index: Int -> if (index == 1) null else ids[index] }

        assertNull(dropTarget(1, ids.size, gap, setOf("b"), complete = true))
        assertEquals(DropTarget.Before("a"), dropTarget(0, ids.size, gap, setOf("b"), complete = true))
    }

    @Test
    fun `a place before the first row is the first row`() {
        assertEquals(DropTarget.Before("a"), dropTarget(-3, ids.size, idAt, setOf("c"), complete = true))
    }

    // ------------------------------------------------------------------------------- at the edges

    @Test
    fun `away from the edges the list does not scroll`() {
        assertEquals(0f, edgeScrollStep(500f, 1000f, edge = 100f, fastest = 20f), 0f)
        assertEquals(0f, edgeScrollStep(100f, 1000f, edge = 100f, fastest = 20f), 0f)
        assertEquals(0f, edgeScrollStep(900f, 1000f, edge = 100f, fastest = 20f), 0f)
    }

    @Test
    fun `near the top it scrolls up, faster the nearer`() {
        assertEquals(-10f, edgeScrollStep(50f, 1000f, edge = 100f, fastest = 20f), 0.001f)
        assertEquals(-20f, edgeScrollStep(0f, 1000f, edge = 100f, fastest = 20f), 0.001f)
        assertEquals(-20f, edgeScrollStep(-80f, 1000f, edge = 100f, fastest = 20f), 0.001f)
    }

    @Test
    fun `near the bottom it scrolls down, faster the nearer`() {
        assertEquals(10f, edgeScrollStep(950f, 1000f, edge = 100f, fastest = 20f), 0.001f)
        assertEquals(20f, edgeScrollStep(1000f, 1000f, edge = 100f, fastest = 20f), 0.001f)
        assertEquals(20f, edgeScrollStep(1300f, 1000f, edge = 100f, fastest = 20f), 0.001f)
    }

    @Test
    fun `in a list shorter than two edges, each edge is half of it`() {
        assertEquals(-10f, edgeScrollStep(25f, 100f, edge = 100f, fastest = 20f), 0.001f)
        assertEquals(0f, edgeScrollStep(50f, 100f, edge = 100f, fastest = 20f), 0f)
        assertEquals(10f, edgeScrollStep(75f, 100f, edge = 100f, fastest = 20f), 0.001f)
    }

    @Test
    fun `a list with no height does not scroll`() {
        assertEquals(0f, edgeScrollStep(0f, 0f, edge = 100f, fastest = 20f), 0f)
    }
}
