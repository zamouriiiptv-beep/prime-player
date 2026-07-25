package com.castivio.data.parsing

import com.castivio.domain.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Classification is the difference between a usable app and films appearing in
 * the live guide, so every rule the classifier claims to implement is pinned
 * here — including the false positives it must *not* produce.
 */
class MediaClassifierTest {

    // ------------------------------------------------------------------- live

    @Test
    fun `a plain live entry stays live`() {
        val result = classify(name = "Nova Sports 1 HD", group = "Sports", duration = -1)
        assertEquals(MediaKind.LIVE, result.kind)
        assertNull(result.seriesTitle)
        assertNull(result.seasonNumber)
    }

    @Test
    fun `an xtream live path is authoritative`() {
        // Even with a VOD-sounding group, /live/ means the provider said live.
        val result = classify(
            name = "Cinema Channel",
            group = "MOVIES",
            url = "http://host:8080/live/user/pass/1234.ts",
        )
        assertEquals(MediaKind.LIVE, result.kind)
    }

    // ------------------------------------------------------------------ movie

    @Test
    fun `a positive duration means vod`() {
        val result = classify(name = "The Long Return", group = null, duration = 7_200)
        assertEquals(MediaKind.MOVIE, result.kind)
    }

    @Test
    fun `an xtream movie path is a movie`() {
        val result = classify(name = "Dune", url = "http://host/movie/user/pass/99.mp4")
        assertEquals(MediaKind.MOVIE, result.kind)
    }

    @Test
    fun `movie group titles are recognised in english and arabic`() {
        for (group in listOf("VOD", "Movies EN", "Films FR", "Cinema 4K", "أفلام عربية", "افلام هندية")) {
            assertEquals("group '$group' should be a movie", MediaKind.MOVIE, classify(group = group).kind)
        }
    }

    // ----------------------------------------------------------------- series

    @Test
    fun `SxxExx splits the show from the episode`() {
        val result = classify(name = "Breaking Bad S01E05 - Gray Matter", group = "Series")
        assertEquals(MediaKind.SERIES, result.kind)
        assertEquals("Breaking Bad", result.seriesTitle)
        assertEquals(1, result.seasonNumber)
        assertEquals(5, result.episodeNumber)
        assertEquals("Gray Matter", result.episodeTitle)
    }

    @Test
    fun `the common marker spellings all parse`() {
        val forms = mapOf(
            "Show S1E2" to (1 to 2),
            "Show S01 E02" to (1 to 2),
            "Show S01.E02" to (1 to 2),
            "Show S01 Ep05" to (1 to 5),
            "Show 2x07" to (2 to 7),
            "Show Season 3 Episode 12" to (3 to 12),
            "Show Season 3 E12" to (3 to 12),
        )
        for ((name, expected) in forms) {
            val result = classify(name = name)
            assertEquals("$name kind", MediaKind.SERIES, result.kind)
            assertEquals("$name season", expected.first, result.seasonNumber)
            assertEquals("$name episode", expected.second, result.episodeNumber)
            assertEquals("$name show", "Show", result.seriesTitle)
        }
    }

    @Test
    fun `an episode with no title of its own keeps the full name`() {
        val result = classify(name = "Breaking Bad S01E05")
        assertEquals("Breaking Bad", result.seriesTitle)
        assertNull(result.episodeTitle)
    }

    @Test
    fun `a series path without a marker is still a series`() {
        val result = classify(name = "Chernobyl", url = "http://host/series/user/pass/551.mkv")
        assertEquals(MediaKind.SERIES, result.kind)
        assertEquals("Chernobyl", result.seriesTitle)
        assertNull(result.seasonNumber)
    }

    @Test
    fun `series group titles are recognised in english and arabic`() {
        for (group in listOf("Series EN", "TV Shows", "مسلسلات تركية")) {
            val result = classify(name = "Some Show", group = group)
            assertEquals("group '$group' should be a series", MediaKind.SERIES, result.kind)
        }
    }

    /**
     * The regression that matters most here. `MBC 4x4` and `24x7 News` are real
     * channel names; reading them as episodes would empty the live list and fill
     * the Series screen with channels.
     */
    @Test
    fun `channel names that look like markers are not series`() {
        val notSeries = listOf(
            "MBC 4x4",
            "Sport 2x2",
            "24x7 News",
            "Sky Sports 1",
            "Season Channel",
            "SEPT HD",
            "S Channel",
        )
        for (name in notSeries) {
            assertEquals("'$name' must stay live", MediaKind.LIVE, classify(name = name).kind)
        }
    }

    // ------------------------------------------------------------------ radio

    @Test
    fun `radio is detected from the group in english and arabic`() {
        for (group in listOf("Radio", "RADIO FM", "راديو المغرب", "إذاعة القرآن")) {
            assertEquals("group '$group' should be radio", MediaKind.RADIO, classify(group = group).kind)
        }
    }

    @Test
    fun `radio is detected from an audio stream url`() {
        for (url in listOf(
            "http://host/stream.mp3",
            "http://host/stream.aac?token=abc",
            "http://host:8000/live.ogg",
            "http://host/x.flac",
        )) {
            assertEquals("url '$url' should be radio", MediaKind.RADIO, classify(url = url).kind)
        }
    }

    @Test
    fun `radio wins over a live path`() {
        // Stations are served from /live/ like everything else, so the path check
        // alone would file every station as a TV channel.
        val result = classify(
            name = "Radio Mars",
            group = "Radio MA",
            url = "http://host:8080/live/user/pass/777.ts",
        )
        assertEquals(MediaKind.RADIO, result.kind)
    }

    @Test
    fun `radio is matched as a word, not a substring`() {
        // "Radiohead" is a band; its concert films are not radio stations.
        val film = classify(name = "Radiohead In Rainbows Live", group = "Radiohead Concerts", duration = 5_400)
        assertEquals(MediaKind.MOVIE, film.kind)
        // A plural still counts as radio.
        assertEquals(MediaKind.RADIO, classify(group = "Radios MA").kind)
    }

    @Test
    fun `a video stream is not radio just because of a dot in the path`() {
        val result = classify(url = "http://host/live/u/p/12.34.ts")
        assertEquals(MediaKind.LIVE, result.kind)
    }

    private fun classify(
        name: String = "Entry",
        group: String? = null,
        url: String = "http://host/path/1.ts",
        duration: Int = -1,
    ) = MediaClassifier.classify(
        M3uEntry(name = name, url = url, groupTitle = group, durationSeconds = duration),
    )
}
