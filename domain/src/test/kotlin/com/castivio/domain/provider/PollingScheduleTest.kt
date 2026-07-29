package com.castivio.domain.provider

import com.castivio.domain.time.HOUR_MS
import com.castivio.domain.time.MINUTE_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Waiting, without anything actually waiting.
 *
 * Every case here is a pure function of elapsed milliseconds, so the ten-minute
 * activation ladder and the six-hour retry ladder are both walked end to end in under a
 * millisecond. That is the reason the schedule is a value rather than a coroutine: a
 * backoff nobody can test is a backoff that ships wrong.
 */
class PollingScheduleTest {

    private val plan = PollingPlan(
        phases = listOf(
            PollingPhase(untilElapsedMs = 30_000, everyMs = 2_000),
            PollingPhase(untilElapsedMs = 2 * MINUTE_MS, everyMs = 5_000),
            PollingPhase(untilElapsedMs = 10 * MINUTE_MS, everyMs = 15_000),
        ),
        maxDelayMs = MINUTE_MS,
    )

    // ------------------------------------------------------------------- the phases

    @Test
    fun `the interval widens as the wait goes on`() {
        assertEquals(PollingStep.Wait(2_000), PollingSchedule.step(plan, elapsedMs = 0))
        assertEquals(PollingStep.Wait(2_000), PollingSchedule.step(plan, elapsedMs = 29_999))
        assertEquals(PollingStep.Wait(5_000), PollingSchedule.step(plan, elapsedMs = 30_000))
        assertEquals(PollingStep.Wait(5_000), PollingSchedule.step(plan, elapsedMs = 119_999))
        assertEquals(PollingStep.Wait(15_000), PollingSchedule.step(plan, elapsedMs = 2 * MINUTE_MS))
    }

    @Test
    fun `the plan ends`() {
        assertEquals(PollingStep.Stop, PollingSchedule.step(plan, elapsedMs = 10 * MINUTE_MS))
        assertEquals(PollingStep.Stop, PollingSchedule.step(plan, elapsedMs = HOUR_MS))
    }

    /**
     * Proposing a wake-up the plan would abandon before it arrives is worse than
     * abandoning now: the user stares at a spinner for a quarter of a minute to be told
     * the app gave up a moment after they started waiting.
     */
    @Test
    fun `a delay that would outlive the plan ends it instead`() {
        assertEquals(PollingStep.Stop, PollingSchedule.step(plan, elapsedMs = 10 * MINUTE_MS - 15_000))
        assertEquals(PollingStep.Stop, PollingSchedule.step(plan, elapsedMs = 10 * MINUTE_MS - 1))
        assertEquals(PollingStep.Wait(15_000), PollingSchedule.step(plan, elapsedMs = 10 * MINUTE_MS - 15_001))
    }

    // --------------------------------------------------------------- the whole ladder

    @Test
    fun `the ladder is finite and fits inside the plan`() {
        val delays = PollingSchedule.delays(plan)

        assertTrue("${delays.size}", delays.isNotEmpty())
        assertTrue(delays.sum() <= plan.givesUpAfterMs)
        // Never gets faster: monotonically widening is the property a user perceives.
        assertEquals(delays.sorted(), delays)
    }

    @Test
    fun `the first half minute is answered fifteen times`() {
        val delays = PollingSchedule.delays(plan)

        assertEquals(15, delays.count { it == 2_000L })
    }

    // ------------------------------------------------------------------- the backoff

    @Test
    fun `consecutive failures widen the interval`() {
        assertEquals(PollingStep.Wait(2_000), PollingSchedule.step(plan, 0, consecutiveFailures = 0))
        assertEquals(PollingStep.Wait(4_000), PollingSchedule.step(plan, 0, consecutiveFailures = 1))
        assertEquals(PollingStep.Wait(8_000), PollingSchedule.step(plan, 0, consecutiveFailures = 2))
        assertEquals(PollingStep.Wait(16_000), PollingSchedule.step(plan, 0, consecutiveFailures = 3))
    }

    @Test
    fun `backoff cannot run away`() {
        assertEquals(PollingStep.Wait(MINUTE_MS), PollingSchedule.step(plan, 0, consecutiveFailures = 20))
        assertEquals(PollingStep.Wait(MINUTE_MS), PollingSchedule.step(plan, 0, consecutiveFailures = 200))
    }

