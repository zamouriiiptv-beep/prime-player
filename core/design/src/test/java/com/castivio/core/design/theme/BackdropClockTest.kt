package com.castivio.core.design.theme

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.platform.InfiniteAnimationPolicy
import kotlin.math.PI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backdrop's clock, run on a clock of our own.
 *
 * `BackdropClock.run()` is the one piece of the backdrop with a moving part in it, and it
 * is three lines: take a frame, decide whether it is one to move on, write the elapsed
 * time. The decision is `BackdropCycle.due`, tested on its own; what is held here is the
 * wiring around it — that the first frame is the start of the cycle, that a frame that is
 * not due writes nothing (which is what makes the backdrop cost half), and that the loop
 * ends when whoever started it goes.
 *
 * The frames are sent by hand, so the test needs neither a device nor a real second.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackdropClockTest {

    private val start = 4_000_000_000_000L // an arbitrary uptime: only differences matter
    private val frame = 16_700_000L // a 60 Hz panel

    @Test
    fun `before the first frame the backdrop is at the start of both cycles`() {
        val clock = BackdropClock()
        assertEquals(0f, clock.wave, 0f)
        assertEquals(0f, clock.drift, 0f)
    }

    @Test
    fun `the first frame is the start of the cycle, wherever the system clock was`() = runTest {
        val frames = BroadcastFrameClock()
        val clock = BackdropClock()
        backgroundScope.launch(frames) { clock.run() }
        runCurrent()

        frames.sendFrame(start)
        runCurrent()

        assertEquals(0f, clock.wave, 0f)
        assertEquals(0f, clock.drift, 0f)
    }

    @Test
    fun `a frame that is not due writes nothing, and the next one that is catches up`() = runTest {
        val frames = BroadcastFrameClock()
        val clock = BackdropClock()
        backgroundScope.launch(frames) { clock.run() }
        runCurrent()

        frames.sendFrame(start)
        runCurrent()

        // 16.7 ms: half a tick. Nothing moves.
        frames.sendFrame(start + frame)
        runCurrent()
        assertEquals(0f, clock.drift, 0f)
        assertEquals(0f, clock.wave, 0f)

        // 33.4 ms: a tick. The clock reads the time since the *first* frame, not since the
        // last write — a skipped frame is not lost time.
        frames.sendFrame(start + 2 * frame)
        runCurrent()
        val elapsedMs = 2 * frame / 1_000_000f
        assertEquals(elapsedMs / Motion.ambientDrift, clock.drift, 1e-6f)
        assertEquals(
            (2.0 * PI * elapsedMs / Motion.ambientWave).toFloat(),
            clock.wave,
            1e-5f,
        )
    }

    @Test
    fun `sixty frames a second move it thirty times`() = runTest {
        val frames = BroadcastFrameClock()
        val clock = BackdropClock()
        backgroundScope.launch(frames) { clock.run() }
        runCurrent()

        var moves = 0
        var seen = -1f
        var now = start
        repeat(120) { // two seconds of 60 Hz frames
            frames.sendFrame(now)
            runCurrent()
            if (clock.drift != seen) {
                moves++
                seen = clock.drift
            }
            now += 1_000_000_000L / 60
        }
        // The very first frame reads zero, which is what `seen` began as not being.
        assertEquals(60, moves)
    }

    @Test
    fun `the wave comes round to where it started, and the drift later still`() = runTest {
        val frames = BroadcastFrameClock()
        val clock = BackdropClock()
        backgroundScope.launch(frames) { clock.run() }
        runCurrent()

        frames.sendFrame(start)
        runCurrent()
        // One full wave later: a whole turn is the same place on the sine.
        frames.sendFrame(start + Motion.ambientWave * 1_000_000L)
        runCurrent()
        assertEquals(0f, clock.wave, 1e-5f)
        // ...and the drift, whose cycle is longer, is not yet round.
        assertTrue(clock.drift > 0.4f)
        assertEquals(Motion.ambientWave.toFloat() / Motion.ambientDrift, clock.drift, 1e-5f)
    }

    /**
     * What a Compose test does to every loop that asked to be infinite: its policy ends the
     * loop rather than let it keep the test from ever being idle. The backdrop's loop must
     * be one of those — `rememberInfiniteTransition` was, and a plain frame callback is not.
     */
    @Test
    fun `a test's policy for infinite animations ends the loop instead of hanging the test`() = runTest {
        val frames = BroadcastFrameClock()
        val clock = BackdropClock()
        val policy = object : InfiniteAnimationPolicy {
            override suspend fun <R> onInfiniteOperation(block: suspend () -> R): R =
                throw CancellationException("infinite animations are off in tests")
        }
        val job = backgroundScope.launch(frames + policy) { clock.run() }
        runCurrent()

        assertTrue(job.isCancelled)
        assertEquals(0f, clock.drift, 0f)
    }

    @Test
    fun `a clock that has been cancelled stops reading frames`() = runTest {
        val frames = BroadcastFrameClock()
        val clock = BackdropClock()
        val job = backgroundScope.launch(frames) { clock.run() }
        runCurrent()

        frames.sendFrame(start)
        runCurrent()
        frames.sendFrame(start + 40_000_000L)
        runCurrent()
        val before = clock.drift
        assertTrue(before > 0f)

        job.cancel()
        runCurrent()
        frames.sendFrame(start + 400_000_000L)
        runCurrent()

        assertEquals(before, clock.drift, 0f)
    }
}
