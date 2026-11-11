package com.castivio.core.design.theme

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backdrop's motion, as arithmetic.
 *
 * Two kinds of test live here. Some pin a rule the backdrop is built on — a cycle repeats,
 * a tick is due at most thirty times a second. The rest pin **the look**: the numbers that
 * decide where a line or a mote is drawn are held to what the backdrop drew before they
 * were taken out of the draw function, each against a copy of the old expression written
 * out below. A test that computed the expected value from the same code would agree with
 * a mistake as readily as with the intent, so the reference is a second copy, not a call.
 */
class BackdropMotionTest {

    private val waveMs = Motion.ambientWave
    private val driftMs = Motion.ambientDrift

    // ------------------------------------------------------------------ the cycles

    @Test
    fun `a cycle starts at zero, and one period later is at zero again`() {
        assertEquals(0f, BackdropCycle.fraction(0L, waveMs), 0f)
        assertEquals(0f, BackdropCycle.fraction(waveMs * 1_000_000L, waveMs), 0f)
        assertEquals(0f, BackdropCycle.fraction(5 * waveMs * 1_000_000L, waveMs), 0f)
    }

    @Test
    fun `a quarter of the way through a cycle reads one quarter`() {
        val quarter = waveMs * 1_000_000L / 4
        assertEquals(0.25f, BackdropCycle.fraction(quarter, waveMs), 1e-6f)
        assertEquals(0.25f, BackdropCycle.fraction(3 * waveMs * 1_000_000L + quarter, waveMs), 1e-6f)
    }

    @Test
    fun `a cycle never reaches one`() {
        val period = waveMs * 1_000_000L
        assertTrue(BackdropCycle.fraction(period - 1, waveMs) < 1f)
        // Rounding a remainder one nanosecond short of the period must not produce 1.
        for (k in 1..50) {
            val f = BackdropCycle.fraction(k * period - 1, waveMs)
            assertTrue("$f", f in 0f..1f && f < 1f)
        }
    }

    @Test
    fun `a cycle repeats exactly`() {
        for (t in longArrayOf(0L, 1L, 333_333_333L, 4_000_000_001L, 10_999_999_999L)) {
            val a = BackdropCycle.fraction(t, waveMs)
            val b = BackdropCycle.fraction(t + 7 * waveMs * 1_000_000L, waveMs)
            assertEquals(a, b, 1e-6f)
        }
    }

    @Test
    fun `a clock that has run for days still knows where it is`() {
        // 40 days of nanoseconds: a Float of this has no digits left for a position, so the
        // remainder has to be taken before the division and not after it.
        val days = 40L * 24 * 3_600 * 1_000_000_000L
        val quarter = waveMs * 1_000_000L / 4
        // 40 days is a whole number of 11 s periods only if 11 divides 40·86400 — it does not,
        // so compare against the integer remainder, which is the definition.
        val expected = ((days + quarter) % (waveMs * 1_000_000L)).toDouble() / (waveMs * 1_000_000L)
        assertEquals(expected.toFloat(), BackdropCycle.fraction(days + quarter, waveMs), 1e-6f)
    }

    @Test
    fun `the wave and the drift are the same clock at two lengths`() {
        val t = 5_500_000_000L
        assertEquals(0.5f, BackdropCycle.fraction(t, waveMs), 1e-6f)
        assertEquals(5_500f / driftMs, BackdropCycle.fraction(t, driftMs), 1e-6f)
    }

    @Test
    fun `radians run from zero to a full turn`() {
        assertEquals(0f, BackdropCycle.radians(0f), 0f)
        assertEquals(PI.toFloat(), BackdropCycle.radians(0.5f), 1e-6f)
        assertTrue(BackdropCycle.radians(0.999999f) < (2.0 * PI).toFloat())
    }

    /**
     * The seam at the end of a cycle. The wave restarts from zero where it had reached a
     * full turn, which is only invisible because `sin` does not care.
     */
    @Test
    fun `the end of the wave meets its start`() {
        for (line in 0..BackdropMesh.LINES) {
            for (x in listOf(0f, 0.3f, 1f)) {
                val end = BackdropMesh.swing(x, line, BackdropCycle.radians(0.9999999f))
                val start = BackdropMesh.swing(x, line, BackdropCycle.radians(0f))
                assertEquals(start, end, 1e-4f)
            }
        }
    }

    // ------------------------------------------------------------------ how often

    @Test
    fun `the first frame always moves the backdrop`() {
        assertTrue(BackdropCycle.due(123L, BackdropCycle.NEVER))
        assertTrue(BackdropCycle.due(0L, BackdropCycle.NEVER))
    }

    @Test
    fun `a frame too soon after the last move leaves it alone`() {
        val last = 1_000_000_000L
        assertFalse(BackdropCycle.due(last + 1, last))
        assertFalse(BackdropCycle.due(last + 16_700_000L, last))
        assertFalse(BackdropCycle.due(last + BackdropCycle.TICK_NANOS - 1, last))
        assertTrue(BackdropCycle.due(last + BackdropCycle.TICK_NANOS, last))
        assertTrue(BackdropCycle.due(last + 100_000_000L, last))
    }

