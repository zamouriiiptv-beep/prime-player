package com.castivio.tv.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.castivio.core.platform.PerfMode
import com.castivio.core.platform.PerformanceLog
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Every finished load's reading, on the clipboard, in a debuggable build.
 *
 * ## Why this exists
 *
 * The performance readout is drawn on the loading gate, and the gate gives way to the
 * section in the same frame the load settles -- so the finished reading, the one with the
 * full load, the bar, the database's last steps and the search index after the import, is
 * never on screen long enough to be read or photographed. The log has all of it, and the
 * log needs a computer and a cable; the person measuring has a phone. So the lines the log
 * is given are put on the clipboard too, where they can be pasted into a message -- the
 * reasoning `CrashReport` is built on.
 *
 * ## What it holds, and what it never holds
 *
 * Exactly the lines `SectionReport.lines` prints: sections, times, counts, request actions,
 * category ids. Never an address, a user name or a password -- that is a rule of the report
 * itself, which goes to the log as well. The last [KEPT] loads, so a reading survives the
 * load after it; an open that loaded nothing is not a load, and does not push one out.
 *
 * ## What it does not touch
 *
 * The load, the screen and the moment the section's first rows appear. It is told after a
 * run has settled, through `PerformanceLog.onSettled`, which no release build sets, and it
 * writes from a thread of its own so the frame that draws the section is not the one that
 * waits on the clipboard service.
 */
internal object MeasurementClipboard {

    private var installed = false

    /**
     * Called once, from the application object, in a build that draws the readout.
     *
     * Sets a callback and nothing else: the clipboard service is not asked for and the
     * thread is not started until the first load ends, so starting the app costs nothing.
     */
    fun install(context: Context) {
        if (installed) return
        installed = true

        val app = context.applicationContext
        val readings = RecentReadings(KEPT)
        val writer: ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, THREAD).apply { isDaemon = true }
        }
        PerformanceLog.onSettled = { report ->
            if (report.mode != PerfMode.WARM) {
                val text = readings.add(report.lines())
                writer.execute {
                    // A clipboard the platform refuses -- a work profile that forbids it --
                    // costs this reading and nothing else.
                    runCatching {
                        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(LABEL, text))
                    }
                }
            }
        }
    }

    /** Loads kept on the clipboard, oldest first. */
    private const val KEPT = 4

    private const val LABEL = "Castivio measurement"
    private const val THREAD = "castivio-measurement-clipboard"
}
