package com.castivio.playback.api

/**
 * Where an open has spent its time so far, as the engine saw it.
 *
 * Every figure is milliseconds after the engine was asked to open the source, on its own
 * monotonic clock, and null for a step that has not happened yet or that this engine cannot
 * see. Together with the player's own figures -- the press reaching it, the previous engine
 * let go, this one built -- they separate what the application spends from what the server,
 * the network and the decoder spend. That is the question a slow channel asks, and a single
 * total cannot answer it: a change made to the wrong part never shows up in the sum.
 */
data class OpeningMarks(
    /** The server answered: the connection is open, any redirect followed, the response begun. */
    val serverAnsweredMs: Long? = null,
    /** The first bytes of the stream arrived. */
    val firstBytesMs: Long? = null,
    /** The stream had been read far enough to say what it carries: its tracks were known. */
    val tracksKnownMs: Long? = null,
    /** The video decoder was ready for the picture. */
    val decoderReadyMs: Long? = null,
    /** How long building that decoder took, when one was built and the engine says. */
    val decoderInitMs: Long? = null,
    /** The decoder the previous source used was kept for this one rather than built again. */
    val decoderKept: Boolean = false,
    /** The first frame was on the screen. */
    val firstFrameMs: Long? = null,
)
