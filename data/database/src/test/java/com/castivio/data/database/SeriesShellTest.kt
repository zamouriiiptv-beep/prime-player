package com.castivio.data.database

import com.castivio.domain.CatalogItem
import com.castivio.domain.ImportMode
import com.castivio.domain.ImportSummary
import com.castivio.domain.MediaKind
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
 * A show's own row, and the episodes written beside it, against real SQLite.
 *
 * The catalogue import writes a show as a row with no episode number; its episodes are fetched when it is opened,
 * by the provider's id for it. The row is what says which provider and which id, so what a lookup of it answers is
 * what the episodes can be asked for at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SeriesShellTest {

    private lateinit var database: CastivioDatabase

    @Before
    fun setUp() {
        database = CastivioDatabase.inMemory(RuntimeEnvironment.getApplication())
        Fixtures.register(database, "src")
        val writer = RoomCatalogWriter(database) { 2_000L }
        writer.begin("src", ImportMode.APPEND)
        writer.writeItems(
            listOf(
                CatalogItem(
                    id = "shell-1", sourceId = "src", kind = MediaKind.SERIES, title = "Dark Harbor",
                    streamUrl = "", artworkUrl = "http://img/dh.jpg", groupId = "g1", providerRef = "551",
                    providerOrder = 0, seriesId = "show-1", seriesTitle = "Dark Harbor",
                ),
                CatalogItem(
                    id = "ep-1", sourceId = "src", kind = MediaKind.SERIES, title = "The Tide Table",
                    streamUrl = "http://host/series/u/p/9001.mkv", artworkUrl = "http://img/e1.jpg", groupId = "g1",
                    providerRef = "9001", providerOrder = 1, durationSeconds = 46 * 60 + 20, seriesId = "show-1",
                    seriesTitle = "Dark Harbor", seasonNumber = 1, episodeNumber = 1,
                ),
                CatalogItem(
                    id = "ep-2", sourceId = "src", kind = MediaKind.SERIES, title = "Low Water",
                    streamUrl = "http://host/series/u/p/9002.mkv", artworkUrl = null, groupId = "g1",
                    providerRef = "9002", providerOrder = 2, durationSeconds = null, seriesId = "show-1",
                    seriesTitle = "Dark Harbor", seasonNumber = 1, episodeNumber = 2,
                ),
            ),
        )
        writer.finish(ImportSummary(sourceId = "src", items = 3, groups = 0, skipped = 0, byKind = mapOf(MediaKind.SERIES to 3), durationMs = 1))
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

    @Test
    fun `a show's own row says which provider it is from and what that provider calls it`() = runBlocking {
        val shell = repository().seriesShell("show-1")!!

        assertEquals("src", shell.sourceId)
        assertEquals("551", shell.providerRef)
        assertEquals("Dark Harbor", shell.title)
        assertEquals("g1", shell.groupId)
        // The row a favourite of the show is kept under.
        assertEquals("shell-1", shell.mediaId)
    }

    @Test
    fun `a show nobody listed has no row`() = runBlocking {
        assertNull(repository().seriesShell("show-none"))
    }

    @Test
    fun `an episode is not a show's own row`() = runBlocking {
        // Looked up by the id the episodes share with their show, the answer is the show's row and never an episode's.
        assertEquals("551", repository().seriesShell("show-1")!!.providerRef)
    }

    @Test
    fun `an episode carries its running time in whole minutes, and none when the provider gave none`() = runBlocking {
        val episodes = repository().seasons("show-1").first().single().episodes

        assertEquals(46, episodes[0].durationMinutes)
        assertNull(episodes[1].durationMinutes)
    }

    private fun favorites() = RoomFavoritesRepository(database.favoriteDao(), clock = { 3_000L })

    @Test
    fun `a show marked as a favourite is listed with its whole counts, and one not marked is not`() = runBlocking {
        assertEquals(emptyList<Any>(), database.mediaDao().windowFavoriteSeries(0, 60))

        favorites().toggle("shell-1")

        val listed = database.mediaDao().windowFavoriteSeries(0, 60).single().toDomain()
        assertEquals("show-1", listed.seriesId)
        assertEquals("Dark Harbor", listed.title)
        assertEquals(2, listed.episodeCount)
        assertEquals(1, listed.seasonCount)
    }

    @Test
    fun `an episode marked as a favourite does not make its show one`() = runBlocking {
        favorites().toggle("ep-1")

        assertEquals(emptyList<Any>(), database.mediaDao().windowFavoriteSeries(0, 60))
    }

    @Test
    fun `taking the mark away takes the show out of the list`() = runBlocking {
        favorites().toggle("shell-1")
        favorites().toggle("shell-1")

        assertEquals(emptyList<Any>(), database.mediaDao().windowFavoriteSeries(0, 60))
    }

    @Test
    fun `other shows of the same category are the ones a show's page offers as similar`() = runBlocking {
        val writer = RoomCatalogWriter(database) { 4_000L }
        writer.begin("src", ImportMode.APPEND)
        writer.writeItems(
            listOf(
                CatalogItem(
                    id = "shell-2", sourceId = "src", kind = MediaKind.SERIES, title = "Chernobyl",
                    streamUrl = "", artworkUrl = null, groupId = "g1", providerRef = "552",
                    providerOrder = 5, seriesId = "show-2", seriesTitle = "Chernobyl",
                ),
                CatalogItem(
                    id = "shell-3", sourceId = "src", kind = MediaKind.SERIES, title = "Elsewhere",
                    streamUrl = "", artworkUrl = null, groupId = "g2", providerRef = "553",
                    providerOrder = 6, seriesId = "show-3", seriesTitle = "Elsewhere",
                ),
            ),
        )
        writer.finish(ImportSummary(sourceId = "src", items = 2, groups = 0, skipped = 0, byKind = mapOf(MediaKind.SERIES to 2), durationMs = 1))

        val similar = repository().similarTo("show-1", limit = 5)

        // The show itself is in its own category and is not offered; another category's show is not either.
        assertEquals(listOf("show-2"), similar.map { it.seriesId })
        assertEquals(emptyList<Any>(), repository().similarTo("show-none", limit = 5))
        assertEquals(emptyList<Any>(), repository().similarTo("show-1", limit = 0))
    }
}
