package com.castivio.core.platform

/** What a line of the strip is about, which the screen turns into a colour. */
enum class StripTone { RUNNING, DONE, FAILED, MUTED }

/**
 * The strip's first line as separate facts, for the card that shows a measurement in a glance -- see `LoadStrip`.
 *
 * **The same session, said again, and nothing measured here.** [LoadTrace.render] fills it from the variables it builds
 * the first line of the strip from, so what the collapsed card says and what the full report says cannot disagree and no
 * figure is taken twice. Plain values, so what a card shows is checked without a device.
 */
data class StripSummary(
    /** The session it is about: a card that is told a new id is a new measurement and begins again. */
    val sessionId: Int,
    /** What was measured and how it began: `CHANNELS · COLD`. */
    val name: String,
    /** The total, to the hundredth, with its unit: `4.97 s`. It is still running while [running] is. */
    val seconds: String,
    /** Where it stands: `done`, `now categories`, `FAILED at streams`, `replaced`. */
    val status: String,
    /** Categories done out of those listed -- `462/462` -- or null for a measurement that has none. */
    val categories: String?,
    val tone: StripTone,
    val running: Boolean,
    /** When it ended, on the monotonic clock in nanoseconds, or null while it runs or where it is not known. */
    val endedAtNanos: Long?,
) {

    /** The second line of the collapsed card: the total, where it stands, and the categories. */
    val detail: String
        get() = buildString {
            append(seconds).append(" · ").append(status)
            categories?.let { append(" · categories ").append(it) }
        }
}

/**
 * How long the card stays after a measurement has ended, and when it goes.
 *
 * Pure: the clock is given. A card is on screen while its measurement runs and for [LINGER_MS] after it ends; it does not
 * go while it is being read -- opened to its full report, or focused with a remote -- and begins its [LINGER_MS] again from
 * the moment the viewer lets go of it.
 */
object StripLinger {

    /**
     * How long a finished measurement stays on screen, in milliseconds. A first choice and not a measurement: long enough to
     * read a line and to reach it with a remote, short enough that it is not there when the viewer has moved on.
     */
    const val LINGER_MS = 20_000L

    private const val NANOS_PER_MS = 1_000_000L

    /**
     * How long until the card goes, in milliseconds -- 0 when it should be gone already -- or null when it is not going:
     * its measurement still runs, or it is [reading]. [touchedAtNanos] is when the viewer last let go of it, if ever, and
     * [seenAtNanos] when it was first drawn, which stands for the end of a measurement that does not say when it ended.
     */
    fun hideInMs(
        summary: StripSummary,
        nowNanos: Long,
        reading: Boolean,
        touchedAtNanos: Long?,
        seenAtNanos: Long,
    ): Long? {
        if (summary.running || reading) return null
        val from = maxOf(summary.endedAtNanos ?: seenAtNanos, touchedAtNanos ?: Long.MIN_VALUE)
        return ((from + LINGER_MS * NANOS_PER_MS - nowNanos) / NANOS_PER_MS).coerceAtLeast(0L)
    }
}
