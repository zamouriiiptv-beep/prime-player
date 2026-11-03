package com.castivio.playback.api

import com.castivio.core.common.StreamQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The monitor over a timeline: a stream played second by second, with the engine's state told as it changes and a reading
 * taken as the player takes one.
 *
 * Time is the test's own, so "ten seconds" is ten calls and nothing sleeps.
 */
class StreamQualityMonitorTest {

    private val monitor = StreamQualityMonitor()

    private var now = 0L
    private var bytes = 0L
    private var faults = 0L

    private val playing = PlaybackState.Playing(positionMs = 0, durationMs = null, bitrateBps = 0)
    private val buffering = PlaybackState.Buffering(bufferedMs = 0, bitrateBps = 0)

    /** One second of a stream: bytes arrive (or not), the buffer holds what it holds, and the monitor is asked. */
    private fun second(
        bytesPerSecond: Long = 500_000,
        buffered: Long? = 3_000,
        newFaults: Long = 0,
        counted: Boolean = true,
    ): StreamQuality? {
        now += 1_000
        bytes += bytesPerSecond
        faults += newFaults
        return monitor.tick(
            now,
            QualityReading(
                bytesReceived = if (counted) bytes else null,
                bufferedAheadMs = buffered,
                dataFaults = faults,
            ),
        )
    }

    private fun seconds(count: Int, block: () -> StreamQuality?): StreamQuality? {
        var last: StreamQuality? = null
        repeat(count) { last = block() }
        return last
    }

    private fun start() {
        monitor.state(now, PlaybackState.Opening, frameShown = false)
        monitor.state(now, playing, frameShown = true)
    }

    /** A clean stream for as long as it takes to be rated. */
    private fun warmedUp(): StreamQuality? {
        start()
        return seconds(StreamQualityMonitor.WARMUP_MS.toInt() / 1_000) { second() }
    }

    @Test
    fun `a clean stream is not rated while it warms up, and then it is excellent`() {
        start()
        val early = seconds(7) { second() }
        val rated = second()

        assertNull(early)
        assertEquals(StreamQuality.EXCELLENT, rated)
    }

    @Test
    fun `nothing is rated before the stream has played`() {
        monitor.state(now, PlaybackState.Opening, frameShown = false)

        assertNull(second())
        assertNull(monitor.rating)
    }

    @Test
    fun `a stall is shown at once, and a stream that keeps stalling is bad`() {
        warmedUp()

        monitor.state(now, buffering, frameShown = true)
        val during = seconds(2) { second(bytesPerSecond = 0, buffered = 0) }
        monitor.state(now, playing, frameShown = true)

        assertEquals(StreamQuality.POOR, during)

        // Two more inside the minute: three in all.
        repeat(2) {
            seconds(3) { second() }
            monitor.state(now, buffering, frameShown = true)
            second(bytesPerSecond = 0, buffered = 0)
            monitor.state(now, playing, frameShown = true)
        }
        assertEquals(StreamQuality.BAD, second())
    }

    @Test
    fun `after a stall it recovers one step at a time and never faster than the hold`() {
        warmedUp()
        monitor.state(now, buffering, frameShown = true)
        seconds(2) { second(bytesPerSecond = 0, buffered = 0) }
        monitor.state(now, playing, frameShown = true)

        // The stall is over and the stream is clean again: it has to stay clean for the hold before it is better.
        val soon = seconds(StreamQualityMonitor.UP_HOLD_MS.toInt() / 1_000 - 1) { second() }
        assertEquals(StreamQuality.POOR, soon)

        // One step, not two: poor to good, and good only becomes excellent once the stall is five minutes old.
        val later = seconds(2) { second() }
        assertEquals(StreamQuality.GOOD, later)
        val muchLater = seconds(60) { second() }
        assertEquals(StreamQuality.GOOD, muchLater)
    }

    @Test
    fun `a stall that is five minutes old is forgotten`() {
        warmedUp()
        monitor.state(now, buffering, frameShown = true)
        second(bytesPerSecond = 0, buffered = 0)
        monitor.state(now, playing, frameShown = true)

        val clean = seconds(330) { second() }

        assertEquals(StreamQuality.EXCELLENT, clean)
    }

    @Test
    fun `a failure that is the connection's is bad at once`() {
        warmedUp()

        monitor.state(now, PlaybackState.Failed(PlaybackError.NETWORK), frameShown = true)

        assertEquals(StreamQuality.BAD, second())
    }

    @Test
    fun `a failure that is not the connection's says nothing about the stream`() {
        warmedUp()

        monitor.state(now, PlaybackState.Failed(PlaybackError.DECODING), frameShown = true)

        assertNull(second())
    }

    @Test
    fun `a soft worsening has to hold before the colour moves, so a wobble does not show`() {
        warmedUp()
        // A cushion of ten seconds, usual for this stream, and then one second with it nearly gone.
        seconds(10) { second(buffered = 10_000) }
        val wobble = second(buffered = 500)
        val recovered = second(buffered = 10_000)

        assertEquals(StreamQuality.EXCELLENT, wobble)
        assertEquals(StreamQuality.EXCELLENT, recovered)
    }

