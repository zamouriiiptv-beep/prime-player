package com.castivio.core.common

/**
 * How often the screens are told while a section loads, as the load grows.
 *
 * ## Why a load is told less often the more it has written
 *
 * Every telling makes every screen that watches the catalogue read it again, and what a read costs grows with what the
 * catalogue holds: counting a section is a walk over its index, and the section is the thing being written. Measured on a
 * phone loading two hundred thousand films, the readers spent a second of processor time on each of the hundred and nine
 * tellings -- more than the load itself, which they shared the cores with and kept waiting for its transaction. The
 * first rows and the first category are what somebody is waiting on and are told at once by whoever knows them, through
 * [UpdatePacer.urgent]; the rest is a rising count and a list that nobody reads at the rate it changes.
 *
 * So the interval starts as it always was and lengthens with the rows written *in this load*: nothing changes for a section
 * of channels, whose load is over in ten seconds, and a film library is told about once in ten seconds for most of its
 * minute and a half.
 *
 * Pure: the writer asks it at every commit and keeps the [UpdatePacer].
 */
object LoadPace {

    /** From this many rows written the screens are told at most every [SLOWER_MS]. */
    const val SLOWER_AFTER_ROWS = 25_000

    /** From this many, at most every [SLOWEST_MS]. */
    const val SLOWEST_AFTER_ROWS = 75_000

    const val SLOWER_MS = 5_000L
    const val SLOWEST_MS = 10_000L

    /**
     * The least time between two tellings that were not urgent, for a load that has written [rowsWritten] rows and
     * whose own interval is [baseMs] -- which it never goes below: a refresh, which is told every ten seconds from its
     * first row, stays there.
     */
    fun intervalMs(baseMs: Long, rowsWritten: Int): Long = when {
        rowsWritten >= SLOWEST_AFTER_ROWS -> maxOf(baseMs, SLOWEST_MS)
        rowsWritten >= SLOWER_AFTER_ROWS -> maxOf(baseMs, SLOWER_MS)
        else -> baseMs
    }
}
