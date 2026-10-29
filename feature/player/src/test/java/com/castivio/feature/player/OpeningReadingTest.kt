package com.castivio.feature.player

import com.castivio.playback.api.EngineId
import com.castivio.playback.api.OpeningMarks
import com.castivio.playback.api.PlaybackError
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * How one opening of a channel is read: the stages that add up, the engine's moments that do not, the picture kept
 * apart from the engine's readiness, and an engine that cannot say a thing saying so rather than guessing.
 *
 * Plain JUnit: a reading is a value, and nothing here reads a clock or an engine.
 */
class OpeningReadingTest {

    @Before
    fun setUp() {
        OpeningDiagnostics.enable(true)
        OpeningDiagnostics.reset()
    }

    @After
    fun tearDown() {
        OpeningDiagnostics.reset()
        OpeningDiagnostics.enable(false)
    }

    // --------------------------------------------------------------------- Media3

    @Test
    fun `a shown opening reads from the press, and ready is kept apart from the picture`() {
        // The engine was asked 60 ms after the press, so each of its moments is read 60 ms later than it says.
        val reading = media3(
            status = OpeningStatus.SHOWN,
            totalMs = 270,
            marks = OpeningMarks(
                serverAnsweredMs = 40, firstBytesMs = 90, tracksKnownMs = 120,
                decoderReadyMs = 150, decoderInitMs = 25, readyMs = 170, firstFrameMs = 210,
            ),
        )

        val text = reading.lines().joinToString("\n") { it.text }

        assertTrue(text, "#3 Al Aoula · MEDIA3 · engine reused · 0.27 s · first frame" in text)
        assertTrue("the engine's moments are from the press: $text", "server 0.10 s · bytes 0.15 s · tracks 0.18 s" in text)
        assertTrue(text, "decoder 0.21 s (built in 25 ms)" in text)
        assertTrue("ready says it is not a picture: $text", "ready (not a picture) 0.23 s" in text)
        assertTrue(text, "frame 0.27 s" in text)
        assertTrue("the gap between readiness and the picture is a figure of its own: $text", "ready→frame 40 ms" in text)
        assertTrue("and so is the engine's own share: $text", "engine asked→frame 210 ms" in text)
        assertFalse("Media3 sees the frame itself, so nothing is approximate: $text", "≈" in text)
    }

    @Test
    fun `the application's stages are one after another and the rest is said as other`() {
        val reading = media3(status = OpeningStatus.WAITING, totalMs = 90, marks = OpeningMarks())

        val app = reading.lines().first { it.text.startsWith("app (ms):") }.text

        assertEquals("app (ms): press→open 3 · release 12 · setup 40 · other 5 = 60 before the engine was asked", app)
    }

    @Test
    fun `an opening that is still waiting says what it is waiting for, and a moment not come is not a zero`() {
        val none = media3(status = OpeningStatus.WAITING, totalMs = 500, marks = OpeningMarks())
        val answered = media3(status = OpeningStatus.WAITING, totalMs = 500, marks = OpeningMarks(serverAnsweredMs = 40))
        val ready = media3(status = OpeningStatus.WAITING, totalMs = 500, marks = OpeningMarks(serverAnsweredMs = 40, readyMs = 170))

        assertTrue(none.lines().first().text, "now the server" in none.lines().first().text)
        assertTrue("server …" in none.lines().joinToString("\n") { it.text })
        assertTrue(answered.lines().first().text, "now the first bytes (the server answered)" in answered.lines().first().text)
        assertTrue(ready.lines().first().text, "now the first frame (the engine is ready)" in ready.lines().first().text)
        assertEquals(HudTone.RUNNING, none.lines().first().tone)
    }

    @Test
    fun `a failure names its reason and what it was waiting for, and is never a success`() {
        val failed = media3(
            status = OpeningStatus.FAILED,
            totalMs = 3_000,
            marks = OpeningMarks(serverAnsweredMs = 40),
            failure = "TIMEOUT",
        )

        val lines = failed.lines()

        assertEquals(HudTone.FAILED, lines.first().tone)
        assertTrue(lines.first().text, "FAILED TIMEOUT" in lines.first().text)
        assertTrue("it says where it stopped", lines.any { "FAILED TIMEOUT while the first bytes (the server answered)" in it.text })
        assertTrue("a moment that never came is a dash once it ended", lines.any { "bytes -" in it.text })
        assertTrue(failed.row(), "FAILED TIMEOUT 3.00 s" in failed.row())
        assertEquals(HudTone.FAILED, failed.rowTone())
    }

