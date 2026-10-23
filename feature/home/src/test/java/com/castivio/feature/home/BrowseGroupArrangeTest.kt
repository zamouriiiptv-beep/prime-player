package com.castivio.feature.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Reorder mode with several rows at once, as the holder runs it: ticked, picked up together, moved
 * together, saved together -- and that a single row, ticked by no one, is moved exactly as before.
 *
 * `ReorderGroupTest` has the rules; the rows as SQLite orders them are `GroupOrderTest`'s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseGroupArrangeTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun kotlinx.coroutines.test.TestScope.arranging(open: String? = "sports", order: FakeOrder = FakeOrder()): Board {
        val board = browseBoard(order = order)
        if (open != null) board.model.choose(open)
        runCurrent()
        board.model.arrange()
        runCurrent()
        return board
    }

    // ------------------------------------------------------------------------------- bouquets

    @Test
    fun `ticked bouquets are picked up together and move together in the rail`() = runTest(dispatcher) {
        val board = arranging()
        board.model.mark(Held.Bouquet("news"))
        board.model.mark(Held.Bouquet("kids"))
        assertEquals(setOf(Held.Bouquet("news"), Held.Bouquet("kids")), board.state.arranging!!.marked)

        board.model.press(Held.Bouquet("kids"))
        board.model.moveHeld(up = true); runCurrent()

        // sports, news, kids -> news and kids both pass: news first, kids behind it.
        assertEquals(listOf("news", "kids", "sports"), board.bouquets)
    }

    @Test
    fun `moving a group of bouquets writes nothing until save`() = runTest(dispatcher) {
        val board = arranging()
        board.model.mark(Held.Bouquet("news"))
        board.model.mark(Held.Bouquet("kids"))
        board.model.press(Held.Bouquet("news"))
        board.model.moveHeld(up = true); runCurrent()

        assertEquals(listOf("begin sports"), board.order.calls)
    }

    @Test
    fun `saving writes the bouquets once, in the order the group left them`() = runTest(dispatcher) {
        val board = arranging()
        board.model.mark(Held.Bouquet("news"))
        board.model.mark(Held.Bouquet("kids"))
        board.model.press(Held.Bouquet("news"))
        board.model.moveHeld(up = true)
        board.model.drop()
        board.model.save(); runCurrent()

        assertEquals(1, board.order.calls.count { it.startsWith("bouquets") })
        assertTrue("bouquets LIVE news,kids,sports" in board.order.calls)
        assertNull(board.state.arranging)
    }

    @Test
    fun `cancelling after a group moved puts the bouquets back and writes none`() = runTest(dispatcher) {
        val board = arranging()
        board.model.mark(Held.Bouquet("news"))
        board.model.mark(Held.Bouquet("kids"))
        board.model.press(Held.Bouquet("news"))
        board.model.moveHeld(up = true); runCurrent()

        board.model.cancel(); runCurrent()

        assertNull(board.state.arranging)
        assertEquals(listOf("sports", "news", "kids"), board.bouquets)
        assertTrue(board.order.calls.none { it.startsWith("bouquets") })
    }

    @Test
    fun `one bouquet with no ticks moves exactly as it did`() = runTest(dispatcher) {
        val board = arranging()
        board.model.press(Held.Bouquet("kids"))

        board.model.moveHeld(up = true); runCurrent()

        assertEquals(listOf("sports", "kids", "news"), board.bouquets)
    }

    @Test
    fun `dropping lets the ticks go, so the next pick-up is of one row`() = runTest(dispatcher) {
        val board = arranging()
        board.model.mark(Held.Bouquet("news"))
        board.model.mark(Held.Bouquet("kids"))
        board.model.press(Held.Bouquet("news"))
        board.model.drop()

        assertTrue(board.state.arranging!!.marked.isEmpty())

        board.model.press(Held.Bouquet("kids"))
        board.model.moveHeld(up = true); runCurrent()
        assertEquals(listOf("sports", "kids", "news"), board.bouquets)
    }

    // -------------------------------------------------------------------------------- channels

    @Test
    fun `ticked channels are moved together, in one write`() = runTest(dispatcher) {
        val board = arranging()
        board.model.mark(Held.Channel("c1"))
        board.model.mark(Held.Channel("c3"))
        board.model.press(Held.Channel("c3"))

        board.model.moveHeld(up = true); runCurrent()

        assertEquals(listOf("begin sports", "moveAll sports c1,c3 up"), board.order.calls)
    }

    @Test
    fun `a single channel is moved with the move it always used`() = runTest(dispatcher) {
        val board = arranging()
        board.model.press(Held.Channel("c4"))

        board.model.moveHeld(up = true); runCurrent()

        assertEquals(listOf("begin sports", "move sports c4 up"), board.order.calls)
    }

    @Test
    fun `one tick alone is one channel`() = runTest(dispatcher) {
        val board = arranging()
        board.model.mark(Held.Channel("c2"))
        board.model.press(Held.Channel("c2"))

        board.model.moveHeld(up = false); runCurrent()

        assertEquals(listOf("begin sports", "move sports c2 down"), board.order.calls)
    }

    @Test
    fun `saving after a group of channels moved writes the bouquet's channels`() = runTest(dispatcher) {
        val board = arranging()
        board.model.mark(Held.Channel("c1"))
        board.model.mark(Held.Channel("c3"))
        board.model.press(Held.Channel("c1"))
        board.model.moveHeld(up = true); runCurrent()
        board.model.drop()

        board.model.save(); runCurrent()

        assertTrue("save sports" in board.order.calls)
        assertNull(board.state.arranging)
    }

    @Test
    fun `a group that could not move leaves the order unsaved`() = runTest(dispatcher) {
        val order = FakeOrder().apply { moves = false }
        val board = arranging(order = order)
        board.model.mark(Held.Channel("c0"))
        board.model.mark(Held.Channel("c1"))
        board.model.press(Held.Channel("c0"))
        board.model.moveHeld(up = true); runCurrent()
        assertFalse(board.state.arranging!!.channelsMoved)

        board.model.save(); runCurrent()

        assertTrue("save sports" !in order.calls)
        assertTrue("discard sports" in order.calls)
    }

    @Test
    fun `cancelling after a group of channels moved discards the draft`() = runTest(dispatcher) {
        val board = arranging()
        board.model.mark(Held.Channel("c1"))
        board.model.mark(Held.Channel("c3"))
        board.model.press(Held.Channel("c1"))
        board.model.moveHeld(up = true); runCurrent()

        board.model.cancel(); runCurrent()

        assertEquals(listOf("begin sports", "moveAll sports c1,c3 up", "discard sports"), board.order.calls)
        assertTrue(board.order.calls.none { it.startsWith("save") || it.startsWith("bouquets") })
    }

    @Test
    fun `a group move that cannot be written says so and keeps the mode open`() = runTest(dispatcher) {
        val order = FakeOrder()
        val board = arranging(order = order)
        board.model.mark(Held.Channel("c1"))
        board.model.mark(Held.Channel("c3"))
        board.model.press(Held.Channel("c1"))
        order.failMove = true

        board.model.moveHeld(up = true); runCurrent()

        assertTrue(board.state.arranging!!.failed)
    }

    // ------------------------------------------------------------------ where a group cannot go

    @Test
    fun `channels cannot be ticked when all is open`() = runTest(dispatcher) {
        val board = arranging(open = null)

        board.model.mark(Held.Channel("c1"))
        board.model.press(Held.Channel("c1"))

        val mode = board.state.arranging!!
        assertTrue(mode.marked.isEmpty())
        assertNull(mode.held)
        assertEquals(emptyList<String>(), board.order.calls)
    }

    @Test
    fun `bouquets can still be ticked and moved when all is open`() = runTest(dispatcher) {
        val board = arranging(open = null)
        board.model.mark(Held.Bouquet("news"))
        board.model.mark(Held.Bouquet("kids"))
        board.model.press(Held.Bouquet("news"))

        board.model.moveHeld(up = true); runCurrent()

        assertEquals(listOf("news", "kids", "sports"), board.bouquets)
    }

    @Test
    fun `a tick outside the mode does nothing`() = runTest(dispatcher) {
        val board = browseBoard()

        board.model.mark(Held.Bouquet("news"))

        assertNull(board.state.arranging)
    }
}
