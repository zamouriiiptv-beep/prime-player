package com.castivio.domain

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
