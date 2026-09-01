package com.castivio.feature.player

import com.castivio.data.subtitles.SubtitleQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search box, which is the whole record of what is being looked for.
 *
 * There is no query held beside it. The text carries the name, the year and the episode, so
 * reading it back is lossless — and that is what makes an edit behave the way a person
 * expects: correcting `Pursuit 2026` to `Pursuit 2025` searches for 2025, rather than
 * quietly keeping the year the filename claimed because that is where the state really was.
 *
 * Plain JUnit. This is a value type; there is nothing here to emulate.
 */
class SubtitleSearchTest {

    /**
     * The listing found on a device: what the box shows, and what it asks for.
     *
     * `PURSUIT -- 2026 Jason Statham Full Action Movie` is a row written to be clicked. What
     * the viewer gets is a name and a year they can check — not the listing, and not a bare
     * `Pursuit` that *Cold Pursuit* would also have produced.
     */
    @Test
    fun `the box shows the name and the year`() {
        val search = searching("PURSUIT -- 2026 Jason Statham Full Action Movie")

        assertEquals("Pursuit 2026", search.query)
        assertEquals(SubtitleQuery("Pursuit", year = 2026), search.asked)
    }

    /** An episode's numbers go through the box in the form the box can be edited in. */
    @Test
    fun `an episode round-trips through the box`() {
        val derived = SubtitleQuery.of("The Office", "Season 5 · Episode 2")
        val search = SubtitleSearch(available = true, query = derived.text)

        assertEquals("The Office S05E02", search.query)
        assertEquals(derived, search.asked)
    }

    /**
     * A typed title replaces the derived one entirely, and is parsed in its own right.
     *
     * The escape hatch, and the reason the box is editable. What is typed is not merged with
     * what was guessed — a viewer who types a different film is not asking for that film *in
     * the year of this one*.
     */
    @Test
    fun `what is typed replaces what was derived`() {
        val search = searching("PURSUIT -- 2026 Jason Statham Full Action Movie")

        assertEquals(
            SubtitleQuery("Casablanca", year = 1942),
            search.copy(query = "Casablanca 1942").asked,
        )
    }

    /** Including a corrected year, which is the edit the box holds a year in order to allow. */
    @Test
    fun `a corrected year reaches the search`() {
        val search = searching("PURSUIT -- 2026 Jason Statham Full Action Movie")

        assertEquals(2025, search.copy(query = "Pursuit 2025").asked.year)
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

    /** An empty box is not a search, and neither is a build with no credentials. */
    @Test
    fun `there is nothing to ask without a name or a key`() {
        val search = searching("Pursuit")

        assertTrue(search.askable)
        assertFalse(search.copy(query = "").askable)
        assertFalse(search.copy(available = false).askable)
    }

    /** The state as the player builds it when a film is opened. */
    private fun searching(title: String) =
        SubtitleSearch(available = true, query = SubtitleQuery.of(title).text)
}
