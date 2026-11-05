package com.castivio.feature.home

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The radio's sleep timer: when it ends and which of the dialog's answers set it.
 *
 * One timer for the two radios -- the subscription's and the world's hold the sound the same way when it ends -- so the dialog that sets
 * it, and the arithmetic that says what is left, are written once. What happens when it ends is each session's: both hold the station
 * on the plate, as the viewer's own press would.
 */
@Stable
class SleepTimer {

    /** When the timer ends, on the clock the screen reads it with, or null while none runs. */
    var atMs: Long? by mutableStateOf(null)
        private set

    /** The time it was set for, in minutes: which answer of the dialog is in force. Null with [atMs]. */
    var minutes: Int? by mutableStateOf(null)
        private set

    /** Ends [minutes] from [nowMs], or -- for null -- lets no timer run. */
    fun set(minutes: Int?, nowMs: Long) {
        this.minutes = minutes
        atMs = minutes?.let { sleepDeadlineMs(nowMs, it) }
    }

    /** A timer is over once it has acted, and one that was cleared never acts. */
    fun clear() {
        minutes = null
        atMs = null
    }
}
