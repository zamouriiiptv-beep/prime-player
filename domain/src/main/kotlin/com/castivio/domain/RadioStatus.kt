package com.castivio.domain

import com.castivio.core.common.PlayOutcome
import com.castivio.core.common.StreamQuality

/**
 * What the radio's sound is doing, in the words the radio's screen needs.
 *
 * The screen is in a module that must not know the player exists, as the channel board is -- see
 * [PlayerPulse] -- and has three things to say about the sound: which station it is, whether it is
 * sounding, and whether it was stopped by the viewer or by the stream. This is the whole of what
 * crosses, and it is derived from the player's own state and held nowhere else.
 */
data class RadioStatus(
    /** The stream the sound was asked for, or null while nothing is. */
    val url: String? = null,
    val phase: RadioPhase = RadioPhase.IDLE,
    /**
     * How the station's stream is arriving, or null while there is no rating to show: the station is opening, or has just
     * been asked for. The radio's own plate draws the indicator from this, in the place the other players draw it, and
     * still does not know the player exists.
     */
    val quality: StreamQuality? = null,
    /**
     * What this listen proved about the stream, or null while it is still being watched: the engine opened it and it ran steadily for a
     * few seconds ([PlayOutcome.CONFIRMED]), it stopped or broke inside that window (UNSTABLE), it failed (FAILED), or the viewer
     * held or left it first (INCONCLUSIVE). A record for the stations that keep one; nothing the plate draws, and nothing that
     * delays the sound being announced -- see `PlayEvidence`.
     */
    val evidence: PlayOutcome? = null,
) {
    /** The stream that is sounding right now, or null: what marks a station's card as the one playing. */
    val soundingUrl: String? get() = url.takeIf { phase == RadioPhase.PLAYING }

    /** The station is asked for and not stopped: its card and the plate show it, sounding or on its way to it. */
    val active: Boolean get() = url != null && phase != RadioPhase.IDLE
}

/** Where the sound is: nothing asked for, on its way, sounding, held by the viewer, or stopped by the stream. */
enum class RadioPhase {
    /** Nothing is asked for. */
    IDLE,

    /** Asked for, or asked for again after the stream dropped; no sound yet. */
    OPENING,

    /** Sounding. A stall that is being filled is still this: the viewer has not stopped it. */
    PLAYING,

    /** Held by the viewer -- or by the sleep timer, which holds it the same way. */
    PAUSED,

    /** The stream failed or ended, and the player has given up on it. */
    FAILED,
}