    @Test
    fun `a frame time earlier than the last move is a restarted clock, not a wait`() {
        assertTrue(BackdropCycle.due(500L, 1_000_000_000L))
    }

    /** Frames a display of [hz] shows in [seconds], as the times Choreographer would report. */
    private fun movesIn(hz: Int, seconds: Int): Int {
        val frame = 1_000_000_000L / hz
        var last = BackdropCycle.NEVER
        var moves = 0
        var now = 4_000_000_000_000L // a large, arbitrary uptime
        repeat(hz * seconds) {
            if (BackdropCycle.due(now, last)) {
                last = now
                moves++
            }
            now += frame
        }
        return moves
    }

    @Test
    fun `a sixty hertz panel moves the backdrop thirty times a second`() {
        assertEquals(30 * 4, movesIn(60, 4))
    }

    @Test
    fun `faster panels do not move it faster`() {
        assertEquals(30 * 4, movesIn(90, 4))
        assertEquals(30 * 4, movesIn(120, 4))
    }

    @Test
    fun `slower panels move it at the panels own pace`() {
        // 30 Hz: every frame is 33.3 ms after the last, which is past the 30 ms floor.
        assertEquals(30 * 4, movesIn(30, 4))
        // 50 Hz: 20 ms is not enough, 40 ms is.
        assertEquals(25 * 4, movesIn(50, 4))
    }

    /**
     * **Why thirty and not fewer.** The rate is only free to be this low because of how
     * little a wave moves between two moves. Holding that to a number is what stops a
     * later "let's make it 10 Hz, nobody will notice" from being a thing that nobody
     * notices until it is on a television.
     */
    @Test
    fun `one move shifts no wave by more than a pixel`() {
        val move = BackdropCycle.radians(BackdropCycle.TICK_NANOS.toFloat() / (waveMs * 1_000_000f))
        val strongest = BackdropMesh.amplitude(BackdropMesh.depth(BackdropMesh.LINES))

        var worst = 0f
        for (line in 0..BackdropMesh.LINES) {
            val amp = BackdropMesh.amplitude(BackdropMesh.depth(line))
            for (xi in 0..200) {
                val x = xi / 200f
                for (pi in 0..120) {
                    val phase = BackdropCycle.radians(pi / 120f)
                    val shift = abs(
                        amp * BackdropMesh.swing(x, line, phase + move) -
                            amp * BackdropMesh.swing(x, line, phase),
                    )
                    if (shift > worst) worst = shift
                }
            }
        }
        assertEquals(51f, strongest, 1e-4f)
        assertTrue("a wave moved $worst px in one step", worst <= 1.05f)
    }

    // ------------------------------------------------------------------ the mesh is the same mesh

    /** The expressions `mesh` used when it was inline in the draw function, verbatim. */
    private object OldMesh {
        fun y(top: Float, bottom: Float, height: Float, i: Int): Float {
            val horizon = top + height * 0.40f
            val t = i / 16f
            return horizon + (bottom - horizon) * (t * t)
        }

        fun amp(i: Int): Float = 7f + 44f * (i / 16f)
        fun alpha(i: Int): Float = 0.025f + 0.07f * (i / 16f)
        fun yy(y: Float, amp: Float, p: Float, i: Int, phase: Float): Float =
            y + amp * sin(p * 11f + i * 0.6f + phase)
    }

    @Test
    fun `every wave sits on the row, with the swing and the weight it always had`() {
        for ((top, bottom) in listOf(0f to 2400f, 0f to 385f, -150f to 235f, 12.5f to 1080.25f)) {
            for (i in 0..16) {
                val depth = BackdropMesh.depth(i)
                assertEquals(
                    OldMesh.y(top, bottom, bottom - top, i),
                    BackdropMesh.row(top, bottom, depth),
                    1e-3f,
                )
                assertEquals(OldMesh.amp(i), BackdropMesh.amplitude(depth), 0f)
                assertEquals(OldMesh.alpha(i), BackdropMesh.alpha(depth), 0f)
            }
        }
    }

    @Test
    fun `the swing is the sine the mesh always drew`() {
        for (i in 0..16) {
            for (phase in floatArrayOf(0f, 0.7f, 3.1f, 6.2f)) {
                for (p in floatArrayOf(0f, 0.25f, 0.5f, 0.9f, 1f)) {
                    val y = 300f
                    val amp = OldMesh.amp(i)
                    assertEquals(
                        OldMesh.yy(y, amp, p, i, phase),
                        y + amp * BackdropMesh.swing(p, i, phase),
                        0f,
                    )
                }
            }
        }
    }

    @Test
    fun `the first wave is on the horizon and the last on the bottom edge`() {
        assertEquals(0.40f * 1080f, BackdropMesh.row(0f, 1080f, BackdropMesh.depth(0)), 1e-3f)
        assertEquals(1080f, BackdropMesh.row(0f, 1080f, BackdropMesh.depth(16)), 1e-3f)
    }

