package com.castivio.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reorder mode with several rows at once -- the ticks, what picking one up carries, and where a group
 * lands one press at a time -- as the pure rules they are. What the holder does with them is
 * `BrowseGroupArrangeTest`'s, what the database does `GroupOrderTest`'s.
 */
class ReorderGroupTest {

    private val ids = listOf("a", "b", "c", "d", "e", "f")

    private fun mode(
        held: Held? = null,
        marked: Set<Held> = emptySet(),
        bouquets: List<String> = ids,
    ) = Arranging(bouquets = bouquets, initial = ids, channelsOf = "sport", held = held, marked = marked)

    private fun bouquet(id: String) = Held.Bouquet(id)
    private fun channel(id: String) = Held.Channel(id)

    // ------------------------------------------------------------------------------ the ticks

    @Test
    fun `a tick is put on a row, and the same tick again takes it off`() {
        val ticked = mode().markedToggled(bouquet("b"))
        assertEquals(setOf(bouquet("b")), ticked.marked)

        assertEquals(emptySet<Held>(), ticked.markedToggled(bouquet("b")).marked)
    }

    @Test
    fun `ticks gather from several rows`() {
        val ticked = mode().markedToggled(bouquet("b")).markedToggled(bouquet("d")).markedToggled(bouquet("e"))

        assertEquals(setOf(bouquet("b"), bouquet("d"), bouquet("e")), ticked.marked)
    }

    @Test
    fun `nothing is ticked while something is picked up`() {
        val holding = mode(held = bouquet("a"))

        assertSame(holding, holding.markedToggled(bouquet("b")))
    }

    @Test
    fun `a tick on the other list begins the ticks again`() {
        val ticked = mode().markedToggled(bouquet("a")).markedToggled(bouquet("b")).markedToggled(channel("x"))

        assertEquals(setOf(channel("x")), ticked.marked)
    }

    @Test
    fun `a tick moves and writes nothing`() {
        val ticked = mode().markedToggled(bouquet("c"))

        assertEquals(ids, ticked.bouquets)
        assertNull(ticked.held)
        assertFalse(ticked.channelsMoved)
        assertFalse(ticked.bouquetsMoved)
    }

    // -------------------------------------------------------------------- picking up and putting down

    @Test
    fun `with no ticks a press picks one row up, as it always did`() {
        val held = mode().pressed(bouquet("c"))

        assertEquals(bouquet("c"), held.held)
        assertEquals(setOf(bouquet("c")), held.carried)
        assertTrue(held.marked.isEmpty())
    }

    @Test
    fun `a press on a ticked row picks every ticked row up together`() {
        val ticked = mode(marked = setOf(bouquet("b"), bouquet("d")))

        val held = ticked.pressed(bouquet("d"))

        assertEquals(bouquet("d"), held.held)
        assertEquals(setOf(bouquet("b"), bouquet("d")), held.carried)
    }

    @Test
    fun `a press on a row that is not ticked picks only that one up and lets the ticks go`() {
        val ticked = mode(marked = setOf(bouquet("b"), bouquet("d")))

        val held = ticked.pressed(bouquet("f"))

        assertEquals(setOf(bouquet("f")), held.carried)
        assertTrue(held.marked.isEmpty())
    }

    @Test
    fun `a press on the row that is held puts the whole group down, and the ticks with it`() {
        val holding = mode(marked = setOf(bouquet("b"), bouquet("d"))).pressed(bouquet("b"))

        val down = holding.pressed(bouquet("b"))

        assertNull(down.held)
        assertTrue(down.marked.isEmpty())
        assertTrue(down.carried.isEmpty())
    }

    @Test
    fun `dropping puts down what is held and lets the ticks go`() {
        val down = mode(marked = setOf(bouquet("b"), bouquet("d"))).pressed(bouquet("d")).dropped()

        assertNull(down.held)
        assertTrue(down.marked.isEmpty())
    }

    @Test
    fun `what is carried is only of the kind that is held`() {
        // A tick on a channel survives only until a bouquet is picked up past it: the carried set is the
        // held row's own kind, whatever else the set holds.
        val mixed = mode(held = bouquet("b"), marked = setOf(bouquet("b"), channel("x")))

        assertEquals(setOf(bouquet("b")), mixed.carried)
    }

    @Test
    fun `nothing held carries nothing`() {
        assertTrue(mode(marked = setOf(bouquet("b"))).carried.isEmpty())
    }

    // ---------------------------------------------------------------------- bouquets moving together

    @Test
    fun `a group of bouquets moves up one place together, keeping its order`() {
        val held = mode(marked = setOf(bouquet("c"), bouquet("e"))).pressed(bouquet("c"))

        val moved = held.withHeldBouquetMoved(up = true)

        assertEquals(listOf("a", "c", "b", "e", "d", "f"), moved.bouquets)
    }

