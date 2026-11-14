package com.castivio.tv.probe.core

import com.castivio.tv.probe.support.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionRuleTest {

    private val thresholds = Thresholds()

    /** The runs of [arm], one in each repeat from the first, at [times]. */
    private fun runs(arm: ProbeArm, vararg times: Long, shown: Boolean = true): List<RunRecord> =
        times.mapIndexed { index, ms ->
            Fixtures.run(arm, block = index + 1, totalMs = ms, evidence = Fixtures.evidence(arm, shown))
        }

    private fun baseline(): List<RunRecord> = runs(ProbeArms.K1, 60_000L, 60_000L, 60_000L)

    private fun complete(vararg arms: ProbeArm): Map<String, Reconciliation.Result> =
        arms.associate { it.id to Fixtures.completeResult(it) }

    private fun decide(runs: List<RunRecord>, results: Map<String, Reconciliation.Result>): Decision =
        DecisionRule.evaluate(runs, results, thresholds)

    private fun Decision.verdict(arm: ProbeArm): ArmVerdict = verdicts.first { it.arm == arm }

    private fun ArmVerdict.criterion(name: String): Criterion = criteria.first { it.name == name }

    @Test
    fun a_clear_repeated_complete_error_free_improvement_is_adopted() {
        val decision = decide(
            baseline() + runs(ProbeArms.K3, 36_000L, 36_000L, 36_000L),
            complete(ProbeArms.K3),
        )
        assertEquals(ArmStatus.PASS, decision.verdict(ProbeArms.K3).status)
        assertEquals(DecisionOutcome.ADOPT, decision.outcome)
        assertEquals(ProbeArms.K3, decision.chosen)
    }

    @Test
    fun a_median_gain_under_the_threshold_fails() {
        val decision = decide(
            baseline() + runs(ProbeArms.K3, 50_000L, 50_000L, 50_000L),
            complete(ProbeArms.K3),
        )
        val verdict = decision.verdict(ProbeArms.K3)
        assertEquals(ArmStatus.FAIL, verdict.status)
        assertEquals(false, verdict.criterion("median faster").met)
    }

    @Test
    fun one_slow_repeat_fails_even_when_the_median_is_good() {
        val decision = decide(
            baseline() + runs(ProbeArms.K3, 30_000L, 30_000L, 58_000L),
            complete(ProbeArms.K3),
        )
        val verdict = decision.verdict(ProbeArms.K3)
        assertEquals(true, verdict.criterion("median faster").met)
        assertEquals(false, verdict.criterion("every repeat faster").met)
        assertEquals(ArmStatus.FAIL, verdict.status)
    }

    @Test
    fun a_failed_request_disqualifies_the_arm() {
        val k3 = listOf(
            Fixtures.run(ProbeArms.K3, 1, 36_000L),
            Fixtures.run(ProbeArms.K3, 2, 36_000L, failures = 1, invalid = listOf(InvalidReason.FAILED_REQUESTS)),
            Fixtures.run(ProbeArms.K3, 3, 36_000L),
        )
        val decision = decide(baseline() + k3, complete(ProbeArms.K3))
        val verdict = decision.verdict(ProbeArms.K3)
        assertEquals(false, verdict.criterion("no errors").met)
        assertEquals(ArmStatus.FAIL, verdict.status)
        assertNull(verdict.criterion("median faster").met)
    }

    @Test
    fun a_status_that_stopped_the_provider_is_an_error_too() {
        val k3 = listOf(
            Fixtures.run(ProbeArms.K3, 1, 36_000L),
            Fixtures.run(ProbeArms.K3, 2, 36_000L),
            Fixtures.run(
                ProbeArms.K3, 3, 36_000L,
                invalid = listOf(InvalidReason.PROVIDER_STOP), stopStatus = 429,
            ),
        )
        val verdict = decide(baseline() + k3, complete(ProbeArms.K3)).verdict(ProbeArms.K3)
        assertEquals(false, verdict.criterion("no errors").met)
    }

    @Test
    fun an_arm_that_is_not_complete_is_not_adopted_however_fast() {
        val decision = decide(
            baseline() + runs(ProbeArms.K3, 30_000L, 30_000L, 30_000L),
            mapOf("K3" to Fixtures.orphanResult(ProbeArms.K3)),
        )
        val verdict = decision.verdict(ProbeArms.K3)
        assertEquals(false, verdict.criterion("complete").met)
        assertEquals(ArmStatus.FAIL, verdict.status)
        assertNull(decision.chosen)
    }

    @Test
    fun a_difference_that_waits_for_the_drift_check_leaves_the_verdict_open() {
        val decision = decide(
            baseline() + runs(ProbeArms.K3, 30_000L, 30_000L, 30_000L),
            mapOf("K3" to Fixtures.pendingResult(ProbeArms.K3)),
        )
        val verdict = decision.verdict(ProbeArms.K3)
        assertNull(verdict.criterion("complete").met)
        assertEquals(ArmStatus.OPEN, verdict.status)
        assertEquals(DecisionOutcome.INCONCLUSIVE, decision.outcome)
    }

    @Test
    fun without_a_verification_completeness_cannot_be_told() {
        val decision = decide(baseline() + runs(ProbeArms.K3, 30_000L, 30_000L, 30_000L), emptyMap())
        assertNull(decision.verdict(ProbeArms.K3).criterion("complete").met)
        assertEquals(ArmStatus.OPEN, decision.verdict(ProbeArms.K3).status)
    }

    @Test
    fun parallelism_that_was_not_shown_fails() {
        val decision = decide(
            baseline() + runs(ProbeArms.K3, 30_000L, 30_000L, 30_000L, shown = false),
            complete(ProbeArms.K3),
        )
        val verdict = decision.verdict(ProbeArms.K3)
        assertEquals(false, verdict.criterion("parallelism shown").met)
        assertEquals(ArmStatus.FAIL, verdict.status)
    }

    @Test
    fun one_run_of_each_is_not_enough_to_decide_anything() {
        val decision = decide(
            runs(ProbeArms.K1, 60_000L) + runs(ProbeArms.K3, 30_000L),
            complete(ProbeArms.K3),
        )
        val verdict = decision.verdict(ProbeArms.K3)
        assertNull(verdict.criterion("median faster").met)
        assertNull(verdict.criterion("every repeat faster").met)
        assertEquals(ArmStatus.OPEN, verdict.status)
        assertEquals(DecisionOutcome.INCONCLUSIVE, decision.outcome)
        assertNull(decision.chosen)
    }

    @Test
    fun the_baseline_needs_its_own_valid_runs() {
        val k1 = listOf(
            Fixtures.run(ProbeArms.K1, 1, 60_000L),
            Fixtures.run(ProbeArms.K1, 2, 60_000L, invalid = listOf(InvalidReason.CONTAMINATED)),
            Fixtures.run(ProbeArms.K1, 3, 60_000L),
        )
        val decision = decide(k1 + runs(ProbeArms.K3, 30_000L, 30_000L, 30_000L), complete(ProbeArms.K3))
        val verdict = decision.verdict(ProbeArms.K3)
        assertNull(verdict.criterion("median faster").met)
        assertNull(verdict.criterion("every repeat faster").met)
    }

    @Test
    fun the_runs_are_paired_by_repeat() {
        // K1's second repeat was slow: the pair for that repeat is the slow one, not the average.
        val k1 = runs(ProbeArms.K1, 60_000L, 100_000L, 60_000L)
        val k3 = runs(ProbeArms.K3, 40_000L, 40_000L, 40_000L)
        val verdict = decide(k1 + k3, complete(ProbeArms.K3)).verdict(ProbeArms.K3)
        assertEquals(true, verdict.criterion("every repeat faster").met)
        assertEquals(true, verdict.criterion("median faster").met)
    }

    @Test
    fun nothing_is_adopted_when_every_candidate_fails() {
        val decision = decide(
            baseline() +
                runs(ProbeArms.K3, 58_000L, 58_000L, 58_000L) +
                runs(ProbeArms.K4, 59_000L, 59_000L, 59_000L) +
                runs(ProbeArms.K8, 61_000L, 61_000L, 61_000L),
            complete(ProbeArms.K3, ProbeArms.K4, ProbeArms.K8),
        )
        assertEquals(DecisionOutcome.NOT_ADOPTED, decision.outcome)
        assertNull(decision.chosen)
    }

    @Test
    fun a_wider_arm_replaces_a_narrower_one_only_when_clearly_faster_again() {
        val decision = decide(
            baseline() +
                runs(ProbeArms.K3, 36_000L, 36_000L, 36_000L) +
                runs(ProbeArms.K4, 30_000L, 30_000L, 30_000L) +
                runs(ProbeArms.K8, 29_000L, 29_000L, 29_000L),
            complete(ProbeArms.K3, ProbeArms.K4, ProbeArms.K8),
        )
        assertEquals(DecisionOutcome.ADOPT, decision.outcome)
        assertEquals(ProbeArms.K4, decision.chosen)
        assertTrue(decision.notes.any { it.startsWith("K4 replaces K3") })
        assertTrue(decision.notes.any { it.startsWith("K8 is not clearly faster than K4") })
    }

    @Test
    fun a_wider_arm_that_is_barely_faster_is_not_chosen_and_the_narrower_is_preferred() {
        val decision = decide(
            baseline() +
                runs(ProbeArms.K3, 36_000L, 36_000L, 36_000L) +
                runs(ProbeArms.K4, 34_000L, 34_000L, 34_000L) +
                runs(ProbeArms.K8, 33_000L, 33_000L, 33_000L),
            complete(ProbeArms.K3, ProbeArms.K4, ProbeArms.K8),
        )
        assertEquals(ProbeArms.K3, decision.chosen)
        assertEquals(2, decision.notes.size)
        assertTrue(decision.notes.all { it.contains("K3 is preferred") })
    }

    @Test
    fun a_wider_arm_that_is_faster_in_the_median_but_not_in_every_repeat_is_not_chosen() {
        val decision = decide(
            baseline() +
                runs(ProbeArms.K3, 36_000L, 36_000L, 36_000L) +
                runs(ProbeArms.K8, 25_000L, 25_000L, 35_000L),
            complete(ProbeArms.K3, ProbeArms.K8),
        )
        assertEquals(ProbeArms.K3, decision.chosen)
    }

    @Test
    fun films_under_several_categories_are_reported_and_do_not_decide() {
        val decision = decide(
            baseline() + runs(ProbeArms.K3, 30_000L, 30_000L, 30_000L),
            mapOf("K3" to Fixtures.duplicateResult(ProbeArms.K3)),
        )
        val verdict = decision.verdict(ProbeArms.K3)
        val duplicates = verdict.criterion("no film under several categories")
        assertEquals(false, duplicates.met)
        assertFalse(duplicates.blocking)
        assertEquals(ArmStatus.PASS, verdict.status)
        assertEquals(ProbeArms.K3, decision.chosen)
    }

    @Test
    fun the_control_arm_and_the_baseline_are_never_candidates() {
        val decision = decide(baseline() + runs(ProbeArms.W1, 10_000L, 10_000L, 10_000L), emptyMap())
        assertEquals(listOf("K3", "K4", "K8"), decision.verdicts.map { it.arm.id })
        assertNotNull(decision)
    }

    @Test
    fun verification_and_drift_runs_never_enter_the_timing() {
        val verification = Fixtures.run(
            ProbeArms.K3, 0, 1L, purpose = StepPurpose.VERIFY, evidence = Fixtures.evidence(ProbeArms.K3),
        )
        val decision = decide(baseline() + runs(ProbeArms.K3, 50_000L, 50_000L, 50_000L) + verification, complete(ProbeArms.K3))
        // A one-millisecond "run" would have made K3 the fastest of all.
        assertEquals(false, decision.verdict(ProbeArms.K3).criterion("median faster").met)
    }

    @Test
    fun the_thresholds_are_the_ones_the_owner_approved() {
        assertEquals(0.25, thresholds.medianImprovement, 0.0)
        assertEquals(0.15, thresholds.repeatImprovement, 0.0)
        assertEquals(0.15, thresholds.widerImprovement, 0.0)
        assertEquals(3, thresholds.minPairs)
    }
}
