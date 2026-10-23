package com.castivio.feature.home

import com.castivio.domain.CatalogQuery
import com.castivio.domain.CatalogRepository
import com.castivio.domain.Channel
import com.castivio.domain.ChannelRef
import com.castivio.domain.ChannelWindows
import com.castivio.domain.MediaItem
import com.castivio.domain.MediaKind
import com.castivio.domain.Page
import com.castivio.domain.PageRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which list the large player's channel keys step through: the one the channel was pressed in, and
 * -- when it was pressed in none, or is not in it any more -- its own bouquet.
 *
 * What stepping does inside a list is `ChannelSteppingTest`'s; the order the list is in is
 * `ChannelStepTest`'s, against SQLite. This is only the choice between lists.
 */
class ChannelZapTest {

    private fun channel(id: String, group: String?) =
        Channel(id, "Channel $id", null, null, group, "http://example.invalid/$id.ts", null)

    private val a = channel("a", "sports")
    private val b = channel("b", "sports")
    private val c = channel("c", "sports")
    private val x = channel("x", "news")
    private val loose = channel("loose", null)

    private val sports = CatalogQuery(kind = MediaKind.LIVE, groupId = "sports")
    private val news = CatalogQuery(kind = MediaKind.LIVE, groupId = "news")
    private val whole = CatalogQuery(kind = MediaKind.LIVE)
    private val searched = CatalogQuery(kind = MediaKind.LIVE, search = "channel b")

    /** Lists by query; a channel the list does not hold has no place in it. */
    private class Lists(private val lists: Map<CatalogQuery, List<Channel>>) : ChannelWindows {
        val asked = mutableListOf<CatalogQuery>()

        override suspend fun rank(query: CatalogQuery, channelId: String): Int? =
            lists[query]?.indexOfFirst { it.id == channelId }?.takeIf { it >= 0 }

        override suspend fun window(query: CatalogQuery, offset: Int, limit: Int): Page<MediaItem> {
            asked += query
            val rows = lists[query].orEmpty()
            return Page(rows.drop(offset).take(limit), offset, rows.size)
        }
    }

    private class Catalogue(private val channels: List<Channel>) : CatalogRepository {
        override fun groups(kind: MediaKind) = flowOf(emptyList<com.castivio.domain.MediaGroup>())
        override suspend fun page(request: PageRequest): Page<MediaItem> = error("not asked")
        override suspend fun item(id: String): MediaItem? = channels.firstOrNull { it.id == id }
        override suspend fun search(query: String, limit: Int): List<MediaItem> = error("not asked")
        override suspend fun search(query: String, kind: MediaKind, limit: Int): List<MediaItem> = error("not asked")
        override fun count(kind: MediaKind, groupId: String?): Flow<Int> = flowOf(0)
        override suspend fun channelRefs(mediaIds: List<String>): List<ChannelRef> = error("not asked")
    }

    private fun zap(lists: Map<CatalogQuery, List<Channel>>) =
        ChannelZapViewModel(Lists(lists), Catalogue(listOf(a, b, c, x, loose)))

    @Test
    fun `a channel pressed in a list is stepped through that list`() = runTest {
        val model = zap(mapOf(sports to listOf(a, b, c), whole to listOf(a, x, b, c)))

        assertEquals("b", model.step(sports, "a", 1)?.channel?.id)
        assertEquals("x", model.step(whole, "a", 1)?.channel?.id)
    }

    @Test
    fun `a search it was pressed in is the list it is stepped through`() = runTest {
        val model = zap(mapOf(searched to listOf(b, c)))

        assertEquals("c", model.step(searched, "b", 1)?.channel?.id)
    }

    @Test
    fun `a channel found some other way is stepped through its own bouquet`() = runTest {
        val model = zap(mapOf(sports to listOf(a, b, c), news to listOf(x)))

        assertEquals("c", model.step(null, "b", 1)?.channel?.id)
        assertEquals("a", model.step(null, "b", -1)?.channel?.id)
    }

    @Test
    fun `a channel with no bouquet is stepped through the whole section`() = runTest {
        val model = zap(mapOf(whole to listOf(a, loose, x)))

        assertEquals("x", model.step(null, "loose", 1)?.channel?.id)
    }

    @Test
    fun `a channel that is not in the list it was pressed in goes on in its own bouquet`() = runTest {
        // The search that was on has changed, or the list was refreshed without it.
        val model = zap(mapOf(searched to listOf(c), sports to listOf(a, b, c)))

        assertEquals("c", model.step(searched, "b", 1)?.channel?.id)
    }

    @Test
    fun `a bouquet is not asked twice when it is the list that was pressed in`() = runTest {
        val lists = Lists(mapOf(sports to listOf(a)))
        val model = ChannelZapViewModel(lists, Catalogue(listOf(a)))

        assertNull(model.step(sports, "a", 1))
        assertEquals("a list of one is read once and then given up", 1, lists.asked.size)
    }

    @Test
    fun `a channel that is not in the catalogue has nowhere to go`() = runTest {
        val model = zap(mapOf(sports to listOf(a, b)))

        assertNull(model.step(null, "gone", 1))
    }
}
