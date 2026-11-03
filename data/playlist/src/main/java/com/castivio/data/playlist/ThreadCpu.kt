package com.castivio.data.playlist

import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * What each thread of this process spent of the processor, by name, over a stretch of time.
 *
 * Read only by a measuring build, twice per load: once before it and once after. The process's own CPU time says that
 * the whole device was busy; this says who was running. The reader and the writer of an import are measured on their
 * own threads' clocks already, so what is left of a name that holds them is somebody else's.
 *
 * The kernel keeps a thread's CPU time only while the thread lives, so a thread that ended inside the stretch is in the
 * process's total and in no thread's: [report] says how much that is instead of hiding it.
 */
internal object ThreadCpu {

    /** One thread at one moment: the name the kernel keeps for it (at most 15 characters) and its CPU time in ticks. */
    class Sample(val name: String, val ticks: Long)

    /** How many of the largest names the line lists. */
    private const val LISTED = 7

    /** A name or a remainder below this many milliseconds is not worth a place on the strip. */
    private const val NEGLIGIBLE_MS = 50L

    private const val DEFAULT_TICKS_PER_SECOND = 100L

    private val DIGITS = Regex("\\d+")

    /** Every thread of this process now, by thread id; null where the platform does not let a process read its own. */
    fun read(): Map<Int, Sample>? {
        val dirs = File("/proc/self/task").listFiles() ?: return null
        val samples = HashMap<Int, Sample>(dirs.size * 2)
        for (dir in dirs) {
            val id = dir.name.toIntOrNull() ?: continue
            // A thread can end between the listing and the read: it is simply not there.
            val text = runCatching { File(dir, "stat").readText() }.getOrNull() ?: continue
            samples[id] = parse(text) ?: continue
        }
        return samples.takeIf { it.isNotEmpty() }
    }

    /** The length of the kernel's tick in milliseconds: its CPU times are counted in these. */
    fun tickMs(): Long {
        val perSecond = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(DEFAULT_TICKS_PER_SECOND)
        return 1000L / perSecond.coerceAtLeast(1L)
    }

    /**
     * The name and CPU time out of one `/proc/<pid>/task/<tid>/stat` line, which reads
     * `tid (name) state ppid ... utime stime ...`.
     *
     * The name is whatever stands between the first `(` and the *last* `)`: it can hold spaces and brackets, so nothing
     * is split before the closing one. After it, `state` is the first field, and the user and system times are the
     * twelfth and thirteenth (the fourteenth and fifteenth of the whole line).
     */
    fun parse(stat: String): Sample? {
        val open = stat.indexOf('(')
        val close = stat.lastIndexOf(')')
        if (open < 0 || close <= open) return null
        val fields = stat.substring(close + 1).trim().split(' ')
        if (fields.size <= STIME) return null
        val user = fields[UTIME].toLongOrNull() ?: return null
        val system = fields[STIME].toLongOrNull() ?: return null
        return Sample(stat.substring(open + 1, close), user + system)
    }

    /**
     * The name a thread is counted under: every number in it is made one symbol, so the workers of a pool are one line,
     * and a thread named for the host it talks to is counted as its library, because a screenshot of this strip is
     * shown to others and the host is the provider's.
     */
    fun group(name: String): String =
        if (name.startsWith("OkHttp")) "OkHttp" else name.replace(DIGITS, "#")

    /**
     * The line for the strip: the names that took the most, each with the processor time it spent over the stretch and
     * how many threads made it, then what the process spent that no live thread accounts for.
     *
     * [before] and [after] are [read]s at the two ends of the stretch, [tickMs] is [tickMs], and [processMs] is the
     * process's own CPU time over the same stretch. A thread that began inside the stretch counts from zero. Null when
     * nothing was spent, or when the numbers cannot be told apart from the noise of a tick.
     */
    fun report(before: Map<Int, Sample>, after: Map<Int, Sample>, tickMs: Long, processMs: Long): String? {
        val spent = HashMap<String, Long>()
        val threads = HashMap<String, Int>()
        var counted = 0L
        for ((id, now) in after) {
            val ticks = (now.ticks - (before[id]?.ticks ?: 0L)).coerceAtLeast(0L)
            if (ticks == 0L) continue
            val name = group(now.name)
            // Not `Map.merge`: that is API 24 and the app's floor is 21.
            spent[name] = (spent[name] ?: 0L) + ticks * tickMs
            threads[name] = (threads[name] ?: 0) + 1
            counted += ticks * tickMs
        }
        val parts = spent.entries
            .filter { it.value >= NEGLIGIBLE_MS }
            .sortedByDescending { it.value }
            .take(LISTED)
            .map { (name, ms) ->
                val count = threads.getValue(name)
                "$name ${plain(ms)}" + if (count > 1) " ×$count" else ""
            }
            .toMutableList()
        val unaccounted = processMs - counted
        if (unaccounted >= NEGLIGIBLE_MS) parts += "ended threads ${plain(unaccounted)}"
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    /** Milliseconds as seconds to a hundredth, with a point whatever the device's language. */
    private fun plain(ms: Long): String {
        val hundredths = ms / 10
        return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
    }

    // Positions after the closing bracket: the state is field 0, so these are fields 14 and 15 of the line.
    private const val UTIME = 11
    private const val STIME = 12
}
