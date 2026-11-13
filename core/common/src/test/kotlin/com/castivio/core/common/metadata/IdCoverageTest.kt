package com.castivio.core.common.metadata

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The count that says whether a provider sends the ids a translated page would be asked for by. */
class IdCoverageTest {

    @Test
    fun `nothing is said before a reply has been read`() {
        assertNull(IdCoverageTally().figures().describe())
    }

    @Test
    fun `films are counted by the id each reply carried`() {
        val tally = IdCoverageTally()

        tally.film(tmdb = true, imdb = false)
        tally.film(tmdb = true, imdb = true)
        tally.film(tmdb = false, imdb = false)

        val figures = tally.figures()
        assertEquals(3, figures.films)
        assertEquals(2, figures.filmsWithTmdb)
        assertEquals(1, figures.filmsWithImdb)
        assertEquals("ids: films tmdb 2/3 · imdb 1/3", figures.describe())
    }

    @Test
    fun `shows are counted apart from films`() {
        val tally = IdCoverageTally()

        tally.film(tmdb = true, imdb = false)
        tally.show(tmdb = false, imdb = false)
        tally.show(tmdb = true, imdb = false)

        assertEquals("ids: films tmdb 1/1 · imdb 0/1 · shows tmdb 1/2 · imdb 0/2", tally.figures().describe())
    }

    @Test
    fun `a kind that was never read is left out of the line`() {
        val tally = IdCoverageTally()

        tally.show(tmdb = true, imdb = true)

        assertEquals("ids: shows tmdb 1/1 · imdb 1/1", tally.figures().describe())
    }

    @Test
    fun `a reply with no ids is a reply, so the fraction has the right denominator`() {
        val tally = IdCoverageTally()

        repeat(20) { tally.film(tmdb = false, imdb = false) }

        assertEquals("ids: films tmdb 0/20 · imdb 0/20", tally.figures().describe())
    }

    @Test
    fun `reset starts the count again`() {
        val tally = IdCoverageTally()
        tally.film(tmdb = true, imdb = true)
        tally.show(tmdb = true, imdb = true)

        tally.reset()

        assertNull(tally.figures().describe())
        assertEquals(IdCoverageTally().figures(), tally.figures())
    }

    @Test
    fun `digits are Western whatever the locale`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            val tally = IdCoverageTally()
            repeat(12) { tally.film(tmdb = true, imdb = false) }

            val line = tally.figures().describe()!!

            assertEquals("ids: films tmdb 12/12 · imdb 0/12", line)
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun `the application's own tally is a tally like any other`() {
        IdCoverage.reset()
        IdCoverage.film(tmdb = true, imdb = false)

        assertEquals("ids: films tmdb 1/1 · imdb 0/1", IdCoverage.figures().describe())

        IdCoverage.reset()
    }
}
