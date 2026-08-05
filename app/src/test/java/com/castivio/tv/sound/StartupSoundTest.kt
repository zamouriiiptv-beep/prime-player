package com.castivio.tv.sound

import android.media.AudioManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * When the startup sound is allowed to make a noise.
 *
 * The rules are short and every one of them is a way to annoy somebody, so they
 * are pinned here rather than left to whoever next reads the method. Testing the
 * decision separately from `SoundPool` is the only way the silent-mode rule gets
 * tested at all: the alternative is remembering to put a phone on vibrate before
 * a release, which is not a test.
 *
 * Robolectric only for the `AudioManager` constants, which are read from the
 * platform rather than restated here -- a test that hard-coded 2 for "normal"
 * would pass a build in which the meaning of 2 had changed.
 */
@RunWith(RobolectricTestRunner::class)
class StartupSoundTest {

    @Test
    fun `a cold start with sound on plays`() {
        assertTrue(
            "a normal cold start is the one case this sound exists for",
            StartupSound.shouldPlay(
                restored = false,
                ringerMode = AudioManager.RINGER_MODE_NORMAL,
                mediaVolume = 7,
            ),
        )
    }

    @Test
    fun `silent mode is silent`() {
        assertFalse(
            "a phone on silent must not chime; media playback does not follow " +
                "the ringer, so nothing else would have stopped it",
            StartupSound.shouldPlay(
                restored = false,
                ringerMode = AudioManager.RINGER_MODE_SILENT,
                mediaVolume = 7,
            ),
        )
    }

    @Test
    fun `so is vibrate`() {
        assertFalse(
            "vibrate means the user asked for no sound just as plainly as silent does",
            StartupSound.shouldPlay(
                restored = false,
                ringerMode = AudioManager.RINGER_MODE_VIBRATE,
                mediaVolume = 7,
            ),
        )
    }

    @Test
    fun `the media volume being down is respected too`() {
        assertFalse(
            "a device with the media stream at zero would play this inaudibly and " +
                "still spend the decode",
            StartupSound.shouldPlay(
                restored = false,
                ringerMode = AudioManager.RINGER_MODE_NORMAL,
                mediaVolume = 0,
            ),
        )
    }

    @Test
    fun `a restore is not a start`() {
        assertFalse(
            "coming back to a session the user never left is not a launch, and " +
                "chiming on it is how somebody ends up turning the sound off",
            StartupSound.shouldPlay(
                restored = true,
                ringerMode = AudioManager.RINGER_MODE_NORMAL,
                mediaVolume = 7,
            ),
        )
    }
}
