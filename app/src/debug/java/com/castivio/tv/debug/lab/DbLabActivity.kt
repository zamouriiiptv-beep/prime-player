package com.castivio.tv.debug.lab

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **Temporary, for development, debug builds only.** The index-cost lab: it writes synthetic channels to scratch
 * Room databases with the app's own writer, with the media indices kept, removed or built afterwards, and reports
 * what each arrangement costs on this device.
 *
 * It answers one question -- are the secondary indices of `media` a significant share of the time a first load
 * spends writing channels, and would building some of them after the rows pay for itself -- before anyone changes
 * how the importer works. Nothing in the product is changed by it or depends on it:
 *
 *  - it is a second launcher entry that exists in debug builds only (`src/debug/`);
 *  - every run makes its own database (`dblab-...`), never the app's, and deletes it afterwards;
 *  - it reads no subscription, uses no network and writes nothing but its own report;
 *  - it does not start, and stops, while a section is loading in the app (see [LabGuard]).
 *
 * Removing it is deleting `app/src/debug/`.
 *
 * Two plans, fixed: the smoke test (3,000 rows, one round) to see it work, and the full plan (18,803 and 55,801
 * rows, every case, five rounds after a warm-up). Stop keeps every run that finished. The report is shown, copied
 * with a button, and saved beside the app's external files (`dblab/dblab-<time>.txt` and `.json`).
 */
class DbLabActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var runButton: Button
    private lateinit var smokeButton: Button
    private lateinit var stopButton: Button

    private val running = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val logText = StringBuilder()

    @Volatile
    private var lastReport: String = ""

    @Volatile
    private var latest: List<DatasetResults> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildContent())
        runButton.requestFocus()
    }

    override fun onDestroy() {
        // The run notices before its next batch, discards itself and drops its scratch database.
        stopRequested.set(true)
        super.onDestroy()
    }

    // ------------------------------------------------------------------------------------------------ screen

    private fun buildContent(): View {
        val pad = dp(16)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(pad, pad, pad, pad)
        }
        column.addView(label("Castivio DB Lab", 22f, bold = true))
        column.addView(
            label(
                "Writes synthetic channels to scratch databases with the app's own writer and times the media indices. " +
                    "Never touches the app's database or the network; does not run while a section is loading. Debug builds " +
                    "only. Plug the device in: the full plan runs for tens of minutes. Stop keeps every run that finished.",
                13f,
            ),
        )
        column.addView(label("Cases: " + LabCase.values().joinToString("; ") { "${it.code} = ${it.summary}" }, 12f))

        runButton = Button(this).apply {
            text = "Run the full plan (18,803 and 55,801 rows, 5 rounds + warm-up)"
            setOnClickListener { start(smoke = false) }
        }
        smokeButton = Button(this).apply {
            text = "Smoke test (3,000 rows, 1 round)"
            setOnClickListener { start(smoke = true) }
        }
        stopButton = Button(this).apply {
            text = "Stop"
            isEnabled = false
            setOnClickListener {
                stopRequested.set(true)
                say("stop requested: the run in progress is discarded, the finished ones are kept")
            }
        }
        val copyButton = Button(this).apply {
            text = "Copy the report"
            setOnClickListener { copyReport() }
        }
        column.addView(runButton)
        column.addView(smokeButton)
        column.addView(stopButton)
        column.addView(copyButton)

        status = label("idle", 14f, bold = true)
        column.addView(status)
        log = label("", 11f).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        column.addView(log)

        return ScrollView(this).apply {
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun label(value: String, size: Float, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPaddingRelative(0, dp(6), 0, dp(2))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    // ------------------------------------------------------------------------------------------------ the run

    private fun start(smoke: Boolean) {
        if (!running.compareAndSet(false, true)) return
        if (LabGuard.loadRunning()) {
            running.set(false)
            say("a section is loading in the app; start the lab when it has ended")
            return
        }
        val plan = if (smoke) {
            LabPlan(listOf(LabDatasetSpec.SMOKE), LabCase.values().toList(), rounds = 1, restMs = 0L)
        } else {
            LabPlan(listOf(LabDatasetSpec.MAIN_18K, LabDatasetSpec.MAIN_55K), LabCase.values().toList(), rounds = 5, restMs = 3_000L)
        }
        val note = "plan: datasets ${plan.datasets.joinToString { it.name }}; cases ${plan.cases.joinToString { it.code }}; " +
            "${plan.rounds} measured round(s) after a warm-up round; ${plan.restMs / 1000} s rest between runs"
        val context = applicationContext
        val environment = LabEnvironment.lines(context)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())

        stopRequested.set(false)
        latest = emptyList()
        lastReport = ""
        synchronized(logText) { logText.setLength(0) }
        setBusy(true)
        say(note)
        for (line in environment) say(line)

        Thread(
            {
                var stopReason: String? = null
                try {
                    val session = LabSession(
                        context = context,
                        plan = plan,
                        stopRequested = { stopRequested.get() },
                        say = { say(it) },
                        onProgress = { results, datasetDone ->
                            latest = results
                            if (datasetDone) publish(environment, LabOutcome(results, null), note, stamp)
                        },
                    )
                    val outcome = session.run()
                    latest = outcome.results
                    stopReason = outcome.stopReason
                } catch (e: Throwable) {
                    stopReason = "failed: ${e.javaClass.simpleName}: ${e.message}"
                } finally {
                    runCatching { LabRunner.sweep(context) }
                    val ended = environment + LabEnvironment.lines(context).map { "at the end - $it" }
                    publish(ended, LabOutcome(latest, stopReason), note, stamp)
                    say(stopReason?.let { "ended early: $it" } ?: "finished")
                    running.set(false)
                    runOnUiThread { setBusy(false) }
                }
            },
            "db-lab",
        ).start()
    }

    private fun setBusy(busy: Boolean) {
        runButton.isEnabled = !busy
        smokeButton.isEnabled = !busy
        stopButton.isEnabled = busy
        if (busy) status.text = "running..."
    }

    // ---------------------------------------------------------------------------------------------- the report

    /** Writes the report -- also when no run finished, so an early end still says why it ended. */
    private fun publish(environment: List<String>, outcome: LabOutcome, note: String, stamp: String) {
        val text = LabReport.text(environment, outcome, note)
        val json = LabReport.json(environment, outcome, note)
        lastReport = text
        val where = save(text, json, stamp)
        runOnUiThread { status.text = if (where != null) "report saved: $where" else "report ready (no file could be written; copy it)" }
        Log.i(TAG, "report ready: ${outcome.results.size} dataset(s)")
    }

    private fun save(text: String, json: String, stamp: String): String? = runCatching {
        val dir = File(getExternalFilesDir(null) ?: filesDir, "dblab").apply { mkdirs() }
        val base = File(dir, "dblab-$stamp")
        File(base.path + ".txt").writeText(text)
        File(base.path + ".json").writeText(json)
        base.path + ".txt / .json"
    }.getOrNull()

    private fun copyReport() {
        val report = lastReport
        if (report.isEmpty()) {
            say("no report yet")
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("dblab", report))
        say("report copied (${report.length} characters)")
    }

    /** One line to the screen and to logcat. Timings and names only: nothing here is, or comes from, a credential. */
    private fun say(line: String) {
        Log.i(TAG, line)
        val shown = synchronized(logText) {
            logText.append(line).append('\n')
            if (logText.length > MAX_LOG) logText.delete(0, logText.length - MAX_LOG)
            logText.toString()
        }
        runOnUiThread { log.text = shown }
    }

    private companion object {
        const val TAG = "CastivioDbLab"
        const val MAX_LOG = 24_000
    }
}
