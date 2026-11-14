package com.castivio.tv.probe.core

/** One condition of the decision rule, and whether the figures meet it. */
data class Criterion(
    val name: String,
    /** True: met. False: not met. Null: cannot be told yet -- too few runs, or no verification. */
    val met: Boolean?,
    val detail: String,
    /** An informational criterion is shown and never decides. */
    val blocking: Boolean = true,
)

enum class ArmStatus {
    PASS,
    FAIL,

    /** Nothing fails, but something cannot be told yet. */
    OPEN,
}

data class ArmVerdict(
    val arm: ProbeArm,
    val criteria: List<Criterion>,
) {
    val status: ArmStatus
        get() {
            val blocking = criteria.filter { it.blocking }
            return when {
                blocking.any { it.met == false } -> ArmStatus.FAIL
                blocking.any { it.met == null } -> ArmStatus.OPEN
                else -> ArmStatus.PASS
            }
        }
}

enum class DecisionOutcome {
    ADOPT,
    NOT_ADOPTED,
    INCONCLUSIVE,
}

data class Decision(
    val verdicts: List<ArmVerdict>,
    val outcome: DecisionOutcome,
    val chosen: ProbeArm?,
    val notes: List<String>,
)

/**
 * The owner's rule for adopting parallel loading, as code.
 *
 *  1. **Clear and repeated improvement.** The median time is at least [Thresholds.medianImprovement] shorter
 *     than K1's, and in *every* repeat the arm's run is at least [Thresholds.repeatImprovement] shorter than K1's
 *     run in the same repeat. Fewer than [Thresholds.minPairs] valid pairs is "not enough runs", never a verdict.
 *  2. **No errors.** No failed request and no timeout in any of the arm's timed runs.
 *  3. **Complete.** The verification download shows every film of the one reply returned, with nothing under no
 *     listed category and nothing unexplained. An arm that cannot guarantee it is not adopted however fast it is.
 *  4. **Parallelism shown.** In every valid run, as many connections as workers received bytes at once.
 *  5. Films listed under several categories are reported, and do not decide: they need a fixed write order, which
 *     is a design question and not a measurement.
 *
 * Among the arms that pass, the narrowest is chosen, and a wider one replaces it only if it is at least
 * [Thresholds.widerImprovement] faster again, in the median and in every repeat. So K8 that does not clearly beat
 * K3 is K3, and K4 the same.
 */
object DecisionRule {

    private const val NAME_MEDIAN = "median faster"
    private const val NAME_REPEATS = "every repeat faster"
    private const val NAME_ERRORS = "no errors"
    private const val NAME_COMPLETE = "complete"
    private const val NAME_PARALLEL = "parallelism shown"
    private const val NAME_DUPLICATES = "no film under several categories"

    fun evaluate(
        runs: List<RunRecord>,
        results: Map<String, Reconciliation.Result>,
        thresholds: Thresholds,
    ): Decision {
        val byArm = runs.filter { it.step.purpose == StepPurpose.TIMED }.groupBy { it.step.arm.id }
        val baseline = byArm[ProbeArms.K1.id].orEmpty()
        val verdicts = ProbeArms.candidates.map { arm ->
            judge(arm, byArm[arm.id].orEmpty(), baseline, results[arm.id], thresholds)
        }
        return choose(verdicts, byArm, thresholds)
    }

    private class Comparison(
        val medianGain: Double?,
        val pairGains: List<Double>,
        val medianArm: Double?,
        val medianBase: Double?,
        val armValid: Int,
        val baseValid: Int,
    )

    /** The valid runs of [armRuns] against those of [baseRuns]: by the median, and in pairs of the same repeat. */
    private fun compare(armRuns: List<RunRecord>, baseRuns: List<RunRecord>): Comparison {
        val validArm = armRuns.filter { it.valid }
        val validBase = baseRuns.filter { it.valid }
        val medianArm = ProbeStats.median(validArm.map { it.totalMs.toDouble() })
        val medianBase = ProbeStats.median(validBase.map { it.totalMs.toDouble() })
        val medianGain = if (medianArm != null && medianBase != null && medianBase > 0.0) {
            1.0 - medianArm / medianBase
        } else {
            null
        }
        val baseByBlock = validBase.associateBy { it.step.block }
        val pairGains = ArrayList<Double>()
        for (run in validArm) {
            val base = baseByBlock[run.step.block] ?: continue
            if (base.totalMs > 0L) pairGains += 1.0 - run.totalMs.toDouble() / base.totalMs.toDouble()
        }
        return Comparison(medianGain, pairGains, medianArm, medianBase, validArm.size, validBase.size)
    }

