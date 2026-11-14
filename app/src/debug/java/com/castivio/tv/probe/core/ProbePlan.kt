package com.castivio.tv.probe.core

/** What a step of the session downloads the films for. */
enum class StepPurpose {
    /** Timed: its numbers enter the comparison. Nothing is read from the body but its length. */
    TIMED,

    /** Not timed: `stream_id` and `category_id` are read from every film, to check that nothing is lost. */
    VERIFY,

    /** The one reply again, at the end: how far the catalogue moved while the session ran. */
    DRIFT,
}

/** One full download of the section, by one arm, for one purpose. */
data class PlanStep(
    val index: Int,
    val arm: ProbeArm,
    val purpose: StepPurpose,
    /** Timed steps: the repeat they belong to, from 1. Verification and drift steps: 0. */
    val block: Int,
    /** The session waits here for the owner: after the pilot. */
    val pauseAfter: Boolean,
) {
    val label: String
        get() = when (purpose) {
            StepPurpose.TIMED -> "${arm.id} #$block"
            StepPurpose.VERIFY -> "${arm.id} verify"
            StepPurpose.DRIFT -> "${arm.id} drift check"
        }
}

/**
 * The order of everything the session downloads.
 *
 * ## Order
 *
 * Within a repeat the four arms come in the order of one row of a **Williams square**, so that over four repeats
 * every arm stands in every position once and follows every other arm once: whatever the network or the
 * provider's own cache does to "the first run after a pause" lands on every arm equally. The control arm, W1,
 * closes each repeat -- it is explanatory and is never compared in position. K8 is never first in a session.
 *
 * ## The pilot
 *
 * The first repeat runs alone, and then the four verification downloads: the one reply, and the walk at K3, K4
 * and K8. The session stops there, for the owner to read the errors, the evidence of parallelism and the
 * completeness before the rest is spent. What remains is the other repeats and, last, the drift check.
 *
 * The count of steps is the count of **full downloads**, which is what the data budget is figured on.
 */
class ProbePlan private constructor(
    val repeats: Int,
    val steps: List<PlanStep>,
) {
    val fullDownloads: Int get() = steps.size

    /** How many steps the pilot holds: those up to and including the one the session pauses after. */
    val pilotSteps: Int get() = steps.indexOfFirst { it.pauseAfter } + 1

    companion object {
        const val MAX_REPEATS = 4

        /** Rows of a Williams square for four treatments: each in every position once, each after every other once. */
        private val SQUARE: List<List<ProbeArm>> = listOf(
            listOf(ProbeArms.K1, ProbeArms.K3, ProbeArms.K8, ProbeArms.K4),
            listOf(ProbeArms.K3, ProbeArms.K4, ProbeArms.K1, ProbeArms.K8),
            listOf(ProbeArms.K4, ProbeArms.K8, ProbeArms.K3, ProbeArms.K1),
            listOf(ProbeArms.K8, ProbeArms.K1, ProbeArms.K4, ProbeArms.K3),
        )

        fun standard(repeats: Int = 3): ProbePlan {
            require(repeats in 1..MAX_REPEATS) { "repeats must be 1..$MAX_REPEATS" }
            val steps = ArrayList<PlanStep>()

            fun add(arm: ProbeArm, purpose: StepPurpose, block: Int, pauseAfter: Boolean = false) {
                steps += PlanStep(steps.size, arm, purpose, block, pauseAfter)
            }

            for (arm in SQUARE[0]) add(arm, StepPurpose.TIMED, 1)
            add(ProbeArms.W1, StepPurpose.TIMED, 1)
            add(ProbeArms.K1, StepPurpose.VERIFY, 0)
            add(ProbeArms.K3, StepPurpose.VERIFY, 0)
            add(ProbeArms.K4, StepPurpose.VERIFY, 0)
            add(ProbeArms.K8, StepPurpose.VERIFY, 0, pauseAfter = true)
            for (block in 2..repeats) {
                for (arm in SQUARE[block - 1]) add(arm, StepPurpose.TIMED, block)
                add(ProbeArms.W1, StepPurpose.TIMED, block)
            }
            add(ProbeArms.K1, StepPurpose.DRIFT, 0)
            return ProbePlan(repeats, steps)
        }
    }
}
