package com.castivio.feature.player

import com.castivio.playback.api.Track
import com.castivio.playback.api.TrackType
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a settings row says a sound or a subtitle is called: pure, so no screen is asked.
 *
 * The container's own label when it says more than the language code, the language written out in the
 * viewer's language when it does not, and nothing invented when it says neither.
 */
class PlayerSettingsTest {

    private fun sound(label: String, language: String? = null, channels: Int? = null, selected: Boolean = false) =
        Track(id = "a:$label", type = TrackType.AUDIO, label = label, language = language, channels = channels, selected = selected)

    @Test
    fun `a label the container wrote is kept as written`() {
        assertEquals("Director's commentary", soundName(sound("Director's commentary", "en"), Locale.ENGLISH))
    }

    @Test
    fun `a label that is only the language code is written out in the viewer's language`() {
        assertEquals("English", soundName(sound("en", "en"), Locale.ENGLISH))
        assertEquals("anglais", soundName(sound("EN", "en"), Locale.FRENCH).lowercase(Locale.FRENCH))
    }

    @Test
    fun `a track that declares no language is named by its label`() {
        assertEquals("Track 2", soundName(sound("Track 2"), Locale.ENGLISH))
        assertEquals("Track 2", soundName(sound("Track 2", "und"), Locale.ENGLISH))
    }

    @Test
    fun `a track with nothing to say is named by nothing`() {
        assertEquals("", soundName(sound("", null), Locale.ENGLISH))
    }

    @Test
    fun `the row names the selected sound and not its layout`() {
        val tracks = listOf(
            sound("French", "fr", channels = 2),
            sound("English", "en", channels = 6, selected = true),
        )

        assertEquals("English", soundValue(tracks, Locale.ENGLISH))
    }

    @Test
    fun `without a selection the row names the first sound, and without sounds it names none`() {
        assertEquals("French", soundValue(listOf(sound("French", "fr"), sound("English", "en")), Locale.ENGLISH))
        assertNull(soundValue(emptyList(), Locale.ENGLISH))
        assertNull(soundValue(listOf(sound("", null)), Locale.ENGLISH))
    }

    @Test
    fun `the clock and the speed are in western digits whatever the language`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ar"))
            assertEquals("2:17:27", clock(8_247_000))
            assertEquals("43:05", clock(2_585_000))
            assertEquals("1.5x", speedLabel(1.5f))
        } finally {
            Locale.setDefault(before)
        }
    }
}
