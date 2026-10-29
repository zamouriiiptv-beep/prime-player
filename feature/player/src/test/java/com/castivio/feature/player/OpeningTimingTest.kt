package com.castivio.feature.player

import com.castivio.playback.api.EngineId
import com.castivio.playback.api.OpeningMarks
import com.castivio.playback.api.PlaybackError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line an opening is written down as.
 *
 * Its whole use is being read beside another one -- this channel against that, this build
 * against the last -- so the claims here are about its shape: the same columns every time, a
 * dash where a step was not seen, and the arithmetic between the application's half and the
 * source's done once, here, rather than by whoever is reading the log.
 */
class OpeningTimingTest {

    private fun timing(
        marks: OpeningMarks = OpeningMarks(),
        firstFrameMs: Long? = 900,
        fallback: Boolean = false,
        reused: Boolean = false,
    ) = OpeningTiming(
        engine = EngineId.PRIMARY,
        fallback = fallback,
        reused = reused,
        pressToOpenMs = 30,
        releaseMs = 180,
        setupMs = 40,
        prepareMs = 260,
        marks = marks,
        firstFrameMs = firstFrameMs,
    )

    @Test
    fun `every figure has its column, in the order the opening happens`() {
        val line = timing(
            OpeningMarks(
                serverAnsweredMs = 120,
                firstBytesMs = 150,
                tracksKnownMs = 400,
                decoderReadyMs = 455,
                decoderInitMs = 50,
                firstFrameMs = 630,
            ),
        ).render()

        assertEquals(
            "engine=PRIMARY fallback=no reused=no | press→open 30ms release 180ms setup 40ms press→prepare 260ms" +
                " | server 120ms bytes 150ms tracks 400ms decoder 455ms (built in 50ms) frame 630ms" +
                " | prepare→frame 640ms press→frame 900ms",
            line,
        )
    }

    /** A step the engine never saw keeps its place, so two lines still line up. */
    @Test
    fun `a step that was not seen is a dash and not a gap`() {
        val line = timing(OpeningMarks(firstBytesMs = 150), firstFrameMs = null).render()

        assertTrue(line, line.contains("server – bytes 150ms tracks – decoder – frame –"))
        assertTrue(line, line.endsWith("prepare→frame – press→frame –"))
    }

    @Test
    fun `a decoder that was kept says so instead of a build time`() {
        val line = timing(OpeningMarks(decoderReadyMs = 300, decoderKept = true)).render()

        assertTrue(line, line.contains("decoder 300ms (kept)"))
    }

    @Test
    fun `the source's half is the total less the application's`() {
        assertEquals(640L, timing().prepareToFrameMs)
        assertNull("no frame, no figure", timing(firstFrameMs = null).prepareToFrameMs)
        assertTrue(timing(fallback = true).render().startsWith("engine=PRIMARY fallback=yes reused=no |"))
    }

    @Test
    fun `an engine kept from the last channel says so`() {
        assertTrue(timing(reused = true).render().startsWith("engine=PRIMARY fallback=no reused=yes |"))
    }

    // ------------------------------------------------------------- the primary giving the source up

    @Test
    fun `an attempt the primary gave up says why, when, and what had reached it`() {
        val left = Handover(PlaybackError.TIMEOUT, afterMs = 3_004, serverAnsweredMs = 1_200, firstBytesMs = null)

        val line = timing(fallback = true).copy(handover = left).render()

        assertTrue(line, line.endsWith(" | primary left TIMEOUT after 3004ms server 1200ms bytes –"))
        assertFalse("no bytes is not the same as bytes", left.bytesArrived)
    }

    @Test
    fun `an attempt the primary did not give up has no such column`() {
        assertFalse(timing().render().contains("primary left"))
        assertNull(timing().handover)
    }

    @Test
    fun `bytes that arrived before the primary left are said as that`() {
        val left = Handover(PlaybackError.DECODER_INIT, afterMs = 420, serverAnsweredMs = 90, firstBytesMs = 140)

        assertTrue(left.bytesArrived)
        assertTrue(timing(fallback = true).copy(handover = left).render().contains("bytes 140ms"))
    }
}
