package com.castivio.tv.debug.lab

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The readable report and the raw one.
 *
 * Every figure is a median over the usable measured runs of a case -- after the warm-up, data checks passed, and
 * nothing else running -- with the minimum, the maximum, the coefficient of variation and the number of runs
 * beside it. Differences are taken round by round (the order of the cases changes between rounds, so a difference
 * inside one round does not carry the device's drift) and printed with what they mean for that case.
 *
 * Failed data checks and timing warnings are reported apart: the first makes a run unusable, the second only
 * says which figures to doubt.
 */
internal object LabReport {

    fun text(environment: List<String>, outcome: LabOutcome, planNote: String): String {
        val out = StringBuilder()
        out.append("CASTIVIO DB LAB - cost of the media indices on this device\n")
        for (line in environment) out.append(line).append('\n')
        out.append(planNote).append('\n')
        out.append(
            "A scratch database per run; the app's own database, subscriptions and network are never used. " +
                "Synthetic rows (example.invalid, no real identifier).\n",
        )
        outcome.stopReason?.let { out.append("ENDED EARLY: ").append(it).append(" -- the figures below cover the runs that finished.\n") }
        for (section in outcome.results) datasetSection(out, section)
        out.append('\n').append(LIMITS)
        return out.toString()
    }

    private fun datasetSection(out: StringBuilder, results: DatasetResults) {
        val spec = results.spec
        val cases = results.byCase.keys.toList()
        val all = results.byCase.values.flatten()
        val measured = cases.associateWith { LabStats.measured(results.byCase.getValue(it)) }
        out.append('\n').append("=".repeat(100)).append('\n')
        out.append(
            String.format(
                Locale.ROOT, "%s: %,d rows in %d categories, epg fill %.2f\n", spec.name, spec.rows, spec.groups, spec.epgFill,
            ),
        )

        out.append("runs by case (warm-up / measured and usable / excluded or failed):\n")
        for (case in cases) {
            val runs = results.byCase.getValue(case)
            val warm = runs.count { it.warmup }
            val used = measured.getValue(case).size
            val rejected = runs.count { !it.warmup && !it.usable }
            out.append(String.format(Locale.ROOT, "  %-6s %d / %d / %d\n", case.code, warm, used, rejected))
        }

        val failed = all.filter { !it.dataOk }
        out.append(
            if (failed.isEmpty()) {
                "data checks: every run passed (row count, row content, indices, search index, counts, integrity_check)\n"
            } else {
                "DATA CHECKS FAILED in ${failed.size} run(s); those runs are not used:\n"
            },
        )
        for (run in failed) out.append("  ").append(run.oneLine()).append('\n')
        val excluded = all.filter { it.dataOk && it.excluded != null }
        if (excluded.isNotEmpty()) {
            out.append("excluded because something else ran beside them (not used):\n")
            for (run in excluded) out.append("  ").append(run.oneLine()).append('\n')
        }
        val warned = all.filter { it.usable && it.warnings.isNotEmpty() }
        if (warned.isNotEmpty()) {
            out.append("timing warnings (runs used; read their figures with care):\n")
            for (run in warned) out.append("  ").append(run.oneLine()).append('\n')
        }

        val reported = cases.filter { measured.getValue(it).isNotEmpty() }
        if (reported.isEmpty()) {
            out.append("no usable measured run yet\n")
            return
        }

        out.append("\nms, median [min-max] cv over the usable measured runs (n)\n")
        out.append(row("case", "rows", "commit", "build (deferred)", "finish (end work)", "TOTAL")).append("     n\n")
        for (case in reported) {
            val runs = measured.getValue(case)
            out.append(
                row(
                    case.code,
                    spread(runs) { it.rowsMs },
                    spread(runs) { it.commitMs },
                    spread(runs) { it.buildMs },
                    spread(runs) { it.finishMs },
                    spread(runs) { it.totalMs },
                ),
            ).append(String.format(Locale.ROOT, " %5d\n", runs.size))
        }

        out.append("\nthree ways to read a case (medians, ms): the insert alone; the insert and the indices built after it; the whole\n")
        for (case in reported) {
            val runs = measured.getValue(case)
            out.append(
                String.format(
                    Locale.ROOT,
                    "  %-6s insert only %7.0f   insert + build %7.0f   whole %7.0f   ends with %s\n",
                    case.code,
                    median(runs) { it.insertOnlyMs },
                    median(runs) { it.insertPlusBuildMs },
                    median(runs) { it.totalMs },
                    if (case.expectedIndices().size == LabIndex.ALL.size) "all ${LabIndex.ALL.size} indices" else "without ${(LabIndex.ALL - case.expectedIndices()).joinToString()}",
                ),
            )
        }

        val a = results.byCase[LabCase.A]
        val aMeasured = measured[LabCase.A].orEmpty()
        if (a != null && aMeasured.isNotEmpty()) {
            out.append("\ndifference from A, round by round: median (share of A), range, rounds positive / rounds compared\n")
            for (case in reported) {
                if (case == LabCase.A) continue
                val runs = results.byCase.getValue(case)
                out.append("  A - ${case.code} (${case.caveat}):\n")
                difference(out, "insert only", a, runs, { it.insertOnlyMs }, median(aMeasured) { it.insertOnlyMs })
                difference(out, "insert + build", a, runs, { it.insertPlusBuildMs }, median(aMeasured) { it.insertPlusBuildMs })
                difference(out, "whole", a, runs, { it.totalMs }, median(aMeasured) { it.totalMs })
            }
        }

        out.append("\nend of the import, medians in ms (a breakdown of 'finish')\n")
        for (case in reported) {
            val runs = measured.getValue(case)
            out.append(String.format(Locale.ROOT, "  %-6s", case.code))
            for (key in runs.first().finishSteps.keys) {
                out.append(String.format(Locale.ROOT, "  %s %.0f", key, median(runs) { it.finishSteps.getValue(key) }))
            }
            out.append('\n')
        }

        val withBuilds = reported.filter { it.rebuilt.isNotEmpty() }
        if (withBuilds.isNotEmpty()) {
            out.append("\nindices built after the insert, one by one: median ms, and the size each took (a breakdown of 'build')\n")
            for (case in withBuilds) {
                val runs = measured.getValue(case)
                out.append(String.format(Locale.ROOT, "  %-6s", case.code))
                for (name in case.rebuilt) {
                    out.append(
                        String.format(
                            Locale.ROOT, "  %s %.0f ms %s", name, median(runs) { it.builds.getValue(name) },
                            megabytes(runs.last().pages[name], runs.last()),
                        ),
                    )
                }
                out.append('\n')
            }
        }

        out.append("\nsizes and memory (last usable run)\n")
        for (case in reported) {
            val s = measured.getValue(case).last().sizes
            out.append(
                String.format(
                    Locale.ROOT,
                    "  %-6s db %.1f MB (after a TRUNCATE checkpoint), wal %.1f MB, native heap %+.1f MB over the rows\n",
                    case.code,
                    (s["db_bytes_after_checkpoint"] ?: 0L) / MB,
                    (s["wal_bytes_after_finish"] ?: 0L) / MB,
                    ((s["native_heap_after_rows_bytes"] ?: 0L) - (s["native_heap_before_bytes"] ?: 0L)) / MB,
                ),
            )
        }

        out.append("\ncross-check: the lab's timers against the writer's own counters (medians, ms)\n")
        for (case in reported) {
            val runs = measured.getValue(case)
            out.append(
                String.format(
                    Locale.ROOT,
                    "  %-6s rows lab %.0f / writer %.0f   commit lab %.0f / writer %.0f\n",
                    case.code,
                    median(runs) { it.rowsMs },
                    median(runs) { it.crossChecks.getValue("writer ROWS step") },
                    median(runs) { it.commitMs },
                    median(runs) { it.crossChecks.getValue("writer commits") },
                ),
            )
        }

        val first = measured.getValue(reported.first()).first()
        out.append("\nsettings as run: ").append(first.pragmas.entries.joinToString(", ") { "${it.key}=${it.value}" }).append('\n')
        for (case in cases) out.append("  ${case.code}: ${case.summary}\n")
    }

    private fun row(vararg cells: String): String {
        val sb = StringBuilder()
        sb.append(String.format(Locale.ROOT, "%-6s", cells[0]))
        for (i in 1 until cells.size) sb.append(String.format(Locale.ROOT, " %26s", cells[i]))
        return sb.toString()
    }

    private fun spread(runs: List<CaseResult>, metric: (CaseResult) -> Double): String =
        LabStats.spreadOf(runs.map(metric)).format()

    private fun median(runs: List<CaseResult>, metric: (CaseResult) -> Double): Double =
        LabStats.median(runs.map(metric))

    private fun difference(
        out: StringBuilder,
        label: String,
        a: List<CaseResult>,
        b: List<CaseResult>,
        metric: (CaseResult) -> Double,
        baseline: Double,
    ) {
        val diffs = LabStats.paired(a, b, metric)
        if (diffs.isEmpty()) {
            out.append(String.format(Locale.ROOT, "      %-15s no round in which both were measured\n", label))
            return
        }
        val med = LabStats.median(diffs)
        out.append(
            String.format(
                Locale.ROOT,
                "      %-15s %7.0f ms (%3.0f%% of A) range [%.0f..%.0f], positive in %d/%d\n",
                label, med, if (baseline > 0) 100.0 * med / baseline else 0.0, diffs.min(), diffs.max(),
                diffs.count { it > 0 }, diffs.size,
            ),
        )
    }

    private fun megabytes(pages: Long?, run: CaseResult): String {
        if (pages == null) return "(size n/a)"
        val pageSize = run.pragmas["page_size"]?.toLongOrNull() ?: 4096L
        return String.format(Locale.ROOT, "(%.2f MB)", pages * pageSize / MB)
    }

    private const val MB = 1_048_576.0

    private const val LIMITS =
        "WHAT THESE FIGURES ARE, AND ARE NOT\n" +
            "- Measured here: each phase of the app's own writer on this device, on synthetic rows, writing alone.\n" +
            "- Not reproduced: the parser thread, the network and the screens running beside the writer in the app; the\n" +
            "  commit that a half-second timer can add; the per-category counts made while a load runs. Production times\n" +
            "  are therefore at least these.\n" +
            "- The rows time includes the writer's text folding and binding (Kotlin) in every case alike. In a debuggable\n" +
            "  build that Kotlin share is inflated; judge a case by the milliseconds it saves, not by its share.\n" +
            "- A_c20 changes the page cache, not an index.\n" +
            "- Synthetic data: category sizes, runs, titles and the share of channels with a guide id are assumptions.\n" +
            "- A difference inside A's own spread (its cv and range) is not a difference.\n"

    // -------------------------------------------------------------------------------------------------- JSON

    fun json(environment: List<String>, outcome: LabOutcome, planNote: String): String {
        val root = JSONObject()
        root.put("environment", JSONArray(environment))
        root.put("plan", planNote)
        root.put("stopReason", outcome.stopReason ?: JSONObject.NULL)
        val datasets = JSONArray()
        for (dataset in outcome.results) {
            val d = JSONObject()
            d.put("name", dataset.spec.name)
            d.put("rows", dataset.spec.rows)
            d.put("groups", dataset.spec.groups)
            d.put("seed", dataset.spec.seed)
            d.put("epgFill", dataset.spec.epgFill)
            val runs = JSONArray()
            for (caseRuns in dataset.byCase.values) {
                for (run in caseRuns) runs.put(runJson(run))
            }
            d.put("runs", runs)
            datasets.put(d)
        }
        root.put("datasets", datasets)
        return root.toString(1)
    }

    private fun runJson(run: CaseResult): JSONObject {
        val o = JSONObject()
        o.put("case", run.case.code)
        o.put("round", run.round)
        o.put("warmup", run.warmup)
        o.put("usable", run.usable)
        o.put("excluded", run.excluded ?: JSONObject.NULL)
        o.put("beginMs", run.beginMs)
        o.put("groupsMs", run.groupsMs)
        o.put("rowsMs", run.rowsMs)
        o.put("commitMs", run.commitMs)
        o.put("buildMs", run.buildMs)
        o.put("builds", mapJson(run.builds))
        o.put("finishMs", run.finishMs)
        o.put("finishSteps", mapJson(run.finishSteps))
        o.put("totalMs", run.totalMs)
        o.put("otherMs", run.otherMs)
        o.put("pages", mapJson(run.pages))
        o.put("sizes", mapJson(run.sizes))
        o.put("pragmas", mapJson(run.pragmas))
        o.put("crossChecks", mapJson(run.crossChecks))
        o.put("checks", mapJson(run.checks))
        o.put("warnings", JSONArray(run.warnings))
        return o
    }

    private fun mapJson(map: Map<String, *>): JSONObject {
        val o = JSONObject()
        for ((key, value) in map) o.put(key, value)
        return o
    }
}

/**
 * What a reading was taken on, at the head of a report: the device, the build, and the conditions that make two
 * readings comparable or not -- power, temperature, memory.
 */
internal object LabEnvironment {

    fun lines(context: Context): List<String> {
        val out = ArrayList<String>()
        out += "device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
            "abi ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}, ${Runtime.getRuntime().availableProcessors()} cpu(s)"
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (manager != null) out += "memory: heap class ${manager.memoryClass} MB, low-RAM device ${manager.isLowRamDevice}"
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        out += "build: debuggable=$debuggable" +
            if (debuggable) " (Kotlin-side work is slower than in a release build; SQLite's native work is not)" else ""
        out += battery(context)
        thermal(context)?.let { out += it }
        return out
    }

    private fun battery(context: Context): String {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "battery: not reported"
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val percent = if (level >= 0 && scale > 0) "${100 * level / scale}%" else "?"
        val temperature = if (tenths == Int.MIN_VALUE) "?" else String.format(Locale.ROOT, "%.1f C", tenths / 10.0)
        return "battery: $percent, plugged in $plugged, temperature $temperature"
    }

    private fun thermal(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        return "thermal status: ${power.currentThermalStatus} (0 none, 1 light, 2 moderate, 3 severe ...)"
    }
}
