package com.castivio.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a load that has grown is told about less often, proved without a clock. */
class LoadPaceTest {

    private val base = 1_000L

    @Test
    fun `a small load is told at its own pace`() {
        assertEquals(base, LoadPace.intervalMs(base, 0))
        assertEquals(base, LoadPace.intervalMs(base, 1))
        assertEquals(base, LoadPace.intervalMs(base, 24_999))
    }

    @Test
    fun `the interval lengthens at twenty-five thousand rows and again at seventy-five thousand`() {
        assertEquals(5_000L, LoadPace.intervalMs(base, 25_000))
        assertEquals(5_000L, LoadPace.intervalMs(base, 74_999))
        assertEquals(10_000L, LoadPace.intervalMs(base, 75_000))
        assertEquals(10_000L, LoadPace.intervalMs(base, 400_000))
    }

    /** A refresh is told every ten seconds from its first row; growing is not a reason to tell it sooner. */
    @Test
    fun `a load told rarely already is never told sooner than it was`() {
        assertEquals(10_000L, LoadPace.intervalMs(10_000L, 0))
        assertEquals(10_000L, LoadPace.intervalMs(10_000L, 30_000))
        assertEquals(10_000L, LoadPace.intervalMs(10_000L, 200_000))
        assertEquals("an even slower base is kept", 30_000L, LoadPace.intervalMs(30_000L, 200_000))
    }

    @Test
    fun `the interval never shortens as rows are written`() {
        var last = 0L
        for (rows in 0..300_000 step 500) {
            val interval = LoadPace.intervalMs(base, rows)
            assertTrue("at $rows rows", interval >= last)
            last = interval
        }
    }

    @Test
    fun `the tiers are the ones the load is told about`() {
        assertEquals(25_000, LoadPace.SLOWER_AFTER_ROWS)
        assertEquals(75_000, LoadPace.SLOWEST_AFTER_ROWS)
        assertEquals(5_000L, LoadPace.SLOWER_MS)
        assertEquals(10_000L, LoadPace.SLOWEST_MS)
    }
}
