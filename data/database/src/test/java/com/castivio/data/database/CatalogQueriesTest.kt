package com.castivio.data.database

import com.castivio.domain.MediaKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The read path: paging, search, series aggregation, watch state.
 *
 * Each of these is a query a screen depends on, so each is verified against real
 * SQLite rather than trusted because it compiles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatalogQueriesTest {

    private lateinit var database: CastivioDatabase

    @Before
    fun setUp() {
        database = CastivioDatabase.inMemory(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ------------------------------------------------------------------- paging

    @Test
    fun `paging returns provider order and pages without gaps`() = runBlocking {
        Fixtures.import(database, Fixtures.livePlaylist(150), batchSize = 40)

        val source = database.mediaDao().pageByProvider("LIVE")
        val first = source.firstPage(size = 60)

        assertEquals(60, first.size)
        assertEquals("Channel 0", first.first().title)
        assertEquals("Channel 59", first.last().title)
        assertEquals((0 until 60).toList(), first.map { it.providerOrder })
    }

    @Test
    fun `a group page contains only that group`() = runBlocking {
        Fixtures.import(
            database,
            Fixtures.livePlaylist(20) { if (it % 2 == 0) "Sports" else "News" },
        )
        val sports = database.groupDao().groupsNow("LIVE").first { it.name == "Sports" }

        val rows = database.mediaDao().pageByProviderInGroup("LIVE", sports.id).firstPage()

        assertEquals(10, rows.size)
        assertTrue(rows.all { it.groupId == sports.id })
        assertEquals(listOf("Channel 0", "Channel 2", "Channel 4"), rows.take(3).map { it.title })
        assertEquals(10, sports.itemCount)
    }

    @Test
    fun `name sort ignores provider decoration and articles`() = runBlocking {
        val lines = listOf(
            "#EXTM3U",
            """#EXTINF:-1 group-title="Movies",[4K] The Matrix""",
            "http://host/movie/u/p/1.mp4",
            """#EXTINF:-1 group-title="Movies",|AR| Zulu Dawn""",
            "http://host/movie/u/p/2.mp4",
            """#EXTINF:-1 group-title="Movies",••• Alpha""",
            "http://host/movie/u/p/3.mp4",
        )
        Fixtures.import(database, lines)

        val ascending = database.mediaDao().pageByNameAsc("MOVIE").firstPage()
        assertEquals(listOf("••• Alpha", "[4K] The Matrix", "|AR| Zulu Dawn"), ascending.map { it.title })

        val descending = database.mediaDao().pageByNameDesc("MOVIE").firstPage()
        assertEquals(listOf("|AR| Zulu Dawn", "[4K] The Matrix", "••• Alpha"), descending.map { it.title })
    }

    @Test
    fun `recently added sorts new arrivals first`() = runBlocking {
        Fixtures.import(database, Fixtures.livePlaylist(2), now = 1_000L)
        // A later refresh adds a third channel; the first two keep their added_at.
        Fixtures.import(database, Fixtures.livePlaylist(3), now = 9_000L)

        val rows = database.mediaDao().pageByRecent("LIVE").firstPage()

        assertEquals("Channel 2", rows.first().title)
        assertEquals(9_000L, rows.first().addedAt)
    }

    // ------------------------------------------------------------------- search

    @Test
    fun `search matches prefixes while the user is still typing`() = runBlocking {
        Fixtures.import(database, Fixtures.mixedPlaylist())
        val repository = repository()

        assertEquals("Nova Sports 1", repository.search("nov", limit = 10).single().title)
        // Two terms, both prefixes, in either order.
        assertEquals("Nova Sports 1", repository.search("spo nov", limit = 10).single().title)
        assertTrue(repository.search("zzz", limit = 10).isEmpty())
    }

    @Test
    fun `search works in arabic`() = runBlocking {
        Fixtures.import(database, Fixtures.mixedPlaylist())

        val hits = repository().search("الري", limit = 10)

        assertEquals("قناة الرياضة", hits.single().title)
    }

    @Test
    fun `punctuation a user types is not a query syntax error`() = runBlocking {
        val lines = listOf(
            "#EXTM3U",
            """#EXTINF:7200 group-title="Movies",Mission: Impossible - Fallout""",
            "http://host/movie/u/p/1.mp4",
        )
        Fixtures.import(database, lines)
        val repository = repository()

        // Raw FTS would treat ':' and '-' as operators and throw.
        assertEquals(1, repository.search("Mission: Impossible", limit = 10).size)
        assertEquals(1, repository.search("fallout", limit = 10).size)
        // Nothing searchable is an idle state, not an error.
        assertTrue(repository.search("...", limit = 10).isEmpty())
    }

    @Test
    fun `search can be scoped to one kind`() = runBlocking {
        Fixtures.import(database, Fixtures.mixedPlaylist())
        val repository = repository()

        assertTrue(repository.search("breaking", MediaKind.LIVE, limit = 10).isEmpty())
        assertEquals(3, repository.search("breaking", MediaKind.SERIES, limit = 10).size)
    }

    @Test
    fun `an episode is findable by its show name`() = runBlocking {
        Fixtures.import(database, Fixtures.mixedPlaylist())

        // "Pilot" is the row's title; "Breaking Bad" is only the show name, which
        // is why search_text carries both.
        val hits = repository().search("breaking", limit = 10)

        assertEquals(3, hits.size)
        assertTrue(hits.any { it.title == "Pilot" })
    }

    // ------------------------------------------------------------------- series

    @Test
    fun `series are listed as shows, not as episodes`() = runBlocking {
        Fixtures.import(database, Fixtures.mixedPlaylist())

        val shows = database.mediaDao().pageSeries().firstPage()

        assertEquals(2, shows.size)
        val breakingBad = shows.first { it.title == "Breaking Bad" }
        assertEquals(3, breakingBad.episodeCount)
        assertEquals(2, breakingBad.seasonCount)
        assertEquals(1, shows.first { it.title == "Chernobyl" }.episodeCount)
    }

    @Test
    fun `seasons come back ordered with their episodes`() = runBlocking {
        Fixtures.import(database, Fixtures.mixedPlaylist())
        val show = database.mediaDao().pageSeries().firstPage().first { it.title == "Breaking Bad" }

        val seasons = repository().seasons(show.seriesId).first()

        assertEquals(listOf(1, 2), seasons.map { it.number })
        assertEquals(listOf("Pilot", "Cat in the Bag"), seasons[0].episodes.map { it.title })
        assertEquals(listOf(1, 2), seasons[0].episodes.map { it.episodeNumber })
    }

    // -------------------------------------------------------------- watch state

    @Test
    fun `continue watching keeps unfinished items and drops finished ones`() = runBlocking {
        Fixtures.import(database, Fixtures.livePlaylist(3))
        val ids = database.mediaDao().window("LIVE", 0, 3).map { it.id }
        val progress = RoomProgressRepository(database.progressDao()) { 1_000L }

        progress.save(ids[0], positionMs = 60_000, durationMs = 600_000)   // 10%
        progress.save(ids[1], positionMs = 594_000, durationMs = 600_000)  // 99% — finished
        progress.save(ids[2], positionMs = 60_000, durationMs = null)      // live, unknown length

        val inProgress = database.progressDao().pageInProgress().firstPage()

        assertEquals(setOf(ids[0], ids[2]), inProgress.map { it.media.id }.toSet())
        // History keeps everything, including what was finished.
        assertEquals(3, database.progressDao().pageHistory().firstPage().size)
    }

    @Test
    fun `a position too short to resume is not stored`() = runBlocking {
        Fixtures.import(database, Fixtures.livePlaylist(1))
        val id = database.mediaDao().window("LIVE", 0, 1).single().id
        val progress = RoomProgressRepository(database.progressDao()) { 1_000L }

        // Tuning past a channel must not fill Continue Watching.
        progress.save(id, positionMs = 3_000, durationMs = 600_000)
        assertEquals(0, database.progressDao().pageHistory().firstPage().size)

        progress.save(id, positionMs = 30_000, durationMs = 600_000)
        assertEquals(1, database.progressDao().pageHistory().firstPage().size)
        assertEquals(30_000L, progress.progress(id)!!.positionMs)
    }

    @Test
    fun `favourites toggle and report their state`() = runBlocking {
        Fixtures.import(database, Fixtures.livePlaylist(2))
        val ids = database.mediaDao().window("LIVE", 0, 2).map { it.id }
        val favorites = RoomFavoritesRepository(database.favoriteDao()) { 1_000L }

        assertTrue(favorites.toggle(ids[0]))
        assertTrue(favorites.isFavorite(ids[0]).first())
        assertEquals(1, favorites.count().first())

        assertTrue(!favorites.toggle(ids[0]))
        assertEquals(0, favorites.count().first())
    }

    // -------------------------------------------------------------------- other

    @Test
    fun `groups and counts are observable per kind`() = runBlocking {
        Fixtures.import(database, Fixtures.mixedPlaylist())
        val repository = repository()

        assertEquals(listOf("Sports", "News"), repository.groups(MediaKind.LIVE).first().map { it.name })
        assertEquals(3, repository.count(MediaKind.LIVE).first())
        assertEquals(1, repository.count(MediaKind.RADIO).first())
    }

    @Test
    fun `a bounded page reports the total without loading it`() = runBlocking {
        Fixtures.import(database, Fixtures.livePlaylist(120), batchSize = 40)

        val page = repository().page(
            com.castivio.domain.PageRequest(kind = MediaKind.LIVE, offset = 100, limit = 60),
        )

        assertEquals(20, page.items.size)
        assertEquals(120, page.totalCount)
        assertTrue(!page.hasMore)
    }

    private fun repository() = RoomCatalogRepository(
        mediaDao = database.mediaDao(),
        groupDao = database.groupDao(),
        favoriteDao = database.favoriteDao(),
        progressDao = database.progressDao(),
    )
}
