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
    /**
     * The engine said it was ready: Media3's `STATE_READY`, LibVLC's `Playing`. **The input is running, which is not
     * a picture** -- a decoder can be ready while the surface is still black -- and it is kept apart from
     * [firstFrameMs] so the time between the two can be read. Null where the engine has not said it yet.
     */
    val readyMs: Long? = null,
    /**
     * For an engine that cannot see the frame itself: the playback clock moved after [firstFrameMs]. LibVLC's only
     * evidence, after the video output appearing, that pictures are being shown. Null for an engine that sees frames.
     */
    val clockMovingMs: Long? = null,
    /**
     * Whether [firstFrameMs] is the engine's own word that a frame was drawn (Media3's `onRenderedFirstFrame`), or
     * the nearest thing it says (LibVLC's video output appearing, which can come before the first picture is on the
     * glass). A figure that is not exact is printed as an approximation and is never presented as a measurement.
     */
    val firstFrameExact: Boolean = true,
)
