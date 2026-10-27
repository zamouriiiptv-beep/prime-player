package com.castivio.feature.home

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.castivio.domain.Channel
import com.castivio.domain.RadioPhase
import com.castivio.domain.RadioStatus

/**
 * The radio's listening: which station the viewer asked for, whether they are holding it, what the sound
 * says it is doing, and when the sleep timer ends.
 *
 * ## It lives as long as the screen does, and the sound with it
 *
 * Held by whoever composes the radio -- the shell -- in the composition of the radio's own destination, so
 * leaving the radio removes it, and the sound is released with it: it does not go on over Settings, and an
 * application closed or sent to the background does not leave a station playing behind it. There is no
 * background service and no notification, because the viewer asked for neither: the radio is heard while the
 * radio is open.
 *
 * ## What is here and what is not
 *
 * The decisions -- a press starts a station, the play control holds it or asks again, a timer that ends holds
 * the sound the way the viewer would -- and no player: the sound is the shell's, which turns [station] into a
 * stream and reports [status] back. Plain state, so the decisions are tested without a decoder or a screen.
 */
@Stable
class RadioSession {

    /** The station the viewer asked for, or null before the first press and after the sound was let go. */
    var station: Channel? by mutableStateOf(null)
        private set

    /** What the sound is doing, as the player last said it. Idle while nothing is asked for. */
    var status: RadioStatus by mutableStateOf(RadioStatus())
        private set

    /** The viewer is holding the sound -- or the sleep timer is. The player is told, and answers with [status]. */
    var paused: Boolean by mutableStateOf(false)
        private set

    /** How many times the viewer has asked for the same station again after it failed. */
    var retries: Int by mutableIntStateOf(0)
        private set

    /** When the sleep timer ends, on the clock the screen reads it with, or null while none runs. */
    var sleepAtMs: Long? by mutableStateOf(null)
        private set

    /** The time the running timer was set for, in minutes: which of the dialog's answers is in force. Null with [sleepAtMs]. */
    var sleepMinutes: Int? by mutableStateOf(null)
        private set

    /** A press on a station: it plays, from the start, and what was held is held no more. */
    fun play(next: Channel) {
        station = next
        paused = false
    }

    /**
     * The play control. A station that failed is asked for again; any other is held, or let go of --
     * the same control, so what it shows is what it does.
     */
    fun toggle() {
        if (station == null) return
        if (status.phase == RadioPhase.FAILED) {
            paused = false
            retries += 1
        } else {
            paused = !paused
        }
    }

    /** The player's report. Applied as it is: the screen draws what the sound is doing, not what it was asked to. */
    fun heard(report: RadioStatus) {
        status = report
    }

    /**
     * The sound was let go -- by the application going to the background, which ends it rather than leaving
     * it behind. Nothing is asked for any more, so the screen goes back to the stations as it was first opened.
     */
    fun release() {
        station = null
        paused = false
        sleepAtMs = null
        sleepMinutes = null
        status = RadioStatus()
    }

    /** Hold the sound after [minutes] from [nowMs], or -- for null -- let no timer run. */
    fun sleepIn(minutes: Int?, nowMs: Long) {
        sleepMinutes = minutes
        sleepAtMs = minutes?.let { sleepDeadlineMs(nowMs, it) }
    }

    /**
     * The timer ended: the sound is held as the viewer's own press would hold it, so the station stays on the
     * plate and one press on play brings it back. A timer is over once it has acted.
     */
    fun sleepEnded() {
        sleepAtMs = null
        sleepMinutes = null
        if (station != null) paused = true
    }
}
