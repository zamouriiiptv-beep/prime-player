package com.castivio.feature.home

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reorder band says one thing at a time, and which thing is a function of the state alone -- so it is tested without a
 * screen. What each sentence *looks like* is `ReorderModeTest`'s; this is that the right one is chosen.
 */
class ReorderHintTest {

    private fun mode(
        held: Held? = null,
        marked: Set<Held> = emptySet(),
        channelsOf: String? = "sport",
        failed: Boolean = false,
    ) = Arranging(
        bouquets = listOf("sport", "news", "kids"),
        initial = listOf("sport", "news", "kids"),
        channelsOf = channelsOf,
        held = held,
        marked = marked,
        failed = failed,
    )

    @Test
    fun `a category is open and nothing is held, so the band says how to begin`() {
        assertEquals(ReorderHint.Pick, mode().hint())
    }

    @Test
    fun `a held row is told how to move`() {
        assertEquals(ReorderHint.Move, mode(held = Held.Channel("c1")).hint())
    }

    @Test
    fun `ticked rows are counted`() {
        val ticked = setOf<Held>(Held.Channel("c1"), Held.Channel("c2"))

        assertEquals(ReorderHint.Marked(2), mode(marked = ticked).hint())
    }

    @Test
    fun `all open and nothing in hand says a category has to be opened`() {
        assertEquals(ReorderHint.AllOpen, mode(channelsOf = null).hint())
    }

    @Test
    fun `ticked bouquets under all are counted, because they can still be moved`() {
        val ticked = setOf<Held>(Held.Bouquet("sport"))

        assertEquals(ReorderHint.Marked(1), mode(channelsOf = null, marked = ticked).hint())
    }

    @Test
    fun `a held row wins over ticked ones, which move with it`() {
        val ticked = setOf<Held>(Held.Channel("c1"), Held.Channel("c2"))

        assertEquals(ReorderHint.Move, mode(held = Held.Channel("c1"), marked = ticked).hint())
    }

    @Test
    fun `a failed save is said before anything else`() {
        val ticked = setOf<Held>(Held.Channel("c1"))

        assertEquals(ReorderHint.Failed, mode(failed = true).hint())
        assertEquals(ReorderHint.Failed, mode(failed = true, held = Held.Channel("c1")).hint())
        assertEquals(ReorderHint.Failed, mode(failed = true, marked = ticked).hint())
        assertEquals(ReorderHint.Failed, mode(failed = true, channelsOf = null).hint())
    }
}
