package com.castivio.core.common

/**
 * How well the stream that is playing is arriving, in the four words the viewer is told.
 *
 * It is about the stream as it is being received -- the connection to the server that sends it, as the player
 * experiences it -- and not about the picture: a sharp film on a bad connection is [BAD], and a soft channel on a good
 * one is [EXCELLENT]. It says nothing about why: a viewer's own network, a loaded provider and a source that is itself
 * failing look the same from here, and telling them apart would need a request of our own, which is never made.
 *
 * Ordered from best to worst, so a comparison of two is a comparison of their ordinals, and a step towards [BAD] is a
 * step up the list. Lives here, below the playback contract and the screens both, because the engines' monitor decides
 * it, the player carries it, the radio's status reports it and the indicator draws it, and none of them may know the
 * others.
 */
enum class StreamQuality {
    /** Nothing has gone wrong for a long while: no stall, no lost data, and a buffer that stays full. */
    EXCELLENT,

    /** Watchable and recently clean, with a blemish: a stall some minutes ago, a little lost data, a thin buffer. */
    GOOD,

    /** The viewer is seeing it: stalls close together, a buffer that cannot stay filled, data being lost. */
    POOR,

    /** The stream is not getting through: it has stopped, keeps stopping, or the player has given up on it. */
    BAD,
}
