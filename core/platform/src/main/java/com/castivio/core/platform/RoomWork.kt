package com.castivio.core.platform

import java.util.Locale

/** One line of a thread's stack, as far as naming the work goes. */
class StackFrame(val className: String, val method: String)

/**
 * What the threads of Room's pool were doing during a load, named from their stacks.
 *
 * A load spent more processor time on those threads than on its own reader and writer together, and a thread's total says
 * who spent it but not on what. [RoomThreadSampler] looks at their stacks a few times a second; this says what a stack
 * shows. A thread running Room's work has, somewhere in its stack, the application's own frame that asked for it -- a
 * generated DAO callable, a paging source -- and the innermost of those is the name.
 *
 * Pure: the stacks are given.
 */
object RoomWork {

    /** Samples of a thread that was not running but waiting for SQLite's connection, which the writer holds. */
    const val WAITING_FOR_CONNECTION = "waiting for a connection"

    /** A running thread whose stack held no frame of the application's own. */
    const val OTHER = "other"

    private const val APP_PACKAGE = "com.castivio."
    private const val WAIT_METHOD = "waitForConnection"

    /** How many names the line lists. */
    private const val LISTED = 6

    /**
     * What one sampled thread was doing, in a short name, or null when it was idle. [frames] are innermost first, as a
     * stack trace lists them.
     */
    fun label(running: Boolean, frames: List<StackFrame>): String? {
        if (!running) return if (frames.any { it.method == WAIT_METHOD }) WAITING_FOR_CONNECTION else null
        val own = frames.firstOrNull { it.className.startsWith(APP_PACKAGE) } ?: return OTHER
        return own.className.substringAfterLast('.') + "." + own.method
    }

    /**
     * The line for a load: how much of the pool's time was spent running and waiting for the connection, and which names
     * the running time went to, each as a share of it. [ticks] is how many times the pool was looked at, [threads] how
     * many threads it had at most, [counts] how many samples each name got.
     */
    fun summary(ticks: Int, threads: Int, counts: Map<String, Int>): String? {
        if (ticks <= 0 || threads <= 0) return null
        val total = ticks.toLong() * threads
        val waiting = counts[WAITING_FOR_CONNECTION] ?: 0
        val running = counts.filterKeys { it != WAITING_FOR_CONNECTION }.values.sum()
        val top = counts.filterKeys { it != WAITING_FOR_CONNECTION }.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(LISTED)
        val head = "running ${percent(running.toLong(), total)} of $threads threads over $ticks looks" +
            " · waiting for a connection ${percent(waiting.toLong(), total)}"
        if (top.isEmpty()) return head
        return head + " · of the running: " + top.joinToString(" · ") { "${it.key} ${percent(it.value.toLong(), running.toLong())}" }
    }

    private fun percent(part: Long, whole: Long): String =
        if (whole <= 0) "0%" else String.format(Locale.ROOT, "%d%%", Math.round(part * 100.0 / whole))
}
