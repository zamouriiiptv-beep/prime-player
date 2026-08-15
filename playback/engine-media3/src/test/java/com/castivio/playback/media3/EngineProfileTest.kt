package com.castivio.playback.media3

import com.castivio.playback.api.EngineId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the backup engine actually is, asserted against the real Media3 on the real
 * classpath.
 *
 * ## Why this file exists
 *
 * A file failed on a device, the user pressed the backup, and it failed identically. The
 * question that followed was the right one: *is the backup a different decoder path at
 * all?* The answer was written in a comment — "software decoders" — and the comment was
 * wrong. A claim about what a build can do belongs in a test, because a comment cannot be
 * falsified by adding a dependency and a test can.
 *
 * These assertions are deliberately about **this build's classpath**, not about Media3 in
 * the abstract. The day somebody adds `media3-decoder-ffmpeg`, the first test here fails
 * and whoever added it has to come and change the sentence that is no longer true.
 */
@RunWith(RobolectricTestRunner::class)
class EngineProfileTest {

    /**
     * `media3-decoder-ffmpeg` is on the classpath, so `EXTENSION_RENDERER_MODE_PREFER`
     * resolves `FfmpegAudioRenderer` for software audio decoding.
     */
    @Test
    fun `media3 ffmpeg decoder extension is on the classpath and available`() {
        val present = runCatching {
            Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer")
        }.isSuccess

        assertTrue(
            "Media3 FFmpeg audio decoder extension must be on the classpath for the backup engine " +
                "to provide genuine software audio decoding fallback.",
            present,
        )
        assertTrue(
            "Media3Engine.isFfmpegAvailable should be true when the extension is packaged.",
            Media3Engine.isFfmpegAvailable,
        )
    }

    /**
     * The backup engine has access to FFmpeg software audio decoders.
     */
    @Test
    fun `the backup engine has access to software decoding via ffmpeg extension`() {
        val bundled = runCatching {
            Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer")
        }.isSuccess

        assertTrue(
            "The backup engine can decode audio in software via bundled media3-decoder-ffmpeg, " +
                "providing a real software-decoding fallback for formats the hardware cannot handle.",
            bundled,
        )
    }

    /**
     * The two profiles are two distinct configurations, and each builds a real player.
     *
     * A real `Media3Engine` on each side — not a fake — because the thing worth checking is
     * that both configurations actually construct. A backup profile that threw during
     * `ExoPlayer.Builder.build()` would present on a device as "the fallback also failed",
     * which is indistinguishable from the codec problem we were chasing and is exactly the
     * confusion this whole pass is unpicking.
     */
    @Test
    fun `both profiles construct a working engine and report their own identity`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()

        val primary = Media3Engine(context, EngineProfile.PRIMARY, com.castivio.playback.api.PlaybackTuning.VOD)
        val backup = Media3Engine(context, EngineProfile.BACKUP, com.castivio.playback.api.PlaybackTuning.VOD)

        try {
            assertNotNull("the primary engine did not construct", primary.state.value)
            assertNotNull("the backup engine did not construct", backup.state.value)

            // Distinct identities, which is what the badge, the memory and every log line
            // key off. Two engines that both called themselves PRIMARY would make the
            // remembered-engine feature silently useless.
            assertEquals(EngineId.PRIMARY, EngineProfile.PRIMARY.id)
            assertEquals(EngineId.BACKUP, EngineProfile.BACKUP.id)

            // No diagnosis before anything has failed. The report is evidence of a failure,
            // not a field that is always populated.
            assertEquals(null, primary.diagnosis.value)
            assertEquals(null, backup.diagnosis.value)
        } finally {
            primary.release()
            backup.release()
        }
    }

    /**
     * The profiles are not the same object, and the enum cannot silently collapse.
     *
     * Trivial to the point of looking unnecessary, and it is here because the failure it
     * guards was real in spirit: the backup was *behaving* as a copy of the primary, and
     * nothing in the codebase asserted that the two were different in any way at all.
     */
    @Test
    fun `the two profiles are distinct`() {
        assertTrue(EngineProfile.PRIMARY != EngineProfile.BACKUP)
        assertTrue(EngineProfile.PRIMARY.id != EngineProfile.BACKUP.id)
        assertEquals(2, EngineProfile.entries.size)
    }
}
