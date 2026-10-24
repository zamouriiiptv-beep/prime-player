package com.castivio.feature.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The channels carried, dragged by a thumb and let go, as the holder runs it: placed in one write before
 * the row they were let go on -- or at the end -- still picked up after, kept by Save and thrown away by
 * Cancel; and the arrows exactly as they were.
 *
 * Where the rows land in SQLite is `GroupOrderTest`'s; where a finger lets them go is `DragToPlaceTest`'s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseDragPlaceTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.arranging(open: String? = "sports", order: FakeOrder = FakeOrder()): Board {
        val board = browseBoard(order = order)
        if (open != null) board.model.choose(open)
        runCurrent()
        board.model.arrange()
        runCurrent()
        return board
    }

    // ------------------------------------------------------------------------------------ placing

    @Test
    fun `ticked channels let go together are placed before the row they were let go on, in one write`() =
        runTest(dispatcher) {
            val board = arranging()
            board.model.mark(Held.Channel("c1"))
            board.model.mark(Held.Channel("c3"))
            board.model.press(Held.Channel("c3"))

            board.model.placeHeld(beforeId = "c0"); runCurrent()

            assertEquals(listOf("begin sports", "place sports c1,c3 before c0"), board.order.calls)
        }

    @Test
    fun `let go after every row, what is carried goes to the end`() = runTest(dispatcher) {
        val board = arranging()
        board.model.press(Held.Channel("c2"))

        board.model.placeHeld(beforeId = null); runCurrent()

        assertEquals("place sports c2 before end", board.order.calls.last())
    }

    @Test
    fun `one channel picked up with no ticks is placed alone`() = runTest(dispatcher) {
        val board = arranging()
        board.model.press(Held.Channel("c4"))

        board.model.placeHeld(beforeId = "c0"); runCurrent()

        assertEquals("place sports c4 before c0", board.order.calls.last())
    }

    @Test
    fun `let go on one of what is carried, nothing is written`() = runTest(dispatcher) {
        val board = arranging()
        board.model.mark(Held.Channel("c1"))
        board.model.mark(Held.Channel("c3"))
        board.model.press(Held.Channel("c1"))

        board.model.placeHeld(beforeId = "c3"); runCurrent()

        assertEquals(listOf("begin sports"), board.order.calls)
        assertFalse(board.state.arranging!!.channelsMoved)
    }

    @Test
    fun `what was placed stays picked up, so the arrows go on with the same group`() = runTest(dispatcher) {
        val board = arranging()
        board.model.mark(Held.Channel("c1"))
        board.model.mark(Held.Channel("c3"))
        board.model.press(Held.Channel("c3"))
        board.model.placeHeld(beforeId = null); runCurrent()

        val mode = board.state.arranging!!
        assertEquals(Held.Channel("c3"), mode.held)
        assertEquals(setOf(Held.Channel("c1"), Held.Channel("c3")), mode.marked)

        board.model.moveHeld(up = true); runCurrent()
        assertEquals("moveAll sports c1,c3 up", board.order.calls.last())
    }

    @Test
    fun `the arrows still move one channel picked up alone one place`() = runTest(dispatcher) {
        val board = arranging()
        board.model.press(Held.Channel("c2"))
        board.model.placeHeld(beforeId = "c0"); runCurrent()

        board.model.moveHeld(up = false); runCurrent()

        assertEquals("move sports c2 down", board.order.calls.last())
    }

    // --------------------------------------------------------------------------- nothing to place

    @Test
    fun `nothing is placed with nothing picked up`() = runTest(dispatcher) {
        val board = arranging()

        board.model.placeHeld(beforeId = "c0"); runCurrent()

        assertEquals(listOf("begin sports"), board.order.calls)
    }

    @Test
    fun `nothing is placed while a bouquet is picked up`() = runTest(dispatcher) {
        val board = arranging()
        board.model.press(Held.Bouquet("news"))

        board.model.placeHeld(beforeId = "c0"); runCurrent()

        assertEquals(listOf("begin sports"), board.order.calls)
        assertEquals(listOf("sports", "news", "kids"), board.bouquets)
    }

    @Test
    fun `nothing is placed with all open, where channels have no order of their own`() = runTest(dispatcher) {
        val board = arranging(open = null)
        board.model.press(Held.Channel("c2"))

        board.model.placeHeld(beforeId = "c0"); runCurrent()

        assertTrue(board.order.calls.none { it.startsWith("place") })
    }

    // ------------------------------------------------------------------------- saving, cancelling

    @Test
    fun `a placement is kept by save`() = runTest(dispatcher) {
        val board = arranging()
        board.model.press(Held.Channel("c2"))
        board.model.placeHeld(beforeId = "c0"); runCurrent()
        assertTrue(board.state.arranging!!.channelsMoved)

        board.model.save(); runCurrent()

        assertEquals("save sports", board.order.calls.last())
        assertNull(board.state.arranging)
    }

    @Test
    fun `a placement that moved nothing is not saved as the viewer's order`() = runTest(dispatcher) {
        val board = arranging(order = FakeOrder().apply { moves = false })
        board.model.press(Held.Channel("c0"))
        board.model.placeHeld(beforeId = "c1"); runCurrent()
        assertFalse(board.state.arranging!!.channelsMoved)

        board.model.save(); runCurrent()

        assertEquals("discard sports", board.order.calls.last())
        assertTrue(board.order.calls.none { it == "save sports" })
    }

    @Test
    fun `cancelling after a placement throws the draft away and saves nothing`() = runTest(dispatcher) {
        val board = arranging()
        board.model.press(Held.Channel("c2"))
        board.model.placeHeld(beforeId = "c0"); runCurrent()

        board.model.cancel(); runCurrent()

        assertNull(board.state.arranging)
        assertEquals("discard sports", board.order.calls.last())
        assertTrue(board.order.calls.none { it == "save sports" })
    }

    @Test
    fun `a placement that does not reach the database says so, and the mode stays open`() = runTest(dispatcher) {
        val board = arranging(order = FakeOrder().apply { failMove = true })
        board.model.press(Held.Channel("c2"))

        board.model.placeHeld(beforeId = "c0"); runCurrent()

        val mode = board.state.arranging
        assertNotNull(mode)
        assertTrue(mode!!.failed)
        assertEquals(Held.Channel("c2"), mode.held)
    }
}
