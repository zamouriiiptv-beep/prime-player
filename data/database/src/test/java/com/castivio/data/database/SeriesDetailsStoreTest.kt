package com.castivio.data.database

import com.castivio.domain.SeriesDetails
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What a provider says of a show and of its episodes, kept in Room and read back as the page reads it.
 *
 * The details are keyed by the show's series id and the plots by the id of each episode's row: a plot is the
 * show's only if it names a row of that show, and one that names none is nobody's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SeriesDetailsStoreTest {

    private lateinit var database: CastivioDatabase
    private lateinit var store: RoomSeriesDetailsStore

    @Before
    fun open() {
        database = CastivioDatabase.inMemory(RuntimeEnvironment.getApplication())
        store = RoomSeriesDetailsStore(database.seriesDetailDao())
    }

    @After
    fun close() {
        database.close()
    }

    /** The rows the fixture holds that are episodes: each one's id, and the series id of its show. */
    private fun episodes(): List<Pair<String, String>> =
        database.openHelper.readableDatabase.query(
            "SELECT id, series_id FROM media WHERE series_id IS NOT NULL AND episode_number IS NOT NULL ORDER BY id",
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
        }

    private val said = SeriesDetails(
        rating = 8.0,
        plot = "An emotional thrill ride.",
        cast = listOf("Nick Gehlfuss", "Yaya DaCosta"),
        genres = listOf("Drama", "Mystery"),
        releaseYear = 2015,
        backdropUrl = "http://cdn/back1.jpg",
    )

    @Test
    fun `a show nothing was kept for has no details`() = runBlocking {
        assertNull(store.details("nobody").first())
        assertTrue(store.episodePlots("nobody").first().isEmpty())
    }

    @Test
    fun `the details kept are read back, lists and all`() = runBlocking {
        store.put("show", said, emptyMap(), now = 1L)

        assertEquals(said, store.details("show").first())
    }

    @Test
    fun `a detail the provider did not send comes back absent, not empty`() = runBlocking {
        store.put("show", SeriesDetails(plot = "Only a plot."), emptyMap(), now = 1L)

        val read = store.details("show").first()!!
        assertEquals("Only a plot.", read.plot)
        assertNull(read.rating)
        assertNull(read.releaseYear)
        assertNull(read.backdropUrl)
        assertTrue(read.cast.isEmpty())
        assertTrue(read.genres.isEmpty())
    }

    @Test
    fun `asking again takes the new reply, and a provider that now says nothing takes the old one back`() = runBlocking {
        store.put("show", said, emptyMap(), now = 1L)
        store.put("show", said.copy(rating = 9.0), emptyMap(), now = 2L)
        assertEquals(9.0, store.details("show").first()!!.rating!!, 0.0)

        store.put("show", null, emptyMap(), now = 3L)
        assertNull(store.details("show").first())
    }

    @Test
    fun `the plots of a show's episodes are those that name a row of that show`() = runBlocking {
        Fixtures.import(database, Fixtures.mixedPlaylist())
        val rows = episodes()
        val (firstEpisode, series) = rows.first()
        val other = rows.firstOrNull { it.second != series }

        store.put(
            series,
            said,
            mapOf(firstEpisode to "The pilot.", "no-such-row" to "Nobody's plot."),
            now = 1L,
        )

        val plots = store.episodePlots(series).first()
        assertEquals(mapOf(firstEpisode to "The pilot."), plots)
        // The other show, where the fixture has one, reads none of it.
        if (other != null) assertTrue(store.episodePlots(other.second).first().isEmpty())
    }
}
