package com.castivio.data.database

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
 * The shows list narrowed by a search and read in the order asked for.
 *
 * The fixture is a playlist whose only series are two: Breaking Bad, three episodes in two seasons, and Chernobyl, one.
 * What matters is that a search is of the shows and found through their episodes -- and that the figures a show carries
 * are the show's, whatever matched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SeriesWindowTest {

    private lateinit var database: CastivioDatabase

    @Before
    fun open() {
        database = CastivioDatabase.inMemory(RuntimeEnvironment.getApplication())
    }

    @After
    fun close() {
        database.close()
    }

    private suspend fun shows(search: String = "", sort: String = "PROVIDER", group: String? = null) =
        FtsQuery.build(search).let { match ->
            if (group == null) database.mediaDao().windowSeries(match, sort, 0, 60)
            else database.mediaDao().windowSeriesInGroup(group, match, sort, 0, 60)
        }

    @Test
    fun `no search and the provider's order is every show, as the list was`() = runBlocking {
        Fixtures.import(database, Fixtures.mixedPlaylist())

        val all = shows()

        assertEquals(listOf("Breaking Bad", "Chernobyl"), all.map { it.title })
        assertEquals(3, all.first().episodeCount)
    }

    @Test
    fun `a search finds the show through its title`() = runBlocking {
        Fixtures.import(database, Fixtures.mixedPlaylist())

        val found = shows(search = "chern")

        assertEquals(listOf("Chernobyl"), found.map { it.title })
    }

    @Test
    fun `a search that matched one episode lists its show once, with every episode the show has`() = runBlocking {
        Fixtures.import(database, Fixtures.mixedPlaylist())

        // "Cat in the Bag" is one episode's title; the show is what is listed, and it has all three.
        val found = shows(search = "cat bag")

        assertEquals(1, found.size)
        assertEquals("Breaking Bad", found.single().title)
        assertEquals(3, found.single().episodeCount)
        assertEquals(2, found.single().seasonCount)
    }

    @Test
    fun `a search that matches nothing is an empty list, and one with nothing searchable in it is the whole list`() = runBlocking {
        Fixtures.import(database, Fixtures.mixedPlaylist())

        assertTrue(shows(search = "zzzzzz").isEmpty())
        assertEquals(2, shows(search = "...").size)
    }

    @Test
    fun `a search finds nothing that is not a show of the active kind`() = runBlocking {
        Fixtures.import(database, Fixtures.mixedPlaylist())

        // "Matrix" is a film and "Nova" a channel: neither is a show.
        assertTrue(shows(search = "matrix").isEmpty())
        assertTrue(shows(search = "nova").isEmpty())
    }

    @Test
    fun `the name orders sort the shows by name, either way`() = runBlocking {
        Fixtures.import(database, Fixtures.mixedPlaylist())

        assertEquals(listOf("Breaking Bad", "Chernobyl"), shows(sort = "NAME_ASC").map { it.title })
        assertEquals(listOf("Chernobyl", "Breaking Bad"), shows(sort = "NAME_DESC").map { it.title })
    }

    @Test
    fun `recently added with nothing added is the provider's order`() = runBlocking {
        Fixtures.import(database, Fixtures.mixedPlaylist())

        assertEquals(shows(sort = "PROVIDER").map { it.seriesId }, shows(sort = "RECENTLY_ADDED").map { it.seriesId })
    }

    @Test
    fun `a category lists its own shows, narrowed and ordered the same way`() = runBlocking {
        Fixtures.import(database, Fixtures.mixedPlaylist())
        val series = database.groupDao().groupsNow("SERIES").first { it.name == "Series" }

        assertEquals(2, shows(group = series.id).size)
        assertEquals(listOf("Chernobyl"), shows(search = "chernobyl", group = series.id).map { it.title })
        assertEquals(listOf("Chernobyl", "Breaking Bad"), shows(sort = "NAME_DESC", group = series.id).map { it.title })
        assertTrue(shows(group = "no-such-group").isEmpty())
    }
}
