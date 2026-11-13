package com.castivio.core.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** How a load's cost per row is told stretch by stretch. */
class LoadCurveTest {

    @Test
    fun `a load that never wrote a stretch has no curve`() {
        assertNull(LoadCurve.text(emptyList()))
    }

    @Test
    fun `each stretch is the microseconds a row took in it, with the processor's share beside, and the log's size at each point`() {
        // 20,000 rows in 6 s (3 s on a processor), then 20,000 more in 14 s (4 s on a processor).
        val points = listOf(
            CurvePoint(rows = 20_000, wallNanos = 6_000_000_000L, cpuNanos = 3_000_000_000L, walBytes = 21_000_000L),
            CurvePoint(rows = 40_000, wallNanos = 20_000_000_000L, cpuNanos = 7_000_000_000L, walBytes = 150_000_000L),
        )

        assertEquals(
            "µs per row (on cpu) per 20k rows: 300 (150) · 700 (200); log MB: 21 · 150",
            LoadCurve.text(points),
        )
    }

    @Test
    fun `a log that could not be measured is a dash`() {
        val points = listOf(CurvePoint(rows = 20_000, wallNanos = 2_000_000_000L, cpuNanos = 1_000_000_000L, walBytes = -1L))

        assertEquals("µs per row (on cpu) per 20k rows: 100 (50); log MB: -", LoadCurve.text(points))
    }

    @Test
    fun `a stretch that wrote nothing is left out and does not divide by zero`() {
        val points = listOf(
            CurvePoint(rows = 20_000, wallNanos = 2_000_000_000L, cpuNanos = 1_000_000_000L, walBytes = 5_000_000L),
            CurvePoint(rows = 20_000, wallNanos = 2_000_000_000L, cpuNanos = 1_000_000_000L, walBytes = 6_000_000L),
        )

        assertEquals("µs per row (on cpu) per 20k rows: 100 (50); log MB: 5 · 6", LoadCurve.text(points))
    }
}
