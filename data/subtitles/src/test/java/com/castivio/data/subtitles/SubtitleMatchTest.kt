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
 * another programme; rejecting a right one tells them no subtitle exists when one does.
 *
 * The rule chosen is the strict one, deliberately: a name has to *be* the name. The escape
 * hatches are the editable search box and the hash exemption, and both are tested here.
 */
class SubtitleMatchTest {

    /* ---------------------------------------------- a word in the title is not the title */

    /**
     * The rule this filter exists for, in the two films that made it necessary.
     *
     * *Pursuit* is contained in *Cold Pursuit* and in *The Pursuit of Happyness*, so a filter
     * that accepted a title *containing* every word of the query accepted all three — which
     * is a keyword search wearing a filter's clothes. Identification means equality.
     */
    @Test
    fun `a film whose title merely contains the query is not this film`() {
        val watching = SubtitleQuery.parse("Pursuit 2026")

        assertFalse(SubtitleMatch.keep(watching, film("Cold Pursuit", 2019)))
        assertFalse(SubtitleMatch.keep(watching, film("The Pursuit of Happyness", 2006)))
        assertFalse(SubtitleMatch.keep(watching, film("Pursuit of the Graf Spee", 1956)))
    }

    /** And the film itself is kept, which is the other half of the same claim. */
    @Test
    fun `the film that was asked for is kept`() {
        val watching = SubtitleQuery.parse("Pursuit 2026")

        assertTrue(SubtitleMatch.keep(watching, film("Pursuit", 2026)))
        assertTrue("a year one out is the same film catalogued elsewhere", SubtitleMatch.keep(watching, film("Pursuit", 2025)))
        assertFalse("a different Pursuit entirely", SubtitleMatch.keep(watching, film("Pursuit", 2015)))
    }

    /**
     * Equality, but not letter by letter.
     *
     * Two catalogues can be expected to agree about a name and not about its punctuation or
     * its article. Both sides are reduced the same way, so *Spider-Man* meets *Spider Man*
     * and a provider's *Matrix 1999* meets a catalogue's *The Matrix* — and nothing else is
     * relaxed, because every further relaxation is a way for a different film to compare
     * equal.
     */
    @Test
    fun `punctuation and a leading article are not part of the name`() {
        assertTrue(SubtitleMatch.keep(SubtitleQuery.parse("Spider-Man 2002"), film("Spider Man", 2002)))
        assertTrue(SubtitleMatch.keep(SubtitleQuery.parse("Matrix 1999"), film("The Matrix", 1999)))
        assertTrue(SubtitleMatch.keep(SubtitleQuery.parse("The Matrix 1999"), film("Matrix", 1999)))
    }

    /** A sequel shares every word of its parent's name and is not it. */
    @Test
    fun `a sequel is not the film`() {
        val watching = SubtitleQuery.parse("The Matrix 1999")

        assertFalse(SubtitleMatch.keep(watching, film("The Matrix Resurrections", 2021)))
        assertFalse(SubtitleMatch.keep(watching, film("The Matrix Reloaded", 2003)))
    }

    /* ------------------------------------------------------------ a release name is not a title */

    /**
     * An uploader's release name is held to a prefix, because it is not a title.
     *
     * It is free text with the name at the front and the encoding behind it, so *Friends
     * complete pack* is plausibly *Friends*. *Cold Pursuit* puts another word first and is
     * not plausibly *Pursuit* — which is the same rejection as above, reached without the
     * catalogue's help.
     */
    @Test
    fun `a release name has to start with the name`() {
        val pursuit = SubtitleQuery.parse("Pursuit 2026")

        assertTrue(SubtitleMatch.keep(pursuit, named("Pursuit.2026.1080p.WEB-DL")))
        assertFalse(SubtitleMatch.keep(pursuit, named("Cold.Pursuit.2019.1080p")))
        assertTrue(SubtitleMatch.keep(SubtitleQuery.parse("Friends"), named("Friends complete pack")))
    }

    /* ----------------------------------------------------------------------- the year */

    /** A result that states no year is judged on its name, not rejected for silence. */
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

