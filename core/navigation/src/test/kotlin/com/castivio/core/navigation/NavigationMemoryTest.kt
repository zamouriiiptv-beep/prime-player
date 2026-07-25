package com.castivio.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationMemoryTest {

    private val live = Route.Section(SectionKind.LIVE)
    private val movies = Route.Section(SectionKind.MOVIES)

    @Test
    fun `a screen not visited yet starts at the beginning`() {
        val memory = NavigationMemory()

        assertNull(memory.recall(live))
        assertEquals(0, memory.resolve(live, listOf("a", "b", "c")))
    }

    @Test
    fun `returning to a screen returns to where focus was`() {
        val memory = NavigationMemory()
        memory.remember(live, row = 2, item = 17, itemKey = "ch17")

        val position = memory.recall(live)!!
        assertEquals(2, position.row)
        assertEquals(17, position.item)
        assertEquals("ch17", position.itemKey)
    }

    @Test
    fun `each screen remembers its own position`() {
        val memory = NavigationMemory()
        memory.remember(live, item = 17)
        memory.remember(movies, item = 4)

        assertEquals(17, memory.recall(live)!!.item)
        assertEquals(4, memory.recall(movies)!!.item)
    }

    @Test
    fun `a category remembers separately from its section`() {
        val memory = NavigationMemory()
        memory.remember(live, item = 3)
        memory.remember(Route.Category(SectionKind.LIVE, "sports"), item = 40)

        assertEquals(3, memory.recall(live)!!.item)
        assertEquals(40, memory.recall(Route.Category(SectionKind.LIVE, "sports"))!!.item)
        assertNull(memory.recall(Route.Category(SectionKind.LIVE, "news")))
    }

    /**
     * The rule that makes this worth having. After a refresh a channel may have
     * moved; landing on the channel is right, landing on its old position is merely
     * close.
     */
    @Test
    fun `the remembered item is found by key even when the list moved`() {
        val memory = NavigationMemory()
        memory.remember(live, item = 2, itemKey = "nova")

        // The provider added two channels above it overnight.
        val refreshed = listOf("new1", "new2", "atlas", "nova", "sky")

        assertEquals(3, memory.resolve(live, refreshed))
    }

    @Test
    fun `a removed item falls back to the remembered index`() {
        val memory = NavigationMemory()
        memory.remember(live, item = 3, itemKey = "gone")

        assertEquals(3, memory.resolve(live, listOf("a", "b", "c", "d", "e")))
    }

    @Test
    fun `a shorter list lands at its end rather than out of bounds`() {
        val memory = NavigationMemory()
        memory.remember(live, item = 40, itemKey = "gone")

        assertEquals(2, memory.resolve(live, listOf("a", "b", "c")))
        assertEquals(0, memory.resolve(live, emptyList()))
    }

    @Test
    fun `replacing the catalogue forgets every position`() {
        val memory = NavigationMemory()
        memory.remember(live, item = 17, itemKey = "ch17")
        memory.remember(movies, item = 4)

        memory.clear()

        // A position in the old library means nothing in the new one.
        assertNull(memory.recall(live))
        assertNull(memory.recall(movies))
        assertEquals(0, memory.size)
    }

    @Test
    fun `one screen can be forgotten on its own`() {
        val memory = NavigationMemory()
        memory.remember(live, item = 17)
        memory.remember(movies, item = 4)

        memory.forget(live)

        assertNull(memory.recall(live))
        assertEquals(4, memory.recall(movies)!!.item)
    }

    /**
     * A user can visit hundreds of categories in a session. Memory that grows with
     * browsing is the same bug as memory that grows with library size.
     */
    @Test
    fun `memory is bounded and drops the least recently used screen`() {
        val memory = NavigationMemory(capacity = 3)
        memory.remember(Route.Category(SectionKind.LIVE, "a"), item = 1)
        memory.remember(Route.Category(SectionKind.LIVE, "b"), item = 2)
        memory.remember(Route.Category(SectionKind.LIVE, "c"), item = 3)

        // Touching "a" makes "b" the oldest.
        memory.recall(Route.Category(SectionKind.LIVE, "a"))
        memory.remember(Route.Category(SectionKind.LIVE, "d"), item = 4)

        assertEquals(3, memory.size)
        assertNull(memory.recall(Route.Category(SectionKind.LIVE, "b")))
        assertEquals(1, memory.recall(Route.Category(SectionKind.LIVE, "a"))!!.item)
        assertEquals(4, memory.recall(Route.Category(SectionKind.LIVE, "d"))!!.item)
    }

    @Test
    fun `a capacity of zero is refused rather than silently disabling memory`() {
        val refused = runCatching { NavigationMemory(capacity = 0) }
        assertEquals(IllegalArgumentException::class, refused.exceptionOrNull()!!::class)
    }

    // ------------------------------------------------------------------- routes

    @Test
    fun `route keys identify screens, and arguments that matter change them`() {
        assertNotEquals(
            Route.Section(SectionKind.LIVE).key,
            Route.Section(SectionKind.MOVIES).key,
        )
        assertNotEquals(
            Route.Category(SectionKind.LIVE, "sports").key,
            Route.Category(SectionKind.LIVE, "news").key,
        )
        assertEquals(Route.Detail("m1").key, Route.Detail("m1").key)
    }

    /**
     * Resuming the same item is the same destination. If position were part of the
     * key, a back stack would let a user walk back through their own seeks.
     */
    @Test
    fun `a resume position does not make a different player destination`() {
        assertEquals(
            Route.Player("m1").key,
            Route.Player("m1", startPositionMs = 90_000).key,
        )
    }

    @Test
    fun `the rail lists every top-level destination once`() {
        val keys = TOP_LEVEL_ROUTES.map { it.key }

        assertEquals(keys.size, keys.toSet().size)
        assertEquals(Route.Home.key, keys.first())
        assertEquals(Route.Settings().key, keys.last())
    }
}
