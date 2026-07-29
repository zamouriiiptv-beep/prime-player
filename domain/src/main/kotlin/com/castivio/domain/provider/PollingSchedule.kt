package com.castivio.domain.provider

import com.castivio.domain.time.HOUR_MS
import com.castivio.domain.time.MINUTE_MS

/**
 * When to ask again.
 *
 * Two things in Castivio wait for an answer that will arrive on its own: the MAC
 * activation screen, where the user is looking at their address while a provider or an
 * administrator attaches a subscription to it, and the periodic licence check, where
 * nobody is looking at anything.
 *
 * Those are the same problem with opposite constraints. On the activation screen a slow
 * poll reads as a broken app — the user has done their part and is waiting for a screen
 * to change. In the background a fast poll is a battery and a bill, ours and theirs.
 * Both are expressed here as phases so the numbers are in one file rather than in two
 * view models, and both are pure functions of elapsed time so they can be tested without
 * anything actually sleeping.
 *
 * What this deliberately does not do is decide *whether* to poll. A schedule that keeps
 * running while the screen is off is a bug no amount of backoff fixes, and the lifecycle
 * belongs to the caller.
 */
sealed interface PollingStep {

    /** Ask again after this long. */
    data class Wait(val delayMs: Long) : PollingStep

    /**
     * Stop, and hand the decision back to the user.
     *
     * Polling forever is how an app ends up draining a battery in someone's pocket
     * because a screen was left open. Every plan ends, and what replaces it is a button.
     */
    data object Stop : PollingStep
}

/**
 * @param untilElapsedMs the end of this phase, measured from when waiting began.
 * @param everyMs how often to ask inside it.
 */
data class PollingPhase(
    val untilElapsedMs: Long,
    val everyMs: Long,
) {
    init {
        require(everyMs > 0) { "A polling interval must be positive" }
        require(untilElapsedMs > 0) { "A polling phase must have a length" }
    }
}

/**
 * A complete waiting strategy: how the interval widens, how failures widen it further,
 * and when to give up.
 *
 * @param phases in ascending order of [PollingPhase.untilElapsedMs]. The first phase
 *   whose end is beyond the elapsed time wins.
 * @param backoffPerFailure multiplier applied once per consecutive failure. Failures
 *   here mean the request did not complete — a server that is down is not a server that
 *   wants asking every two seconds.
 * @param maxDelayMs a ceiling, so backoff cannot turn a two-second poll into an hour.
 * @param jitterFraction how much of the delay may be added at random, to keep a large
 *   number of devices that all started waiting at the same moment from arriving at our
 *   licence host in one wave.
 */
