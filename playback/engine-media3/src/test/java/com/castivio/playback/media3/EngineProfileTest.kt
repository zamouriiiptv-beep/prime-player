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
     * There is no bundled software decoder, so the extension renderer mode is a no-op.
     *
     * `DefaultRenderersFactory` finds extension renderers by `Class.forName` and silently
     * skips the ones that are not present — which means `EXTENSION_RENDERER_MODE_PREFER`
     * on the backup profile adds exactly nothing to this build. That is the fact the
     * previous documentation got wrong, and this is the assertion that keeps it honest.
     *
     * The names are Media3's own, from `DefaultRenderersFactory`. They are hard-coded here
     * for the same reason they are hard-coded there: reflection is how the optional
     * dependency is made optional.
     */
    @Test
    fun `no media3 decoder extension is on the classpath, so PREFER resolves nothing`() {
        val extensions = listOf(
            "androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer",
            "androidx.media3.decoder.av1.Libgav1VideoRenderer",
            "androidx.media3.decoder.vp9.LibvpxVideoRenderer",
            "androidx.media3.decoder.opus.LibopusAudioRenderer",
            "androidx.media3.decoder.flac.LibflacAudioRenderer",
        )
        val present = extensions.filter { name ->
            runCatching { Class.forName(name) }.isSuccess
        }

        assertTrue(
            "A decoder extension is now on the classpath: $present. The backup engine can " +
                "genuinely decode in software, which is a real capability change -- update " +
                "EngineId.BACKUP, FallbackPolicy.canBackupHelp and this test to say so.",
            present.isEmpty(),
        )
    }

    /**
     * Therefore: the backup cannot play a codec the device has no decoder for.
     *
     * Stated as a test rather than as prose because it is the question that was actually
     * asked, and because the answer follows mechanically from the assertion above. With no
     * bundled decoder, every decoder available to either engine comes from the platform,
     * and both engines ask the same platform.
     */
    @Test
    fun `both engines draw their decoders from the same platform list`() {
        val bundled = runCatching {
            Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer")
        }.isSuccess

        assertFalse(
            "With no bundled decoder, the backup engine's only advantage is trying the " +
                "next entry in the device's own decoder list. It cannot invent a decoder " +
                "the device does not have, and no card or comment may imply that it can.",
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