    @Test
    fun `a success clears the backoff`() {
        val backedOff = PollingSchedule.step(plan, 0, consecutiveFailures = 4)
        val recovered = PollingSchedule.step(plan, 0, consecutiveFailures = 0)

        assertEquals(PollingStep.Wait(2_000), recovered)
        assertTrue("$backedOff", (backedOff as PollingStep.Wait).delayMs > 2_000)
    }

    // -------------------------------------------------------------------- the jitter

    @Test
    fun `jitter spreads a delay upward only`() {
        val jittered = plan.copy(jitterFraction = 0.5)

        assertEquals(PollingStep.Wait(2_000), PollingSchedule.step(jittered, 0, jitterSample = 0.0))
        assertEquals(PollingStep.Wait(2_500), PollingSchedule.step(jittered, 0, jitterSample = 0.5))
        assertEquals(PollingStep.Wait(3_000), PollingSchedule.step(jittered, 0, jitterSample = 1.0))
    }

    /**
     * Passed in rather than drawn inside, so the schedule stays a pure function — the
     * same arguments give the same answer, forever, on every device.
     */
    @Test
    fun `the same sample always produces the same delay`() {
        val jittered = plan.copy(jitterFraction = 0.3)

        val answers = (0 until 50).map { PollingSchedule.step(jittered, 12_000, 1, 0.37) }.toSet()

        assertEquals(1, answers.size)
    }

    @Test
    fun `no jitter means no jitter`() {
        assertEquals(PollingStep.Wait(2_000), PollingSchedule.step(plan, 0, jitterSample = 1.0))
    }

    // -------------------------------------------------------------- the shipped plans

    /**
     * The activation screen is the one a user watches. Two seconds is the interval that
     * makes a QR code feel connected to something; anything slower reads as a screen
     * that has stopped working.
     */
    @Test
    fun `the activation plan answers within seconds and gives up within ten minutes`() {
        val plan = PollingPlan.ACTIVATION

        assertEquals(PollingStep.Wait(2_000), PollingSchedule.step(plan, elapsedMs = 0))
        assertEquals(10 * MINUTE_MS, plan.givesUpAfterMs)
        assertEquals(PollingStep.Stop, PollingSchedule.step(plan, elapsedMs = 10 * MINUTE_MS))
        assertTrue(plan.jitterFraction > 0.0)
    }

    /** Nobody is watching this one, and the offline grace is measured in weeks. */
    @Test
    fun `the licence retry plan starts in minutes and gives up in hours`() {
        val plan = PollingPlan.LICENCE_RETRY

        assertEquals(PollingStep.Wait(MINUTE_MS), PollingSchedule.step(plan, elapsedMs = 0))
        assertEquals(6 * HOUR_MS, plan.givesUpAfterMs)
        assertTrue(PollingSchedule.delays(plan).size < PollingSchedule.delays(PollingPlan.ACTIVATION).size)
    }

    @Test
    fun `both shipped plans terminate`() {
        for (plan in listOf(PollingPlan.ACTIVATION, PollingPlan.LICENCE_RETRY)) {
            val delays = PollingSchedule.delays(plan)

            assertTrue("$plan", delays.isNotEmpty())
            assertTrue("$plan", delays.sum() <= plan.givesUpAfterMs)
        }
    }

    // ------------------------------------------------------------ refusing nonsense

    @Test(expected = IllegalArgumentException::class)
    fun `a plan needs a phase`() {
        PollingPlan(phases = emptyList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `phases must be in order`() {
        PollingPlan(
            phases = listOf(
                PollingPhase(untilElapsedMs = MINUTE_MS, everyMs = 1_000),
                PollingPhase(untilElapsedMs = 30_000, everyMs = 5_000),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `backoff cannot make a poll more frequent`() {
        PollingPlan(phases = listOf(PollingPhase(30_000, 2_000)), backoffPerFailure = 0.5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an interval of zero would be a spin`() {
        PollingPhase(untilElapsedMs = 30_000, everyMs = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `failures cannot be negative`() {
        PollingSchedule.step(plan, elapsedMs = 0, consecutiveFailures = -1)
    }
}
