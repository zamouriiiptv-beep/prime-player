package com.castivio.data.parsing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What counts as an id, proved on the shapes panels send.
 *
 * An id kept wrongly names another film, so the rule is that a value is an id only if it is exactly one; the tests below are the
 * values that are not, and each is a thing a panel has been seen to send in a field of this kind.
 */
class ExternalIdsTest {

    @Test
    fun `a TMDB id is a whole number above zero in digits`() {
        assertEquals(603L, ExternalIds.tmdb("603"))
        assertEquals(1396L, ExternalIds.tmdb(" 1396 "))
        assertEquals(1_234_567L, ExternalIds.tmdb("1234567"))
    }

    @Test
    fun `nought, nothing and a blank are no id, which is what a panel sends for a title it could not match`() {
        for (raw in listOf(null, "", " ", "0", "00", "000000")) {
            assertNull("'$raw' is not an id", ExternalIds.tmdb(raw))
        }
    }

    @Test
    fun `a number with a point, a sign or letters is no id, so a rating written in the field is not taken for one`() {
        for (raw in listOf("8.0", "7.5", "603.0", "-5", "+603", "6e2", "abc", "603a", "tt0133093", "NaN", "1,396")) {
            assertNull("'$raw' is not an id", ExternalIds.tmdb(raw))
        }
    }

    @Test
    fun `an address is no id, and neither is a number too long to be one`() {
        assertNull(ExternalIds.tmdb("https://www.themoviedb.org/movie/603"))
        assertNull(ExternalIds.tmdb("1234567890123"))
        assertNull(ExternalIds.tmdb("99999999999999999999"))
    }

    @Test
    fun `an IMDb id is tt and digits, in lower case whatever was sent`() {
        assertEquals("tt0133093", ExternalIds.imdb("tt0133093"))
        assertEquals("tt0133093", ExternalIds.imdb("TT0133093"))
        assertEquals("tt0903747", ExternalIds.imdb("  tt0903747  "))
        assertEquals("tt12345678", ExternalIds.imdb("tt12345678"))
    }

    @Test
    fun `digits alone are no IMDb id, because the field may as well hold a rating or a count of votes`() {
        for (raw in listOf("0133093", "8.7", "133093", "")) {
            assertNull("'$raw' is not an IMDb id", ExternalIds.imdb(raw))
        }
    }

    @Test
    fun `an IMDb id with too few or too many digits, or with something else in it, is none`() {
        for (raw in listOf(null, "tt", "tt1", "tt1234", "tt12345678901", "tt01330x3", "tt-123456", "nm0000206", "https://www.imdb.com/title/tt0133093/")) {
            assertNull("'$raw' is not an IMDb id", ExternalIds.imdb(raw))
        }
    }
}
