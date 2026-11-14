package com.castivio.tv.probe.session

import com.castivio.tv.probe.core.DataBudget
import com.castivio.tv.probe.core.DecisionOutcome
import com.castivio.tv.probe.core.InvalidReason
import com.castivio.tv.probe.core.OneReplyScan
import com.castivio.tv.probe.core.PlanStep
import com.castivio.tv.probe.core.ProbeArm
import com.castivio.tv.probe.core.ProbeArms
import com.castivio.tv.probe.core.ProbeConfig
import com.castivio.tv.probe.core.ProbePlan
import com.castivio.tv.probe.core.Reconciliation
import com.castivio.tv.probe.core.RunExecutor
import com.castivio.tv.probe.core.RunOutcome
import com.castivio.tv.probe.core.RunProgress
import com.castivio.tv.probe.core.RunRequest
import com.castivio.tv.probe.core.SessionStatus
import com.castivio.tv.probe.core.StepPurpose
import com.castivio.tv.probe.core.StopReason
import com.castivio.tv.probe.core.WalkScan
import com.castivio.tv.probe.support.Fixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session's rules, with an executor that answers from a script and a clock that is virtual.
 *
 * Nothing here touches the network; the runner has tests of its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProbeSessionTest {

    private val config = ProbeConfig(cooldownMs = 3_000L, priorReplyBytes = 1_000L)

    private class Script(private val handler: (RunRequest) -> RunOutcome) : RunExecutor {
        val calls = ArrayList<PlanStep>()

        override fun execute(request: RunRequest): RunOutcome {
            calls += request.step
            return handler(request)
        }
    }

    private fun timeOf(arm: ProbeArm): Long = when (arm.id) {
        "K1" -> 60_000L
        "K3" -> 36_000L
        "K4" -> 50_000L
        "K8" -> 50_000L
        else -> 100_000L
    }

    /** A run that went well and downloaded 1,000 bytes: the plan's twenty runs come to exactly what was announced. */
    private fun outcome(
        step: PlanStep,
        replyBytes: Long = 900L,
        invalid: List<InvalidReason> = emptyList(),
        stopStatus: Int? = null,
        fatalStatus: Int? = null,
        reply: OneReplyScan? = null,
        walk: WalkScan? = null,
    ): RunOutcome = RunOutcome(
        Fixtures.run(
            step.arm, step.block, timeOf(step.arm), step.purpose,
            invalid = invalid,
            replyBytes = replyBytes,
            stopStatus = stopStatus,
            fatalStatus = fatalStatus,
            planStep = step,
        ),
        reply,
        walk,
    )

    private fun TestScope.newSession(
        executor: RunExecutor,
        sessionConfig: ProbeConfig = config,
        plan: ProbePlan = ProbePlan.standard(3),
    ): ProbeSession = ProbeSession(plan, executor, sessionConfig, Fixtures.header(), StandardTestDispatcher(testScheduler))

    // ----------------------------------------------------------------------------- the pilot

    @Test
    fun nothing_happens_until_the_session_is_run() = runTest {
        val script = Script { outcome(it.step) }
        val session = newSession(script)
        advanceUntilIdle()
        assertEquals(SessionStatus.IDLE, session.state.value.status)
        assertEquals(0, script.calls.size)
    }

    @Test
    fun the_pilot_pauses_the_session_and_resuming_finishes_it() = runTest {
        val script = Script { outcome(it.step) }
        val session = newSession(script)
        val job = launch { session.run() }
        advanceUntilIdle()
        assertEquals(SessionStatus.PAUSED_PILOT, session.state.value.status)
        assertEquals(9, session.state.value.completedSteps)
        assertEquals(9, script.calls.size)

        session.resume()
        advanceUntilIdle()
        job.join()
        val state = session.state.value
        assertEquals(SessionStatus.FINISHED, state.status)
        assertNull(state.stop)
        assertEquals(20, state.completedSteps)
        assertEquals(20, script.calls.size)
        assertEquals(20, state.runs.size)
    }

    @Test
    fun the_downloads_rest_between_each_other_and_the_pause_costs_no_time() = runTest {
        val script = Script { outcome(it.step) }
        val session = newSession(script)
        val job = launch { session.run() }
        advanceUntilIdle()
        session.resume()
        advanceUntilIdle()
        job.join()
        // Twenty downloads, nineteen rests of three seconds.
        assertEquals(19 * 3_000L, testScheduler.currentTime)
    }

    @Test
    fun the_steps_run_in_the_order_of_the_plan() = runTest {
        val script = Script { outcome(it.step) }
        val session = newSession(script)
        val job = launch { session.run() }
        advanceUntilIdle()
        session.resume()
        advanceUntilIdle()
        job.join()
        assertEquals(ProbePlan.standard(3).steps, script.calls)
    }

    // ------------------------------------------------------------------------------ stopping

    @Test
    fun a_429_ends_the_session_at_once_and_keeps_what_was_measured() = runTest {
        val script = Script { request ->
            if (request.step.index == 1) {
                outcome(request.step, stopStatus = 429, invalid = listOf(InvalidReason.PROVIDER_STOP, InvalidReason.FAILED_REQUESTS))
            } else {
                outcome(request.step)
            }
        }
        val session = newSession(script)
        val job = launch { session.run() }
        advanceUntilIdle()
        job.join()
        val state = session.state.value
        assertEquals(SessionStatus.STOPPED, state.status)
        assertEquals(StopReason.ProviderStatus(429, "K3 #1"), state.stop)
        assertEquals(2, state.runs.size)
        assertEquals(2, script.calls.size)
        assertNotNull(state.decision)
    }

    @Test
    fun a_403_and_any_5xx_end_it_the_same_way() = runTest {
        for (status in listOf(403, 500, 503)) {
            val script = Script { request ->
                if (request.step.index == 0) {
                    outcome(request.step, stopStatus = status, invalid = listOf(InvalidReason.PROVIDER_STOP))
                } else {
                    outcome(request.step)
                }
            }
            val session = newSession(script)
            val job = launch { session.run() }
            advanceUntilIdle()
            job.join()
            assertEquals("HTTP $status", StopReason.ProviderStatus(status, "K1 #1"), session.state.value.stop)
            assertEquals(1, script.calls.size)
        }
    }

    @Test
    fun a_refused_list_or_reply_ends_it_as_a_configuration_problem() = runTest {
        val script = Script { request ->
            outcome(request.step, fatalStatus = 401, invalid = listOf(InvalidReason.FAILED_REQUESTS))
        }
        val session = newSession(script)
        val job = launch { session.run() }
        advanceUntilIdle()
        job.join()
        assertEquals(StopReason.Configuration(401, "K1 #1"), session.state.value.stop)
        assertEquals(1, script.calls.size)
    }

    @Test
    fun three_invalid_runs_in_a_row_end_it() = runTest {
        val script = Script { outcome(it.step, invalid = listOf(InvalidReason.CONTAMINATED)) }
        val session = newSession(script)
        val job = launch { session.run() }
        advanceUntilIdle()
        job.join()
        val state = session.state.value
        assertEquals(StopReason.ConsecutiveInvalid(3), state.stop)
        assertEquals(3, state.runs.size)
    }

    @Test
    fun a_valid_run_in_between_resets_the_count_of_invalid_ones() = runTest {
        // Every third run is valid: two invalid, one valid, two invalid, one valid... never three together.
        val script = Script { request ->
            if (request.step.index % 3 == 2) {
                outcome(request.step)
            } else {
                outcome(request.step, invalid = listOf(InvalidReason.NETWORK_CHANGED))
            }
        }
        val session = newSession(script)
        val job = launch { session.run() }
        advanceUntilIdle()
        assertEquals(SessionStatus.PAUSED_PILOT, session.state.value.status)
        assertNull(session.state.value.stop)
        assertEquals(9, session.state.value.runs.size)
        session.stop()
        advanceUntilIdle()
        job.join()
    }

    @Test
    fun stopping_during_a_cooldown_ends_the_session() = runTest {
        val script = Script { outcome(it.step) }
        val session = newSession(script)
        val job = launch { session.run() }
        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(SessionStatus.COOLING, session.state.value.status)
        session.stop()
        advanceUntilIdle()
        job.join()
        assertEquals(SessionStatus.STOPPED, session.state.value.status)
        assertEquals(StopReason.ByUser, session.state.value.stop)
        assertEquals(1, script.calls.size)
    }

    @Test
    fun stopping_while_paused_after_the_pilot_keeps_the_pilot() = runTest {
        val script = Script { outcome(it.step) }
        val session = newSession(script)
        val job = launch { session.run() }
        advanceUntilIdle()
        assertEquals(SessionStatus.PAUSED_PILOT, session.state.value.status)
        session.stop()
        advanceUntilIdle()
        job.join()
        val state = session.state.value
        assertEquals(SessionStatus.STOPPED, state.status)
        assertEquals(StopReason.ByUser, state.stop)
        assertEquals(9, state.runs.size)
    }

    @Test
    fun stopping_during_a_run_cancels_that_run() = runTest {
        lateinit var session: ProbeSession
        val script = Script { request ->
            if (request.step.index == 0) session.stop()
            val cancelled: List<InvalidReason> =
                if (request.control.cancelled) listOf(InvalidReason.CANCELLED) else emptyList()
            outcome(request.step, invalid = cancelled)
        }
        session = newSession(script)
        val job = launch { session.run() }
        advanceUntilIdle()
        job.join()
        val state = session.state.value
        assertEquals(StopReason.ByUser, state.stop)
        assertEquals(1, state.runs.size)
        assertEquals(listOf(InvalidReason.CANCELLED), state.runs.first().invalid)
    }

    @Test
    fun an_error_nobody_expected_ends_it_and_keeps_what_was_measured() = runTest {
        val script = Script { request ->
            if (request.step.index == 1) throw IllegalStateException("boom")
            outcome(request.step)
        }
        val session = newSession(script)
        val job = launch { session.run() }
        advanceUntilIdle()
        job.join()
        val state = session.state.value
        assertEquals(StopReason.Unexpected("IllegalStateException"), state.stop)
        assertEquals(SessionStatus.STOPPED, state.status)
        assertEquals(1, state.runs.size)
    }

    // ------------------------------------------------------------------------------- the data

    @Test
    fun a_reply_larger_than_announced_pauses_before_the_next_download_and_resuming_goes_on() = runTest {
        val script = Script { request ->
            if (request.step.index == 0) outcome(request.step, replyBytes = 2_000L) else outcome(request.step)
        }
        val session = newSession(script)
        val job = launch { session.run() }
        advanceUntilIdle()
        assertEquals(SessionStatus.PAUSED_BUDGET, session.state.value.status)
        assertEquals(1, session.state.value.completedSteps)
        assertEquals(DataBudget.Level.SOFT, session.state.value.budget.level)
        assertEquals(1, script.calls.size)

        session.resume()
        advanceUntilIdle()
        assertEquals(SessionStatus.PAUSED_PILOT, session.state.value.status)
        session.stop()
        advanceUntilIdle()
        job.join()
    }

    @Test
    fun stopping_at_a_data_alert_ends_the_session_without_another_download() = runTest {
        val script = Script { request ->
            if (request.step.index == 0) outcome(request.step, replyBytes = 2_000L) else outcome(request.step)
        }
        val session = newSession(script)
        val job = launch { session.run() }
        advanceUntilIdle()
        session.stop()
        advanceUntilIdle()
        job.join()
        assertEquals(StopReason.ByUser, session.state.value.stop)
        assertEquals(1, script.calls.size)
    }

    @Test
    fun data_far_past_the_announced_figure_stops_the_session_for_good() = runTest {
        val script = Script { outcome(it.step, replyBytes = 30_000L) }
        val session = newSession(script)
        val job = launch { session.run() }
        advanceUntilIdle()
        job.join()
        val state = session.state.value
        assertTrue(state.stop is StopReason.HardBudget)
        assertEquals(1, state.runs.size)
        assertEquals(SessionStatus.STOPPED, state.status)
    }

    @Test
    fun the_announced_figure_is_the_plan_times_the_reply_measured_before() = runTest {
        val session = newSession(Script { outcome(it.step) })
        assertEquals(20_000L, session.state.value.budget.announcedBytes)
        assertEquals(DataBudget.Level.WITHIN, session.state.value.budget.level)
    }

    // ---------------------------------------------------------------------- what the runs say

    @Test
    fun progress_from_a_run_reaches_the_state() = runTest {
        lateinit var session: ProbeSession
        var seen: RunProgress? = null
        val script = Script { request ->
            if (request.step.index == 0) {
                request.progress(RunProgress("K1 #1", 500L, 1_000L, 1, 1))
                seen = session.state.value.current
            }
            outcome(request.step)
        }
        session = newSession(script)
        val job = launch { session.run() }
        advanceUntilIdle()
        val progress = seen
        assertNotNull(progress)
        assertEquals("K1 #1", progress!!.label)
        assertEquals(1_000L, progress.bytes)
        session.stop()
        advanceUntilIdle()
        job.join()
    }

    @Test
    fun the_walks_are_reconciled_against_the_first_one_reply_and_the_drift_check_settles_them() = runTest {
        val reply = Fixtures.reply(setOf("a", "b"), listOf(1L to "a", 2L to "a", 3L to "b"))
        val script = Script { request ->
            val step = request.step
            when {
                step.purpose == StepPurpose.VERIFY && step.arm == ProbeArms.K1 -> outcome(step, reply = reply)
                step.purpose == StepPurpose.VERIFY -> outcome(
                    step,
                    // The walk does not bring film 3, which is listed under category b.
                    walk = Fixtures.walk(listOf("a", "b"), listOf(Triple(1L, "a", null), Triple(2L, "a", null))),
                )
                step.purpose == StepPurpose.DRIFT -> outcome(step, reply = reply)
                else -> outcome(step)
            }
        }
        val session = newSession(script)
        val job = launch { session.run() }
        advanceUntilIdle()

        // After the pilot: three walks reconciled, the differences waiting for the drift check.
        val pilot = session.state.value
        assertEquals(listOf("K3", "K4", "K8"), pilot.reconciliations.keys.toList())
        for (result in pilot.reconciliations.values) {
            assertEquals(Reconciliation.Completeness.PENDING, result.completeness)
            assertEquals(1, result.missingPending)
        }
        assertNull(pilot.drift)

        session.resume()
        advanceUntilIdle()
        job.join()

        // The film was still in the catalogue when the drift check looked: the walk really missed it.
        val done = session.state.value
        assertEquals(Reconciliation.Drift(3, 3, 0, 0), done.drift)
        for (result in done.reconciliations.values) {
            assertEquals(Reconciliation.Completeness.INCOMPLETE, result.completeness)
            assertEquals(1, result.missingUnexplained)
        }
        val verdict = done.decision!!.verdicts.first { it.arm == ProbeArms.K3 }
        assertEquals(false, verdict.criteria.first { it.name == "complete" }.met)
    }

    @Test
    fun a_one_reply_verification_that_failed_is_not_used_as_the_reference() = runTest {
        val reply = Fixtures.reply(setOf("a"), listOf(1L to "a"))
        val script = Script { request ->
            val step = request.step
            when {
                step.purpose == StepPurpose.VERIFY && step.arm == ProbeArms.K1 ->
                    outcome(step, reply = reply, invalid = listOf(InvalidReason.FAILED_REQUESTS))
                step.purpose == StepPurpose.VERIFY ->
                    outcome(step, walk = Fixtures.walk(listOf("a"), listOf(Triple(1L, "a", null))))
                else -> outcome(step)
            }
        }
        val session = newSession(script)
        val job = launch { session.run() }
        advanceUntilIdle()
        assertTrue(session.state.value.reconciliations.isEmpty())
        session.stop()
        advanceUntilIdle()
        job.join()
    }

    @Test
    fun a_complete_faster_error_free_arm_is_adopted_at_the_end() = runTest {
        val reply = Fixtures.reply(setOf("a", "b"), listOf(1L to "a", 2L to "a", 3L to "b"))
        val script = Script { request ->
            val step = request.step
            when {
                step.purpose == StepPurpose.VERIFY && step.arm == ProbeArms.K1 -> outcome(step, reply = reply)
                step.purpose == StepPurpose.VERIFY -> outcome(
                    step,
                    walk = Fixtures.walk(
                        listOf("a", "b"),
                        listOf(Triple(1L, "a", null), Triple(2L, "a", null), Triple(3L, "b", null)),
                    ),
                )
                step.purpose == StepPurpose.DRIFT -> outcome(step, reply = reply)
                else -> outcome(step)
            }
        }
        val session = newSession(script)
        val job = launch { session.run() }
        advanceUntilIdle()
        session.resume()
        advanceUntilIdle()
        job.join()
        val state = session.state.value
        assertEquals(SessionStatus.FINISHED, state.status)
        val decision = state.decision!!
        assertEquals(DecisionOutcome.ADOPT, decision.outcome)
        assertEquals(ProbeArms.K3, decision.chosen)
    }
}
