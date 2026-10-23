package com.castivio.domain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Stepping from one channel to the next in the order a list shows: the neighbour, going round at the
 * ends, and nowhere to go when there is nowhere.
 *
 * Over a fake list, so the rule is asserted without a database. That the real windows are the list's
 * own windows, in the viewer's order, is `ChannelStepTest`'s, against real SQLite.
 */
class ChannelSteppingTest {

    /** A list of channels in the order given, and how many windows were read from it. */
    private class FakeList(vararg ids: String) : ChannelWindows {
        val rows = ids.map { Channel(it, "Channel $it", null, null, "g", "http://example.invalid/$it.ts", null) }
        var windows = 0
        override suspend fun rank(query: CatalogQuery, channelId: String): Int? =
            rows.indexOfFirst { it.id == channelId }.takeIf { it >= 0 }

        override suspend fun window(query: CatalogQuery, offset: Int, limit: Int): Page<MediaItem> {
            windows++
            return Page(rows.drop(offset).take(limit), offset, rows.size)
        }
    }

    private val query = CatalogQuery(kind = MediaKind.LIVE, groupId = "g")

    @Test
    fun `the next channel is the one after`() = runTest {
        val list = FakeList("a", "b", "c", "d", "e")

        val stepped = list.step(query, "c", by = 1)

        assertEquals("d", stepped?.channel?.id)
        assertEquals(3, stepped?.position)
        assertEquals(5, stepped?.total)
    }

    @Test
    fun `the previous channel is the one before`() = runTest {
        val list = FakeList("a", "b", "c", "d", "e")

        assertEquals("b", list.step(query, "c", by = -1)?.channel?.id)
    }

    @Test
    fun `inside the list the neighbour is one read`() = runTest {
        val list = FakeList("a", "b", "c", "d", "e")

        list.step(query, "c", by = 1)

        assertEquals(1, list.windows)
    }

    @Test
    fun `after the last is the first`() = runTest {
        val list = FakeList("a", "b", "c")

        val stepped = list.step(query, "c", by = 1)

        assertEquals("a", stepped?.channel?.id)
        assertEquals(0, stepped?.position)
    }

    @Test
    fun `before the first is the last`() = runTest {
        val list = FakeList("a", "b", "c")

        val stepped = list.step(query, "a", by = -1)

        assertEquals("c", stepped?.channel?.id)
        assertEquals(2, stepped?.position)
    }

    @Test
    fun `two channels step to each other both ways`() = runTest {
        val list = FakeList("a", "b")

        assertEquals("b", list.step(query, "a", by = 1)?.channel?.id)
        assertEquals("a", list.step(query, "b", by = 1)?.channel?.id)
        assertEquals("b", list.step(query, "a", by = -1)?.channel?.id)
    }

    @Test
    fun `a list of one has nowhere to go, and is never its own neighbour`() = runTest {
        val list = FakeList("a")

        assertNull(list.step(query, "a", by = 1))
        assertNull(list.step(query, "a", by = -1))
    }

    @Test
    fun `an empty list has nowhere to go`() = runTest {
        assertNull(FakeList().step(query, "a", by = 1))
    }

    @Test
    fun `a channel that is not in the list has no neighbour in it`() = runTest {
        val list = FakeList("a", "b", "c")

        assertNull(list.step(query, "elsewhere", by = 1))
    }

    @Test
    fun `a step of more than one goes round the list as often as it needs to`() = runTest {
        val list = FakeList("a", "b", "c")

        assertEquals("b", list.step(query, "a", by = 4)?.channel?.id)
        assertEquals("c", list.step(query, "a", by = -4)?.channel?.id)
    }

    @Test
    fun `every channel reaches every other by stepping, and returns where it began`() = runTest {
        val list = FakeList("a", "b", "c", "d")

        var at = "a"
        val seen = mutableListOf(at)
        repeat(4) {
            at = list.step(query, at, by = 1)!!.channel.id
            seen += at
        }

        assertEquals(listOf("a", "b", "c", "d", "a"), seen)
    }
}
