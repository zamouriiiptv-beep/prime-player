package com.castivio.tv.debug

import android.content.Context
import android.os.Build
import com.castivio.tv.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last thing that went wrong, kept where the person who saw it can read it.
 *
 * ## Why this exists
 *
 * A tester reported "the app closes when I press a video". That is a complete and useful
 * report and it is not a diagnosis: it fits an `ActivityNotFoundException`, a decoder that
 * refused to construct, a missing binding, and half a dozen other things that need
 * completely different fixes. The correct next step is the stack trace, and the stack trace
 * was unreachable — it lives in `logcat`, `logcat` needs a computer and a cable, and the
 * person testing has a phone.
 *
 * So the trace is written to the app's own storage as it happens and shown on the next
 * launch. No network, no service, no third party: one file, in the app's private
 * directory, which the user can read out or screenshot.
 *
 * ## Why it is debug-only
 *
 * A crash dialog full of Java frames is a diagnostic tool, not a product feature. In a
 * release build the handler is not installed at all — `BuildConfig.DEBUG` is a constant the
 * compiler folds, so the code below is not merely inert there, it is absent.
 *
 * ## Why it chains rather than replaces
 *
 * The default handler is what actually ends the process and shows the system's own dialog.
 * Swallowing the exception would leave the application running in an unknown state, which
 * is worse than the crash — so this writes the file and then hands the throwable on
 * exactly as it found it.
 */
object CrashReport {

    /**
     * Install the recorder. Called once, from the application object.
     *
     * Idempotent, because an activity that installs it on every creation would build a
     * chain of handlers one link longer on each rotation.
     */
    fun install(context: Context) {
        if (!BuildConfig.DEBUG || installed) return
        installed = true

        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * A failure that did not kill the process, recorded anyway.
     *
     * The class of bug this is for is the one that is hardest to find from a screenshot:
     * an action that quietly does nothing because something threw inside a `runCatching`.
     * Every place in this application that swallows an exception on purpose should say so
     * here, so that "I pressed it and nothing happened" has an answer too.
     */
    fun note(context: Context, where: String, error: Throwable) {
        if (!BuildConfig.DEBUG) return
        runCatching { write(context.applicationContext, Thread.currentThread(), error, where) }
    }

    /** The last report, or null. Read on launch and shown if it is there. */
    fun last(context: Context): String? {
        if (!BuildConfig.DEBUG) return null
        val file = file(context)
        return if (file.exists()) runCatching { file.readText() }.getOrNull() else null
    }

    /** Dismissing the report is what deletes it, so it is shown once and not for ever. */
    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun write(context: Context, thread: Thread, error: Throwable, where: String? = null) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
        val report = buildString {
            appendLine("Castivio ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine(STAMP.format(Date()))
            appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            where?.let { appendLine("while: $it") }
            appendLine("thread: ${thread.name}")
            appendLine()
            append(trace.toString())
        }
        file(context).writeText(report.take(LIMIT))
    }

    private fun file(context: Context) = File(context.filesDir, FILE)

    private var installed = false

    private const val FILE = "castivio-last-crash.txt"

    /**
     * Enough for the frames that matter.
     *
     * A stack trace with forty causes is mostly Compose's own internals; the top of it is
     * where the answer is, and a file that grows without bound is a file that eventually
     * fails to write.
     */
    private const val LIMIT = 16_000

    private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
}
