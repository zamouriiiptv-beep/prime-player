package com.castivio.feature.home

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.castivio.domain.RadioPhase
import com.castivio.domain.RadioStatus
import com.castivio.domain.WorldStation

/** Which of the radio's two sources is open: the subscription's own stations, or the world's directory. */
enum class RadioSource { Subscription, World }

/**
 * The world radio's listening: which station the viewer asked for, at which address, whether they are holding it, what the sound says it
 * is doing, and when the sleep timer ends.
 *
 * ## Its own session, not a slot in [RadioSession]
 *
 * A station of the directory is not a [com.castivio.domain.Channel] and is never turned into one for the subscription's session, the
 * shell's recently-watched or the subscription's favourites -- those would take it for something the subscription carries. So the
 * subscription's session is as it was, and this is a second one with the same shape. The shell holds both and plays whichever source is
 * open; opening the other lets the first go.
 *
 * ## The address is the session's, not the station's
 *
 * A station has two addresses -- the one it was submitted with and the one the directory resolved it to -- and when the first does not
 * play, the other is asked for once before the viewer is told it failed ([url]). A report about any stream but the one now asked for is
 * the previous station's, arriving late, and is dropped: what the plate says is the sound of what was asked for last.
 *
 * Plain state, so the decisions are tested without a decoder or a screen.
 */
@Stable
class WorldRadioSession {

    /** The station the viewer asked for, or null before the first press and after the sound was let go. */
    var station: WorldStation? by mutableStateOf(null)
        private set

    /** The address the sound was asked for: the station's first choice, or its other one after the first failed. Null with [station]. */
    var url: String? by mutableStateOf(null)
        private set

    /** What the sound is doing, as the player last said it about [url]. Idle while nothing is asked for. */
    var status: RadioStatus by mutableStateOf(RadioStatus())
        private set

    /** The viewer is holding the sound -- or the sleep timer is. */
    var paused: Boolean by mutableStateOf(false)
        private set

    /** How many times the viewer has asked for the same stream again after it failed. */
    var retries: Int by mutableIntStateOf(0)
        private set

    /**
     * Which listen this is: a new number for every stream asked for -- a station pressed, its other address, a retry on a new one -- so
     * what a listen proved is written down once, against the listen it belongs to.
     */
    var listen: Int by mutableIntStateOf(0)
        private set

    /** The sleep timer, the same one the subscription's radio has: see [SleepTimer]. */
    val sleep = SleepTimer()

    private var otherTried = false

    /** A press on a station: it plays, from the start, and what was held is held no more. The same station again is not asked for twice. */
    fun play(next: WorldStation) {
        if (station?.uuid == next.uuid) {
            if (failedNow()) toggle() else paused = false
            return
        }
        station = next
        url = next.streamUrl
        otherTried = false
        paused = false
        status = RadioStatus()
        listen += 1
    }

    /**
     * The play control. A stream that failed is asked for again -- from the station's first address, with the other still in hand --
     * and any other is held, or let go of: the same control, so what it shows is what it does.
     */
    fun toggle() {
        val current = station ?: return
        if (failedNow()) {
            paused = false
            otherTried = false
            // Nothing of the failed listen is left standing: its proof was written against it, and the plate says "connecting" at once.
            status = RadioStatus()
            if (url != current.streamUrl) {
                // A new address is a new request, which the route opens by itself; asking again as well would open it twice.
                url = current.streamUrl
            } else {
                retries += 1
            }
            listen += 1
        } else {
            paused = !paused
        }
    }

    /**
     * The player's report. Applied as it is -- the screen draws what the sound is doing, not what it was asked to -- except that a report
     * about another stream is dropped, and that the first failure of a station that has another address is not shown: the other is asked for.
     */
    fun heard(report: RadioStatus) {
        val current = station ?: return
        if (report.url != url) return
        val other = current.alternateUrl
        if (report.phase == RadioPhase.FAILED && !otherTried && other != null) {
            otherTried = true
            url = other
            status = RadioStatus()
            listen += 1
            return
        }
        status = report
    }

    /**
     * The sound was let go -- by the application going to the background, by the other source being opened, or by leaving the radio.
     * Nothing is asked for any more, so the screen goes back to the stations as it was first opened.
     */
    fun release() {
        station = null
        url = null
        otherTried = false
        paused = false
        sleep.clear()
        status = RadioStatus()
    }

    /** Hold the sound after [minutes] from [nowMs], or -- for null -- let no timer run. */
    fun sleepIn(minutes: Int?, nowMs: Long) = sleep.set(minutes, nowMs)

    /** The timer ended: the sound is held as the viewer's own press would hold it, so the station stays on the plate. */
    fun sleepEnded() {
        sleep.clear()
        if (station != null) paused = true
    }

    private fun failedNow(): Boolean = status.phase == RadioPhase.FAILED && status.url == url
}
