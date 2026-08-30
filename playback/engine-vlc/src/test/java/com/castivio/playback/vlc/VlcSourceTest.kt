package com.castivio.playback.vlc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which URLs LibVLC cannot open by itself.
 *
 * ## The failure this file exists for
 *
 * Every local song and video is a `content://` URL — that is what `MediaStore` hands out —
 * and LibVLC resolves a URL through its own access modules, which cover `file`, `http`,
 * `rtsp` and the rest but know nothing about Android's content providers. Handed one it
 * simply fails to open. On a device that arrived as:
 *
 * ```
 * engine: BACKUP
 * reason: SOURCE
 * errorCode: 266  (LIBVLC_PLAYBACK_ERROR)
 * source: content://media/external/audio/media/105
 * ```
 *
 * — the backup engine refusing the one kind of URL the local library produces. The engine
 * opens a descriptor for those instead, and this pins the rule.
 *
 * ## Why the rule is a string test
 *
 * So it can be checked here. Everything else in this engine needs a device and a native
 * library; a plain prefix test needs neither, which is the difference between a rule that
 * is asserted and a rule that is hoped for.
 */
class VlcSourceTest {

    @Test
    fun `a content url needs a descriptor`() {
        assertTrue(
            "MediaStore hands out content:// and LibVLC cannot open one",
            VlcPlaybackEngine.requiresDescriptor("content://media/external/audio/media/105"),
        )
        assertTrue(
            VlcPlaybackEngine.requiresDescriptor("content://media/external/video/media/42"),
        )
    }

    /**
     * The scheme is compared without case, because a URI scheme is case-insensitive and a
     * provider is free to hand back `CONTENT://`. Getting this wrong fails in exactly one
     * place — somebody else's device.
     */
    @Test
    fun `the scheme is matched without case`() {
        assertTrue(VlcPlaybackEngine.requiresDescriptor("CONTENT://media/external/audio/media/7"))
        assertTrue(VlcPlaybackEngine.requiresDescriptor("Content://media/external/audio/media/7"))
    }

    /**
     * Everything LibVLC can open must keep going to LibVLC.
     *
     * Not a formality: routing a stream through a file descriptor would lose the streaming
     * behaviour that is the whole reason to hand it to LibVLC, and IPTV is streams. The
     * descriptor path is for local provider URLs and nothing else.
     */
    @Test
    fun `every scheme LibVLC can open is left to LibVLC`() {
        for (url in listOf(
            "http://provider.tv:8080/live/1234.ts",
            "https://provider.tv/movie/9.mkv",
            "file:///storage/emulated/0/Movies/holiday.mp4",
            "rtsp://camera.local/stream",
            "udp://@239.0.0.1:1234",
        )) {
            assertFalse("$url is not a provider URL", VlcPlaybackEngine.requiresDescriptor(url))
        }
    }

    /** A URL that merely mentions the word is not a provider URL. */
    @Test
    fun `the match is on the scheme and not on the text`() {
        assertFalse(VlcPlaybackEngine.requiresDescriptor("https://host/content://x"))
        assertFalse(VlcPlaybackEngine.requiresDescriptor("contented://host/a.ts"))
        assertFalse(VlcPlaybackEngine.requiresDescriptor("content:/media/1"))
        assertFalse(VlcPlaybackEngine.requiresDescriptor(""))
    }
}
