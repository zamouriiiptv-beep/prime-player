package com.castivio.playback.media3

import com.castivio.playback.api.EngineId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EngineProfileTest {

    @Test
    fun `media3 ffmpeg decoder extension is not on the classpath after removal`() {
        assertFalse(
            "Media3Engine.isFfmpegAvailable should honestly report false without media3-decoder-ffmpeg.",
            Media3Engine.isFfmpegAvailable,
        )
    }

    @Test
    fun `primary engine constructs with hardware decoders`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val primary = Media3Engine(
            context = context,
            profile = EngineProfile.PRIMARY,
            tuning = com.castivio.playback.api.PlaybackTuning.VOD,
        )
        try {
            assertNotNull("the primary engine did construct", primary.state.value)
            assertEquals(EngineId.PRIMARY, EngineProfile.PRIMARY.id)
            assertEquals(null, primary.diagnosis.value)
        } finally {
            primary.release()
        }
    }
}