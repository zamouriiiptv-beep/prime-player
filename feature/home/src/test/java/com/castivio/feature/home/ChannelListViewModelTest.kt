package com.castivio.feature.home

import com.castivio.domain.CatalogQuery
import com.castivio.domain.CatalogRepository
import com.castivio.domain.Channel
import com.castivio.domain.ChannelRef
import com.castivio.domain.ChannelWindows
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaItem
import com.castivio.domain.MediaKind
import com.castivio.domain.Page
import com.castivio.domain.PageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
 * The channels the large player lists: which list, where it opens, and that it is read a window at a time.
 *
 * What a window is -- and the ceiling on how many are held -- is `WindowedRowsTest`'s; the order the list is
 * in is `ChannelStepTest`'s, against SQLite. This is the choice of list, the place the list opens at, and
 * what the state says while a window arrives, fails or never comes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelListViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun channel(id: String, group: String?) =
        Channel(id, "Channel $id", null, null, group, "http://example.invalid/$id.ts", null)

    private val sports = CatalogQuery(kind = MediaKind.LIVE, groupId = "sports")
    private val news = CatalogQuery(kind = MediaKind.LIVE, groupId = "news")

    /** Lists by query; every window read is recorded, and a list can be made to fail. */
    private class Lists(private val lists: Map<CatalogQuery, List<Channel>>) : ChannelWindows {
        val reads = mutableListOf<Pair<CatalogQuery, Int>>()
        var failing = false

        override suspend fun rank(query: CatalogQuery, channelId: String): Int? =
            lists[query]?.indexOfFirst { it.id == channelId }?.takeIf { it >= 0 }

        override suspend fun window(query: CatalogQuery, offset: Int, limit: Int): Page<MediaItem> {
            if (failing) error("the database is gone")
            reads += query to offset
            val rows = lists[query].orEmpty()
            return Page(rows.drop(offset).take(limit), offset, rows.size)
        }
    }

    private class Catalogue(private val channels: List<Channel>) : CatalogRepository {
        override fun groups(kind: MediaKind) = flowOf(emptyList<MediaGroup>())
        override suspend fun page(request: PageRequest): Page<MediaItem> = error("not asked")
        override suspend fun item(id: String): MediaItem? = channels.firstOrNull { it.id == id }
        override suspend fun search(query: String, limit: Int): List<MediaItem> = error("not asked")
        override suspend fun search(query: String, kind: MediaKind, limit: Int): List<MediaItem> = error("not asked")
        override fun count(kind: MediaKind, groupId: String?): Flow<Int> = flowOf(0)
        override suspend fun channelRefs(mediaIds: List<String>): List<ChannelRef> = error("not asked")
    }

    private fun bouquet(group: String, size: Int) = (0 until size).map { channel("$group$it", group) }

    private fun model(lists: Lists, channels: List<Channel> = emptyList()) =
        ChannelListViewModel(lists, Catalogue(channels))

    @Test
    fun `before anything is read the list is not ready and has no rows`() = runTest {
        val model = model(Lists(mapOf(sports to bouquet("sports", 3))))

        assertFalse(model.state.value.ready)
        assertNull(model.channelAt(0))
    }

    @Test
    fun `the list opens on the window of the playing channel and says where it stands`() = runTest {
        val rows = bouquet("sports", 200)
        val lists = Lists(mapOf(sports to rows))
        val model = model(lists)

        model.open(sports, "sports130")
        advanceUntilIdle()

        val state = model.state.value
        assertTrue(state.ready)
        assertEquals(200, state.total)
        assertEquals("the playing channel is the row it opens on", 130, state.start)
        assertEquals("its own window was read first, and only it", listOf(sports to 120), lists.reads)
        assertEquals("sports130", model.channelAt(130)?.id)
        assertNull("a row of a window that was not read is not there yet", model.channelAt(5))
    }

    @Test
    fun `a channel the list does not hold is listed in its own bouquet`() = runTest {
        val here = bouquet("news", 4)
        val lists = Lists(mapOf(sports to bouquet("sports", 3), news to here))
        val model = model(lists, channels = here)

        // Pressed in the sports list, and not in it: the keys would step in its own bouquet, and so does this.
        model.open(sports, "news2")
        advanceUntilIdle()

        assertEquals(4, model.state.value.total)
        assertEquals(2, model.state.value.start)
        assertEquals(news, lists.reads.single().first)
    }

    @Test
    fun `a channel pressed in no list is listed in its own bouquet`() = runTest {
        val here = bouquet("sports", 5)
        val lists = Lists(mapOf(sports to here))
        val model = model(lists, channels = here)

        model.open(null, "sports3")
        advanceUntilIdle()

        assertEquals(5, model.state.value.total)
        assertEquals(3, model.state.value.start)
    }

    @Test
    fun `a channel the catalogue no longer has is a list of nothing`() = runTest {
        val model = model(Lists(emptyMap()))

        model.open(sports, "gone")
        advanceUntilIdle()

        assertTrue("the answer is in", model.state.value.ready)
        assertEquals(0, model.state.value.total)
    }

    @Test
    fun `opening the same list on the same channel again reads nothing more`() = runTest {
        val lists = Lists(mapOf(sports to bouquet("sports", 100)))
        val model = model(lists)

        model.open(sports, "sports10")
        advanceUntilIdle()
        model.open(sports, "sports10")
        advanceUntilIdle()

        assertEquals(1, lists.reads.size)
    }

    @Test
    fun `asking for a row reads its window, and the next when it is near the edge`() = runTest {
        val lists = Lists(mapOf(sports to bouquet("sports", 300)))
        val model = model(lists)
        model.open(sports, "sports0")
        advanceUntilIdle()
        val before = model.state.value.version

        // Row 55 is five from the end of the first window: the second is read before it is needed.
        model.want(55)
        advanceUntilIdle()

        assertEquals("the second window is held", "sports60", model.channelAt(60)?.id)
        assertTrue("and a screen drawing rows is told", model.state.value.version > before)
        assertEquals("nothing further than the next window", listOf(0, 60), lists.reads.map { it.second })
    }

    @Test
    fun `a row far from any edge reads nothing`() = runTest {
        val lists = Lists(mapOf(sports to bouquet("sports", 300)))
        val model = model(lists)
        model.open(sports, "sports30")
        advanceUntilIdle()
        val reads = lists.reads.size

        model.want(30)
        advanceUntilIdle()

        assertEquals(reads, lists.reads.size)
    }

    @Test
    fun `a window that cannot be read is a list of nothing and not a crash`() = runTest {
        val lists = Lists(mapOf(sports to bouquet("sports", 100)))
        lists.failing = true
        val model = model(lists)

        model.open(sports, "sports10")
        advanceUntilIdle()

        assertTrue(model.state.value.ready)
        assertEquals(0, model.state.value.total)
    }

    @Test
    fun `a list that failed is read again the next time it is opened`() = runTest {
        val lists = Lists(mapOf(sports to bouquet("sports", 100)))
        lists.failing = true
        val model = model(lists)
        model.open(sports, "sports10")
        advanceUntilIdle()

        lists.failing = false
        model.open(sports, "sports10")
        advanceUntilIdle()

        assertEquals("the second try read it", 100, model.state.value.total)
        assertEquals("sports10", model.channelAt(10)?.id)
    }

    @Test
    fun `a later window that cannot be read leaves its rows as they were`() = runTest {
        val lists = Lists(mapOf(sports to bouquet("sports", 300)))
        val model = model(lists)
        model.open(sports, "sports0")
        advanceUntilIdle()

        lists.failing = true
        model.want(200)
        advanceUntilIdle()

        assertNull(model.channelAt(200))
        assertEquals("the list is as it was", 300, model.state.value.total)
        assertEquals("sports0", model.channelAt(0)?.id)
    }
}
