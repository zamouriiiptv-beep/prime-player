package com.castivio.tv.debug.lab

import android.content.Context
import com.castivio.core.platform.PerformanceLog

/** What to run: which datasets, every case, how many rounds, and how long to rest between runs. */
internal class LabPlan(
    val datasets: List<LabDatasetSpec>,
    val cases: List<LabCase>,
    /** Measured rounds. One more, the first, is run first and kept apart as the warm-up. */
    val rounds: Int,
    val restMs: Long,
)

/** What a session leaves: every run it finished, and why it ended early when it did. */
internal class LabOutcome(val results: List<DatasetResults>, val stopReason: String?)

/**
 * The app's own loads, as the lab sees them.
 *
 * The writer the lab runs feeds `PerformanceLog`'s step counters, and so does a section loading in the app;
 * the two must not overlap, or each would read the other's time. So the lab does not start while a load runs,
 * stops between batches as soon as one begins, and compares the run number before and after each of its runs
 * to catch a load that began and ended in between.
 */
internal object LabGuard {
    fun loadRunning(): Boolean = PerformanceLog.current.value?.running == true

    /** The app's latest load, by number; it changes whenever a load begins. */
    fun loadNumber(): Int? = PerformanceLog.current.value?.run
}

/**
 * Runs a [LabPlan]: for each dataset, a warm-up round and then the measured rounds, every case once per round.
 *
 * ## Fairness
 *
 * - **The same rows every time.** A dataset is a pure function of its seed: every run writes byte-identical
 *   rows in the same order, checked against the generator, and the folded columns are compared across cases.
 * - **The order of the cases changes.** Round `r` starts at case `r mod n` and the odd rounds run backwards,
 *   so no case always runs first on a cold device or last on a hot one.
 * - **A warm-up round** warms the code, the allocator and the file system for every case alike; no figure reads it.
 * - **A rest between runs**, outside every timer, and **a fresh database for every run**.
 *
 * ## Ending early
 *
 * Stop, a load in the app, or a failure ends the session between two runs or inside one. A run interrupted
 * inside is discarded -- half a run is not a measurement -- and every run that finished is kept, the dataset in
 * progress included, so a report comes out of any session that finished at least one run.
 */
internal class LabSession(
    private val context: Context,
    private val plan: LabPlan,
    private val stopRequested: () -> Boolean,
    private val say: (String) -> Unit,
    /** Everything finished so far, after every run; `datasetDone` is true at the end of each dataset. */
    private val onProgress: (results: List<DatasetResults>, datasetDone: Boolean) -> Unit,
) {

    fun run(): LabOutcome {
        val done = ArrayList<Pair<LabDatasetSpec, LinkedHashMap<LabCase, MutableList<CaseResult>>>>()
        var stopReason: String? = null
        val removed = LabRunner.sweep(context)
        if (removed > 0) say("removed $removed scratch database(s) left by an earlier session")

        outer@ for (spec in plan.datasets) {
            say("dataset ${spec.name}: ${spec.rows} rows in ${spec.groups} categories, epg fill ${spec.epgFill}")
            val data = LabDataset(spec)
            say("  expected digest ${data.expectedDigest.take(12)}")
            val byCase = LinkedHashMap<LabCase, MutableList<CaseResult>>()
            for (case in plan.cases) byCase[case] = ArrayList()
            done.add(spec to byCase)
            var derivedBaseline: String? = null

            for (round in 0..plan.rounds) {
                for (case in orderOf(round)) {
                    stopReason = blocker()
                    if (stopReason != null) break@outer
                    say("  round $round/${plan.rounds}${if (round == 0) " (warm-up)" else ""}: ${case.code}")
                    val loadBefore = LabGuard.loadNumber()
                    val result = try {
                        LabRunner.run(context, case, data, round) { stopRequested() || LabGuard.loadRunning() }
                    } catch (interrupted: LabCancelled) {
                        stopReason = blocker() ?: "stopped"
                        say("    ${case.code} round $round interrupted and discarded")
                        break@outer
                    } catch (failure: Exception) {
                        stopReason = "failed in ${case.code} round $round: ${failure.javaClass.simpleName}: ${failure.message}"
                        say("    $stopReason")
                        break@outer
                    }
                    if (result.excluded == null && (LabGuard.loadNumber() != loadBefore || LabGuard.loadRunning())) {
                        result.excluded = "a section load began in the app during the run"
                    }
                    if (result.dataOk) {
                        val baseline = derivedBaseline
                        if (baseline == null) derivedBaseline = result.derivedDigest
                        else result.checks["folded columns equal in every case"] = result.derivedDigest == baseline
                    }
                    byCase.getValue(case).add(result)
                    say("    " + result.oneLine())
                    onProgress(snapshot(done), false)
                    if (result.excluded != null) {
                        stopReason = "a section load ran in the app; the lab stopped so that nothing more is measured beside it"
                        break@outer
                    }
                    rest()
                }
            }
            onProgress(snapshot(done), true)
        }
        val results = snapshot(done)
        if (stopReason != null) onProgress(results, true)
        return LabOutcome(results, stopReason)
    }

    /** Why the next run must not start, if it must not. */
    private fun blocker(): String? = when {
        stopRequested() -> "stopped"
        LabGuard.loadRunning() -> "a section load is running in the app"
        else -> null
    }

    /** The results so far, copied, so a report is built from a fixed picture. */
    private fun snapshot(
        done: List<Pair<LabDatasetSpec, LinkedHashMap<LabCase, MutableList<CaseResult>>>>,
    ): List<DatasetResults> = done.map { (spec, byCase) -> DatasetResults(spec, byCase.mapValues { it.value.toList() }) }

    /** The cases in the order round [round] runs them. */
    private fun orderOf(round: Int): List<LabCase> {
        val cases = plan.cases
        val shift = round % cases.size
        val rotated = cases.drop(shift) + cases.take(shift)
        return if (round % 2 == 1) rotated.reversed() else rotated
    }

    /** A rest between runs, outside every timer, cut short by Stop. */
    private fun rest() {
        Runtime.getRuntime().gc()
        val until = System.currentTimeMillis() + plan.restMs
        while (System.currentTimeMillis() < until && !stopRequested()) {
            try {
                Thread.sleep(100)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }
}
