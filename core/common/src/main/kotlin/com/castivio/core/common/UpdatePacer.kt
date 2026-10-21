package com.castivio.core.common

/**
 * When to tell the screens that something changed: at once if they were last told a while
 * ago, otherwise when the interval is up -- so a run of changes is told at most once per
 * [intervalMs], and the last of them is never left untold.
 *
 * ## Why
 *
 * A load commits several times a second -- a playlist every thousand entries, a panel every
 * category or half second -- and every commit was told to every screen watching the
 * catalogue: the rail read its categories again, the cards their counts and the list its
 * window, four to seven times a second, on a television that has better things to draw.
 * Nobody reads a rising count that fast. The commits are not this class's business and are
 * not slowed by it: it decides only when they are *told*.
 *
 * The two moments somebody is waiting on -- the first rows of a load, its first category --
 * are told at once by whoever knows them, through [urgent].
 *
 * Pure: the caller passes the time, from a clock that never moves backwards, and keeps the
 * timer [Step.Later] asks for. Safe from any thread: a writer commits on one and its timer
 * fires on another.
 *
 * @param intervalMs the least time between two tellings that were not [urgent].
 */
class UpdatePacer(private val intervalMs: Long) {

    /** What the caller does now. */
    sealed interface Step {
        /** Tell the screens now. */
        data object Now : Step

        /** Nothing yet: start a timer for [afterMs], and call [due] when it fires. */
        data class Later(val afterMs: Long) : Step

        /** Nothing: a timer already running will tell it, or there is nothing left to tell. */
        data object Wait : Step
    }

    /** When the screens were last told, or [NEVER] since the start. */
    private var toldAt = NEVER

    /** Something changed after the screens were last told. */
    private var untold = false

    /** A timer [Step.Later] asked for has not fired yet. */
    private var timing = false

    /** Something changed, at [nowMs]. */
    @Synchronized
    fun changed(nowMs: Long): Step {
        if (toldAt == NEVER || nowMs - toldAt >= intervalMs) return tell(nowMs)
        untold = true
        return later(nowMs)
    }

    /**
     * Somebody is waiting on what changed: it is told now, whatever the interval -- and
     * nothing is, when everything that changed was told already.
     */
    @Synchronized
    fun urgent(nowMs: Long): Step = if (untold) tell(nowMs) else Step.Wait

    /** The timer [Step.Later] asked for fired, at [nowMs]. */
    @Synchronized
    fun due(nowMs: Long): Step {
        timing = false
        if (!untold) return Step.Wait
        if (toldAt == NEVER || nowMs - toldAt >= intervalMs) return tell(nowMs)
        return later(nowMs)
    }

    /** Start again: the next change is told at once, and a timer still running tells nothing. */
    @Synchronized
    fun reset() {
        toldAt = NEVER
        untold = false
        timing = false
    }

    private fun tell(nowMs: Long): Step {
        toldAt = nowMs
        untold = false
        return Step.Now
    }

    /** With something untold, less than an interval after the last telling. */
    private fun later(nowMs: Long): Step {
        if (timing) return Step.Wait
        timing = true
        return Step.Later(toldAt + intervalMs - nowMs)
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
    }
}