    /** And another series' episode is rejected on the name before the numbers are reached. */
    @Test
    fun `the same episode of another series is not this episode`() {
        val watching = SubtitleQuery.parse("Friends S05E02")

        assertFalse(SubtitleMatch.keep(watching, episode("The Office", 5, 2)))
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
        assertTrue(SubtitleMatch.keep(SubtitleQuery.parse("Friends"), episode("Friends", 5, 9)))
    }

    /**
     * When the catalogue states no numbers, the release name is read for them.
     *
     * Weaker evidence, used weakly: a name that states a different episode is rejected, and
     * a name that states nothing is allowed through — a subtitle filed under the right series
     * with no episode in its name is a plausible answer, and its name is visible in the row.
     */
    @Test
    fun `an undeclared episode is read from the release, and silence is allowed`() {
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
        assertFalse("the same pair without the hash", SubtitleMatch.keep(watching, film("The Matrix", 1999)))
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

    /** A title that is only a small word is still a title. */
    @Test
    fun `a title of small words still has to match`() {
        val watching = SubtitleQuery.parse("The One")

        assertTrue(SubtitleMatch.keep(watching, film("The One", 2001)))
        assertFalse(SubtitleMatch.keep(watching, film("The Two Towers", 2002)))
    }

    /* -------------------------------------------------------- resolving before searching */

    /**
     * The gate on the catalogue lookup is the same gate.
     *
     * `/features` is a search as well, and asked for "Pursuit" it offers *Cold Pursuit*
     * among the answers. Taking its first row on trust would be a keyword search with an
     * extra round trip in front of it.
     */
    @Test
    fun `only an exact catalogue entry identifies the work`() {
        val watching = SubtitleQuery.parse("Pursuit 2026")

        assertTrue(SubtitleMatch.sameWork(watching, "Pursuit", 2026))
        assertTrue("a catalogue that states no year", SubtitleMatch.sameWork(watching, "Pursuit", null))
        assertFalse(SubtitleMatch.sameWork(watching, "Cold Pursuit", 2019))
        assertFalse(SubtitleMatch.sameWork(watching, "Pursuit", 1998))
    }

    /**
     * Results asked for by identifier are not re-judged on their name.
     *
     * The identifier answered the question better than a comparison of strings can, so
     * checking the title again could only reject a right answer for being spelled
     * differently — a French catalogue title over a film asked for in English, say.
     *
     * The episode is still checked: a series' identifier covers every episode of it.
     */
    @Test
    fun `a search by identifier trusts the name and still checks the episode`() {
        val film = SubtitleQuery.parse("Pursuit 2026")
        assertEquals(1, SubtitleMatch.ofFeature(film, listOf(film("Poursuite", 2026))).size)

        val watching = SubtitleQuery.parse("Friends S05E02")
        assertEquals(0, SubtitleMatch.ofFeature(watching, listOf(episode("Friends", 5, 9))).size)
        assertEquals(1, SubtitleMatch.ofFeature(watching, listOf(episode("Friends", 5, 2))).size)
    }

    /* -------------------------------------------------------------------------- the order */

    /**
     * Best first: this file's own subtitle, then the right year, then the popular one.
     *
     * A viewer takes the top row, so the top row has to be the one that fits this file — and
     * popularity alone puts the most-downloaded subtitle for some other copy above the one
     * timed against these exact bytes.
     */
    @Test
    fun `results are ranked by what fits this file`() {
        val watching = SubtitleQuery.parse("Pursuit 2026")
        val offers = listOf(
            film("Pursuit", 2025).copy(fileId = 1, downloads = 900),
            film("Pursuit", 2026).copy(fileId = 2, downloads = 10),
            film("Pursuit", 2026).copy(fileId = 3, downloads = 5, matchesThisFile = true),
        )

        assertEquals(listOf(3L, 2L, 1L), SubtitleMatch.relevant(watching, offers).map { it.fileId })
    }

    /** What the ranking cannot separate keeps the order OpenSubtitles put it in. */
    @Test
    fun `results the ranking cannot separate are left as they arrived`() {
        val watching = SubtitleQuery.parse("Friends S05E02")
        val offers = listOf(
            episode("The Office", 5, 2),
            episode("Friends", 5, 2).copy(fileId = 7),
            episode("Friends", 5, 9),
            episode("Friends", 5, 2).copy(fileId = 8),
        )

        assertEquals(listOf(7L, 8L), SubtitleMatch.relevant(watching, offers).map { it.fileId })
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
