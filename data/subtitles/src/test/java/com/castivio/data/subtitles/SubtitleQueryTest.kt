package com.castivio.data.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What is searched for, derived from what a provider happened to call the file.
 *
 * Plain JUnit and no Robolectric: this is text arithmetic with no Android in it, which is
 * exactly why it was put in its own type. The rules below are the ones that decide whether
 * the subtitle search works at all, and every one of them is a claim about a string.
 */
class SubtitleQueryTest {

    /* ------------------------------------------------------------------ the release name */

    /**
     * A release name is reduced to the name of the film.
     *
     * The single most valuable rule here. `The.Matrix.1999.1080p.BluRay.x264-GROUP` returns
     * nothing from OpenSubtitles, because no catalogue anywhere lists a film under its
     * encoder settings — so a player that sent the file name verbatim had a subtitle search
     * that failed on the commonest kind of file it would ever be pointed at.
     */
    @Test
    fun `a release name becomes the name of the film`() {
        val query = SubtitleQuery.parse("The.Matrix.1999.1080p.BluRay.x264-GROUP.mkv")

        assertEquals("The Matrix 1999", query.title)
        assertEquals(1999, query.year)
        assertNull(query.season)
    }

    /**
     * The extension goes, and only if it is one.
     *
     * `substringBeforeLast('.')` was the obvious way to do this and is wrong twice over: it
     * takes the end off `S.W.A.T` and it takes `Odyssey` off `2001. A Space Odyssey`.
     */
    @Test
    fun `a name that is full of dots keeps its last word`() {
        assertEquals("S W A T", SubtitleQuery.parse("S.W.A.T.mkv").title)
        assertEquals("2001 A Space Odyssey", SubtitleQuery.parse("2001. A Space Odyssey").title)
    }

    /** A hyphen is part of a name and is not a separator. */
    @Test
    fun `a hyphenated name survives`() {
        assertEquals("Spider-Man 2002", SubtitleQuery.parse("Spider-Man.2002.720p.WEB-DL").title)
    }

    /**
     * A name that is nothing but release words is left alone rather than emptied.
     *
     * The cut takes everything from the first marker onwards, so a title that *starts* with
     * one would cut to nothing — and a query of nothing finds nothing, which is a worse
     * outcome than a query with noise in it. `Heat` is a film; so, awkwardly, is `4K`.
     */
    @Test
    fun `a name is never cut down to nothing`() {
        assertEquals("4K", SubtitleQuery.parse("4K").title)
    }

    /* ---------------------------------------------------------------------- the episode */

    @Test
    fun `the usual episode marker is read and removed`() {
        val query = SubtitleQuery.parse("Friends.S05E02.720p.HDTV.x264.mkv")

        assertEquals("Friends", query.title)
        assertEquals(5, query.season)
        assertEquals(2, query.episode)
    }

    /** The other written forms, which providers use interchangeably. */
    @Test
    fun `the other forms of the marker are read too`() {
        listOf(
            "The Office 5x02",
            "The Office Season 5 Episode 2",
            "The Office s5 e2",
            "The Office الموسم 5 الحلقة 2",
        ).forEach { written ->
            val query = SubtitleQuery.parse(written)
            assertEquals(written, "The Office", query.title)
            assertEquals(written, 5, query.season)
            assertEquals(written, 2, query.episode)
        }
    }

    /**
     * A resolution is not an episode.
     *
     * `1920x1080` is the reason the `1x02` form is guarded on both sides. Without the guards
     * it reads season 20 episode 108 out of the middle of a resolution and then removes the
     * digits it took, leaving a name nobody can search for.
     */
    @Test
    fun `a resolution is not mistaken for an episode`() {
        val query = SubtitleQuery.parse("Interstellar 1920x1080")

        assertNull(query.season)
        assertNull(query.episode)
    }

    /* ------------------------------------------------------------ the request, and back */

    /**
     * The numbers are taken from the second line when the first has none.
     *
     * A library row splits them — "The Office" in the title, "Season 5 · Episode 2" beneath
     * it — and a file name does not. Both have to arrive at the same query.
     */
    @Test
    fun `an episode named on the second line is still an episode`() {
        val query = SubtitleQuery.of(title = "The Office", subtitle = "Season 5 · Episode 2")

        assertEquals("The Office", query.title)
        assertEquals(5, query.season)
        assertEquals(2, query.episode)
    }

    /** And a second line that is not about an episode contributes nothing. */
    @Test
    fun `a second line that names no episode is ignored`() {
        val query = SubtitleQuery.of(title = "The Road", subtitle = "Drama · 2009")

        assertEquals("The Road", query.title)
        assertNull(query.season)
        assertNull(query.episode)
    }

    /**
     * What the box shows is what the box can be typed back in as.
     *
     * The field is editable, so its contents have to survive a round trip: a viewer who
     * corrects the season must not have the episode disappear because the form printed and
     * the form parsed were different forms.
     */
    @Test
    fun `the displayed text parses back to the same query`() {
        val original = SubtitleQuery.parse("Friends.S05E02.1080p.WEB-DL.mkv")

        val round = SubtitleQuery.parse(original.text)

        assertEquals("Friends S05E02", original.text)
        assertEquals(original, round)
    }

    /** A film's displayed text is just its name. */
    @Test
    fun `a film shows its name and its year`() {
        assertEquals("The Matrix 1999", SubtitleQuery.parse("The.Matrix.1999.BluRay.mkv").text)
    }

    /* -------------------------------------------------------------- nothing to search for */

    /**
     * The URL that started all this, parsed as what it is: nothing.
     *
     * The player used to search with the last segment of the stream address. This test does
     * not assert that the parser rescues `502` — nothing could — but that a bare number is
     * not mistaken for a name, so a query built from one is visibly empty rather than
     * confidently wrong.
     */
    @Test
    fun `a bare stream number is not a name`() {
        assertEquals("502", SubtitleQuery.parse("502").title)
        assertEquals(true, SubtitleQuery.parse("   ").isBlank)
    }
}
