package com.castivio.data.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a result is about the thing being watched.
 *
 * The filter has two ways to fail and they are not symmetric. Letting a wrong result through
 * wastes one download out of a metered daily allowance and shows the viewer subtitles for
 * another programme; rejecting a right one tells them no subtitle exists when one does, and
 * there is nothing they can do about that from the sheet. So the tests below come in pairs:
 * every rule that rejects something has one beside it proving what it still lets through.
 */
class SubtitleMatchTest {

    /* ------------------------------------------------------------- the wrong programme */

    /**
     * The defect this was written for.
     *
     * A search for `502` — the last segment of an IPTV address — returned episode 502 of
     * every series that has one. Each row was a real subtitle correctly matching the query,
     * and none of them was the film.
     */
    @Test
    fun `a subtitle for another series is not about this film`() {
        val watching = SubtitleQuery.parse("The Matrix 1999")

        assertFalse(SubtitleMatch.keep(watching, episode("Friends", 5, 2)))
        assertFalse(SubtitleMatch.keep(watching, episode("The Office", 5, 2)))
    }

    @Test
    fun `a subtitle for this film is about this film`() {
        val watching = SubtitleQuery.parse("The Matrix 1999")

        assertTrue(SubtitleMatch.keep(watching, film("The Matrix", 1999)))
    }

    /**
     * A sequel is not the film, and the name alone cannot tell.
     *
     * "The Matrix Resurrections" contains every significant word of "The Matrix", so the
     * word check passes it. The year is what separates them, and it is the only thing that
     * can.
     */
    @Test
    fun `a sequel twenty years later is not this film`() {
        val watching = SubtitleQuery.parse("The Matrix 1999")

        assertFalse(SubtitleMatch.keep(watching, film("The Matrix Resurrections", 2021)))
    }

    /** A year that is one out is the same film catalogued by somebody else. */
    @Test
    fun `a year that is one out is still this film`() {
        val watching = SubtitleQuery.parse("The Road 2009")

        assertTrue(SubtitleMatch.keep(watching, film("The Road", 2010)))
    }

    /** And a result that states no year is judged on its name, not rejected for silence. */
    @Test
    fun `a result with no year is judged on its name`() {
        val watching = SubtitleQuery.parse("The Road 2009")

        assertTrue(SubtitleMatch.keep(watching, film("The Road", year = null)))
    }

    /* ------------------------------------------------------------------ the wrong episode */

    @Test
    fun `another episode of the right series is not this episode`() {
        val watching = SubtitleQuery.parse("Friends S05E02")

        assertFalse(SubtitleMatch.keep(watching, episode("Friends", 5, 9)))
        assertFalse(SubtitleMatch.keep(watching, episode("Friends", 4, 2)))
        assertTrue(SubtitleMatch.keep(watching, episode("Friends", 5, 2)))
    }

    /**
     * Searching a series without naming an episode accepts every episode of it.
     *
     * Someone who types "Friends" is looking for whatever there is, and a filter that
     * demanded an episode number they did not give would answer an open question with
     * nothing.
     */
    @Test
    fun `a search with no episode accepts any episode of the series`() {
        val watching = SubtitleQuery.parse("Friends")

        assertTrue(SubtitleMatch.keep(watching, episode("Friends", 5, 9)))
    }

    /**
     * When the catalogue states no numbers, the result's own name is read for them.
     *
     * Weaker evidence, used weakly: a name that states a different episode is rejected, and
     * a name that states nothing is allowed through — a subtitle filed under the right series
     * with no episode in its name is a plausible answer, and its name is visible in the row.
     */
    @Test
    fun `an undeclared episode is read from the name, and silence is allowed`() {
        val watching = SubtitleQuery.parse("Friends S05E02")

        assertFalse(SubtitleMatch.keep(watching, named("Friends.S05E09.DVDRip")))
        assertTrue(SubtitleMatch.keep(watching, named("Friends.S05E02.DVDRip")))
        assertTrue(SubtitleMatch.keep(watching, named("Friends complete pack")))
    }

    /* --------------------------------------------------------------------- the hash wins */

    /**
     * A hash match is never filtered, whatever anything is called.
     *
     * The escape hatch for the case no amount of text handling solves: a film held under an
     * Arabic name and catalogued under an English one. The hash is evidence about these
     * exact bytes; a title is evidence about somebody's spelling.
     */
    @Test
    fun `a hash match is kept whatever it is catalogued as`() {
        val watching = SubtitleQuery.parse("المصفوفة")

        assertTrue(SubtitleMatch.keep(watching, film("The Matrix", 1999).copy(matchesThisFile = true)))
    }

    /** Without the hash, that same pair is exactly what the filter is supposed to reject. */
    @Test
    fun `the same pair without a hash is rejected`() {
        val watching = SubtitleQuery.parse("المصفوفة")

        assertFalse(SubtitleMatch.keep(watching, film("The Matrix", 1999)))
    }

    /* ------------------------------------------------------------- nothing to judge with */

    /**
     * An empty query keeps everything.
     *
     * There is no such thing as an irrelevant answer to a question nobody asked, and the
     * sheet does not run a search on an empty box anyway. Failing open here means the filter
     * can never be the reason a viewer sees nothing.
     */
    @Test
    fun `an empty query filters nothing`() {
        assertTrue(SubtitleMatch.keep(SubtitleQuery.parse(""), film("Anything", 2001)))
    }

    /**
     * A title made only of articles is matched on the articles.
     *
     * "The One" is a series. Dropping "the" as noise leaves "one"; dropping both would leave
     * nothing to match on and accept every result on the site.
     */
    @Test
    fun `a title of small words still has to match`() {
        val watching = SubtitleQuery.parse("The One")

        assertTrue(SubtitleMatch.keep(watching, film("The One", 2001)))
        assertFalse(SubtitleMatch.keep(watching, film("The Two Towers", 2002)))
    }

    /** Whole words only: `ar` must not be found inside `Arrival`. */
    @Test
    fun `a word is matched whole`() {
        assertFalse(SubtitleMatch.keep(SubtitleQuery.parse("Her"), film("Sherlock", 2010)))
    }

    /* ------------------------------------------------------------------------- the list */

    /** The list keeps its order: the filter removes, it does not re-rank. */
    @Test
    fun `filtering keeps what is left in the order it arrived`() {
        val watching = SubtitleQuery.parse("Friends S05E02")
        val offers = listOf(
            episode("The Office", 5, 2),
            episode("Friends", 5, 2).copy(fileId = 7),
            episode("Friends", 5, 9),
            episode("Friends", 5, 2).copy(fileId = 8),
        )

        val kept = SubtitleMatch.relevant(watching, offers)

        assertEquals(listOf(7L, 8L), kept.map { it.fileId })
    }

    /* ----------------------------------------------------------------------- the fixtures */

    private fun film(title: String, year: Int?) = SubtitleOffer(
        fileId = 1,
        language = "en",
        name = "subtitle.srt",
        downloads = 10,
        matchesThisFile = false,
        featureTitle = title,
        year = year,
    )

    private fun episode(series: String, season: Int, episode: Int) = SubtitleOffer(
        fileId = 2,
        language = "en",
        name = "subtitle.srt",
        downloads = 10,
        matchesThisFile = false,
        featureTitle = "An Episode",
        parentTitle = series,
        season = season,
        episode = episode,
    )

    /** A result the catalogue said nothing about, which is a great many of the older ones. */
    private fun named(release: String) = SubtitleOffer(
        fileId = 3,
        language = "en",
        name = "subtitle.srt",
        downloads = 10,
        matchesThisFile = false,
        release = release,
    )
}
