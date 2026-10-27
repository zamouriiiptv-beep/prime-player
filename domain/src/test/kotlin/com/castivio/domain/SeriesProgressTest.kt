package com.castivio.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where Play picks a show up, and what a row says about an episode, as rules.
 *
 * They are the whole of the decision: the page only asks them, so what "Continue S2 E5" means is held here and
 * not in a screen nobody can run without a device.
 */
class SeriesProgressTest {

    private fun episode(season: Int, number: Int) = Episode(
        id = "s${season}e$number",
        title = "Episode $number",
        artworkUrl = null,
        streamUrl = "http://example.invalid/series/$season/$number.mkv",
        seasonNumber = season,
        episodeNumber = number,
    )

    private val seasons = listOf(
        Season(1, listOf(episode(1, 1), episode(1, 2), episode(1, 3))),
        Season(2, listOf(episode(2, 1), episode(2, 2))),
    )

    private fun at(id: String, position: Long, duration: Long, updated: Long) =
        id to PlaybackProgress(id, positionMs = position, durationMs = duration, updatedAtEpochMs = updated)

    @Test
    fun `a show not begun starts at its first episode`() {
        val target = continueTarget(seasons, emptyMap())!!
        assertEquals("s1e1", target.episode.id)
        assertEquals(false, target.resume)
    }

    @Test
    fun `an episode begun and not finished is continued`() {
        val target = continueTarget(seasons, mapOf(at("s1e2", 600, 3_000, updated = 10)))!!
        assertEquals("s1e2", target.episode.id)
        assertEquals(true, target.resume)
    }

    @Test
    fun `a finished episode is followed by the next one, across a season`() {
        val within = continueTarget(seasons, mapOf(at("s1e1", 2_990, 3_000, updated = 10)))!!
        assertEquals("s1e2", within.episode.id)
        assertEquals(false, within.resume)

        val across = continueTarget(seasons, mapOf(at("s1e3", 3_000, 3_000, updated = 10)))!!
        assertEquals("s2e1", across.episode.id)
        assertEquals(false, across.resume)
    }

    @Test
    fun `the last episode finished is offered again, since nothing follows it`() {
        val target = continueTarget(seasons, mapOf(at("s2e2", 3_000, 3_000, updated = 10)))!!
        assertEquals("s2e2", target.episode.id)
        assertEquals(false, target.resume)
    }

    @Test
    fun `the episode touched last decides, not the furthest one`() {
        val target = continueTarget(
            seasons,
            mapOf(at("s2e1", 3_000, 3_000, updated = 5), at("s1e2", 900, 3_000, updated = 20)),
        )!!
        assertEquals("s1e2", target.episode.id)
        assertEquals(true, target.resume)
    }

    @Test
    fun `a show with no episodes has no target`() {
        assertNull(continueTarget(emptyList(), emptyMap()))
        assertNull(continueTarget(listOf(Season(1, emptyList())), emptyMap()))
    }

    @Test
    fun `an episode is marked by what was kept for it`() {
        assertEquals(EpisodeMark.None, episodeMark(null))
        assertEquals(EpisodeMark.None, episodeMark(PlaybackProgress("x", 0, 3_000, 1)))
        assertEquals(EpisodeMark.Watched, episodeMark(PlaybackProgress("x", 2_950, 3_000, 1)))
        val partly = episodeMark(PlaybackProgress("x", 1_500, 3_000, 1))
        assertTrue(partly is EpisodeMark.Partly)
        assertEquals(0.5f, (partly as EpisodeMark.Partly).percent, 0.001f)
    }
}