    @Test
    fun `a buffer that stays drained for a few seconds does move it`() {
        warmedUp()
        seconds(10) { second(buffered = 10_000) }

        val drained = seconds(StreamQualityMonitor.DOWN_CONFIRM_MS.toInt() / 1_000 + 1) { second(buffered = 500) }

        assertEquals(StreamQuality.POOR, drained)
    }

    @Test
    fun `a rating that flaps between two verdicts every second does not change colour`() {
        warmedUp()
        seconds(10) { second(buffered = 10_000) }

        val seen = (1..20).map { second(buffered = if (it % 2 == 0) 10_000 else 500) }

        assertEquals(setOf(StreamQuality.EXCELLENT), seen.toSet())
    }

    @Test
    fun `a stream whose bytes have stopped, with no buffer to carry it, is bad`() {
        // What the backup engine reports: bytes counted and no buffer at all.
        start()
        seconds(10) { second(buffered = null) }

        val silent = seconds(3) { second(bytesPerSecond = 0, buffered = null) }

        assertEquals(StreamQuality.BAD, silent)
    }

    @Test
    fun `a film that has stopped reading because its buffer is full is not a stream that has stopped`() {
        start()
        seconds(12) { second(buffered = 30_000) }

        val idle = seconds(20) { second(bytesPerSecond = 0, buffered = 30_000) }

        assertEquals(StreamQuality.EXCELLENT, idle)
    }

    @Test
    fun `a stream that never holds a cushion is read by its stalls and its bytes, not by a buffer that says nothing`() {
        start()

        // A provider sending at exactly the speed it plays: a few hundred milliseconds ahead, always.
        val rated = seconds(30) { second(buffered = 400) }

        assertEquals(StreamQuality.EXCELLENT, rated)
    }

    @Test
    fun `a rate that drops with no buffer to carry it is poor, and a steady one is not`() {
        start()
        seconds(20) { second(buffered = null) }
        // A fifth of the rate it had, for as long as the rate looks back.
        val slow = seconds(12) { second(bytesPerSecond = 100_000, buffered = null) }

        assertEquals(StreamQuality.POOR, slow)
    }

    @Test
    fun `a pause keeps the rating and its silence is not counted against the stream`() {
        warmedUp()
        seconds(5) { second() }

        monitor.state(now, PlaybackState.Paused(positionMs = 0), frameShown = true)
        // Nothing is read for thirty seconds, and the player does not ask.
        now += 30_000
        assertEquals(StreamQuality.EXCELLENT, monitor.tick(now, QualityReading(bytesReceived = bytes)))

        monitor.state(now, playing, frameShown = true)
        val resumed = second()

        assertEquals(StreamQuality.EXCELLENT, resumed)
    }

    @Test
    fun `a counter that starts again is a new count and not a rate`() {
        warmedUp()
        seconds(10) { second() }

        // An engine that was opened again: its bytes begin from nothing.
        bytes = 0
        val after = second(bytesPerSecond = 500_000)

        assertEquals(StreamQuality.EXCELLENT, after)
    }

    @Test
    fun `lost data is a blemish, and a burst of it is a stream degrading`() {
        warmedUp()

        val one = second(newFaults = 1)
        assertEquals(StreamQuality.EXCELLENT, one) // soft: held for the confirm time first
        val held = seconds(StreamQualityMonitor.DOWN_CONFIRM_MS.toInt() / 1_000) { second() }
        assertEquals(StreamQuality.GOOD, held)

        // The third fault inside the window arrives on the second second of the burst, and has to hold for the confirm
        // time from there: two seconds to get to three, then three more.
        val burst = seconds(2 + StreamQualityMonitor.DOWN_CONFIRM_MS.toInt() / 1_000) { second(newFaults = 1) }
        assertEquals(StreamQuality.POOR, burst)
    }

    @Test
    fun `a stream that drops and is asked for again after it played counts as a stall`() {
        warmedUp()

        // A radio reconnecting: the engine is opened again with no picture and no reset in between.
        monitor.state(now, PlaybackState.Opening, frameShown = false)
        val dropped = seconds(5) { second(bytesPerSecond = 0, buffered = null) }

        assertEquals(StreamQuality.BAD, dropped)
    }

    @Test
    fun `starting again forgets the last stream entirely`() {
        warmedUp()
        monitor.state(now, buffering, frameShown = true)
        seconds(5) { second(bytesPerSecond = 0, buffered = 0) }
        assertEquals(StreamQuality.BAD, monitor.rating)

        monitor.reset()

        assertNull(monitor.rating)
        assertNull(second())
        // And the next stream is its own: none of the last one's stalls are counted against it.
        start()
        seconds(8) { second() }
        assertEquals(StreamQuality.EXCELLENT, second())
    }

