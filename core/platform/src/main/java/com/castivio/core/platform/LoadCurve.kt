package com.castivio.core.platform

import java.util.Locale

/** One mark of a load's curve: the rows written so far, what they cost the writer's thread so far, and the log's size then. */
data class CurvePoint(val rows: Int, val wallNanos: Long, val cpuNanos: Long, val walBytes: Long)

/**
 * How the cost of a row changed as a load went on, in one line.
 *
 * A load's average hides whether the writer slowed as the table grew or ran at one speed throughout, and the two have
 * different causes. A point is taken for every [STEP_ROWS] rows written; the line gives, for each stretch between two,
 * the microseconds a row took on the writer's thread and, in brackets, how many of them it was on a processor -- then
 * the write-ahead log's size at each point.
 */
object LoadCurve {

    /** A point for every this many rows written. */
    const val STEP_ROWS = 20_000

    /** The figures of each stretch and the log's sizes, or null when the load never wrote [STEP_ROWS] rows. */
    fun text(points: List<CurvePoint>): String? {
        if (points.isEmpty()) return null
        val steps = ArrayList<String>(points.size)
        var rows = 0
        var wall = 0L
        var cpu = 0L
        for (point in points) {
            val count = point.rows - rows
            if (count > 0) {
                steps += "${micros(point.wallNanos - wall, count)} (${micros(point.cpuNanos - cpu, count)})"
            }
            rows = point.rows
            wall = point.wallNanos
            cpu = point.cpuNanos
        }
        val log = points.joinToString(" · ") { if (it.walBytes < 0) "-" else String.format(Locale.ROOT, "%.0f", it.walBytes / 1_000_000.0) }
        return "µs per row (on cpu) per ${STEP_ROWS / 1_000}k rows: ${steps.joinToString(" · ")}; log MB: $log"
    }

    private fun micros(nanos: Long, rows: Int): Long = nanos.coerceAtLeast(0L) / rows / 1_000
}
