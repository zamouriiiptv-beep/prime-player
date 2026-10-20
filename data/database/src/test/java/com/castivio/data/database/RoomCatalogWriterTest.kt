package com.castivio.data.database

import com.castivio.core.platform.DatabaseStep
import com.castivio.core.platform.PerfMode
import com.castivio.core.platform.PerfSection
import com.castivio.core.platform.PerformanceLog
import com.castivio.domain.CatalogItem
import com.castivio.domain.ImportMode
import com.castivio.domain.ImportSummary
import com.castivio.domain.MediaKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The write path, against real SQLite.
 *
 * These run on the JVM through Robolectric rather than on a device, because a
 * query that only gets exercised by launching the app on a TV is a query nobody
 * verifies.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomCatalogWriterTest {

    private lateinit var database: CastivioDatabase

    @Before
    fun setUp() {
        database = CastivioDatabase.inMemory(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `an import writes every kind with its groups and counts`() = runBlocking {
        val summary = Fixtures.import(database, Fixtures.mixedPlaylist())

        assertEquals(10, summary.items)
        val media = database.mediaDao()
        assertEquals(3, media.countNow("LIVE"))
        assertEquals(2, media.countNow("MOVIE"))
        assertEquals(4, media.countNow("SERIES"))
        assertEquals(1, media.countNow("RADIO"))

        val liveGroups = database.groupDao().groupsNow("LIVE")
        assertEquals(listOf("Sports", "News"), liveGroups.map { it.name })
        // Counts are denormalised at the end of the import, not computed per read.
        assertEquals(2, liveGroups.first { it.name == "Sports" }.itemCount)
        assertEquals(1, liveGroups.first { it.name == "News" }.itemCount)

        // Radio is its own kind, so it never appears in a live query.
        assertTrue(database.groupDao().groupsNow("RADIO").isNotEmpty())
    }

    @Test
    fun `metadata survives the write`() = runBlocking {
        Fixtures.import(database, Fixtures.mixedPlaylist())

        val row = database.mediaDao().window("LIVE", 0, 10).first()
        assertEquals("Nova Sports 1", row.title)
        assertEquals("nova.1", row.epgChannelId)
        assertEquals("http://cdn/nova.png", row.artworkUrl)
        assertEquals("http://host/live/u/p/1.ts", row.streamUrl)
        assertEquals(0, row.providerOrder)
        assertEquals(1_000L, row.addedAt)
        assertNull("live rows must carry no duration", row.durationSeconds)
        assertNotNull(row.groupId)

        val movie = database.mediaDao().window("MOVIE", 0, 10).first()
        assertEquals(7_200, movie.durationSeconds)
        // Decoration is stripped from the sort key so "[4K] The Matrix" sorts
        // under M, where a user looks for it.
        assertEquals("matrix", movie.sortTitle)
    }

    @Test
    fun `re-importing prunes dropped rows and keeps the original added_at`() = runBlocking {
        Fixtures.import(database, Fixtures.livePlaylist(4), now = 1_000L)
        val media = database.mediaDao()
        assertEquals(4, media.countNow("LIVE"))
        val firstGeneration = media.currentGeneration("src")

        // The provider drops two channels and the app refreshes.
        Fixtures.import(database, Fixtures.livePlaylist(2), now = 9_000L)

        assertEquals(2, media.countNow("LIVE"))
        assertTrue("generation must advance", media.currentGeneration("src")!! > firstGeneration!!)

        val survivor = media.window("LIVE", 0, 1).single()
        assertEquals("Channel 0", survivor.title)
        assertEquals(
            "a refresh must not make the whole library look recently added",
            1_000L,
            survivor.addedAt,
        )

        // Search must not keep returning a channel the provider removed.
        val repository = repository()
        assertTrue(repository.search("Channel 3", limit = 10).isEmpty())
        assertEquals(1, repository.search("Channel 1", limit = 10).size)
    }

    @Test
    fun `ids are stable across imports so user data still points somewhere`() = runBlocking {
        Fixtures.import(database, Fixtures.livePlaylist(3), now = 1_000L)
        val before = database.mediaDao().window("LIVE", 0, 10).map { it.id }

        Fixtures.import(database, Fixtures.livePlaylist(3), now = 5_000L)
        val after = database.mediaDao().window("LIVE", 0, 10).map { it.id }

        assertEquals(before, after)
    }

    @Test
    fun `a cancelled import keeps what was already committed`() = runBlocking {
        val summary = Fixtures.import(
            database,
            Fixtures.livePlaylist(500),
            batchSize = 20,
            isCancelled = { true },
        )

        assertTrue(summary.cancelled)
        assertEquals(20, summary.items)
        // A partial catalogue beats an empty one: the user can watch something
        // while a retry fills in the rest.
        assertEquals(20, database.mediaDao().countNow("LIVE"))
    }

    /**
     * The reason `favorite` has no foreign key onto `media`.
     *
     * Providers drop and restore channels constantly. A cascade would delete the
     * user's favourite the first time that happened, silently and permanently.
     */
    @Test
    fun `a favourite survives its channel disappearing from the playlist`() = runBlocking {
        Fixtures.import(database, Fixtures.livePlaylist(3), now = 1_000L)
        val favorites = RoomFavoritesRepository(database.favoriteDao()) { 1_000L }
        val channel2 = database.mediaDao().window("LIVE", 2, 1).single()

        assertTrue(favorites.toggle(channel2.id))
        assertEquals(1, database.favoriteDao().page().firstPage().size)

        Fixtures.import(database, Fixtures.livePlaylist(2), now = 2_000L)

        // Not listed while the channel is gone…
        assertEquals(0, database.favoriteDao().page().firstPage().size)
        // …but not forgotten either.
        assertEquals(1, favorites.count().first())

        Fixtures.import(database, Fixtures.livePlaylist(3), now = 3_000L)
        assertEquals(1, database.favoriteDao().page().firstPage().size)
    }

    @Test
    fun `two sources coexist without colliding`() = runBlocking {
        Fixtures.import(database, Fixtures.livePlaylist(3), sourceId = "a")
        Fixtures.import(database, Fixtures.livePlaylist(3), sourceId = "b")

        // Counted per source in the table, not through the browse reads: those are
        // scoped to the active subscription and would see one of the two.
        assertEquals(3, rowsOf("a"))
        assertEquals(3, rowsOf("b"))

        // Pruning one source must not touch the other.
        Fixtures.import(database, Fixtures.livePlaylist(1), sourceId = "a", now = 4_000L)
        assertEquals(1, rowsOf("a"))
        assertEquals(3, rowsOf("b"))
    }

    private fun rowsOf(sourceId: String): Int =
        database.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM media WHERE source_id = ?", arrayOf<Any>(sourceId))
            .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else -1 }

    @Test
    fun `an empty playlist leaves the catalogue empty rather than broken`() = runBlocking {
        val summary = Fixtures.import(database, listOf("#EXTM3U"))

        assertEquals(0, summary.items)
        assertEquals(0, database.mediaDao().countNow("LIVE"))
        assertTrue(repository().search("anything", limit = 10).isEmpty())
    }

    // ------------------------------------------------------------ the measurement build

    /**
     * Rows and their search entries were one figure, and the question it could not answer
     * was how much of it the index was. A replacing import writes no entry per row -- it
     * rebuilds the index once -- so it reports no search rows at all, and says how it saved.
     */
    @Test
    fun `rows and search rows are timed apart, and search rows only where they are written`() {
        PerformanceLog.begin(PerfSection.CHANNELS)
        Fixtures.import(database, Fixtures.livePlaylist(3))
        PerformanceLog.settled(PerfMode.COLD, fullLoad = true)
        val replaced = PerformanceLog.current.value!!

        assertEquals(listOf("REPLACE"), replaced.saveModes)
        assertTrue(DatabaseStep.ROWS in replaced.databaseSteps)
        assertFalse("a replacing import indexed row by row", DatabaseStep.SEARCH_ROWS in replaced.databaseSteps)
        assertTrue(DatabaseStep.SEARCH in replaced.databaseSteps)

        PerformanceLog.begin(PerfSection.CHANNELS)
        appendLive(3)
        PerformanceLog.settled(PerfMode.COLD, fullLoad = true)
        val appended = PerformanceLog.current.value!!

        assertEquals(listOf("APPEND"), appended.saveModes)
        assertTrue(DatabaseStep.SEARCH_ROWS in appended.databaseSteps)
        assertFalse("an append rebuilt the index", DatabaseStep.SEARCH in appended.databaseSteps)
    }

    /** Rows written straight through the writer, appending, as a section import does. */
    private fun appendLive(count: Int) {
        val writer = RoomCatalogWriter(database) { 2_000L }
        writer.begin("src", ImportMode.APPEND)
        writer.writeItems(
            (0 until count).map {
                CatalogItem(id = "appended-$it", sourceId = "src", kind = MediaKind.LIVE, title = "Appended $it", streamUrl = "http://host/a/$it.ts")
            },
        )
        writer.finish(
            ImportSummary(sourceId = "src", items = count, groups = 0, skipped = 0, byKind = mapOf(MediaKind.LIVE to count), durationMs = 1),
        )
    }

    private fun repository() = RoomCatalogRepository(
        mediaDao = database.mediaDao(),
        groupDao = database.groupDao(),
        favoriteDao = database.favoriteDao(),
        progressDao = database.progressDao(),
    )
}
