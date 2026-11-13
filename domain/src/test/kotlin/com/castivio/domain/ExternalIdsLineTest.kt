package com.castivio.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** The line a debuggable build prints of a title's ids. */
class ExternalIdsLineTest {

    @Test
    fun `both ids are said, TMDB first`() {
        assertEquals("tmdb 603 · imdb tt0133093", externalIdsLine(603L, "tt0133093"))
    }

    @Test
    fun `one id alone is said alone`() {
        assertEquals("tmdb 1396", externalIdsLine(1396L, null))
        assertEquals("imdb tt0903747", externalIdsLine(null, "tt0903747"))
    }

    @Test
    fun `no id is said as none, so a title without one is told from a title that was not read`() {
        assertEquals("no ids", externalIdsLine(null, null))
    }

    @Test
    fun `details kept before the ids were read say so, and not that the provider has none`() {
        assertEquals("ids not read yet", externalIdsLine(null, null, read = false))
        assertEquals("ids not read yet", externalIdsLine(603L, "tt0133093", read = false))
    }

    @Test
    fun `a film's details carry the ids as the provider gave them, and by default none, read`() {
        assertEquals(null, MovieDetails().tmdbId)
        assertEquals(null, SeriesDetails().imdbId)
        assertEquals(true, MovieDetails().idsRead)
        assertEquals(MovieDetails(plot = "A story."), MovieDetails(plot = "A story.", tmdbId = null, imdbId = null))
        assertEquals(603L, MovieDetails(tmdbId = 603L).tmdbId)
    }

    @Test
    fun `details that were not read for ids are not the empty details, so they are kept and drawn like any others`() {
        assertEquals(false, MovieDetails(plot = "Kept before.", idsRead = false).idsRead)
        assertEquals(false, MovieDetails(idsRead = false) == MovieDetails())
    }
}
