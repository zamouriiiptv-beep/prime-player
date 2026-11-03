package com.castivio.playback.api

import com.castivio.core.common.StreamQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The debug trace: one line a second while a stream is read, what is in it, and that having it changes nothing.
 *
 * Time is the test's own, as in [StreamQualityMonitorTest].
 */
class QualityTraceTest {

    private val lines = mutableListOf<QualityTraceLine>()
    private val monitor = StreamQualityMonitor { lines += it }

    private var now = 0L
    private var bytes = 0L
    private var faults = 0L

    private val playing = PlaybackState.Playing(positionMs = 0, durationMs = null, bitrateBps = 0)
    private val buffering = PlaybackState.Buffering(bufferedMs = 0, bitrateBps = 0)

    private fun second(
        bytesPerSecond: Long = 500_000,
        buffered: Long? = 3_000,
        newFaults: Long = 0,
        into: StreamQualityMonitor = monitor,
    ): StreamQuality? {
        now += 1_000
        bytes += bytesPerSecond
        faults += newFaults
        return into.tick(now, QualityReading(bytesReceived = bytes, bufferedAheadMs = buffered, dataFaults = faults))
    }

    private fun start(into: StreamQualityMonitor = monitor) {
        into.state(now, PlaybackState.Opening, frameShown = false)
        into.state(now, playing, frameShown = true)
    }

    @Test
    fun `a line is written for every second that reads a stream, and for none that does not`() {
        // Nothing has played: nothing is written.
        second()
        monitor.state(now, PlaybackState.Opening, frameShown = false)
        second()
        assertTrue(lines.isEmpty())

        start()
        repeat(3) { second() }
        assertEquals(3, lines.size)

        // A pause reads nothing.
        monitor.state(now, PlaybackState.Paused(positionMs = 0), frameShown = true)
        repeat(3) { second() }
        assertEquals(3, lines.size)

        monitor.state(now, playing, frameShown = true)
        second()
        assertEquals(4, lines.size)
    }

    @Test
    fun `the rate, the usual rate and the buffer are written in the units a provider is spoken of in`() {
        start()
        repeat(12) { second(buffered = 3_000) }

        val line = lines.last()
        // 500,000 bytes a second is four megabits.
        assertEquals(4_000_000.0, line.rateBps!!, 1.0)
        assertEquals(4_000_000.0, line.recentRateBps!!, 1.0)
        assertEquals(4_000_000.0, line.usualRateBps!!, 1.0)
        assertEquals(3_000L, line.bufferedMs)
        assertEquals(0L, line.silentMs)
        assertEquals(12_000L, line.sinceStartMs)
        assertEquals(StreamQuality.EXCELLENT, line.verdict)
        assertEquals(StreamQuality.EXCELLENT, line.shown)
    }

    @Test
    fun `the first second has no rate, because a rate needs two counts`() {
        start()
        second()

        assertNull(lines.single().rateBps)
        assertNull(lines.single().shown)
    }

    @Test
    fun `silence, buffering, lost data and the grace after a seek are written as they are`() {
        start()
        repeat(10) { second() }

        second(bytesPerSecond = 0)
        second(bytesPerSecond = 0)
        assertEquals(2_000L, lines.last().silentMs)
        assertEquals(0.0, lines.last().rateBps!!, 0.0)

        monitor.state(now, buffering, frameShown = true)
        second(bytesPerSecond = 0, buffered = 0)
        assertEquals("STALLED", lines.last().phase)
        assertEquals(1_000L, lines.last().stalledNowMs)
        assertEquals(1, lines.last().stallsInWindow)

        monitor.state(now, playing, frameShown = true)
        second(newFaults = 2)
        assertEquals(2L, lines.last().newFaults)
        assertEquals(2, lines.last().faultsInWindow)
        second()
        assertEquals(0L, lines.last().newFaults)
        assertEquals(2, lines.last().faultsInWindow)

        monitor.moved(now)
        second()
        assertEquals(StreamQualityMonitor.SEEK_GRACE_MS - 1_000L, lines.last().graceMs)
    }

    @Test
    fun `the verdict and what is shown are written side by side, so a held-back worsening can be read`() {
        start()
        repeat(10) { second(buffered = 10_000) }

        second(buffered = 500)

        val line = lines.last()
        assertEquals(StreamQuality.POOR, line.verdict)
        assertFalse(line.firm)
        assertEquals(StreamQuality.EXCELLENT, line.shown)
    }

    @Test
    fun `a line is one short line of key=value pairs, with a dash for what is not known`() {
        val line = QualityTraceLine(
            sinceStartMs = 63_400,
            phase = "PLAYING",
            rateBps = 4_012_345.0,
            recentRateBps = null,
            usualRateBps = 3_990_000.0,
            silentMs = 0,
            bufferedMs = null,
            bufferRatio = 0.5,
            stalledNowMs = 0,
            stallsInWindow = 1,
            newFaults = 0,
            faultsInWindow = 3,
            graceMs = 0,
            verdict = StreamQuality.GOOD,
            firm = false,
            shown = null,
        )

        assertEquals(
            "q t=63.4s PLAYING rate=4012.3kbps r5=- usual=3990.0kbps silent=0ms buf=- ratio=0.50 stall=0ms " +
                "stalls60=1 faults=+0/3 grace=0ms verdict=GOOD/soft shown=-",
            line.format(),
        )
        assertFalse(line.format().contains('\n'))
    }

    @Test
    fun `writing the trace changes nothing the indicator shows`() {
        val plain = StreamQualityMonitor()
        val traced = StreamQualityMonitor { lines += it }

        fun timeline(monitor: StreamQualityMonitor): List<StreamQuality?> {
            now = 0
            bytes = 0
            faults = 0
            val shown = mutableListOf<StreamQuality?>()
            start(monitor)
            repeat(12) { shown += second(buffered = 10_000, into = monitor) }
            shown += second(buffered = 500, into = monitor)
            repeat(4) { shown += second(buffered = 500, into = monitor) }
            monitor.state(now, buffering, frameShown = true)
            repeat(3) { shown += second(bytesPerSecond = 0, buffered = 0, into = monitor) }
            monitor.state(now, playing, frameShown = true)
            monitor.moved(now)
            repeat(12) { shown += second(newFaults = if (it % 4 == 0) 1 else 0, into = monitor) }
            return shown
        }

        assertEquals(timeline(plain), timeline(traced))
        assertTrue(lines.isNotEmpty())
    }
}
