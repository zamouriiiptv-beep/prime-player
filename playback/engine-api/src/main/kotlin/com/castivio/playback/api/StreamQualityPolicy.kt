package com.castivio.playback.api

import com.castivio.core.common.StreamQuality

/**
 * Everything the verdict on a stream is made from, as the monitor has gathered it: what a viewer has seen, and what the
 * engine has said about the data arriving. Plain numbers, so a verdict is checked without an engine, a clock or a device.
 */
data class QualityEvidence(
    /** The player has given the stream up for a reason that is the connection's: a network failure, a timeout, a refusal. */
    val failed: Boolean = false,
    /** How long the stream has been stalled right now -- a picture that stopped for want of data -- or 0 while it runs. */
    val stalledNowMs: Long = 0,
    /** Stalls that began inside [StreamQualityPolicy.STALL_WINDOW_MS], the one in progress included. */
    val stallsInWindow: Int = 0,
    /** Stalls that began inside [StreamQualityPolicy.LONG_WINDOW_MS]. */
    val stallsInLongWindow: Int = 0,
    /** How long no byte has arrived, or null where the engine does not count bytes. */
    val silentMs: Long? = null,
    /**
     * The buffer as a share of what it usually holds, 0 to 1, or null where there is no usual to compare with.
     *
     * Relative on purpose, and null for a stream that has never had a cushion: a live channel that a provider sends at
     * exactly the speed it plays holds next to nothing at its best, a film holds tens of seconds, and one absolute
     * figure for "thin" would call every live channel starved. A buffer is only evidence when it has been something and
     * has stopped being it.
     */
    val bufferRatio: Double? = null,
    /** Bytes are arriving at well under the rate this stream has been arriving at, over the last seconds. */
    val rateDrop: Boolean = false,
    /** Faults that arrived inside [StreamQualityPolicy.FAULT_WINDOW_MS]. */
    val faultsInWindow: Int = 0,
)

/**
 * A rating, and whether it stands on something the viewer can see.
 *
 * A [firm] verdict is a stall, a stopped stream or a failure: it is shown at once, because the viewer is looking at it. A
 * soft one -- a thin buffer, a slowing rate, lost packets -- has to hold for a while before the indicator moves, so a
 * figure that wobbles does not make the colour wobble with it. See [StreamQualityMonitor].
 */
data class QualityVerdict(val quality: StreamQuality, val firm: Boolean)

/**
 * What the evidence says the stream is, and every number that says it.
 *
 * Pure, with the thresholds in one place and named, the way [FallbackPolicy] keeps the opening's. **The figures are a
 * first reading of what a viewer notices, not measurements of any provider**: a stall that lasts a second is felt, three in
 * a minute is a stream that is not working, a stall five minutes ago is a blemish and nothing now. They are meant to be
 * tuned against real playback, and being here and named is what lets that be one edit and the tests say what moved.
 *
 * ## What is read, and what is deliberately not
 *
 * Stalls and failure are what the viewer sees, and decide first. Data that stops arriving matters only when the buffer is
 * not carrying the picture through it: a film's player stops reading when its buffer is full, and silence then is health.
 * The buffer is read as a share of its own recent best, and a rate that drops as a share of its own recent median, because
 * the absolute scale differs between a live channel, a film and a radio station and nothing here may assume which it is.
 * The opening's speed is not read at all: how long a channel took to start says how busy a server was then, not how the
 * stream is arriving now.
 */
object StreamQualityPolicy {

    /** The stretch inside which stalls decide how bad it is now: a minute. */
    const val STALL_WINDOW_MS = 60_000L

    /** The stretch inside which a stall still counts as a blemish: five minutes. */
    const val LONG_WINDOW_MS = 300_000L

    /** The stretch inside which lost data is counted: half a minute. */
    const val FAULT_WINDOW_MS = 30_000L

    /** A stall in progress this long is a stream that is not getting through. */
    const val BAD_STALL_NOW_MS = 4_000L

    /** A stall in progress this long is felt. */
    const val POOR_STALL_NOW_MS = 1_000L

    /** Stalls in [STALL_WINDOW_MS] that mean the stream is not working. */
    const val BAD_STALLS_IN_WINDOW = 3

    /** Stalls in [STALL_WINDOW_MS] that mean the viewer is being interrupted. */
    const val POOR_STALLS_IN_WINDOW = 2

    /** No bytes for this long, with nothing in the buffer to carry the picture, is a stream that has stopped. */
    const val STARVED_SILENCE_MS = 3_000L

    /** A buffer under this share of what it usually holds is thin: the viewer is close to a stall. */
    const val BUFFER_LOW_RATIO = 0.25

    /** A buffer under this share of what it usually holds is not as full as it was; at or over it, silence is health. */
    const val BUFFER_HEALTHY_RATIO = 0.5

    /** Lost data in [FAULT_WINDOW_MS] that is the stream degrading and not a blemish. */
    const val POOR_FAULTS_IN_WINDOW = 3

    /** The verdict on [evidence]. */
    fun verdict(evidence: QualityEvidence): QualityVerdict {
        val ratio = evidence.bufferRatio
        // Carrying the picture: enough media is held for a stop in the data not to be felt yet. Unknown is not carrying.
        val carried = ratio != null && ratio >= BUFFER_HEALTHY_RATIO
        val silent = evidence.silentMs
        val starved = silent != null && silent >= STARVED_SILENCE_MS && !carried

        // What the viewer sees decides first, and is shown at once.
        if (evidence.failed ||
            evidence.stalledNowMs >= BAD_STALL_NOW_MS ||
            evidence.stallsInWindow >= BAD_STALLS_IN_WINDOW ||
            starved
        ) {
            return QualityVerdict(StreamQuality.BAD, firm = true)
        }
        if (evidence.stalledNowMs >= POOR_STALL_NOW_MS || evidence.stallsInWindow >= POOR_STALLS_IN_WINDOW) {
            return QualityVerdict(StreamQuality.POOR, firm = true)
        }

        // What is coming: it has to hold before the indicator moves.
        // A rate that drops is only a fault when no buffer is carrying it: a film's player stops reading when it is full.
        val slow = evidence.rateDrop && !carried
        val thin = ratio != null && ratio < BUFFER_LOW_RATIO
        if (thin || slow || evidence.faultsInWindow >= POOR_FAULTS_IN_WINDOW) {
            return QualityVerdict(StreamQuality.POOR, firm = false)
        }

        val notFull = ratio != null && ratio < BUFFER_HEALTHY_RATIO
        if (evidence.stallsInLongWindow >= 1 || evidence.faultsInWindow >= 1 || notFull) {
            return QualityVerdict(StreamQuality.GOOD, firm = false)
        }
        return QualityVerdict(StreamQuality.EXCELLENT, firm = false)
    }
}