    // -------------------------------------------------------------------- LibVLC

    @Test
    fun `LibVLC says what it cannot say, and its first frame is an approximation`() {
        val reading = OpeningReading(
            id = 5, attempt = 7, channel = "Al Aoula", case = OpeningCase.FALLBACK, status = OpeningStatus.SHOWN, totalMs = 940,
            timing = OpeningTiming(
                engine = EngineId.BACKUP, fallback = true, reused = false,
                pressToOpenMs = 3, releaseMs = 20, setupMs = 15, prepareMs = 40,
                marks = OpeningMarks(
                    firstBytesMs = 300, tracksKnownMs = 450, readyMs = 500, firstFrameMs = 900, clockMovingMs = 1_000,
                    firstFrameExact = false,
                ),
                firstFrameMs = 940,
            ),
            stalls = MainThreadStalls(),
        )

        val text = reading.lines().joinToString("\n") { it.text }

        assertTrue(text, "LIBVLC · fallback · 0.94 s≈ · first frame≈" in text)
        assertTrue("LibVLC reports no server answer: $text", "server غير متاح" in text)
        assertTrue("and no decoder of its own: $text", "decoder غير متاح" in text)
        assertTrue("the nearest it says to bytes is a buffer: $text", "first buffer≈ 0.34 s" in text)
        assertTrue("the video output appearing is not called the picture: $text", "frame≈ (video output) 0.94 s" in text)
        assertTrue("the clock moving after it is its other evidence: $text", "clock moving 1.04 s" in text)
        assertTrue(text, "ready→frame≈ 400 ms" in text)
    }

    // ------------------------------------------------------------- the primary giving the source up

    @Test
    fun `a fallback says why the primary left and that nothing had reached it`() {
        val reading = afterThePrimary(Handover(PlaybackError.TIMEOUT, afterMs = 3_004, serverAnsweredMs = null, firstBytesMs = null))

        val line = reading.lines()[1]

        assertEquals("primary left: TIMEOUT at 3.00 s · server did not answer · no bytes arrived", line.text)
        assertEquals("it is the line to read, so it is not one of the muted ones", HudTone.RUNNING, line.tone)
        assertTrue(reading.row(), "fallback (TIMEOUT, no bytes)" in reading.row())
    }

    @Test
    fun `a fallback says when the server answered and when bytes arrived`() {
        val reading = afterThePrimary(Handover(PlaybackError.TIMEOUT, afterMs = 3_004, serverAnsweredMs = 1_200, firstBytesMs = 2_400))

        assertEquals(
            "primary left: TIMEOUT at 3.00 s · server answered 1.20 s · bytes arrived 2.40 s",
            reading.lines()[1].text,
        )
        assertTrue(reading.row(), "fallback (TIMEOUT, bytes)" in reading.row())
    }

    /** What the line is for: a silent network and a refused stream read differently, without decoding a dash. */
    @Test
    fun `a refused stream is told apart from a silent network`() {
        val refused = afterThePrimary(Handover(PlaybackError.DECODER_INIT, afterMs = 420, serverAnsweredMs = 90, firstBytesMs = 140))
        val silent = afterThePrimary(Handover(PlaybackError.TIMEOUT, afterMs = 3_004, serverAnsweredMs = null, firstBytesMs = null))

        assertEquals(
            "primary left: DECODER_INIT at 0.42 s · server answered 0.09 s · bytes arrived 0.14 s",
            refused.lines()[1].text,
        )
        assertTrue(refused.row(), "fallback (DECODER_INIT, bytes)" in refused.row())
        assertTrue(silent.row(), "fallback (TIMEOUT, no bytes)" in silent.row())
    }

    @Test
    fun `an opening the primary did not give up has no such line and no tag`() {
        val reading = media3(status = OpeningStatus.SHOWN, totalMs = 270, marks = OpeningMarks(firstFrameMs = 210))

        assertFalse(reading.lines().any { it.text.startsWith("primary left") })
        assertEquals("#3 MEDIA3 · engine reused · 0.27 s · Al Aoula", reading.row())
    }

