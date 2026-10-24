package com.castivio.feature.home

import com.castivio.domain.Movie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the films screen says about a film, and what it leaves unsaid: a running time in the reference's
 * form, the facts of a film in reading order, and which list the sidebar, heading and filter agree they
 * are in.
 */
class MovieFactsTest {

    private fun film(year: Int? = null, minutes: Int? = null) = Movie(
        id = "m1",
        title = "Film",
        artworkUrl = null,
        streamUrl = "http://example.invalid/movie/u/p/1.mp4",
        year = year,
        durationMinutes = minutes,
    )

    // ------------------------------------------------------------------------- the running time

    @Test
    fun `a running time reads as hours and minutes`() {
        assertEquals("2h 46m", movieDuration(166))
        assertEquals("1h 36m", movieDuration(96))
        assertEquals("1h 1m", movieDuration(61))
    }

    @Test
    fun `under an hour it is minutes alone, and whole hours are hours alone`() {
        assertEquals("46m", movieDuration(46))
        assertEquals("1m", movieDuration(1))
        assertEquals("3h", movieDuration(180))
        assertEquals("1h", movieDuration(60))
    }

    @Test
    fun `no running time is no text, not a zero`() {
        assertNull(movieDuration(null))
        assertNull(movieDuration(0))
        assertNull(movieDuration(-5))
    }

    // ------------------------------------------------------------------------------- the facts

    @Test
    fun `the facts are the year and then the running time, as far as the provider sent them`() {
        assertEquals(listOf("2024", "2h 46m"), movieFacts(film(year = 2024, minutes = 166)))
        assertEquals(listOf("2024"), movieFacts(film(year = 2024)))
        assertEquals(listOf("1h 36m"), movieFacts(film(minutes = 96)))
    }

    @Test
    fun `a film the provider told nothing about has no facts`() {
        assertEquals(emptyList<String>(), movieFacts(film()))
        assertEquals(emptyList<String>(), movieFacts(film(year = 0, minutes = 0)))
    }

    // ---------------------------------------------------------------------------- which list it is

    @Test
    fun `with nothing chosen it is all the films`() {
        assertEquals(MovieList.All, BrowseState().movieList())
    }

    @Test
    fun `a chosen category is that category`() {
        assertEquals(MovieList.Category("action"), BrowseState(selectedGroup = "action").movieList())
    }

    @Test
    fun `the favourites are the favourites, whatever category was open before them`() {
        assertEquals(MovieList.Favorites, BrowseState(favorites = true).movieList())
        assertEquals(MovieList.Favorites, BrowseState(favorites = true, selectedGroup = "action").movieList())
    }

    // ----------------------------------------------------------------------- the figure by the heading

    @Test
    fun `the heading carries the scope's count`() {
        assertEquals(16_432, BrowseState(total = 16_432).headingCount())
        assertEquals(842, BrowseState(total = 842, selectedGroup = "recent").headingCount())
    }

    @Test
    fun `a search or the favourites has no count that is true, so the heading carries none`() {
        assertNull(BrowseState(total = 16_432, search = "dune").headingCount())
        assertNull(BrowseState(total = 16_432, favorites = true).headingCount())
    }
}
