package com.castivio.feature.home

import androidx.lifecycle.ViewModelStore
import com.castivio.domain.CatalogQuery
import com.castivio.domain.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
 * Reorder mode, from the key that asks for it to the stored order: what is picked up, what moves,
 * what is written and when -- and that cancelling writes nothing.
 *
 * The order itself, read back from SQLite, is `CatalogOrderTest`'s. What is asked of it, in what
 * sequence, and what the board says while it is asked, is the holder's, and is here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseArrangeTest {

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

    private fun live(group: String? = null, arranging: Boolean = false) =
        CatalogQuery(kind = MediaKind.LIVE, groupId = group, arranging = arranging)

    // ----------------------------------------------------------------- what an earlier run left

    @Test
    fun `opening the board asks for drafts an earlier run left to be swept`() = runTest(dispatcher) {
        val board = browseBoard()

        assertEquals(1, board.order.sweeps)
    }

    @Test
    fun `a board that goes away in the middle of an arrangement says its draft was abandoned`() = runTest(dispatcher) {
        val store = ViewModelStore()
        val board = browseBoard(store = store)
        board.model.choose("sports"); runCurrent()
        board.model.arrange(); runCurrent()

        store.clear()

        assertEquals(1, board.order.abandoned)
    }

    @Test
    fun `a board that goes away with no arrangement open abandons nothing`() = runTest(dispatcher) {
        val store = ViewModelStore()
        val board = browseBoard(store = store)

        store.clear()

        assertEquals(0, board.order.abandoned)
    }

    @Test
    fun `a board that goes away after saving abandons nothing`() = runTest(dispatcher) {
        val store = ViewModelStore()
        val board = browseBoard(store = store)
        board.model.choose("sports"); runCurrent()
        board.model.arrange(); runCurrent()
        board.model.save(); runCurrent()

        store.clear()

        assertEquals(0, board.order.abandoned)
    }

    // ------------------------------------------------------------------------------ entering

    @Test
    fun `the board is not in the mode until it is asked`() = runTest(dispatcher) {
        val board = browseBoard()

        assertNull(board.state.arranging)
        assertEquals(listOf("sports", "news", "kids"), board.bouquets)
    }

    @Test
    fun `asking for the mode makes the draft of the open bouquet first, and then enters`() = runTest(dispatcher) {
        val board = arranging(open = "news")

        assertEquals(listOf("begin news"), board.order.calls)
        val mode = board.state.arranging
        assertNotNull(mode)
        assertEquals("news", mode!!.channelsOf)
        assertEquals(listOf("sports", "news", "kids"), mode.bouquets)
        // The list now asks for the draft, of that bouquet.
        assertEquals(live(group = "news", arranging = true), board.last)
    }

    @Test
    fun `with all open only the bouquets are arranged`() = runTest(dispatcher) {
        val board = arranging(open = null)

        assertNull(board.state.arranging!!.channelsOf)
        assertEquals(emptyList<String>(), board.order.calls)
        assertFalse(board.last.arranging)
    }

    @Test
    fun `a search is cleared when the mode begins`() = runTest(dispatcher) {
        val board = browseBoard()
        board.model.search("bein"); advanceTimeBy(121); runCurrent()
        assertEquals("bein", board.state.search)

        board.model.arrange(); runCurrent()

        assertEquals("", board.model.searchText.value)
        assertEquals("", board.state.search)
    }

    @Test
    fun `asking twice makes one draft`() = runTest(dispatcher) {
        val board = browseBoard()
        board.model.choose("sports"); runCurrent()

        board.model.arrange()
        board.model.arrange()
        runCurrent()

        assertEquals(listOf("begin sports"), board.order.calls)
    }

    @Test
    fun `a draft that could not be made leaves the board as it was`() = runTest(dispatcher) {
        val board = browseBoard(order = FakeOrder().apply { failBegin = true })
        board.model.choose("sports"); runCurrent()

        board.model.arrange(); runCurrent()

        assertNull(board.state.arranging)
        assertFalse(board.last.arranging)
    }

    // ------------------------------------------------------------------------ picking up

    @Test
    fun `OK picks a row up, and OK on it again puts it down`() = runTest(dispatcher) {
        val board = arranging()

        board.model.press(Held.Bouquet("news")); runCurrent()
        assertEquals(Held.Bouquet("news"), board.state.arranging!!.held)

        board.model.press(Held.Bouquet("news")); runCurrent()
        assertNull(board.state.arranging!!.held)
    }

    @Test
    fun `picking up another row puts the first one down`() = runTest(dispatcher) {
        val board = arranging()
        board.model.press(Held.Bouquet("news"))

        board.model.press(Held.Channel("c1")); runCurrent()

        assertEquals(Held.Channel("c1"), board.state.arranging!!.held)
    }

    @Test
    fun `no channel can be picked up while all is open`() = runTest(dispatcher) {
        val board = arranging(open = null)

        board.model.press(Held.Channel("c1")); runCurrent()

        assertNull(board.state.arranging!!.held)
    }

    @Test
    fun `dropping puts down whatever is held`() = runTest(dispatcher) {
        val board = arranging()
        board.model.press(Held.Bouquet("news"))

        board.model.drop(); runCurrent()

        assertNull(board.state.arranging!!.held)
    }

    // ---------------------------------------------------------------------------- moving

    @Test
    fun `a held bouquet moves in the rail and nothing is written yet`() = runTest(dispatcher) {
        val board = arranging()
        board.model.press(Held.Bouquet("kids"))

        board.model.moveHeld(up = true); runCurrent()
        assertEquals(listOf("sports", "kids", "news"), board.bouquets)
        board.model.moveHeld(up = true); runCurrent()
        assertEquals(listOf("kids", "sports", "news"), board.bouquets)

        assertEquals("only the draft was made", listOf("begin sports"), board.order.calls)
    }

    @Test
    fun `a bouquet cannot be moved past either end`() = runTest(dispatcher) {
        val board = arranging()
        board.model.press(Held.Bouquet("sports"))

        board.model.moveHeld(up = true); runCurrent()
        assertEquals(listOf("sports", "news", "kids"), board.bouquets)

        board.model.press(Held.Bouquet("sports")); board.model.press(Held.Bouquet("kids"))
        board.model.moveHeld(up = false); runCurrent()
        assertEquals(listOf("sports", "news", "kids"), board.bouquets)
    }

    @Test
    fun `nothing held means the arrows move nothing`() = runTest(dispatcher) {
        val board = arranging()

        board.model.moveHeld(up = true); runCurrent()

        assertEquals(listOf("sports", "news", "kids"), board.bouquets)
        assertEquals(listOf("begin sports"), board.order.calls)
    }

    @Test
    fun `a held channel moves in the stored draft, one place a press, in the order pressed`() = runTest(dispatcher) {
        val board = arranging()
        board.model.press(Held.Channel("c4"))

        board.model.moveHeld(up = true)
        board.model.moveHeld(up = true)
        board.model.moveHeld(up = false)
        runCurrent()

        assertEquals(
            listOf("begin sports", "move sports c4 up", "move sports c4 up", "move sports c4 down"),
            board.order.calls,
        )
    }

    @Test
    fun `a move that could not be written says so and the mode stays`() = runTest(dispatcher) {
        val order = FakeOrder()
        val board = arranging(order = order)
        board.model.press(Held.Channel("c4"))
        order.failMove = true

        board.model.moveHeld(up = true); runCurrent()

        assertTrue(board.state.arranging!!.failed)
        assertNotNull(board.state.arranging)
    }

    @Test
    fun `the rail lists a bouquet that began to be listed after the mode began at the end`() = runTest(dispatcher) {
        val board = arranging()
        board.model.press(Held.Bouquet("kids")); board.model.moveHeld(up = true)

        board.catalogue.held.value = board.catalogue.held.value + com.castivio.domain.MediaGroup("docs", "Docs", MediaKind.LIVE, 2)
        runCurrent()

        assertEquals(listOf("sports", "kids", "news", "docs"), board.bouquets)
    }

    @Test
    fun `the rail's filter is set aside while the bouquets are moved`() = runTest(dispatcher) {
        val board = browseBoard()
        board.model.filterGroups("spo"); runCurrent()
        assertEquals(listOf("sports"), board.bouquets)

        board.model.arrange(); runCurrent()

        assertEquals(listOf("sports", "news", "kids"), board.bouquets)
    }

    // ------------------------------------------------------------------- what presses do

    @Test
    fun `typing, filtering and choosing are ignored while the mode is on`() = runTest(dispatcher) {
        val board = arranging(open = "sports")
        val before = board.pager.asked.size

        board.model.search("bein"); advanceTimeBy(200)
        board.model.filterGroups("kid")
        board.model.choose("news")
        runCurrent()

        assertEquals("", board.model.searchText.value)
        assertEquals("", board.state.groupFilter)
        assertEquals("sports", board.state.selectedGroup)
        assertEquals(before, board.pager.asked.size)
    }

    // ----------------------------------------------------------------------------- saving

    @Test
    fun `saving writes the moved bouquets, then the channels, and leaves the mode`() = runTest(dispatcher) {
        val board = arranging()
        board.model.press(Held.Bouquet("kids")); board.model.moveHeld(up = true); board.model.moveHeld(up = true)
        board.model.press(Held.Channel("c4")); board.model.moveHeld(up = true)

        board.model.save(); runCurrent()

        assertEquals(
            listOf("begin sports", "move sports c4 up", "bouquets LIVE kids,sports,news", "save sports"),
            board.order.calls,
        )
        assertNull(board.state.arranging)
        // The list goes back to asking for the saved order, which is the one just written.
        assertEquals(live(group = "sports"), board.last)
    }

    @Test
    fun `bouquets that were not moved are not written`() = runTest(dispatcher) {
        val board = arranging()
        board.model.press(Held.Channel("c4")); board.model.moveHeld(up = true)

        board.model.save(); runCurrent()

        assertEquals(listOf("begin sports", "move sports c4 up", "save sports"), board.order.calls)
    }

    @Test
    fun `a bouquet moved away and back is not a change`() = runTest(dispatcher) {
        val board = arranging()
        board.model.press(Held.Bouquet("news"))
        board.model.moveHeld(up = true); board.model.moveHeld(up = false)

        board.model.save(); runCurrent()

        assertEquals(listOf("begin sports", "discard sports"), board.order.calls)
    }

    @Test
    fun `saving without having moved a channel stores no order for the channels`() = runTest(dispatcher) {
        val board = arranging()
        board.model.press(Held.Bouquet("kids")); board.model.moveHeld(up = true)

        board.model.save(); runCurrent()

        // The draft is dropped: an order the viewer did not make is not stored in their name.
        assertEquals(listOf("begin sports", "bouquets LIVE sports,kids,news", "discard sports"), board.order.calls)
    }

    @Test
    fun `a move that did not happen is not a change`() = runTest(dispatcher) {
        val order = FakeOrder().apply { moves = false }
        val board = arranging(order = order)
        board.model.press(Held.Channel("c0")); board.model.moveHeld(up = true)

        board.model.save(); runCurrent()

        assertEquals(listOf("begin sports", "move sports c0 up", "discard sports"), order.calls)
    }

    @Test
    fun `saving with all open writes the bouquets and no channels`() = runTest(dispatcher) {
        val board = arranging(open = null)
        board.model.press(Held.Bouquet("news")); board.model.moveHeld(up = true)

        board.model.save(); runCurrent()

        assertEquals(listOf("bouquets LIVE news,sports,kids"), board.order.calls)
    }

    @Test
    fun `a save that could not be written keeps the mode, with everything where it was put`() = runTest(dispatcher) {
        val order = FakeOrder()
        val board = arranging(order = order)
        board.model.press(Held.Bouquet("kids")); board.model.moveHeld(up = true)
        order.failSave = true

        board.model.save(); runCurrent()

        val mode = board.state.arranging
        assertNotNull(mode)
        assertTrue(mode!!.failed)
        assertFalse(mode.saving)
        assertEquals(listOf("sports", "kids", "news"), board.bouquets)

        // Pressed again, once the disk allows it.
        order.failSave = false
        board.model.save(); runCurrent()
        assertNull(board.state.arranging)
        assertEquals("bouquets LIVE sports,kids,news", order.calls.single { it.startsWith("bouquets") })
    }

    @Test
    fun `pressing save twice writes once`() = runTest(dispatcher) {
        val board = arranging()
        board.model.press(Held.Channel("c4")); board.model.moveHeld(up = true)

        board.model.save()
        board.model.save()
        runCurrent()

        assertEquals(1, board.order.calls.count { it == "save sports" })
    }

    // --------------------------------------------------------------------------- cancelling

    @Test
    fun `cancelling leaves the mode, writes no order, and throws the draft away`() = runTest(dispatcher) {
        val board = arranging()
        board.model.press(Held.Bouquet("kids")); board.model.moveHeld(up = true)
        board.model.press(Held.Channel("c4")); board.model.moveHeld(up = true)

        board.model.cancel()
        runCurrent()

        // Out of the mode, and the list reads the saved order again.
        assertNull(board.state.arranging)
        assertEquals(live(group = "sports"), board.last)
        assertEquals(listOf("sports", "news", "kids"), board.bouquets)
        assertEquals(listOf("begin sports", "move sports c4 up", "discard sports"), board.order.calls)
    }

    @Test
    fun `a move still being written is written before the draft is thrown away`() = runTest(dispatcher) {
        val board = arranging()
        board.model.press(Held.Channel("c4"))

        board.model.moveHeld(up = true)
        board.model.cancel()
        runCurrent()

        assertEquals(listOf("begin sports", "move sports c4 up", "discard sports"), board.order.calls)
    }

    @Test
    fun `cancelling with all open throws no draft away, for there is none`() = runTest(dispatcher) {
        val board = arranging(open = null)
        board.model.press(Held.Bouquet("kids")); board.model.moveHeld(up = true)

        board.model.cancel(); runCurrent()

        assertEquals(emptyList<String>(), board.order.calls)
        assertEquals(listOf("sports", "news", "kids"), board.bouquets)
    }

    @Test
    fun `cancelling when not in the mode does nothing`() = runTest(dispatcher) {
        val board = browseBoard()

        board.model.cancel(); runCurrent()

        assertEquals(emptyList<String>(), board.order.calls)
    }

    @Test
    fun `the mode can be entered again after it was left`() = runTest(dispatcher) {
        val board = arranging()
        board.model.cancel(); runCurrent()

        board.model.arrange(); runCurrent()

        assertNotNull(board.state.arranging)
        assertEquals(listOf("begin sports", "discard sports", "begin sports"), board.order.calls)
    }
}
