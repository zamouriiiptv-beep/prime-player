package com.castivio.tv.debug.lab

import java.util.Locale

/**
 * One run of one case on one scratch database, in milliseconds.
 *
 * The phases do not overlap and each is timed once, so `begin + groups + rows + commit + build + finish +
 * other = total`. [otherMs] is whatever fell between the timers. The total leaves out the lab's own work inside
 * the window -- making the synthetic rows and reading page counts and the native heap -- which an import would
 * not do. [finishSteps] and [builds] are breakdowns *inside* [finishMs] and [buildMs], never further phases.
 *
 * Two kinds of finding are kept apart: [checks] are about the data (a failed one means the run wrote the wrong
 * thing and is not used); [warnings] are about the timing (the run is used, and the report says what to doubt).
 * [excluded] is set when something outside the lab ran during the run; such a run is kept in the raw report and
 * left out of every figure.
 */
internal class CaseResult(
    val case: LabCase,
    val dataset: String,
    val round: Int,
    val rowCount: Int,
    val beginMs: Double,
    val groupsMs: Double,
    /** `writeItems`, batch by batch: the writer's text folding and binding and SQLite, with the indices. */
    val rowsMs: Double,
    /** Every `commit()` of the rows, the last one included. */
    val commitMs: Double,
    /** The block that builds the indices the case left out, begin and commit of its transaction included. */
    val buildMs: Double,
    /** Each index of that block on its own: a breakdown of [buildMs]. */
    val builds: Map<String, Double>,
    /** The writer's `finish()` as a whole: prune, counts, the search index, its commit and the pragmas. */
    val finishMs: Double,
    /** A breakdown of [finishMs], from the writer's own step counters. */
    val finishSteps: Map<String, Double>,
    val totalMs: Double,
    val otherMs: Double,
    /** Pages each built index took. */
    val pages: Map<String, Long>,
    val sizes: Map<String, Long>,
    val pragmas: Map<String, String>,
    /** The writer's own counters for the same run, beside the lab's timers. */
    val crossChecks: Map<String, Double>,
    /** The digest of the columns the writer folds from the title; equal across every case. */
    val derivedDigest: String,
    val checks: MutableMap<String, Boolean>,
    val warnings: MutableList<String>,
) {
    /** Why this run is left out of every figure, when it is. */
    var excluded: String? = null

    val warmup: Boolean get() = round == 0

    /** The data is what it must be. */
    val dataOk: Boolean get() = checks.values.all { it }

    /** Counted in the figures: data correct and nothing else ran beside it. */
    val usable: Boolean get() = dataOk && excluded == null

    /** Rows and commits: the insert on its own. */
    val insertOnlyMs: Double get() = rowsMs + commitMs

    /** The insert and the indices built after it. */
    val insertPlusBuildMs: Double get() = rowsMs + commitMs + buildMs

    val otherPercent: Double get() = if (totalMs > 0) 100.0 * otherMs / totalMs else 0.0

    fun oneLine(): String {
        val flags = buildList {
            if (!dataOk) add("DATA CHECK FAILED: " + checks.filterValues { !it }.keys.joinToString(", "))
            excluded?.let { add("EXCLUDED: $it") }
            if (warnings.isNotEmpty()) add("timing warning: " + warnings.joinToString("; "))
        }
        return String.format(
            Locale.ROOT,
            "%s %s r%d: total %.0f ms = rows %.0f + commit %.0f + build %.0f + finish %.0f (other %.1f%%)",
            dataset, case.code, round, totalMs, rowsMs, commitMs, buildMs, finishMs, otherPercent,
        ) + if (flags.isEmpty()) "" else "  [" + flags.joinToString(" | ") + "]"
    }
}

/** All the runs of one dataset so far, by case. Round 0 of each case is the warm-up. */
internal class DatasetResults(
    val spec: LabDatasetSpec,
    val byCase: Map<LabCase, List<CaseResult>>,
)

/** Median, minimum, maximum and the spread of a handful of runs. */
internal class Spread(val median: Double, val min: Double, val max: Double, val cvPercent: Double) {
    fun format(): String = String.format(Locale.ROOT, "%.0f [%.0f-%.0f] cv %.0f%%", median, min, max, cvPercent)
}

internal object LabStats {

    fun spreadOf(values: List<Double>): Spread {
        require(values.isNotEmpty()) { "no runs to summarise" }
        val sorted = values.sorted()
        val median = medianOf(sorted)
        val mean = sorted.average()
        var squares = 0.0
        for (v in sorted) squares += (v - mean) * (v - mean)
        val deviation = Math.sqrt(squares / sorted.size)
        return Spread(median, sorted.first(), sorted.last(), if (median == 0.0) 0.0 else 100.0 * deviation / median)
    }

    fun median(values: List<Double>): Double = medianOf(values.sorted())

    private fun medianOf(sorted: List<Double>): Double {
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    /** The runs a figure is made of: every round after the warm-up whose data is right and that ran alone. */
    fun measured(runs: List<CaseResult>): List<CaseResult> = runs.filter { !it.warmup && it.usable }

    /**
     * [a] minus [b] for the same round, one figure per round in which both were measured. The order of the cases
     * changes from round to round, so a difference taken inside one round does not carry the device's drift.
     */
    fun paired(a: List<CaseResult>, b: List<CaseResult>, metric: (CaseResult) -> Double): List<Double> {
        val byRound = measured(b).associateBy { it.round }
        return measured(a).mapNotNull { run -> byRound[run.round]?.let { metric(run) - metric(it) } }
    }
}
