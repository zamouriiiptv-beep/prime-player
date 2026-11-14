package com.castivio.tv.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ProbeStatsTest {

    @Test
    fun the_statistics_of_nothing_are_nothing() {
        assertNull(ProbeStats.mean(emptyList()))
        assertNull(ProbeStats.median(emptyList()))
        assertNull(ProbeStats.percentile(emptyList(), 95.0))
        assertNull(ProbeStats.minimum(emptyList()))
        assertNull(ProbeStats.maximum(emptyList()))
    }

    @Test
    fun the_mean_is_the_average() {
        assertEquals(2.0, ProbeStats.mean(listOf(1.0, 2.0, 3.0))!!, 1e-9)
    }

    @Test
    fun the_median_of_an_odd_and_of_an_even_sample() {
        assertEquals(3.0, ProbeStats.median(listOf(5.0, 1.0, 3.0))!!, 0.0)
        assertEquals(2.5, ProbeStats.median(listOf(4.0, 1.0, 3.0, 2.0))!!, 1e-9)
    }

    @Test
    fun the_percentile_is_the_nearest_rank() {
        val values = (1..20).map { it.toDouble() }
        assertEquals(19.0, ProbeStats.percentile(values, 95.0)!!, 0.0)
        assertEquals(10.0, ProbeStats.percentile(values, 50.0)!!, 0.0)
        assertEquals(20.0, ProbeStats.percentile(values, 100.0)!!, 0.0)
        assertEquals(1.0, ProbeStats.percentile(values, 1.0)!!, 0.0)
    }

    @Test
    fun the_percentile_of_one_value_is_that_value() {
        assertEquals(4.1, ProbeStats.percentile(listOf(4.1), 95.0)!!, 0.0)
    }

    @Test
    fun the_percentile_is_always_a_value_that_was_measured() {
        val values = listOf(0.31, 0.88, 0.40, 2.5, 0.35)
        val p95 = ProbeStats.percentile(values, 95.0)!!
        assertEquals(true, p95 in values)
    }

    @Test
    fun the_percentile_does_not_depend_on_the_order() {
        assertEquals(
            ProbeStats.percentile(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 80.0),
            ProbeStats.percentile(listOf(3.0, 1.0, 5.0, 2.0, 4.0), 80.0),
        )
    }

    @Test
    fun a_percentile_outside_the_range_is_refused() {
        assertThrows(IllegalArgumentException::class.java) { ProbeStats.percentile(listOf(1.0), 0.0) }
        assertThrows(IllegalArgumentException::class.java) { ProbeStats.percentile(listOf(1.0), 101.0) }
    }

    @Test
    fun the_extremes() {
        assertEquals(1.5, ProbeStats.minimum(listOf(3.0, 1.5, 2.0))!!, 0.0)
        assertEquals(3.0, ProbeStats.maximum(listOf(3.0, 1.5, 2.0))!!, 0.0)
    }
}
