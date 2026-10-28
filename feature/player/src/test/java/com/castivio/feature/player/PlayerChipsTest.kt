package com.castivio.feature.player

import com.castivio.core.common.FrameSize
import com.castivio.playback.api.Track
import com.castivio.playback.api.TrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words under the title say what the engine has reported and nothing it has not: five when it has
 * said everything, none when it has said nothing, and never a guess in between.
 */
class PlayerChipsTest {

    private fun state(
        size: FrameSize? = null,
        ratio: Float? = null,
        video: List<Track> = emptyList(),
        audio: List<Track> = emptyList(),
    ) = PlayerState(
        request = PlayerRequest(url = "u", title = "t", kind = com.castivio.playback.api.MediaKind.VOD),
        videoSize = size,
        videoAspectRatio = ratio,
        videoTracks = video,
        audioTracks = audio,
    )

    private fun video(label: String, selected: Boolean = true) =
        Track(id = "v$label", type = TrackType.VIDEO, label = label, selected = selected)

    private fun sound(language: String?, channels: Int?, selected: Boolean = true) =
        Track(id = "a$language$channels", type = TrackType.AUDIO, label = "x", language = language, channels = channels, selected = selected)

    @Test
    fun `a stream that has said everything has the five words, in the order of the reference`() {
        val chips = techChips(
            state(
                size = FrameSize(1920, 1080),
                ratio = 1.7778f,
                video = listOf(video("AVC")),
                audio = listOf(sound("en", 2)),
            ),
        )

        assertEquals(listOf("FHD", "H.264", "16:9", "2.0", "ENG"), chips.map { it.text })
        assertEquals("only the class of the picture is lit", listOf(true, false, false, false, false), chips.map { it.highlighted })
    }

    @Test
    fun `a stream that has said nothing has no words, rather than the last channel's`() {
        assertTrue(techChips(state()).isEmpty())
    }

    @Test
    fun `each word is left out when its own source is silent`() {
        val noSound = techChips(state(size = FrameSize(1280, 720), ratio = 1.7778f, video = listOf(video("HEVC"))))
        assertEquals(listOf("HD", "H.265", "16:9"), noSound.map { it.text })

        val soundOnly = techChips(state(audio = listOf(sound("fr", 6))))
        assertEquals(listOf("5.1", "FRA"), soundOnly.map { it.text })
    }

    @Test
    fun `the class of the picture is measured by the lines it has`() {
        assertEquals("SD", techChips(state(size = FrameSize(720, 576))).single().text)
        assertEquals("HD", techChips(state(size = FrameSize(1280, 720))).single().text)
        assertEquals("FHD", techChips(state(size = FrameSize(1920, 1080))).single().text)
        assertEquals("4K", techChips(state(size = FrameSize(3840, 2160))).single().text)
        assertEquals("a letterboxed full-HD picture is still full HD", "FHD", techChips(state(size = FrameSize(1920, 800))).single().text)
    }

    @Test
    fun `a video track that is not named for a codec is not printed as one`() {
        assertNull(codecWord(listOf(video("1080p"))))
        assertNull(codecWord(listOf(video("Main"))))
        assertNull(codecWord(emptyList()))
        assertEquals("H.264", codecWord(listOf(video("H264"))))
        assertEquals("H.264", codecWord(listOf(video("h.264"))))
        assertEquals("H.265", codecWord(listOf(video("HEVC"))))
        assertEquals("AV1", codecWord(listOf(video("AV1"))))
        assertEquals("MPEG-2", codecWord(listOf(video("MPEG2VIDEO"))))
    }

    @Test
    fun `the selected video track is the one named`() {
        val tracks = listOf(video("HEVC", selected = false), video("AVC", selected = true))
        assertEquals("H.264", codecWord(tracks))
    }

    @Test
    fun `a ratio near a known shape is that shape and any other is a figure`() {
        assertEquals("16:9", aspectWord(1.778f))
        assertEquals("16:9", aspectWord(1.76f))
        assertEquals("4:3", aspectWord(1.333f))
        assertEquals("21:9", aspectWord(2.333f))
        assertEquals("1:1", aspectWord(1.0f))
        assertEquals("2.39:1", aspectWord(2.39f))
        assertNull(aspectWord(null))
        assertNull(aspectWord(0f))
    }

    @Test
    fun `the channels are written as they are on a box`() {
        assertEquals("1.0", channelsWord(1))
        assertEquals("2.0", channelsWord(2))
        assertEquals("5.1", channelsWord(6))
        assertEquals("7.1", channelsWord(8))
        assertEquals("6.1", channelsWord(7))
        assertNull(channelsWord(0))
    }

    @Test
    fun `a language is three capital letters whatever tag it was declared with`() {
        assertEquals("ENG", languageWord("en"))
        assertEquals("ENG", languageWord("eng"))
        assertEquals("ENG", languageWord("en-US"))
        assertEquals("ARA", languageWord("ar"))
        assertEquals("FRA", languageWord("fr"))
        assertNull(languageWord("und"))
        assertNull(languageWord(""))
        assertNull(languageWord("zz-not-a-language-at-all"))
    }
}
