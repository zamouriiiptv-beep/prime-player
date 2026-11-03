package com.castivio.playback.api

/**
 * What an engine can say, at the moment it is asked, about how the stream is arriving.
 *
 * Counters, not rates, and cumulative from the open: what the monitor wants is the difference between two readings, and
 * a rate would be one more clock for the engine to keep. Every field is something the engine already holds or counts as
 * it plays -- nothing here is measured specially, fetched from the network or asked of the server -- and null says the
 * engine cannot tell, which the monitor takes as "unknown" and never as zero.
 *
 * A pull, like [PlaybackEngine.sample] and [PlaybackEngine.openingMarks], and for the same reason: nothing is computed
 * until it is asked for, and it is asked for once a second or so while a stream plays and not at all otherwise.
 */
data class QualityReading(
    /**
     * Bytes of the stream received since the engine was asked to open it, or null where the engine does not count them.
     *
     * A counter that goes back is a new count: the monitor starts again from it rather than reading a negative rate.
     */
    val bytesReceived: Long? = null,
    /**
     * Media held ahead of the picture being shown, in milliseconds, or null where the engine does not say.
     *
     * Media3 knows it exactly. LibVLC keeps its own small cache and does not report how full it is as a duration, so it
     * says null and the monitor reads the stream by its stalls and its bytes.
     */
    val bufferedAheadMs: Long? = null,
    /**
     * Cumulative count of things that went wrong on the way without stopping the stream: a load that failed and was tried
     * again, a packet that arrived corrupt, a gap in the transport stream. Null where the engine cannot count them.
     */
    val dataFaults: Long? = null,
)
