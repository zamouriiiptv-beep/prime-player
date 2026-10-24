package com.castivio.feature.home

import androidx.compose.ui.unit.dp
import com.castivio.domain.Movie
import com.castivio.domain.MovieDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the films screen says about a film, and what it leaves unsaid: a running time in the reference's
 * form, the facts of a film in reading order, and which list the rail, the band and the wall agree they
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

    @Test
    fun `the films played last are their own list, over any category`() {
        assertEquals(MovieList.Recent, BrowseState(recent = true).movieList())
        assertEquals(MovieList.Recent, BrowseState(recent = true, selectedGroup = "action").movieList())
    }

    // ----------------------------------------------------------------------- the figure by the heading

    @Test
    fun `the heading carries the scope's count`() {
        assertEquals(16_432, BrowseState(total = 16_432).headingCount())
        assertEquals(842, BrowseState(total = 842, selectedGroup = "recent").headingCount())
    }

    @Test
    fun `a search, the favourites or the films played last have no count that is true, so none is drawn`() {
        assertNull(BrowseState(total = 16_432, search = "dune").headingCount())
        assertNull(BrowseState(total = 16_432, favorites = true).headingCount())
        assertNull(BrowseState(total = 16_432, recent = true).headingCount())
    }

    // ------------------------------------------------------------------------- the poster's height

    @Test
    fun `two whole rows and the top of the third show, the sliver being a small share of a poster`() {
        val available = 500.dp
        val rowExtra = 20.dp
        val rowGap = 10.dp

        val poster = posterHeightFor(available, rowExtra, rowGap, peek = 0.075f, floor = 80.dp)

        // The third row starts after two rows of poster, extra and gap; what is left of the wall is the sliver.
        val pitch = poster + rowExtra + rowGap
        val sliver = available - pitch * 2
        assertTrue(sliver.value >= poster.value * 0.07f && sliver.value <= poster.value * 0.08f)
    }

    @Test
    fun `a surface too short for the rule still draws a poster at the floor`() {
        assertEquals(80.dp, posterHeightFor(available = 60.dp, rowExtra = 20.dp, rowGap = 10.dp, peek = 0.075f, floor = 80.dp))
    }

    // ------------------------------------------------------------------------ what the details add

    @Test
    fun `details fill only what the list row lacks, and never replace it`() {
        val row = film(year = 2024)
        val details = MovieDetails(releaseYear = 1999, durationSeconds = 5_400, genres = listOf("Drama"))

        val shown = row.withDetails(details)

        assertEquals(2024, shown.year)
        assertEquals(90, shown.durationMinutes)
        assertEquals(listOf("Drama"), shown.genres)
    }

    @Test
    fun `no details leaves the film as the row gave it`() {
        val row = film(year = 2024, minutes = 100)
        assertEquals(row, row.withDetails(null))
    }

    @Test
    fun `a rating reads as one decimal, as the provider's figure is shown`() {
        assertEquals("8.1", ratingText(8.1))
        assertEquals("7.0", ratingText(7.0))
    }

    // ------------------------------------------------------------------------------- the wall's columns

    @Test
    fun `as many posters fit across as the width has room for, with a gutter between each pair`() {
        // (800 + 10) / (100 + 10) = 7.36 -> 7
        assertEquals(7, posterColumns(available = 800.dp, gutter = 10.dp, minimum = 100.dp))
        // Exactly three of 100 and two gutters of 10: (320 + 10) / 110 = 3
        assertEquals(3, posterColumns(available = 320.dp, gutter = 10.dp, minimum = 100.dp))
    }

    @Test
    fun `a pane too narrow for two posters still draws two, which is where a wall stops reading as a list`() {
        assertEquals(2, posterColumns(available = 90.dp, gutter = 10.dp, minimum = 100.dp))
        assertEquals(2, posterColumns(available = 0.dp, gutter = 10.dp, minimum = 100.dp))
    }
}
