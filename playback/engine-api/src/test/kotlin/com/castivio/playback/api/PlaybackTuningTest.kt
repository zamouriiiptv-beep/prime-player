package com.castivio.playback.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live buffer profiles, as figures a player is built from.
 *
 * ExoPlayer's load control stops reading a source once it holds the maximum and starts again
 * only when it has drained to the minimum. On a live connection that gap is time in which the
 * provider's writes go unread, so the live profiles are held to a rule rather than to numbers.
 */
class PlaybackTuningTest {

    @Test
    fun `a live profile never stops reading the connection`() {
        for (tuning in listOf(PlaybackTuning.LIVE_FAST, PlaybackTuning.LIVE_LEAN)) {
            assertEquals(
                "the buffer is topped up as it plays, with no stretch of not reading",
                tuning.maxBufferMs,
                tuning.minBufferMs,
            )
        }
    }

    @Test
    fun `the live peaks are the figures they were`() {
        assertEquals(20_000, PlaybackTuning.LIVE_FAST.maxBufferMs)
        assertEquals(12_000, PlaybackTuning.LIVE_LEAN.maxBufferMs)
    }

    @Test
    fun `a live channel still starts at half a second`() {
        assertEquals(500, PlaybackTuning.LIVE_FAST.bufferForPlaybackMs)
        assertEquals(500, PlaybackTuning.LIVE_LEAN.bufferForPlaybackMs)
    }

    @Test
    fun `every profile is one ExoPlayer accepts`() {
        // DefaultLoadControl rejects a start above the minimum, and a minimum above the maximum.
        for (tuning in listOf(
            PlaybackTuning.LIVE_FAST,
            PlaybackTuning.LIVE_LEAN,
            PlaybackTuning.VOD,
            PlaybackTuning.RESILIENT,
        )) {
            assertTrue(tuning.bufferForPlaybackMs <= tuning.minBufferMs)
            assertTrue(tuning.bufferForPlaybackAfterRebufferMs <= tuning.minBufferMs)
            assertTrue(tuning.minBufferMs <= tuning.maxBufferMs)
        }
    }

    @Test
    fun `films keep the room to buffer ahead`() {
        assertTrue(PlaybackTuning.VOD.minBufferMs < PlaybackTuning.VOD.maxBufferMs)
    }
}
