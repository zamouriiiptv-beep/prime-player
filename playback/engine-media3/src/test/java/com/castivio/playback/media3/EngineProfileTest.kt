package com.castivio.playback.media3

import com.castivio.core.platform.ConservativeCapabilities
import com.castivio.playback.api.EngineId
import com.castivio.playback.api.MediaKind
import com.castivio.playback.api.PlaybackTuning
import com.castivio.playback.vlc.VlcPlaybackEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What the two engines actually are, asserted rather than described in a comment.
 *
 * This file exists because a comment in this module once claimed the backup engine did
 * software decoding, and it did not: the backup was a second Media3 profile whose
 * `EXTENSION_RENDERER_MODE_PREFER` resolved to nothing, because no `media3-decoder-*`
 * artifact was ever on the classpath. A real MP4 failed on both engines and the claim was
 * what sent the investigation to the wrong place.
 *
 * So every statement this codebase makes about engine capability is checked here against
 * the classpath and the object graph, not against prose.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EngineProfileTest {

    /**
     * The claim that started it: Media3 ships no bundled decoder in this build.
     *
     * `DefaultRenderersFactory` resolves extension renderers by `Class.forName` and
     * silently skips the ones it cannot find, so a missing artifact is not an error — it is
     * a renderer mode that quietly does nothing. That silence is precisely why this is a
     * test and not an assumption.
     */
    @Test
    fun `no media3 decoder extension is on the classpath`() {
        assertFalse(
            "Media3Engine.isFfmpegAvailable must honestly report false without media3-decoder-ffmpeg",
            Media3Engine.isFfmpegAvailable,
        )
    }

    @Test
    fun `the primary engine constructs on the platform decoders`() {
        val primary = Media3Engine(
            context = RuntimeEnvironment.getApplication(),
            profile = EngineProfile.PRIMARY,
            tuning = PlaybackTuning.VOD,
        )
        try {
            assertNotNull("the primary engine did construct", primary.state.value)
            assertEquals(EngineId.PRIMARY, EngineProfile.PRIMARY.id)
            assertNull("nothing has failed, so there is nothing to diagnose", primary.diagnosis.value)
        } finally {
            primary.release()
        }
    }

    /** Both Media3 profiles still build, and they are not the same identity. */
    @Test
    fun `the two media3 profiles are distinct and both construct`() {
        assertEquals(EngineId.PRIMARY, EngineProfile.PRIMARY.id)
        assertEquals(EngineId.BACKUP, EngineProfile.BACKUP.id)
        assertTrue(
            "the profiles must not collapse onto one engine id",
            EngineProfile.PRIMARY.id != EngineProfile.BACKUP.id,
        )

        val backup = Media3Engine(
            context = RuntimeEnvironment.getApplication(),
            profile = EngineProfile.BACKUP,
            tuning = PlaybackTuning.VOD,
        )
        try {
            assertNotNull("the backup profile did construct", backup.state.value)
        } finally {
            backup.release()
        }
    }

    /**
     * The fallback is a **different engine**, not a reconfigured ExoPlayer.
     *
     * This is the assertion the whole investigation was missing. While both engines were
     * Media3, "try the backup player" rebuilt the same decoder stack with one flag moved,
     * and a file the device had no decoder for failed identically both times — which is
     * exactly what happened on a real device. Now `BACKUP` resolves to LibVLC, which
     * brings its own codecs.
     *
     * Asserted on the concrete type because that is the only thing that makes the promise
     * true: a factory that returned a `Media3Engine` for `BACKUP` would satisfy the
     * `PlaybackEngine` interface perfectly and offer the user a button that cannot help.
     *
     * The engines are only constructed here, never opened. Both hold their native handles
     * behind `lazy`, so no decoder and no LibVLC native library is loaded by this test.
     */
    @Test
    fun `the factory builds two genuinely different engines`() {
        val factory = Media3EngineFactory(
            context = RuntimeEnvironment.getApplication(),
            capabilities = ConservativeCapabilities,
        )

        val primary = factory.create(EngineId.PRIMARY, MediaKind.VOD)
        try {
            assertTrue(
                "the primary must be Media3 on the platform decoders, but was ${primary.javaClass.name}",
                primary is Media3Engine,
            )
        } finally {
            primary.release()
        }

        val backup = factory.create(EngineId.BACKUP, MediaKind.VOD)
        assertTrue(
            "the backup must be a different engine entirely, but was ${backup.javaClass.name}",
            backup is VlcPlaybackEngine,
        )
        assertFalse(
            "a backup that is another Media3 offers a button that cannot help",
            backup is Media3Engine,
        )
    }

    /**
     * The statistics panel reports the picture, not the file's idea of it.
     *
     * A phone records portrait by writing a landscape frame plus a rotation in the
     * container. The format keeps the coded size and `onVideoSizeChanged` reports the size
     * with the rotation applied, so a video the panel described as 720×1280 was drawn
     * 1280×720 on every device that played it. The panel is read by someone looking at the
     * picture, so the displayed size wins and the format is the fallback.
     */
    @Test
    fun `the reported resolution is the one on screen`() {
        assertEquals(
            "the coded size was preferred over the size actually drawn",
            1280,
            Media3Engine.shownSize(displayed = 1280, coded = 720),
        )
        assertEquals(
            "a container that reported no video size must still show its format's",
            720,
            Media3Engine.shownSize(displayed = null, coded = 720),
        )
        assertNull(
            "a source with no picture must report no resolution rather than a zero",
            Media3Engine.shownSize(displayed = 0, coded = 0),
        )
        assertNull(Media3Engine.shownSize(displayed = null, coded = null))
    }
}
