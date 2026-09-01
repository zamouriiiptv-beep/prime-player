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

        assertEquals("The Matrix", query.title)
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
        assertEquals("Spider-Man", SubtitleQuery.parse("Spider-Man.2002.720p.WEB-DL").title)
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

    /* ------------------------------------------------------------------- a shop listing */

    /**
     * The case found on a device, and the reason a year is treated as a boundary.
     *
     * A provider's row is written to be clicked, not to be searched with: the name, then the
     * year, then the star, then what kind of film it is. Sent whole it matched nothing at
     * all, and the player said "no subtitles available" for a film that has them.
     *
     * Note what is *not* here: any knowledge of who Jason Statham is. The year ends the name,
     * and everything unbounded that follows a year goes with it unnamed — which is the only
     * way this can work, because the list of actors is not a list anybody can write down.
     */
    @Test
    fun `a shop listing is reduced to the name of the film`() {
        val query = SubtitleQuery.parse("PURSUIT -- 2026 Jason Statham Full Action Movie")

        assertEquals("Pursuit", query.title)
        assertEquals(2026, query.year)
    }

    /** And without a year, the sales phrase ends it instead. */
    @Test
    fun `a sales phrase ends the name`() {
        assertEquals("Pursuit", SubtitleQuery.parse("PURSUIT - Full Action Movie").title)
        assertEquals("Pursuit", SubtitleQuery.parse("Pursuit Official Trailer HD").title)
    }

    /**
     * A phrase and not a word, because the words are in real titles.
     *
     * `movie` alone would take the end off *The Lego Movie* and `full` alone off *Full Metal
     * Jacket*. It is only the combination that means somebody is selling something.
     */
    @Test
    fun `a title that contains a sales word is not cut`() {
        assertEquals("The Lego Movie", SubtitleQuery.parse("The Lego Movie").title)
        assertEquals("Full Metal Jacket", SubtitleQuery.parse("Full Metal Jacket 1987 1080p").title)
    }

    /**
     * A number at the end of a name is a name, not a date.
     *
     * *Blade Runner 2049* was read as *Blade Runner* released in 2049 — which truncated the
     * title *and* then rejected every subtitle for the film, whose catalogue year is 2017.
     * No file that exists is dated that far ahead, so a number that far ahead is not a date.
     */
    @Test
    fun `a title that ends in a number keeps it`() {
        val query = SubtitleQuery.parse("Blade Runner 2049")

        assertEquals("Blade Runner 2049", query.title)
        assertNull(query.year)
    }

    /** A shouted listing is a listing, not a spelling. */
    @Test
    fun `a title in capitals is written normally`() {
        assertEquals("Pursuit", SubtitleQuery.parse("PURSUIT").title)
        assertEquals("The LEGO Movie", SubtitleQuery.parse("The LEGO Movie").title)
        assertEquals("4K", SubtitleQuery.parse("4K").title)
    }

    /**
     * An Arabic playlist row: the shelf in front, the sales words behind.
     *
     * `مسلسل` is "series" and `كامل` is "complete" — the first says what kind of row this is
     * and the last says what you are getting. Neither is the name, and rows in this shape are
     * most of an Arabic playlist.
     */
    @Test
    fun `an arabic listing keeps only the name`() {
        val query = SubtitleQuery.parse("مسلسل الاختيار الموسم 2 الحلقة 5 كامل")

        assertEquals("الاختيار", query.title)
        assertEquals(2, query.season)
        assertEquals(5, query.episode)
    }

    /* ------------------------------------------------------------------------ the ladder */

    /**
     * What is tried, in order, before "no subtitles available" is said.
     *
     * Each rung drops one assumption. The year goes first because it is the likeliest to
     * disagree between a provider and a catalogue; the subtitle goes last because a work
     * listed under its short name is rarer than one dated differently.
     */
    @Test
    fun `the ladder drops one assumption at a time`() {
        val query = SubtitleQuery.parse("Blade Runner: The Final Cut 2007")

        assertEquals(
            listOf("Blade Runner: The Final Cut" to 2007, "Blade Runner: The Final Cut" to null, "Blade Runner" to null),
            query.attempts().map { it.title to it.year },
        )
    }

    /** With nothing to drop there is one rung, so the common case is still one request. */
    @Test
    fun `a plain name is asked for once`() {
        assertEquals(1, SubtitleQuery.parse("Pursuit").attempts().size)
        assertEquals(2, SubtitleQuery.parse("The.Matrix.1999.mkv").attempts().size)
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

    /**
     * A film's displayed text is its name *and its year*.
     *
     * The year was left out of the box once, on the grounds that a box should hold a name
     * and not a parameter. That was wrong, and the reason is what the box is for: a person
     * reading `Pursuit` cannot tell whether the player has understood which film is playing,
     * because *Cold Pursuit* and *The Pursuit of Happyness* would produce the same word.
     * `Pursuit 2026` can be read and checked.
     *
     * It also makes the text a complete record, so parsing it back loses nothing and there
     * is no second copy of the query kept beside the box for the parts it could not hold.
     */
    @Test
    fun `a film shows its name and its year`() {
        assertEquals("The Matrix 1999", SubtitleQuery.parse("The.Matrix.1999.BluRay.mkv").text)
        assertEquals("Pursuit 2026", SubtitleQuery.parse("PURSUIT -- 2026 Jason Statham Full Action Movie").text)
    }

    /** And everything in the text comes back out of it, year and episode alike. */
    @Test
    fun `every part of the query survives the box`() {
        listOf(
            "PURSUIT -- 2026 Jason Statham Full Action Movie",
            "The.Matrix.1999.1080p.BluRay.mkv",
            "Friends.S05E02.720p.HDTV.mkv",
            "Blade Runner 2049",
        ).forEach { name ->
            val original = SubtitleQuery.parse(name)
            assertEquals(name, original, SubtitleQuery.parse(original.text))
        }
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
