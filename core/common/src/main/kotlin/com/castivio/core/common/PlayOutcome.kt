package com.castivio.core.common

/**
 * What one listen to a station said about whether the stream works.
 *
 * Four answers, kept apart because they are paid for differently: only a failure rests a station, an unstable listen lowers its place and
 * nothing else, a confirmed one is the one thing that counts as working, and an inconclusive one -- the viewer held the sound, changed the
 * station or left -- says nothing about the stream and writes nothing.
 */
enum class PlayOutcome {
    /** The engine opened the stream and it ran steadily for the whole verification window. */
    CONFIRMED,

    /** The stream opened, and stopped or broke inside the window. It lowers the station's priority; it is never counted as a failure. */
    UNSTABLE,

    /** The engine failed the stream, or never opened it inside the player's own deadlines. The only outcome that rests a station. */
    FAILED,

    /** The viewer held, left or changed the sound before the stream could say. Nothing is recorded. */
    INCONCLUSIVE,
}
