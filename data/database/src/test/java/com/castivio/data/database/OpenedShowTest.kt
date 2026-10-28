package com.castivio.data.database

import com.castivio.domain.CatalogItem
import com.castivio.domain.ImportMode
import com.castivio.domain.ImportSummary
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What opening a show leaves the list of shows as: the same.
 *
 * An Xtream import writes a show as one row and its category with a count; its episodes are written when the page opens,
 * into the same category and with numbers of their own from nought. They made a category read 394 shows before a show was
 * opened and 564 after it, took the show to the head of the provider's order, and put one of its stills where its poster
 * stood. None of that is the viewer's doing, and none of it may follow from opening a page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenedShowTest {

    private lateinit var database: CastivioDatabase

    @Before
    fun setUp() {
        database = CastivioDatabase.inMemory(RuntimeEnvironment.getApplication())
        Fixtures.register(database, "src")
        // The catalogue as an Xtream import leaves it: three shows in the panel's order, in one category.
        val writer = RoomCatalogWriter(database) { 1_000L }
        writer.begin("src", ImportMode.APPEND)
        writer.writeGroups(listOf(MediaGroup(GROUP, "Drama", MediaKind.SERIES)))
        writer.writeItems(listOf(shell("a", 100, "Alpha"), shell("b", 200, "Bravo"), shell("c", 300, "Charlie")))
        writer.countGroups(listOf(GROUP))
        writer.commit()
        writer.finish(ImportSummary(sourceId = "src", items = 3, groups = 1, skipped = 0, byKind = mapOf(MediaKind.SERIES to 3), durationMs = 1))
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun shell(key: String, order: Int, title: String) = CatalogItem(
        id = "shell-$key", sourceId = "src", kind = MediaKind.SERIES, title = title, streamUrl = "",
        artworkUrl = "http://img/$key.jpg", groupId = GROUP, providerRef = key, providerOrder = order,
        seriesId = "show-$key", seriesTitle = title,
    )

    /** A show's episodes, written the way opening its page writes them: an append, no category, numbered from nought. */
    private fun open(key: String, episodes: Int, title: String) {
        val writer = RoomCatalogWriter(database) { 2_000_000L }
        writer.begin("src", ImportMode.APPEND)
        writer.writeItems(
            (0 until episodes).map { number ->
                CatalogItem(
                    id = "ep-$key-$number", sourceId = "src", kind = MediaKind.SERIES, title = "Episode $number",
                    streamUrl = "http://host/series/u/p/$number.mkv", artworkUrl = "http://img/$key-still-$number.jpg",
                    groupId = GROUP, providerRef = "$number", providerOrder = number, seriesId = "show-$key",
                    seriesTitle = title, seasonNumber = 1, episodeNumber = number + 1,
                )
            },
        )
        writer.finish(ImportSummary(sourceId = "src", items = episodes, groups = 0, skipped = 0, byKind = mapOf(MediaKind.SERIES to episodes), durationMs = 1))
    }

    private suspend fun shows(sort: String = "PROVIDER") = database.mediaDao().windowSeriesInGroup(GROUP, null, sort, 0, 60)

    private suspend fun counted() = database.groupDao().groupsNow("SERIES").single { it.id == GROUP }.itemCount

    @Test
    fun `a category counts its shows, and opening one of them does not add its episodes`() = runBlocking {
        assertEquals(3, counted())

        open("b", episodes = 170, title = "Bravo")

        assertEquals(3, counted())
    }

    @Test
    fun `a category counted again counts shows, whatever the shows hold`() = runBlocking {
        open("b", episodes = 170, title = "Bravo")

        // Any import of the subscription that wrote categories counts all of them again.
        val writer = RoomCatalogWriter(database) { 3_000_000L }
        writer.begin("src", ImportMode.APPEND)
        writer.writeGroups(listOf(MediaGroup(GROUP, "Drama", MediaKind.SERIES)))
        writer.finish(ImportSummary(sourceId = "src", items = 0, groups = 1, skipped = 0, byKind = emptyMap(), durationMs = 1))

        assertEquals(3, counted())
    }

    @Test
    fun `a show that was opened keeps its place in the provider's order`() = runBlocking {
        open("b", episodes = 170, title = "Bravo")

        assertEquals(listOf("Alpha", "Bravo", "Charlie"), shows().map { it.title })
    }

    @Test
    fun `a show that was opened is not the most recently added`() = runBlocking {
        // Added in the order the provider listed them at the time of the import: all three when it ran.
        val before = shows(sort = "RECENTLY_ADDED").map { it.seriesId }

        open("b", episodes = 3, title = "Bravo")

        assertEquals(before, shows(sort = "RECENTLY_ADDED").map { it.seriesId })
    }

    @Test
    fun `a show that was opened keeps its own poster, and has its episodes counted`() = runBlocking {
        open("b", episodes = 3, title = "Bravo")

        val bravo = shows().single { it.title == "Bravo" }

        assertEquals("http://img/b.jpg", bravo.artworkUrl)
        assertEquals(3, bravo.episodeCount)
        assertEquals(1, bravo.seasonCount)
    }

    private companion object {
        const val GROUP = "g-drama"
    }
}