    @Test
    fun `a group of bouquets moves down one place together`() {
        val held = mode(marked = setOf(bouquet("b"), bouquet("d"))).pressed(bouquet("b"))

        val moved = held.withHeldBouquetMoved(up = false)

        assertEquals(listOf("a", "c", "b", "e", "d", "f"), moved.bouquets)
    }

    @Test
    fun `a group reaches the top and does not go past it`() {
        var held = mode(marked = setOf(bouquet("d"), bouquet("f"))).pressed(bouquet("d"))

        repeat(10) { held = held.withHeldBouquetMoved(up = true) }

        assertEquals(listOf("d", "f", "a", "b", "c", "e"), held.bouquets)
    }

    @Test
    fun `a group reaches the bottom and does not go past it`() {
        var held = mode(marked = setOf(bouquet("a"), bouquet("c"))).pressed(bouquet("a"))

        repeat(10) { held = held.withHeldBouquetMoved(up = false) }

        assertEquals(listOf("b", "d", "e", "f", "a", "c"), held.bouquets)
    }

    @Test
    fun `a group is moved in the whole, not only the row under the finger`() {
        val held = mode(marked = setOf(bouquet("b"), bouquet("c"), bouquet("d"))).pressed(bouquet("c"))

        val moved = held.withHeldBouquetMoved(up = true)

        assertEquals(listOf("b", "c", "d", "a", "e", "f"), moved.bouquets)
    }

    @Test
    fun `one bouquet held alone moves as it always did`() {
        val moved = mode().pressed(bouquet("c")).withHeldBouquetMoved(up = true)

        assertEquals(listOf("a", "c", "b", "d", "e", "f"), moved.bouquets)
    }

    @Test
    fun `a ticked group that is not picked up moves nothing`() {
        val ticked = mode(marked = setOf(bouquet("b"), bouquet("c")))

        assertSame(ticked, ticked.withHeldBouquetMoved(up = true))
    }

    @Test
    fun `a held channel leaves the bouquets where they stand`() {
        val holding = mode(held = channel("x"))

        assertSame(holding, holding.withHeldBouquetMoved(up = true))
    }

    // ----------------------------------------------------------------------------- the list function

    @Test
    fun `movedAll with one id is moved`() {
        for (id in ids) {
            for (up in listOf(true, false)) {
                assertEquals("$id up=$up", ids.moved(id, up), ids.movedAll(listOf(id), up))
            }
        }
    }

    @Test
    fun `members next to each other pass the row beside them together`() {
        assertEquals(listOf("a", "c", "d", "b", "e", "f"), ids.movedAll(listOf("c", "d"), up = true))
        assertEquals(listOf("a", "b", "e", "c", "d", "f"), ids.movedAll(listOf("c", "d"), up = false))
    }

    @Test
    fun `members never pass one another`() {
        val group = listOf("b", "d", "e")
        var list = ids
        repeat(8) { list = list.movedAll(group, up = true) }
        assertEquals(group, list.filter { it in group })

        repeat(8) { list = list.movedAll(group, up = false) }
        assertEquals(group, list.filter { it in group })
    }

    @Test
    fun `scattered members bunch at the edge one press at a time`() {
        // b d f: up once, b meets the top first; d and f each pass one row.
        val once = ids.movedAll(listOf("b", "d", "f"), up = true)
        assertEquals(listOf("b", "a", "d", "c", "f", "e"), once)

        val twice = once.movedAll(listOf("b", "d", "f"), up = true)
        assertEquals(listOf("b", "d", "a", "f", "c", "e"), twice)

        val thrice = twice.movedAll(listOf("b", "d", "f"), up = true)
        assertEquals(listOf("b", "d", "f", "a", "c", "e"), thrice)

        assertEquals(thrice, thrice.movedAll(listOf("b", "d", "f"), up = true))
    }

    @Test
    fun `rows that are not members only change places with members, and keep their own order`() {
        val others = ids.filterNot { it in setOf("c", "e") }
        var list = ids
        repeat(7) {
            list = list.movedAll(listOf("c", "e"), up = it % 2 == 0)
            assertEquals(others, list.filterNot { id -> id in setOf("c", "e") })
        }
    }

    @Test
    fun `ids that are not in the list move nothing`() {
        assertEquals(ids, ids.movedAll(listOf("x", "y"), up = true))
        assertEquals(ids, ids.movedAll(emptyList(), up = false))
    }

    @Test
    fun `the whole list as a group does not move`() {
        assertEquals(ids, ids.movedAll(ids, up = true))
        assertEquals(ids, ids.movedAll(ids, up = false))
    }

    @Test
    fun `the order the ids are named in makes no difference`() {
        assertEquals(
            ids.movedAll(listOf("b", "d", "e"), up = false),
            ids.movedAll(listOf("e", "b", "d"), up = false),
        )
    }
}
