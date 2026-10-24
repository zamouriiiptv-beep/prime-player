package com.castivio.feature.home

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
 * The rail's Recently watched entry, as the board's holder runs it: it asks for the channels watched, of
 * no category, lights alone, and is left by a category, by the favourites, by reorder mode and by a search
 * -- which gives it back when the field is cleared, as it does a category.
 *
 * What the list holds, and in what order, is `RecentChannelsTest`'s in `:data:database`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseRecentTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the entry asks for the channels watched, of no category, and lights alone`() = runTest(dispatcher) {
        val board = browseBoard()
        board.model.choose("sports"); runCurrent()

        board.model.openRecent(); runCurrent()

        assertTrue(board.last.recent)
        assertFalse(board.last.favorites)
        assertNull(board.last.groupId)
        assertTrue(board.state.recent)
        assertFalse(board.state.favorites)
        assertNull(board.state.selectedGroup)
    }

    @Test
    fun `choosing a category leaves it`() = runTest(dispatcher) {
        val board = browseBoard()
        board.model.openRecent(); runCurrent()

        board.model.choose("news"); runCurrent()

        assertFalse(board.last.recent)
        assertEquals("news", board.last.groupId)
        assertFalse(board.state.recent)
    }

    @Test
    fun `it and the favourites are never open together`() = runTest(dispatcher) {
        val board = browseBoard()

        board.model.openRecent(); runCurrent()
        board.model.openFavorites(); runCurrent()
        assertTrue(board.last.favorites)
        assertFalse(board.last.recent)

        board.model.openRecent(); runCurrent()
        assertTrue(board.last.recent)
        assertFalse(board.last.favorites)
    }

    @Test
    fun `opening it ends a search`() = runTest(dispatcher) {
        val board = browseBoard()
        board.model.search("bbc")
        advanceTimeBy(1_000); runCurrent()

        board.model.openRecent(); runCurrent()

        assertEquals("", board.model.searchText.value)
        assertEquals("", board.last.search)
        assertTrue(board.last.recent)
    }

    @Test
    fun `a search typed while it is open is of the whole section, and clearing it gives it back`() =
        runTest(dispatcher) {
            val board = browseBoard()
            board.model.openRecent(); runCurrent()

            board.model.search("bbc")
            advanceTimeBy(1_000); runCurrent()
            assertFalse(board.last.recent)
            assertEquals("bbc", board.last.search)

            board.model.search("")
            advanceTimeBy(1_000); runCurrent()
            assertTrue(board.last.recent)
        }

    @Test
    fun `reorder mode leaves it, opens on the category chosen before, and cannot open it`() = runTest(dispatcher) {
        val board = browseBoard()
        board.model.choose("sports"); runCurrent()
        board.model.openRecent(); runCurrent()

        board.model.arrange(); runCurrent()
        assertNotNull(board.state.arranging)
        assertFalse(board.last.recent)
        assertEquals("sports", board.last.groupId)

        board.model.openRecent(); runCurrent()
        assertFalse(board.last.recent)
    }
}
