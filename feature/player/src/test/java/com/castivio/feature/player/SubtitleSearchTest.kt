package com.castivio.feature.player

import com.castivio.data.subtitles.SubtitleQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search box, and the one rule that decides what a press of it actually asks for.
 *
 * The box holds a name, because a name is what a person reads to decide whether the guess is
 * right. The year and the episode worked out from the title are not written in it — so
 * parsing the box back would quietly lose them, and with the year goes the check that tells
 * *The Matrix* from *The Matrix Resurrections*.
 *
 * Hence [SubtitleSearch.asked]: an untouched box searches with everything that was derived,
 * an edited one with what was typed. It is three lines and it is the seam where a subtitle
 * search silently starts asking a smaller question than it knows how to ask.
 *
 * Plain JUnit. This is a value type; there is nothing here to emulate.
 */
class SubtitleSearchTest {

    /**
     * The listing found on a device: what the box shows, and what it asks for.
     *
     * `PURSUIT -- 2026 Jason Statham Full Action Movie` is a row written to be clicked. The
     * viewer sees the name; the request still carries the year that was read out of it.
     */
    @Test
    fun `the box shows a name and asks with everything`() {
        val search = searching("PURSUIT -- 2026 Jason Statham Full Action Movie")

        assertEquals("Pursuit", search.query)
        assertEquals(SubtitleQuery("Pursuit", year = 2026), search.asked)
    }

    /** Spacing is not an edit. A box nobody touched is a box nobody touched. */
    @Test
    fun `whitespace around an untouched box changes nothing`() {
        val search = searching("The.Matrix.1999.1080p.BluRay.mkv")

        assertEquals(search.derived, search.copy(query = "  ${search.query}  ").asked)
    }

    /**
     * A typed title replaces the derived one entirely, and is parsed in its own right.
     *
     * The escape hatch. What is typed is not merged with what was guessed — a viewer who
     * types a different film is not asking for that film *in the year of this one*.
     */
    @Test
    fun `what is typed replaces what was derived`() {
        val search = searching("PURSUIT -- 2026 Jason Statham Full Action Movie")

        assertEquals(
            SubtitleQuery("Casablanca", year = 1942),
            search.copy(query = "Casablanca 1942").asked,
        )
    }

    /** And a typed episode marker is read as numbers, exactly as a title's would be. */
    @Test
    fun `a typed episode is read as numbers`() {
        val search = searching("Pursuit")

        assertEquals(
            SubtitleQuery("Friends", season = 5, episode = 2),
            search.copy(query = "Friends S05E02").asked,
        )
    }

    /** An episode's numbers go through the box in the form the box can be edited in. */
    @Test
    fun `an episode round-trips through the box`() {
        val derived = SubtitleQuery.of("The Office", "Season 5 · Episode 2")
        val search = SubtitleSearch(available = true, derived = derived, query = derived.text)

        assertEquals("The Office S05E02", search.query)
        assertEquals(derived, search.asked)
    }

    /** An empty box is not a search, and neither is a build with no credentials. */
    @Test
    fun `there is nothing to ask without a name or a key`() {
        val search = searching("Pursuit")

        assertTrue(search.askable)
        assertFalse(search.copy(query = "").askable)
        assertFalse(search.copy(available = false).askable)
    }

    /** The state as the player builds it when a film is opened. */
    private fun searching(title: String): SubtitleSearch {
        val derived = SubtitleQuery.of(title)
        return SubtitleSearch(available = true, derived = derived, query = derived.text)
    }
}
