package com.castivio.tv.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyAndBudgetTest {

    // ------------------------------------------------------------------------------ safety guard

    @Test
    fun a_403_a_429_and_every_5xx_end_the_session() {
        for (status in listOf(403, 429, 500, 501, 502, 503, 504, 599)) {
            assertTrue("HTTP $status", SafetyGuard.stopsSession(status))
        }
    }

    @Test
    fun other_statuses_do_not() {
        for (status in listOf(200, 204, 301, 304, 400, 401, 404, 408, 600)) {
            assertFalse("HTTP $status", SafetyGuard.stopsSession(status))
        }
    }

    // ----------------------------------------------------------------------------------- budget

    private fun budget() = DataBudget(
        plannedDownloads = 10,
        priorReplyBytes = 100L,
        softFactor = 1.05,
        hardFactor = 1.30,
        runawayFactor = 2.0,
    )

    @Test
    fun the_announced_figure_is_the_plan_times_the_reply_as_measured_before() {
        val budget = budget()
        assertEquals(1_000L, budget.announcedBytes)
        assertEquals(1_000L, budget.projectedBytes)
        assertEquals(DataBudget.Level.WITHIN, budget.level())
        assertEquals(200L, budget.runLimitBytes)
    }

    @Test
    fun a_reply_larger_than_announced_raises_the_alert_before_anything_more_is_downloaded() {
        val budget = budget()
        budget.add(200L)
        budget.adoptReference(200L)
        assertEquals(2_000L, budget.projectedBytes)
        assertEquals(400L, budget.runLimitBytes)
        assertEquals(DataBudget.Level.SOFT, budget.level())
    }

    @Test
    fun a_reply_that_is_about_the_size_announced_raises_nothing() {
        val budget = budget()
        budget.adoptReference(102L)
        assertEquals(DataBudget.Level.WITHIN, budget.level())
    }

    @Test
    fun receiving_more_than_announced_alerts_and_much_more_stops() {
        val budget = budget()
        budget.add(1_100L)
        assertEquals(DataBudget.Level.SOFT, budget.level())
        budget.add(300L)
        assertEquals(DataBudget.Level.HARD, budget.level())
    }

    @Test
    fun acknowledging_takes_the_projection_as_the_new_figure_and_a_later_excess_alerts_again() {
        val budget = budget()
        budget.add(200L)
        budget.adoptReference(200L)
        assertEquals(DataBudget.Level.SOFT, budget.level())
        budget.acknowledge()
        assertEquals(DataBudget.Level.WITHIN, budget.level())
        budget.add(2_200L)
        assertEquals(DataBudget.Level.SOFT, budget.level())
        budget.add(500L)
        assertEquals(DataBudget.Level.HARD, budget.level())
    }

    @Test
    fun the_hard_limit_is_not_lifted_by_acknowledging() {
        val budget = budget()
        budget.add(1_400L)
        assertEquals(DataBudget.Level.HARD, budget.level())
        budget.acknowledge()
        assertEquals(DataBudget.Level.HARD, budget.level())
    }

    @Test
    fun the_announced_figure_does_not_move_when_the_reply_is_measured() {
        val budget = budget()
        budget.adoptReference(500L)
        assertEquals(1_000L, budget.announcedBytes)
        assertEquals(5_000L, budget.projectedBytes)
    }

    @Test
    fun a_reply_of_no_bytes_or_less_is_not_a_measurement() {
        val budget = budget()
        budget.adoptReference(0L)
        budget.adoptReference(-5L)
        budget.add(-10L)
        assertEquals(100L, budget.referenceBytes)
        assertEquals(0L, budget.receivedBytes)
    }
}
