package com.castivio.data.database

import com.castivio.domain.CatalogQuery
import com.castivio.domain.MediaKind
import com.castivio.domain.SortOrder
import com.castivio.domain.step
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The channel before and the channel after, against real SQLite: read from the list's own windows, so
 * they follow the order the list is in -- the provider's, the viewer's own, a search's.
 *
 * Five channels in two bouquets, in the order the playlist listed them:
 * Sport Alpha, News Beta, Sport Gamma, Sport Delta, News Epsilon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChannelStepTest {

    private lateinit var database: CastivioDatabase

    @Before
    fun setUp() {
        database = CastivioDatabase.inMemory(RuntimeEnvironment.getApplication())
        Fixtures.import(
            database,
            listOf(
                "#EXTM3U",
                """#EXTINF:-1 group-title="Sports",Sport Alpha""", "http://host/live/u/p/1.ts",
                """#EXTINF:-1 group-title="News",News Beta""", "http://host/live/u/p/2.ts",
                """#EXTINF:-1 group-title="Sports",Sport Gamma""", "http://host/live/u/p/3.ts",
                """#EXTINF:-1 group-title="Sports",Sport Delta""", "http://host/live/u/p/4.ts",
                """#EXTINF:-1 group-title="News",News Epsilon""", "http://host/live/u/p/5.ts",
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun repository() = RoomCatalogRepository(
        mediaDao = database.mediaDao(),
        groupDao = database.groupDao(),
        favoriteDao = database.favoriteDao(),
        progressDao = database.progressDao(),
        database = database,
    )

    private fun groupId(name: String) =
        runBlocking { database.groupDao().groupsNow("LIVE").single { it.name == name }.id }

    private fun idOf(title: String) =
        runBlocking { database.mediaDao().window("LIVE", 0, 100).single { it.title == title }.id }

    private val whole = CatalogQuery(kind = MediaKind.LIVE)
    private fun sports() = CatalogQuery(kind = MediaKind.LIVE, groupId = groupId("Sports"))

    private fun next(query: CatalogQuery, title: String, by: Int = 1) =
        runBlocking { repository().step(query, idOf(title), by)?.channel?.title }

    // ------------------------------------------------------------------------ the provider's order

    @Test
    fun `a channel's place is the number of channels before it`() = runBlocking {
        assertEquals(0, repository().rank(whole, idOf("Sport Alpha")))
        assertEquals(3, repository().rank(whole, idOf("Sport Delta")))
        assertEquals(1, repository().rank(sports(), idOf("Sport Gamma")))
        assertEquals(2, repository().rank(sports(), idOf("Sport Delta")))
    }

    @Test
    fun `the next channel of a bouquet is the one after it in that bouquet`() {
        assertEquals("Sport Gamma", next(sports(), "Sport Alpha"))
        assertEquals("Sport Delta", next(sports(), "Sport Gamma"))
    }

    @Test
    fun `the previous channel of a bouquet is the one before it in that bouquet`() {
        assertEquals("Sport Gamma", next(sports(), "Sport Delta", by = -1))
        assertEquals("Sport Alpha", next(sports(), "Sport Gamma", by = -1))
    }

    @Test
    fun `after the last of a bouquet is its first, and before the first is its last`() {
        assertEquals("Sport Alpha", next(sports(), "Sport Delta"))
        assertEquals("Sport Delta", next(sports(), "Sport Alpha", by = -1))
    }

    @Test
    fun `the whole section steps through every bouquet in the order the provider listed it`() {
        assertEquals("News Beta", next(whole, "Sport Alpha"))
        assertEquals("Sport Gamma", next(whole, "News Beta"))
        assertEquals("Sport Alpha", next(whole, "News Epsilon"))
        assertEquals("News Epsilon", next(whole, "Sport Alpha", by = -1))
    }

    @Test
    fun `a channel of another bouquet is not in the list`() = runBlocking {
        assertNull(repository().rank(sports(), idOf("News Beta")))
        assertNull(repository().step(sports(), idOf("News Beta"), 1))
    }

    @Test
    fun `a window is the list's own page, and says how long the list is`() = runBlocking {
        val page = repository().window(sports(), offset = 1, limit = 2)

        assertEquals(listOf("Sport Gamma", "Sport Delta"), page.items.map { it.title })
        assertEquals(3, page.totalCount)
    }

    @Test
    fun `a window past the end is empty and still says how long the list is`() = runBlocking {
        val page = repository().window(sports(), offset = 3, limit = 1)

        assertEquals(emptyList<String>(), page.items.map { it.title })
        assertEquals(3, page.totalCount)
    }

    // ------------------------------------------------------------------------ the viewer's order

    @Test
    fun `the viewer's own order is the order that is stepped through`() = runBlocking {
        val repository = repository()
        val group = groupId("Sports")
        repository.begin(group)
        repository.move(group, idOf("Sport Delta"), up = true)
        repository.move(group, idOf("Sport Delta"), up = true)
        repository.save(group)
        // Delta, Alpha, Gamma now.

        assertEquals(0, repository.rank(sports(), idOf("Sport Delta")))
        assertEquals("Sport Alpha", repository.step(sports(), idOf("Sport Delta"), 1)?.channel?.title)
        assertEquals("Sport Gamma", repository.step(sports(), idOf("Sport Alpha"), 1)?.channel?.title)
        assertEquals("Sport Delta", repository.step(sports(), idOf("Sport Gamma"), 1)?.channel?.title)
        assertEquals("Sport Gamma", repository.step(sports(), idOf("Sport Delta"), -1)?.channel?.title)
    }

    @Test
    fun `an arrangement of one bouquet does not change the whole section's order`() = runBlocking {
        val repository = repository()
        val group = groupId("Sports")
        repository.begin(group)
        repository.move(group, idOf("Sport Delta"), up = true)
        repository.save(group)

        assertEquals("News Beta", repository.step(whole, idOf("Sport Alpha"), 1)?.channel?.title)
    }

    // ------------------------------------------------------------------------------- a search

    @Test
    fun `a search steps through what it found and nothing else`() {
        val sport = CatalogQuery(kind = MediaKind.LIVE, search = "sport")

        assertEquals("Sport Gamma", next(sport, "Sport Alpha"))
        assertEquals("Sport Delta", next(sport, "Sport Gamma"))
        assertEquals("Sport Alpha", next(sport, "Sport Delta"))
        assertEquals("Sport Delta", next(sport, "Sport Alpha", by = -1))
    }

    @Test
    fun `a channel the search left out is not in its list`() = runBlocking {
        val sport = CatalogQuery(kind = MediaKind.LIVE, search = "sport")

        assertNull(repository().rank(sport, idOf("News Beta")))
    }

    // -------------------------------------------------------------------------------- a sort

    @Test
    fun `a sort that is not read in windows has nothing to step through`() = runBlocking {
        val byName = CatalogQuery(kind = MediaKind.LIVE, sort = SortOrder.NAME_ASC)

        assertNull(repository().rank(byName, idOf("Sport Alpha")))
        assertNull(repository().step(byName, idOf("Sport Alpha"), 1))
    }
}