data class PollingPlan(
    val phases: List<PollingPhase>,
    val backoffPerFailure: Double = 2.0,
    val maxDelayMs: Long = 5 * MINUTE_MS,
    val jitterFraction: Double = 0.0,
) {
    init {
        require(phases.isNotEmpty()) { "A polling plan needs at least one phase" }
        require(phases.zipWithNext().all { (a, b) -> a.untilElapsedMs < b.untilElapsedMs }) {
            "Polling phases must be in ascending order"
        }
        require(backoffPerFailure >= 1.0) { "Backoff cannot make a poll more frequent" }
        require(jitterFraction in 0.0..1.0) { "Jitter is a fraction of the delay" }
    }

    /** When the plan gives up, measured from when waiting began. */
    val givesUpAfterMs: Long get() = phases.last().untilElapsedMs

    companion object {

        /**
         * Someone is watching a QR code and waiting for their subscription to appear.
         *
         * Fast while attention is highest, then widening as the wait stops being a few
         * seconds and starts being an errand. Ten minutes is the end: past that the user
         * has walked away, and a "Check again" button is both cheaper and more honest
         * than a screen that has been polling at them for an hour.
         */
        val ACTIVATION: PollingPlan = PollingPlan(
            phases = listOf(
                PollingPhase(untilElapsedMs = 30 * 1000, everyMs = 2 * 1000),
                PollingPhase(untilElapsedMs = 2 * MINUTE_MS, everyMs = 5 * 1000),
                PollingPhase(untilElapsedMs = 10 * MINUTE_MS, everyMs = 15 * 1000),
            ),
            maxDelayMs = MINUTE_MS,
            // The activation portal is ours, and every device that fails to reach it
            // retries against it, so the herd is real.
            jitterFraction = 0.2,
        )

        /**
         * Nobody is watching. This is the retry ladder for a licence check that failed,
         * not the check itself — the interval between successful checks is
         * [com.castivio.domain.entitlement.PricingConfig.verifyIntervalMs], because it
         * is a business decision rather than a network one.
         *
         * It gives up after six hours and waits for the next scheduled check. There is
         * no urgency: the offline grace in `PricingConfig` is measured in weeks, so a
         * device that cannot reach us today loses nothing by stopping until tomorrow.
         */
        val LICENCE_RETRY: PollingPlan = PollingPlan(
            phases = listOf(
                PollingPhase(untilElapsedMs = 5 * MINUTE_MS, everyMs = MINUTE_MS),
                PollingPhase(untilElapsedMs = 30 * MINUTE_MS, everyMs = 5 * MINUTE_MS),
                PollingPhase(untilElapsedMs = 6 * HOUR_MS, everyMs = 30 * MINUTE_MS),
            ),
            maxDelayMs = HOUR_MS,
            jitterFraction = 0.25,
        )
    }
}

object PollingSchedule {

    /**
     * The next step, from where the waiting has got to.
     *
     * @param elapsedMs since waiting began. Must come from a monotonic source — an
     *   elapsed duration, not two wall-clock readings subtracted, or a clock change
     *   mid-wait either ends the wait instantly or extends it forever.
     * @param consecutiveFailures requests that did not complete since the last that did.
     * @param jitterSample a value in `[0, 1)` from the caller's own random source.
     *   Passed in rather than drawn here so the schedule stays a pure function and the
     *   tests stay exact.
     */
    fun step(
        plan: PollingPlan,
        elapsedMs: Long,
        consecutiveFailures: Int = 0,
        jitterSample: Double = 0.0,
    ): PollingStep {
        require(consecutiveFailures >= 0) { "Failures cannot be negative" }
        require(jitterSample in 0.0..1.0) { "A jitter sample is a fraction" }

        if (elapsedMs >= plan.givesUpAfterMs) return PollingStep.Stop

        val phase = plan.phases.first { elapsedMs < it.untilElapsedMs }
        val backed = backedOff(phase.everyMs, plan, consecutiveFailures)
        val jittered = backed + (backed * plan.jitterFraction * jitterSample).toLong()

        // Never propose a wake-up past the point the plan would have stopped anyway:
        // waiting nine minutes to ask a question that is about to be abandoned is worse
        // than abandoning it now.
        val remaining = plan.givesUpAfterMs - elapsedMs
        if (jittered >= remaining) return PollingStep.Stop

        return PollingStep.Wait(jittered)
    }

    /**
     * Every delay the plan produces from a clean start, for a caller that wants the
     * whole ladder rather than one rung — a test, or a diagnostics screen.
     */
    fun delays(plan: PollingPlan): List<Long> {
        val out = mutableListOf<Long>()
        var elapsed = 0L
        while (true) {
            when (val next = step(plan, elapsed)) {
                is PollingStep.Stop -> return out
                is PollingStep.Wait -> {
                    out += next.delayMs
                    elapsed += next.delayMs
                }
            }
        }
    }

    private fun backedOff(everyMs: Long, plan: PollingPlan, failures: Int): Long {
        if (failures == 0) return minOf(everyMs, plan.maxDelayMs)
        var delay = everyMs.toDouble()
        repeat(failures) {
            delay *= plan.backoffPerFailure
            if (delay >= plan.maxDelayMs) return plan.maxDelayMs
        }
        return minOf(delay.toLong(), plan.maxDelayMs)
    }
}
