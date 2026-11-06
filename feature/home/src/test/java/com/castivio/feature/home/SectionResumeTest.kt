package com.castivio.feature.home

import com.castivio.domain.Channel
import com.castivio.domain.Episode
import com.castivio.domain.MediaKind
import com.castivio.domain.Movie
import com.castivio.domain.PlaybackProgress
import com.castivio.domain.WatchedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a small card on Home offers once its section has something watched, and which face the card shows
 * (`design/mockups/home-tiles-resume-ar.png`, `home-tiles-load-ar.png`).
 */
class SectionResumeTest {

    private fun movie(title: String = "Inception") = Movie(
        id = "m1",
        title = title,
        artworkUrl = "http://cdn/poster.jpg",
        streamUrl = "http://provider.invalid/movie/1.mp4",
        durationMinutes = 148,
    )

    private fun episode(season: Int = 2, number: Int = 5) = Episode(
        id = "e1",
        title = "Pilot",
        artworkUrl = "http://cdn/still.jpg",
        streamUrl = "http://provider.invalid/series/1.mkv",
        seasonNumber = season,
        episodeNumber = number,
    )

    private fun station() = Channel(
        id = "r1",
        title = "Radio Monte Carlo",
        artworkUrl = "http://cdn/mc.png",
        number = null,
        groupId = null,
        streamUrl = "http://provider.invalid/live/r1.ts",
        epgChannelId = null,
    )

    private fun progress(positionMs: Long, durationMs: Long?) =
        PlaybackProgress(mediaId = "x", positionMs = positionMs, durationMs = durationMs, updatedAtEpochMs = 1L)

    // ------------------------------------------------------------------------------------------ the offer

    @Test
    fun `nothing watched offers nothing`() {
        for (kind in listOf(MediaKind.MOVIE, MediaKind.SERIES, MediaKind.RADIO)) assertNull(sectionResumeOf(kind, null))
    }

    @Test
    fun `a film part-watched is offered with its name, its poster and how far in it the viewer was`() {
        val offer = sectionResumeOf(MediaKind.MOVIE, WatchedItem(movie(), progress(3_000, 6_000)))!!

        assertEquals("Inception", offer.title)
        assertEquals("http://cdn/poster.jpg", offer.artworkUrl)
        assertEquals(0.5f, offer.progress!!, 0.001f)
        assertNull(offer.detail)
        assertTrue(offer.open is ResumeOpen.Film)
    }

    @Test
    fun `a film only started has no bar`() {
        assertNull(sectionResumeOf(MediaKind.MOVIE, WatchedItem(movie(), progress = null))!!.progress)
    }

    @Test
    fun `a film with a position and no length has no bar`() {
        assertNull(sectionResumeOf(MediaKind.MOVIE, WatchedItem(movie(), progress(3_000, null)))!!.progress)
    }

    @Test
    fun `a finished film is still the last one watched, with no bar at its end`() {
        val offer = sectionResumeOf(MediaKind.MOVIE, WatchedItem(movie(), progress(5_900, 6_000)))!!

        assertEquals("Inception", offer.title)
        assertNull(offer.progress)
    }

    @Test
    fun `an episode is offered under its show's name, with which episode it was`() {
        val offer = sectionResumeOf(
            MediaKind.SERIES,
            WatchedItem(episode(season = 2, number = 5), progress(1_000, 4_000), showId = "s9", showTitle = "Dark"),
        )!!

        assertEquals("Dark", offer.title)
        assertEquals("S02E05", offer.detail)
        assertEquals(0.25f, offer.progress!!, 0.001f)
        val open = offer.open as ResumeOpen.Show
        assertEquals("s9", open.show.seriesId)
        assertEquals("Dark", open.show.title)
    }

    @Test
    fun `an episode whose row carries no show is not offered, for there is no page to open`() {
        assertNull(sectionResumeOf(MediaKind.SERIES, WatchedItem(episode(), progress(1_000, 4_000), showId = null, showTitle = "Dark")))
        assertNull(sectionResumeOf(MediaKind.SERIES, WatchedItem(episode(), progress(1_000, 4_000), showId = "s9", showTitle = null)))
        assertNull(sectionResumeOf(MediaKind.SERIES, WatchedItem(episode(), progress(1_000, 4_000), showId = "s9", showTitle = "  ")))
    }

    @Test
    fun `a station is offered with its logo and no bar`() {
        val offer = sectionResumeOf(MediaKind.RADIO, WatchedItem(station(), progress = null))!!

        assertEquals("Radio Monte Carlo", offer.title)
        assertEquals("http://cdn/mc.png", offer.artworkUrl)
        assertNull(offer.progress)
        assertTrue(offer.open is ResumeOpen.Station)
    }

    @Test
    fun `an item of another kind than the card's is not offered`() {
        assertNull(sectionResumeOf(MediaKind.MOVIE, WatchedItem(station(), null)))
        assertNull(sectionResumeOf(MediaKind.RADIO, WatchedItem(movie(), null)))
        assertNull(sectionResumeOf(MediaKind.SERIES, WatchedItem(movie(), null, showId = "s", showTitle = "Dark")))
    }

    @Test
    fun `the live channels have their own offer, not this one`() {
        assertNull(sectionResumeOf(MediaKind.LIVE, WatchedItem(station(), null)))
    }

    // ------------------------------------------------------------------------------------------ the face

    @Test
    fun `a card never fetched and not on its way invites the viewer to load it`() {
        assertEquals(HeroFace.NotLoaded, tileFace(unfetched = true, counted = false, load = CardLoad.Idle, resumed = false))
    }

    @Test
    fun `a card here with something watched offers to carry on`() {
        assertEquals(HeroFace.Continue, tileFace(unfetched = false, counted = true, load = CardLoad.Idle, resumed = true))
    }

    @Test
    fun `a card here with nothing watched is the card as it was`() {
        assertEquals(HeroFace.Plain, tileFace(unfetched = false, counted = true, load = CardLoad.Idle, resumed = false))
    }

    @Test
    fun `a card fetched and found empty is the card as it was`() {
        assertEquals(HeroFace.Plain, tileFace(unfetched = false, counted = false, load = CardLoad.Idle, resumed = true))
    }

    @Test
    fun `every state of a load keeps the card as it was`() {
        val loads = listOf(
            CardLoad.Waiting(),
            CardLoad.Arriving(items = 0, percent = null, refresh = false),
            CardLoad.Arrived(items = 10, seconds = 3),
            CardLoad.Failed(refresh = true),
        )
        for (load in loads) {
            assertEquals("$load", HeroFace.Plain, tileFace(unfetched = true, counted = false, load = load, resumed = true))
            assertEquals("$load", HeroFace.Plain, tileFace(unfetched = false, counted = true, load = load, resumed = true))
        }
    }
}