    private fun judge(
        arm: ProbeArm,
        armRuns: List<RunRecord>,
        baseRuns: List<RunRecord>,
        result: Reconciliation.Result?,
        t: Thresholds,
    ): ArmVerdict {
        val comparison = compare(armRuns, baseRuns)
        val criteria = ArrayList<Criterion>()

        val gain = comparison.medianGain
        criteria += if (comparison.armValid < t.minPairs || comparison.baseValid < t.minPairs || gain == null) {
            Criterion(
                NAME_MEDIAN,
                null,
                "needs ${t.minPairs} valid runs of each; has ${comparison.armValid} of ${arm.id} and ${comparison.baseValid} of K1",
            )
        } else {
            Criterion(
                NAME_MEDIAN,
                gain >= t.medianImprovement,
                "${change(gain)} (median ${Fmt.seconds(comparison.medianArm ?: 0.0)} s against " +
                    "${Fmt.seconds(comparison.medianBase ?: 0.0)} s; needs ${Fmt.percent(t.medianImprovement)} faster)",
            )
        }

        criteria += if (comparison.pairGains.size < t.minPairs) {
            Criterion(NAME_REPEATS, null, "needs ${t.minPairs} paired repeats; has ${comparison.pairGains.size}")
        } else {
            Criterion(
                NAME_REPEATS,
                comparison.pairGains.all { it >= t.repeatImprovement },
                comparison.pairGains.joinToString(" ") { Fmt.signedPercent(it) } +
                    " against K1 in the same repeat; each needs ${Fmt.percent(t.repeatImprovement)} faster",
            )
        }

        val failedRequests = armRuns.sumOf { it.failures }
        val stopped = armRuns.count { it.stopStatus != null }
        criteria += if (armRuns.isEmpty()) {
            Criterion(NAME_ERRORS, null, "not run yet")
        } else {
            val stoppedText = if (stopped > 0) ", $stopped stopped by the provider" else ""
            Criterion(
                NAME_ERRORS,
                failedRequests == 0 && stopped == 0,
                "$failedRequests failed requests in ${armRuns.size} runs$stoppedText",
            )
        }

        criteria += if (result == null) {
            Criterion(NAME_COMPLETE, null, "no verification download yet")
        } else {
            when (result.completeness) {
                Reconciliation.Completeness.COMPLETE ->
                    Criterion(NAME_COMPLETE, true, "every film of the one reply is returned")
                Reconciliation.Completeness.PENDING ->
                    Criterion(
                        NAME_COMPLETE,
                        null,
                        "${result.missingPending + result.extraPending} differences wait for the drift check to be told apart",
                    )
                Reconciliation.Completeness.INCOMPLETE ->
                    Criterion(NAME_COMPLETE, false, incompleteDetail(result))
            }
        }

        val shown = armRuns.filter { it.valid }.map { ParallelProof.verdict(it.evidence, t.minFullWidthMs) }
        criteria += if (shown.isEmpty()) {
            Criterion(NAME_PARALLEL, null, "no valid run yet")
        } else {
            val proven = shown.count { it == ParallelVerdict.PROVEN }
            Criterion(
                NAME_PARALLEL,
                proven == shown.size,
                "$proven of ${shown.size} valid runs had ${arm.workers} connections receiving at once " +
                    "for at least ${Fmt.seconds(t.minFullWidthMs)} s",
            )
        }

        criteria += if (result == null) {
            Criterion(NAME_DUPLICATES, null, "no verification download yet", blocking = false)
        } else {
            val several = result.draft.idsInSeveralCategories
            Criterion(
                NAME_DUPLICATES,
                several == 0,
                if (several == 0) {
                    "no film is listed under more than one category"
                } else {
                    "${Fmt.count(several)} films listed under several categories (${Fmt.count(result.draft.extraRowsFromSeveralCategories)} " +
                        "extra rows): the stored id comes from stream_id alone, so the category a film ends in would need a fixed write order"
                },
                blocking = false,
            )
        }

        return ArmVerdict(arm, criteria)
    }

    private fun incompleteDetail(result: Reconciliation.Result): String {
        val parts = ArrayList<String>()
        if (result.draft.missingOrphans > 0) {
            parts += "${Fmt.count(result.draft.missingOrphans)} films under no listed category cannot be reached"
        }
        if (result.draft.missingFailed > 0) {
            parts += "${Fmt.count(result.draft.missingFailed)} films in categories whose request failed"
        }
        if (result.missingUnexplained > 0) {
            parts += "${Fmt.count(result.missingUnexplained)} films in the reply and not in the walk, unexplained"
        }
        if (result.extraUnexplained > 0) {
            parts += "${Fmt.count(result.extraUnexplained)} films in the walk and not in the reply, unexplained"
        }
        return parts.joinToString("; ")
    }

    private fun choose(
        verdicts: List<ArmVerdict>,
        byArm: Map<String, List<RunRecord>>,
        t: Thresholds,
    ): Decision {
        val passing = verdicts.filter { it.status == ArmStatus.PASS }.sortedBy { it.arm.workers }
        val notes = ArrayList<String>()
        if (passing.isEmpty()) {
            val outcome = if (verdicts.all { it.status == ArmStatus.FAIL }) {
                DecisionOutcome.NOT_ADOPTED
            } else {
                DecisionOutcome.INCONCLUSIVE
            }
            return Decision(verdicts, outcome, null, notes)
        }
        var chosen = passing.first()
        for (next in passing.drop(1)) {
            val comparison = compare(byArm[next.arm.id].orEmpty(), byArm[chosen.arm.id].orEmpty())
            val gain = comparison.medianGain
            val clearly = gain != null &&
                comparison.pairGains.size >= t.minPairs &&
                gain >= t.widerImprovement &&
                comparison.pairGains.all { it >= t.widerImprovement }
            val figure = if (gain == null) "no median" else change(gain)
            if (clearly) {
                notes += "${next.arm.id} replaces ${chosen.arm.id}: $figure at the median and at least " +
                    "${Fmt.percent(t.widerImprovement)} in every repeat"
                chosen = next
            } else {
                notes += "${next.arm.id} is not clearly faster than ${chosen.arm.id} ($figure at the median; " +
                    "needs ${Fmt.percent(t.widerImprovement)} at the median and in every repeat): ${chosen.arm.id} is preferred"
            }
        }
        return Decision(verdicts, DecisionOutcome.ADOPT, chosen.arm, notes)
    }

    /** `0.35` as `35.0% faster`, `-0.1` as `10.0% slower`. */
    private fun change(gain: Double): String =
        if (gain >= 0.0) "${Fmt.percent(gain)} faster" else "${Fmt.percent(-gain)} slower"
}
