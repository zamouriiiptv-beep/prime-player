package com.castivio.feature.home

import com.castivio.core.common.AppError
import com.castivio.domain.Channel
import com.castivio.domain.Episode
import com.castivio.domain.MediaKind
import com.castivio.domain.Movie
import com.castivio.domain.Series
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules the browse screens are built on, proved without a device.
 *
 * All of them are the kind that only fail on somebody else's provider: an episode
 * nobody numbered, a show pressed where a film was expected, an error offered a retry
 * that cannot possibly work. None can be found by looking at a screen with a healthy
 * catalogue behind it, which is exactly why they are asserted here.
 */
class CatalogTest {

    // -------------------------------------------------------- three, and only three

    /**
     * Home is a choice between three things, and the flow depends on that being true.
     *
     * A fourth section would be a fourth thing to fetch and a fourth tile on a screen
     * whose whole job is to be an unambiguous first press on a remote.
     */
    @Test
    fun `there are three sections and each reads its own kind`() {
        assertEquals(3, CatalogSection.entries.size)
        assertEquals(MediaKind.LIVE, CatalogSection.Channels.kind)
        assertEquals(MediaKind.MOVIE, CatalogSection.Movies.kind)
        assertEquals(MediaKind.SERIES, CatalogSection.Series.kind)
    }

    /** No two sections read the same table, so opening one cannot load another. */
    @Test
    fun `no two sections read the same kind`() {
        val kinds = CatalogSection.entries.map { it.kind }
        assertEquals(kinds.size, kinds.toSet().size)
    }

    // ------------------------------------------------------------- episode numbering

    @Test
    fun `a fully numbered episode reads as season and episode`() {
        assertEquals("S01E04", episodeLabel(1, 4))
        assertEquals("S12E138", episodeLabel(12, 138))
    }

    /**
     * Zero means "the provider did not say", which the import writes rather than
     * dropping the row. It must never be shown as `S00`.
     */
    @Test
    fun `an unnumbered half is left out rather than shown as zero`() {
        assertEquals("E04", episodeLabel(0, 4))
        assertEquals("S02", episodeLabel(2, 0))
    }

    @Test
    fun `an episode numbered by neither gets no label at all`() {
        assertNull(episodeLabel(0, 0))
    }

    // ------------------------------------------------------------------- what plays

    @Test
    fun `a channel carries everything the player needs and nothing it would fetch`() {
        val channel = Channel(
            id = "c1",
            title = "Sky Documentaries",
            artworkUrl = "http://example.invalid/logo.png",
            number = 128,
            groupId = "docs",
            streamUrl = "http://example.invalid/live/u/p/128.ts",
            epgChannelId = "sky.docs",
            catchUpHours = 72,
        )

        val selection = channel.asSelection()

        requireNotNull(selection)
        assertEquals("http://example.invalid/live/u/p/128.ts", selection.url)
        assertEquals("Sky Documentaries", selection.title)
        assertTrue(selection.live)
        assertFalse(selection.episode)
        assertEquals("128", selection.channelNumber)
        assertEquals("sky.docs", selection.epgChannelId)
        assertEquals(72, selection.catchUpHours)
    }

    /**
     * Catch-up is null rather than zero when the provider does not offer it, so no
     * rewind control is drawn at all.
     */
    @Test
    fun `a channel without catch-up says so with a null rather than a zero`() {
        val channel = Channel(
            id = "c2",
            title = "News 24",
            artworkUrl = null,
            number = null,
            groupId = null,
            streamUrl = "http://example.invalid/live/u/p/9.ts",
            epgChannelId = null,
        )

        val selection = channel.asSelection()

        requireNotNull(selection)
        assertNull(selection.catchUpHours)
        assertNull(selection.channelNumber)
    }

    @Test
    fun `a film is neither live nor an episode`() {
        val movie = Movie(
            id = "m1",
            title = "Pursuit",
            artworkUrl = null,
            streamUrl = "http://example.invalid/movie/u/p/77.mp4",
            year = 2022,
        )

        val selection = movie.asSelection()

        requireNotNull(selection)
        assertFalse(selection.live)
        assertFalse("a film must not be opened as an episode", selection.episode)
        assertEquals("2022", selection.subtitle)
    }

    /**
     * An episode says it is one, which is what the engine reads to know what "next"
     * means. A season that behaves like a single film is the bug this prevents.
     */
    @Test
    fun `an episode is marked as one and carries its numbering`() {
        val episode = Episode(
            id = "e1",
            title = "The Bells",
            artworkUrl = null,
            streamUrl = "http://example.invalid/series/u/p/3.mp4",
            seasonNumber = 8,
            episodeNumber = 5,
        )

        val selection = episode.asSelection()

        requireNotNull(selection)
        assertTrue(selection.episode)
        assertFalse(selection.live)
        assertEquals("S08E05", selection.subtitle)
    }

    /**
     * A show is not a stream, and this is where that stops being a convention.
     *
     * Null is the only answer that cannot be handed to the engine by accident. A
     * `Series` has no `streamUrl` to give, so anything returning a request here would
     * have had to invent one — which is exactly what "play the series" would be.
     */
    @Test
    fun `a show is not playable`() {
        val show = Series(id = "s1", title = "Chernobyl", artworkUrl = null)

        assertNull(show.asSelection())
    }

    // --------------------------------------------------------------- what to offer

    /**
     * Retry is offered only where pressing it could plausibly work.
     *
     * Offering it on a refused subscription wastes the one move the user has and
     * teaches them the button means nothing.
     */
    @Test
    fun `only the transient failures offer a retry`() {
        val offered = AppError.entries.filter { it.retryable }.toSet()

        assertEquals(
            setOf(
                AppError.NETWORK_UNAVAILABLE,
                AppError.TIMEOUT,
                AppError.SERVER_ERROR,
                AppError.UNKNOWN,
            ),
            offered,
        )
    }

    @Test
    fun `every error has an answer`() {
        // Exhaustive by construction: a new AppError makes `retryable` fail to compile
        // rather than silently defaulting. This asserts none was given a `when` branch
        // that throws instead.
        AppError.entries.forEach { it.retryable }
    }
}
