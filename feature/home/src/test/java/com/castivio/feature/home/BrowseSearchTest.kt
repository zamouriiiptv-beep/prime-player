package com.castivio.feature.home

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
import org.junit.Before
import org.junit.Test

/**
 * The header's field, from the keys to the query the rows are read with.
 *
 * The field used to be a button: pressing it left the board for the catalogue-wide search
 * screen, and the player under it stopped. It is now a field, and what it must do is this --
 * the channels narrow as the viewer types, one query for a word and not one for a key, two
 * typings of the same words are the same search, and clearing the field is the list that was
 * open before. The rows themselves are the data layer's (`CatalogQueriesTest` reads the
 * windows from SQLite); what is asked of it is the whole of this holder's part.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseSearchTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun live(group: String? = null, search: String = "") =
        CatalogQuery(kind = MediaKind.LIVE, groupId = group, search = search)

    @Test
    fun `the board opens unnarrowed`() = runTest(dispatcher) {
        val board = browseBoard()

        assertEquals(listOf(live()), board.pager.asked)
        assertEquals("", board.state.search)
        assertEquals("", board.model.searchText.value)
    }

    @Test
    fun `typing a name that exists asks for it once, where the viewer pauses`() = runTest(dispatcher) {
        val board = browseBoard()

        // Four keys, none of them a pause: the query for "b", "be" and "bei" is never made.
        board.model.search("b"); advanceTimeBy(50)
        board.model.search("be"); advanceTimeBy(50)
        board.model.search("bei"); advanceTimeBy(50)
        board.model.search("bein"); advanceTimeBy(119)
        runCurrent()
        assertEquals("nothing is asked before the pause", listOf(live()), board.pager.asked)

        advanceTimeBy(2); runCurrent()
        assertEquals(listOf(live(), live(search = "bein")), board.pager.asked)
        assertEquals("bein", board.state.search)
    }

    @Test
    fun `the field keeps exactly what was typed, at once`() = runTest(dispatcher) {
        val board = browseBoard()

        board.model.search("  BeIN  ")

        // Before any pause: a field that waited on the query would lag the keys under it.
        assertEquals("  BeIN  ", board.model.searchText.value)
    }

    @Test
    fun `capitals, extra spaces and punctuation are the same search`() = runTest(dispatcher) {
        val board = browseBoard()
        board.model.search("bein sports"); advanceTimeBy(200); runCurrent()
        val settled = board.pager.asked.size

        board.model.search("  BEIN   Sports "); advanceTimeBy(200); runCurrent()
        board.model.search("bein, sports!"); advanceTimeBy(200); runCurrent()

        assertEquals("no new stream for what was already asked", settled, board.pager.asked.size)
        assertEquals(live(search = "bein sports"), board.last)
    }

    @Test
    fun `a name that is not there is asked for like any other`() = runTest(dispatcher) {
        val board = browseBoard()

        board.model.search("zzz"); advanceTimeBy(121); runCurrent()

        // Whether anything matches is the data's answer, and the list says it; the holder's part
        // is that the search is in force, and says which one -- "Nothing matched “zzz”".
        assertEquals(live(search = "zzz"), board.last)
        assertEquals("zzz", board.state.search)
    }

    @Test
    fun `clearing the field gives back the list that was open, at once`() = runTest(dispatcher) {
        val board = browseBoard()
        board.model.choose("sports"); runCurrent()
        assertEquals(live(group = "sports"), board.last)

        board.model.search("bein"); advanceTimeBy(121); runCurrent()
        assertEquals(live(search = "bein"), board.last)

        board.model.search(""); runCurrent()

        // No time passed: there is no pause to wait out when the answer is the list from before.
        assertEquals(live(group = "sports"), board.last)
        assertEquals("sports", board.state.selectedGroup)
        assertEquals("", board.state.search)
    }

    @Test
    fun `a search is of the whole section, and lights the rail's all`() = runTest(dispatcher) {
        val board = browseBoard()
        board.model.choose("sports"); runCurrent()
        assertEquals("sports", board.state.selectedGroup)

        board.model.search("bein"); advanceTimeBy(121); runCurrent()

        // A name typed while a bouquet was open finds the channel wherever it is, and the rail
        // says so rather than leaving a bouquet lit over rows that are not all from it.
        assertEquals(null, board.last.groupId)
        assertEquals(null, board.state.selectedGroup)
    }

    @Test
    fun `nothing but spaces and punctuation is no search`() = runTest(dispatcher) {
        val board = browseBoard()

        board.model.search("   -- ! "); advanceTimeBy(200); runCurrent()

        assertEquals(listOf(live()), board.pager.asked)
        assertEquals("", board.state.search)
    }

    @Test
    fun `choosing a category ends the search and opens it`() = runTest(dispatcher) {
        val board = browseBoard()
        board.model.search("bein"); advanceTimeBy(121); runCurrent()

        board.model.choose("news"); runCurrent()

        // A press on the rail that changed nothing the viewer could see would be a dead key.
        assertEquals("", board.model.searchText.value)
        assertEquals(live(group = "news"), board.last)
        assertEquals("news", board.state.selectedGroup)
    }

    @Test
    fun `the search does not change which channels the section holds`() = runTest(dispatcher) {
        val board = browseBoard()
        val whole = board.state.sectionTotal

        board.model.search("bein"); advanceTimeBy(121); runCurrent()

        // The counts are the scope's. A search that moved them would make "does this section have
        // channels at all" depend on what was last typed into a field.
        assertEquals(whole, board.state.sectionTotal)
        assertEquals(5, board.state.total)
    }
}
