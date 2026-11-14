package com.castivio.tv.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbePlanTest {

    private val plan = ProbePlan.standard(3)

    private fun timedBlock(plan: ProbePlan, block: Int): List<ProbeArm> =
        plan.steps.filter { it.purpose == StepPurpose.TIMED && it.block == block }.map { it.arm }

    @Test
    fun three_repeats_are_twenty_full_downloads() {
        assertEquals(20, plan.fullDownloads)
        assertEquals(15, plan.steps.count { it.purpose == StepPurpose.TIMED })
        assertEquals(4, plan.steps.count { it.purpose == StepPurpose.VERIFY })
        assertEquals(1, plan.steps.count { it.purpose == StepPurpose.DRIFT })
    }

    @Test
    fun the_steps_are_numbered_in_order() {
        assertEquals((0 until 20).toList(), plan.steps.map { it.index })
    }

    @Test
    fun the_pilot_is_the_first_repeat_and_then_the_four_verifications() {
        assertEquals(9, plan.pilotSteps)
        val pilot = plan.steps.take(plan.pilotSteps)
        assertEquals(listOf("K1", "K3", "K8", "K4", "W1"), pilot.take(5).map { it.arm.id })
        assertTrue(pilot.take(5).all { it.purpose == StepPurpose.TIMED && it.block == 1 })
        assertEquals(listOf("K1", "K3", "K4", "K8"), pilot.drop(5).map { it.arm.id })
        assertTrue(pilot.drop(5).all { it.purpose == StepPurpose.VERIFY })
    }

    @Test
    fun the_session_pauses_once_and_after_the_pilot() {
        assertEquals(1, plan.steps.count { it.pauseAfter })
        assertTrue(plan.steps[plan.pilotSteps - 1].pauseAfter)
    }

    @Test
    fun every_arm_has_one_timed_run_in_every_repeat() {
        for (block in 1..3) {
            for (arm in ProbeArms.all) {
                val count = plan.steps.count { it.purpose == StepPurpose.TIMED && it.block == block && it.arm == arm }
                assertEquals("$arm in repeat $block", 1, count)
            }
        }
    }

    @Test
    fun the_control_arm_closes_every_repeat() {
        for (block in 1..3) assertEquals(ProbeArms.W1, timedBlock(plan, block).last())
    }

    @Test
    fun the_drift_check_is_the_one_reply_and_comes_last() {
        val last = plan.steps.last()
        assertEquals(StepPurpose.DRIFT, last.purpose)
        assertEquals(ProbeArms.K1, last.arm)
    }

    @Test
    fun the_widest_arm_never_opens_the_session() {
        assertNotEquals(ProbeArms.K8, plan.steps.first().arm)
        assertEquals(ProbeArms.K1, plan.steps.first().arm)
    }

    @Test
    fun four_repeats_stand_every_arm_in_every_position_once() {
        val four = ProbePlan.standard(4)
        val rows = (1..4).map { block -> timedBlock(four, block).filter { it != ProbeArms.W1 } }
        for (position in 0 until 4) {
            val armsAtPosition = rows.map { it[position] }.toSet()
            assertEquals("position $position", 4, armsAtPosition.size)
        }
    }

    @Test
    fun four_repeats_follow_every_arm_with_every_other_once() {
        val four = ProbePlan.standard(4)
        val rows = (1..4).map { block -> timedBlock(four, block).filter { it != ProbeArms.W1 } }
        val pairs = ArrayList<Pair<String, String>>()
        for (row in rows) for (index in 0 until row.size - 1) pairs += row[index].id to row[index + 1].id
        assertEquals(12, pairs.size)
        assertEquals(12, pairs.toSet().size)
    }

    @Test
    fun the_number_of_repeats_is_bounded() {
        assertThrows(IllegalArgumentException::class.java) { ProbePlan.standard(0) }
        assertThrows(IllegalArgumentException::class.java) { ProbePlan.standard(5) }
    }

    @Test
    fun one_repeat_is_the_pilot_and_the_drift_check() {
        val one = ProbePlan.standard(1)
        assertEquals(10, one.fullDownloads)
    }

    @Test
    fun a_step_says_what_it_is() {
        assertEquals("K1 #1", plan.steps[0].label)
        assertEquals("K1 verify", plan.steps[5].label)
        assertEquals("K1 drift check", plan.steps.last().label)
    }
}
