package com.castivio.data.database

import com.castivio.domain.CatalogQuery
import com.castivio.domain.MediaKind
import com.castivio.domain.step
import kotlinx.coroutines.flow.first
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
 * Favourites marked and unmarked several at a time, and the list they make: the provider's order, the
 * same windows as every other list of channels, and so the same neighbours for the channel keys.
 *
 * Five channels in the order the playlist listed them:
 * Sport Alpha, News Beta, Sport Gamma, Sport Delta, News Epsilon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FavoriteBatchTest {

    private lateinit var database: CastivioDatabase
    private var now = 1_000L

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

    private fun favorites() = RoomFavoritesRepository(database.favoriteDao(), clock = { now })

    private fun repository() = RoomCatalogRepository(
        mediaDao = database.mediaDao(),
        groupDao = database.groupDao(),
        favoriteDao = database.favoriteDao(),
        progressDao = database.progressDao(),
        database = database,
    )

    private fun idOf(title: String) =
        runBlocking { database.mediaDao().window("LIVE", 0, 100).single { it.title == title }.id }

    private val listed = CatalogQuery(kind = MediaKind.LIVE, favorites = true)

    private fun titles() = runBlocking {
        repository().window(listed, offset = 0, limit = 100).items.map { it.title }
    }

    // ----------------------------------------------------------------------------------- the batch

    @Test
    fun `a batch marks every channel in it`() = runBlocking {
        favorites().set(listOf(idOf("Sport Delta"), idOf("News Beta")), favorite = true)

        assertEquals(setOf(idOf("Sport Delta"), idOf("News Beta")), favorites().ids().first())
    }

    @Test
    fun `a channel that already is a favourite keeps the time it was marked`() = runBlocking {
        favorites().set(listOf(idOf("Sport Alpha")), favorite = true)
        now = 5_000L

        favorites().set(listOf(idOf("Sport Alpha"), idOf("News Beta")), favorite = true)

        assertEquals(1_000L, database.openHelper.readableDatabase
            .query("SELECT added_at FROM favorite WHERE media_id = '${idOf("Sport Alpha")}'")
            .use { it.moveToFirst(); it.getLong(0) })
        assertEquals(2, favorites().ids().first().size)
    }

    @Test
    fun `a batch removes the channels in it and no others`() = runBlocking {
        favorites().set(listOf(idOf("Sport Alpha"), idOf("News Beta"), idOf("Sport Gamma")), favorite = true)

        favorites().set(listOf(idOf("News Beta"), idOf("News Epsilon")), favorite = false)

        assertEquals(setOf(idOf("Sport Alpha"), idOf("Sport Gamma")), favorites().ids().first())
    }

    @Test
    fun `an empty batch changes nothing`() = runBlocking {
        favorites().set(listOf(idOf("Sport Alpha")), favorite = true)

        favorites().set(emptyList(), favorite = true)
        favorites().set(emptyList(), favorite = false)

        assertEquals(setOf(idOf("Sport Alpha")), favorites().ids().first())
    }

    @Test
    fun `a batch larger than one statement lands whole`() = runBlocking {
        val ids = (1..1_000).map { "gone-$it" }

        favorites().set(ids, favorite = true)
        assertEquals(1_000, favorites().ids().first().size)

        favorites().set(ids, favorite = false)
        assertEquals(0, favorites().ids().first().size)
    }

    // ------------------------------------------------------------------------------- the list

    @Test
    fun `the list is the provider's order and not the order they were marked in`() {
        runBlocking {
            favorites().set(listOf(idOf("Sport Delta")), favorite = true)
            now = 2_000L
            favorites().set(listOf(idOf("Sport Alpha")), favorite = true)
            now = 3_000L
            favorites().set(listOf(idOf("Sport Gamma")), favorite = true)
        }

        assertEquals(listOf("Sport Alpha", "Sport Gamma", "Sport Delta"), titles())
    }

    @Test
    fun `a list with nothing marked is empty and says how long it is`() = runBlocking {
        val page = repository().window(listed, offset = 0, limit = 10)

        assertEquals(emptyList<String>(), page.items.map { it.title })
        assertEquals(0, page.totalCount)
    }

    @Test
    fun `a channel that is not a favourite is not in the list`() = runBlocking {
        favorites().set(listOf(idOf("Sport Alpha")), favorite = true)

        assertNull(repository().rank(listed, idOf("News Beta")))
    }

    @Test
    fun `a channel's place is counted among the favourites`() = runBlocking {
        favorites().set(listOf(idOf("Sport Alpha"), idOf("Sport Delta"), idOf("News Epsilon")), favorite = true)

        assertEquals(0, repository().rank(listed, idOf("Sport Alpha")))
        assertEquals(1, repository().rank(listed, idOf("Sport Delta")))
        assertEquals(2, repository().rank(listed, idOf("News Epsilon")))
    }

    @Test
    fun `the channel keys step through the favourites, and go round`() = runBlocking {
        favorites().set(listOf(idOf("Sport Alpha"), idOf("Sport Delta"), idOf("News Epsilon")), favorite = true)

        assertEquals("Sport Delta", repository().step(listed, idOf("Sport Alpha"), 1)?.channel?.title)
        assertEquals("News Epsilon", repository().step(listed, idOf("Sport Delta"), 1)?.channel?.title)
        assertEquals("Sport Alpha", repository().step(listed, idOf("News Epsilon"), 1)?.channel?.title)
        assertEquals("News Epsilon", repository().step(listed, idOf("Sport Alpha"), -1)?.channel?.title)
    }

    @Test
    fun `marking favourites does not change any other list's order`() = runBlocking {
        val whole = CatalogQuery(kind = MediaKind.LIVE)
        val before = repository().window(whole, 0, 100).items.map { it.title }

        favorites().set(listOf(idOf("Sport Delta"), idOf("News Beta")), favorite = true)

        assertEquals(before, repository().window(whole, 0, 100).items.map { it.title })
    }
}