    /** One screenshot of the panel is the whole account: the latest opening's line, and every row's reason. */
    @Test
    fun `the panel carries the reason for the latest opening and for each row of the table`() {
        OpeningDiagnostics.publish(
            afterThePrimary(Handover(PlaybackError.TIMEOUT, 3_004, 1_200, null), id = 1),
        )
        OpeningDiagnostics.publish(
            afterThePrimary(Handover(PlaybackError.DECODER_INIT, 420, 90, 140), id = 2),
        )

        val panel = OpeningDiagnostics.panel.value.map { it.text }

        assertTrue(panel.toString(), panel.any { it.startsWith("primary left: DECODER_INIT") })
        assertTrue(panel.toString(), panel.any { "fallback (DECODER_INIT, bytes)" in it && it.startsWith("#7 LIBVLC") })
        assertTrue(panel.toString(), panel.any { "fallback (TIMEOUT, no bytes)" in it })
    }

    // ------------------------------------------------------------- the main thread

    @Test
    fun `the main thread's stalls are counted and named, and none is said as none`() {
        val quiet = media3(status = OpeningStatus.SHOWN, totalMs = 240, marks = OpeningMarks(firstFrameMs = 210))
        val stalled = quiet.copy(stalls = MainThreadStalls(count = 2, maxMs = 340, totalMs = 520))

        assertTrue(quiet.lines().any { it.text == "main thread (from open): no stall over 100 ms" })
        val line = stalled.lines().first { it.text.startsWith("main thread") }
        assertEquals("main thread (from open): 2 stall(s) over 100 ms · longest 340 ms · total 520 ms", line.text)
        assertEquals(HudTone.RUNNING, line.tone)
    }

    // ------------------------------------------------------------- the last openings

    @Test
    fun `the table keeps the last eight, newest first, and an opening is in it once`() {
        for (id in 1..10) OpeningDiagnostics.publish(media3(id = id, status = OpeningStatus.SHOWN, totalMs = 100L * id, marks = OpeningMarks(firstFrameMs = 50)))
        OpeningDiagnostics.publish(media3(id = 10, status = OpeningStatus.SHOWN, totalMs = 1_000, marks = OpeningMarks(firstFrameMs = 50)))

        val recent = OpeningDiagnostics.recent.value

        assertEquals(8, recent.size)
        assertEquals((10 downTo 3).toList(), recent.map { it.id })
        val panel = OpeningDiagnostics.panel.value.map { it.text }
        assertTrue(panel.toString(), "last 8:" in panel)
    }

    @Test
    fun `an opening another press cut off is in the table as replaced, never as a success`() {
        val running = media3(id = 1, status = OpeningStatus.WAITING, totalMs = 400, marks = OpeningMarks())
        OpeningDiagnostics.publish(running)
        assertTrue("a running opening is not in the table yet", OpeningDiagnostics.recent.value.isEmpty())

        OpeningDiagnostics.replaced(running)

        val kept = OpeningDiagnostics.recent.value.single()
        assertEquals(OpeningStatus.REPLACED, kept.status)
        assertFalse(kept.running)
        assertTrue(kept.row(), kept.row().startsWith("#3") && "replaced 0.40 s" in kept.row())
    }

    // -------------------------------------------------------------------- fixtures

    /** The backup's opening after the primary gave the source up: the shape of the owner's `Bfm alpes`. */
    private fun afterThePrimary(handover: Handover, id: Int = 1) = OpeningReading(
        id = id,
        attempt = 7,
        channel = "Bfm alpes",
        case = OpeningCase.FALLBACK,
        status = OpeningStatus.SHOWN,
        totalMs = 5_761,
        timing = OpeningTiming(
            engine = EngineId.BACKUP, fallback = true, reused = false,
            pressToOpenMs = 36, releaseMs = 21, setupMs = 2, prepareMs = 3_064,
            marks = OpeningMarks(firstBytesMs = 2_680, firstFrameMs = 2_697, firstFrameExact = false),
            firstFrameMs = 5_761,
            handover = handover,
        ),
        stalls = MainThreadStalls(),
    )

    private fun media3(
        status: OpeningStatus,
        totalMs: Long,
        marks: OpeningMarks,
        id: Int = 1,
        failure: String? = null,
    ) = OpeningReading(
        id = id,
        attempt = 3,
        channel = "Al Aoula",
        case = OpeningCase.REUSED,
        status = status,
        totalMs = totalMs,
        timing = OpeningTiming(
            engine = EngineId.PRIMARY, fallback = false, reused = true,
            pressToOpenMs = 3, releaseMs = 12, setupMs = 40, prepareMs = 30 + 30,
            marks = marks,
            firstFrameMs = marks.firstFrameMs?.let { it + 60 },
        ),
        stalls = MainThreadStalls(),
        failure = failure,
    )
}