    @Test
    fun `a seek starts the references again so a buffer refilling is not a bad connection`() {
        warmedUp()
        seconds(10) { second(buffered = 20_000) }

        monitor.moved(now)
        // After a seek the buffer is empty and fills over many seconds.
        val refilling = seconds(6) { second(buffered = 1_000) }

        assertEquals(StreamQuality.EXCELLENT, refilling)
    }

    private val graceSeconds = StreamQualityMonitor.SEEK_GRACE_MS.toInt() / 1_000

    @Test
    fun `buffering that begins right after a seek is not a stall, and leaves no mark on the stream`() {
        warmedUp()
        seconds(10) { second(buffered = 20_000) }

        monitor.moved(now)
        monitor.state(now, buffering, frameShown = true)
        val refilling = seconds(5) { second(bytesPerSecond = 0, buffered = 0) }
        monitor.state(now, playing, frameShown = true)
        // A stall that was counted would hold the rating at good for five minutes: a clean stretch shows there was none.
        val clean = seconds(20) { second() }

        assertEquals(StreamQuality.EXCELLENT, refilling)
        assertEquals(StreamQuality.EXCELLENT, clean)
    }

    @Test
    fun `a viewer who keeps jumping about is not served badly by the jumps`() {
        warmedUp()

        repeat(4) {
            monitor.moved(now)
            monitor.state(now, buffering, frameShown = true)
            seconds(2) { second(bytesPerSecond = 0, buffered = 0) }
            monitor.state(now, playing, frameShown = true)
            seconds(3) { second() }
        }

        assertEquals(StreamQuality.EXCELLENT, monitor.rating)
    }

    @Test
    fun `a seek that never lands is excused for the grace and then judged like any stall`() {
        warmedUp()
        seconds(10) { second(buffered = 20_000) }
        monitor.moved(now)
        monitor.state(now, buffering, frameShown = true)

        val excused = seconds(graceSeconds) { second(bytesPerSecond = 0, buffered = 0) }
        val oneSecondOn = second(bytesPerSecond = 0, buffered = 0)
        val twoSecondsOn = second(bytesPerSecond = 0, buffered = 0)
        val threeSecondsOn = second(bytesPerSecond = 0, buffered = 0)

        assertEquals(StreamQuality.EXCELLENT, excused)
        assertEquals(StreamQuality.POOR, oneSecondOn)
        assertEquals(StreamQuality.POOR, twoSecondsOn)
        assertEquals(StreamQuality.BAD, threeSecondsOn)
    }

    @Test
    fun `silence right after a seek is not a stream that has stopped, and silence that outlasts the grace is`() {
        // What the backup engine and a radio report: bytes counted and no buffer at all.
        start()
        seconds(12) { second(buffered = null) }
        monitor.moved(now)

        val excused = seconds(graceSeconds) { second(bytesPerSecond = 0, buffered = null) }
        val silent = seconds(StreamQualityPolicy.STARVED_SILENCE_MS.toInt() / 1_000) { second(bytesPerSecond = 0, buffered = null) }

        assertEquals(StreamQuality.EXCELLENT, excused)
        assertEquals(StreamQuality.BAD, silent)
    }

    @Test
    fun `a stall long after a seek counts at once`() {
        warmedUp()
        monitor.moved(now)
        seconds(graceSeconds + 2) { second() }

        monitor.state(now, buffering, frameShown = true)
        val stalled = seconds(2) { second(bytesPerSecond = 0, buffered = 0) }

        assertEquals(StreamQuality.POOR, stalled)
    }

    @Test
    fun `a second jump gives the buffering that is waiting a fresh grace`() {
        warmedUp()
        seconds(10) { second(buffered = 20_000) }
        monitor.moved(now)
        monitor.state(now, buffering, frameShown = true)
        seconds(5) { second(bytesPerSecond = 0, buffered = 0) }

        monitor.moved(now)
        // Ten seconds from the first jump, and past its grace: only the second jump's keeps the stream from being rated.
        val stillExcused = seconds(5) { second(bytesPerSecond = 0, buffered = 0) }

        assertEquals(StreamQuality.EXCELLENT, stillExcused)
    }

    @Test
    fun `a stall that was under way before the seek stays counted`() {
        warmedUp()
        monitor.state(now, buffering, frameShown = true)
        seconds(2) { second(bytesPerSecond = 0, buffered = 0) }

        monitor.moved(now)
        val after = second(bytesPerSecond = 0, buffered = 0)

        assertEquals(StreamQuality.POOR, after)
    }

    @Test
    fun `an engine that cannot count anything is read by its stalls alone`() {
        start()
        val rated = seconds(12) {
            now += 1_000
            monitor.tick(now, null)
        }
        assertEquals(StreamQuality.EXCELLENT, rated)

        monitor.state(now, buffering, frameShown = true)
        now += 1_500
        assertEquals(StreamQuality.POOR, monitor.tick(now, null))
    }
}
