package com.castivio.core.design.theme

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * The arithmetic of the backdrop's motion — no canvas, no Compose, nothing to mock.
 *
 * `Backdrop.kt` draws; this decides *what* it draws and *how often*. They are apart for
 * one reason: the numbers below are the look. A tick rate, a step count or a mote's seed is
 * a decision about how the product looks and feels, and a decision that can only be
 * checked by photographing a device is a decision nobody checks. Here each one is a
 * function a test can call, and `BackdropMotionTest` holds them to what the backdrop
 * has always drawn.
 */

/**
 * Where the backdrop is in its cycles, and when it is worth moving on.
 *
 * The wave and the drift are the same kind of thing — a position in a loop of some
 * length — so they share one clock and differ only in the length they are asked for.
 */
internal object BackdropCycle {

    private const val NANOS_PER_MS = 1_000_000L

    /**
     * How often the backdrop moves, in nanoseconds between two moves.
     *
     * Thirty a second, not sixty. The slowest line on the screen travels about a pixel
     * between two moves at this rate (`BackdropMotionTest` holds it to that), and a
     * line that travels a pixel is a line that cannot be seen to step. What halving the
     * rate buys is half the drawing, on every device that animates it.
     *
     * Not the exact frame time, but a little under it: a frame arrives every 16.7 ms
     * on a 60 Hz panel, so asking for 30 ms takes every second frame, while asking for
     * 33.3 ms would take every second frame only until a frame came early.
     */
    const val TICK_NANOS = 30 * NANOS_PER_MS

    /** "No move yet" — the value a clock holds before its first frame. */
    const val NEVER = Long.MIN_VALUE

    /**
     * Whether a frame that arrived at [nowNanos] is the one to move on.
     *
     * A frame time that is *earlier* than the last move is not a reason to wait for the
     * clock to catch up: it is a clock that was restarted, and the backdrop moves on
     * from where it is.
     */
    fun due(nowNanos: Long, lastNanos: Long): Boolean {
        if (lastNanos == NEVER) return true
        val gap = nowNanos - lastNanos
        return gap < 0L || gap >= TICK_NANOS
    }

    /**
     * How far through a cycle of [periodMs] a clock that has run for [elapsedNanos] is:
     * 0 at the start, approaching 1 and never reaching it — at 1 it is 0 again.
     *
     * Integer remainder first, then a division: elapsed time is a `Long` that only grows,
     * and a `Float` of a day's worth of nanoseconds has no digits left for the position.
     */
    fun fraction(elapsedNanos: Long, periodMs: Int): Float {
        val period = periodMs * NANOS_PER_MS
        val within = elapsedNanos.mod(period)
        val fraction = (within.toDouble() / period.toDouble()).toFloat()
        // A remainder one nanosecond short of the period can round up to exactly 1.
        return if (fraction < 1f) fraction else 0f
    }

    /** The wave's phase, in radians, for a position [fraction] of the way round its cycle. */
    fun radians(fraction: Float): Float = (2.0 * PI * fraction).toFloat()
}

/**
 * The contour mesh across the lower half of the screen: sixteen-plus-one waves in
 * perspective, each a sine whose row, swing and weight grow towards the viewer.
 *
 * The constants and the three small functions are the ones the backdrop drew with from
 * the start; only [STEPS] changed, and `BackdropMotionTest` measures by how much.
 */
internal object BackdropMesh {

    /** Waves are indexed `0..LINES`, so there are `LINES + 1` of them. */
    const val LINES = 16

    /**
     * Straight segments per wave.
     *
     * Sixty-four of them was more than the eye can use: at the strongest swing (51 px) a
     * wave of this wavelength departs from its chord by `51 · (11/STEPS)² / 8` pixels —
     * 0.19 px at 64 steps, 0.75 px at 32 — on a line 1.2 px wide and at most 9.5 %
     * opaque. Half the segments is half the points to build, to stroke and to tessellate
     * on every move, for a curve nobody can tell from the first.
     */
    const val STEPS = 32

    /** Where the mesh begins, as a fraction of the ground's height from its top. */
    const val HORIZON = 0.40f

    /** Radians of sine across the full width: a little under two full waves. */
    const val WAVES = 11f

    /** How much further round the cycle each wave is than the one behind it. */
    const val LINE_SHIFT = 0.6f

    /** Stroke width in pixels. */
    const val STROKE = 1.2f

    /** Depth of wave [line]: 0 at the horizon, 1 at the bottom edge. */
    fun depth(line: Int): Float = line / LINES.toFloat()

    /** The row wave of [depth] is centred on, between [top] and [bottom] of the ground. */
    fun row(top: Float, bottom: Float, depth: Float): Float {
        val horizon = top + (bottom - top) * HORIZON
        return horizon + (bottom - horizon) * (depth * depth)
    }

    /** How far, in pixels, a wave of [depth] swings above and below its row. */
    fun amplitude(depth: Float): Float = 7f + 44f * depth

    /** How opaque a wave of [depth] is: faint at the horizon, strongest at the viewer. */
    fun alpha(depth: Float): Float = 0.025f + 0.07f * depth

    /**
     * Where a wave stands, between -1 and 1, at [across] of the way along it — 0 on the
     * left edge, 1 on the right — for [line], [phase] radians into the cycle.
     */
    fun swing(across: Float, line: Int, phase: Float): Float =
        sin(across * WAVES + line * LINE_SHIFT + phase)
}

/** One drifting point of light. All numbers are fractions or pixels, none are colours. */
internal class Mote(
    /** Position across the ground, 0..1. */
    val x: Float,
    /** Position down the ground at the start of the cycle, 0..1. */
    val y: Float,
    /** How many times faster than the slowest it rises: 0.5..1.5. */
    val speed: Float,
    /** Radius in pixels. */
    val radius: Float,
    /** How far round its twinkle it starts, 0..1. */
    val phase: Float,
)

/**
 * The motes: thirteen faint points that rise, wrap and twinkle.
 *
 * They used to be drawn from a `Random(7)` that was made afresh on every frame and asked
 * for the same sixty-five numbers in the same order, sixty times a second. A seeded
 * generator is a table with a slow way of reading it, so this is the table — built once,
 * from the same seed in the same order, which is what keeps every mote where it was.
 */
internal object BackdropMotes {

    const val COUNT = 13
    private const val SEED = 7

    /** Peak opacity of a mote, before the twinkle takes its share. */
    const val ALPHA = 0.20f

    /** Read by index, in the hot path, so an array rather than a list with an iterator. */
    val all: Array<Mote> = build()

    private fun build(): Array<Mote> {
        val rng = Random(SEED)
        return Array(COUNT) {
            val x = rng.nextFloat()
            val y = rng.nextFloat()
            val speed = 0.5f + rng.nextFloat()
            val radius = 1.4f + rng.nextFloat() * 2.2f
            val phase = rng.nextFloat()
            Mote(x, y, speed, radius, phase)
        }
    }

    /** How far down the ground [mote] is when the drift is [drift] of the way round: rises, then wraps. */
    fun height(mote: Mote, drift: Float): Float = ((mote.y - drift * mote.speed) % 1f + 1f) % 1f

    /** How much of [ALPHA] [mote] is showing: between a quarter and four fifths of it. */
    fun twinkle(mote: Mote, drift: Float): Float =
        0.25f + 0.55f * abs(sin((drift + mote.phase) * 2f * PI.toFloat()))
}