    @Test
    fun `no wave is stronger than a tenth opaque or fainter than a fortieth`() {
        for (i in 0..16) {
            val a = BackdropMesh.alpha(BackdropMesh.depth(i))
            assertTrue("$i: $a", a in 0.025f..0.095f + 1e-6f)
        }
    }

    /**
     * **The look, measured.** Thirty-two straight segments against the curve they stand
     * for — at the size of a 4K television, where the same 51 px swing is the smallest
     * part of the picture and a flaw would show soonest.
     */
    @Test
    fun `thirty-two segments stay within a pixel of the curve`() {
        val worst = worstDeviation(BackdropMesh.STEPS)
        assertTrue("worst deviation was $worst px", worst <= 1.0f)
    }

    @Test
    fun `and within a pixel of the sixty-four the mesh used to be drawn with`() {
        var worst = 0f
        for (i in 0..BackdropMesh.LINES) {
            val amp = BackdropMesh.amplitude(BackdropMesh.depth(i))
            for (phase in floatArrayOf(0f, 1.3f, 2.9f, 4.4f, 5.9f)) {
                for (xi in 0..640) {
                    val p = xi / 640f
                    val d = abs(
                        polyline(amp, i, phase, p, BackdropMesh.STEPS) -
                            polyline(amp, i, phase, p, 64),
                    )
                    if (d > worst) worst = d
                }
            }
        }
        assertTrue("worst difference was $worst px", worst <= 1.0f)
    }

    @Test
    fun `fewer segments than this would show`() {
        // The bound is not slack. At 24 steps the same measure is over a pixel and a quarter:
        // lowering STEPS again is a decision about the look, and this is where it is noticed.
        assertTrue(worstDeviation(24) > 1.0f)
    }

    /** Height of the piecewise-linear wave of [steps] segments at [p], less its row. */
    private fun polyline(amp: Float, line: Int, phase: Float, p: Float, steps: Int): Float {
        val s = (p * steps).toInt().coerceAtMost(steps - 1)
        val p0 = s / steps.toFloat()
        val p1 = (s + 1) / steps.toFloat()
        val y0 = amp * BackdropMesh.swing(p0, line, phase)
        val y1 = amp * BackdropMesh.swing(p1, line, phase)
        return y0 + (y1 - y0) * ((p - p0) / (p1 - p0))
    }

    private fun worstDeviation(steps: Int): Float {
        var worst = 0f
        for (i in 0..BackdropMesh.LINES) {
            val amp = BackdropMesh.amplitude(BackdropMesh.depth(i))
            for (phase in floatArrayOf(0f, 1.3f, 2.9f, 4.4f, 5.9f)) {
                for (xi in 0..640) {
                    val p = xi / 640f
                    val exact = amp * BackdropMesh.swing(p, i, phase)
                    val d = abs(exact - polyline(amp, i, phase, p, steps))
                    if (d > worst) worst = d
                }
            }
        }
        return worst
    }

    // ------------------------------------------------------------------ the motes are the same motes

    @Test
    fun `there are thirteen motes, and they are the ones the seed always gave`() {
        assertEquals(13, BackdropMotes.all.size)

        // The old draw function, verbatim: a generator made afresh on every frame.
        val rng = Random(7)
        repeat(13) { k ->
            val baseX = rng.nextFloat()
            val baseY = rng.nextFloat()
            val speed = 0.5f + rng.nextFloat()
            val radius = 1.4f + rng.nextFloat() * 2.2f
            val phase = rng.nextFloat()
            val mote = BackdropMotes.all[k]
            assertEquals("x of $k", baseX, mote.x, 0f)
            assertEquals("y of $k", baseY, mote.y, 0f)
            assertEquals("speed of $k", speed, mote.speed, 0f)
            assertEquals("radius of $k", radius, mote.radius, 0f)
            assertEquals("phase of $k", phase, mote.phase, 0f)
        }
    }

    @Test
    fun `a mote rises with the drift, wraps at the top, and never leaves the ground`() {
        for (mote in BackdropMotes.all) {
            for (step in 0..260) {
                val drift = step / 260f
                val y = BackdropMotes.height(mote, drift)
                assertTrue("$y", y >= 0f && y < 1f + 1e-6f)
                // The expression the draw function used, verbatim.
                assertEquals(((mote.y - drift * mote.speed) % 1f + 1f) % 1f, y, 0f)
            }
        }
    }

    @Test
    fun `a mote twinkles between a quarter and four fifths of its brightness`() {
        for (mote in BackdropMotes.all) {
            for (step in 0..260) {
                val drift = step / 260f
                val t = BackdropMotes.twinkle(mote, drift)
                assertTrue("$t", t in 0.25f..0.80f + 1e-6f)
                assertEquals(
                    0.25f + 0.55f * abs(sin((drift + mote.phase) * 2f * PI.toFloat())),
                    t,
                    0f,
                )
            }
        }
        // And so at its brightest it is a fifth of the colour's own opacity.
        assertEquals(0.16f, BackdropMotes.ALPHA * 0.80f, 1e-6f)
    }
}
