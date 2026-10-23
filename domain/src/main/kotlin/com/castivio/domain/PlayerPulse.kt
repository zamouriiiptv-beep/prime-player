package com.castivio.domain

import com.castivio.core.common.FrameSize

/**
 * What the one player is doing, in the words the screens beside it need.
 *
 * The channel board is in a module that must not know the player exists, and still has three
 * things to say about it: which row is playing, whether the preview is opening or showing, and
 * what the picture measures. This is the whole of what crosses, and it is derived from the
 * player's own state -- the request and the picture it is drawing -- and held nowhere else.
 */
data class PlayerPulse(
    /** The stream the player was asked for. */
    val url: String,
    val phase: PulsePhase,
    /** The decoded picture's size once the decoder has said, whatever the phase. */
    val frame: FrameSize? = null,
    /**
     * Opening has been silent long enough for the player to say so: the moment its own spinner
     * would turn. Only ever true in [PulsePhase.OPENING], and it is the player's call, not the
     * board's -- the delay is one number, and it lives in the player.
     */
    val waiting: Boolean = false,
) {
    /** The stream with a picture on it, or null: the only thing that marks a row as playing. */
    val playingUrl: String? get() = url.takeIf { phase == PulsePhase.SHOWING }
}

/** Where the stream is on its way to a picture, and after it. */
enum class PulsePhase {
    /** Asked for; no frame yet. The preview has nothing of the channel to show. */
    OPENING,

    /** A frame has been drawn and the source has not stopped since. */
    SHOWING,

    /** It stopped: failed, ended or being reopened. The player's own card says which. */
    STOPPED,
}
