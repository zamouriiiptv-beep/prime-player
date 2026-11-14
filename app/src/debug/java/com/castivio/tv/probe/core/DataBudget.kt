package com.castivio.tv.probe.core

/**
 * How much data the session may use, and when to say so.
 *
 * The owner was told how much the session would download before pressing Start: [announcedBytes], the number of
 * full downloads in the plan times the size of the one reply as it was measured before this tool existed. The reply
 * is measured again by the first run, and if the plan would cost more than announced the session says so **before
 * the next download**, not after it.
 *
 *  - [Level.SOFT]: the data received, or the projection, is more than the announced figure by the soft factor.
 *    The session pauses and asks; [acknowledge] takes the new figure as the one to be measured against.
 *  - [Level.HARD]: the data received is past the hard factor of the figure in force. The session stops, and no
 *    acknowledgement lifts it.
 */
class DataBudget(
    private val plannedDownloads: Int,
    priorReplyBytes: Long,
    private val softFactor: Double,
    private val hardFactor: Double,
    private val runawayFactor: Double,
) {
    enum class Level { WITHIN, SOFT, HARD }

    /** What the owner was told at the start. */
    val announcedBytes: Long = plannedDownloads.toLong() * priorReplyBytes

    /** The figure the limits are measured against: the announced one, until the owner agrees to a larger. */
    private var base: Long = announcedBytes

    var referenceBytes: Long = priorReplyBytes
        private set

    var receivedBytes: Long = 0L
        private set

    /** What the whole plan would cost at the reply's size as last measured. */
    val projectedBytes: Long get() = plannedDownloads.toLong() * referenceBytes

    /** One run that receives more than this is not a download any more. */
    val runLimitBytes: Long get() = (referenceBytes * runawayFactor).toLong()

    /** A run has measured the one reply: the projection follows it. */
    fun adoptReference(replyBytes: Long) {
        if (replyBytes > 0L) referenceBytes = replyBytes
    }

    fun add(bytes: Long) {
        if (bytes > 0L) receivedBytes += bytes
    }

    fun level(): Level {
        val soft = (base * softFactor).toLong()
        val hard = (base * hardFactor).toLong()
        return when {
            receivedBytes > hard -> Level.HARD
            receivedBytes > soft || projectedBytes > soft -> Level.SOFT
            else -> Level.WITHIN
        }
    }

    /**
     * The owner has seen the figure and goes on: the projection, or what has been received, is the new base.
     *
     * Only for a soft alert. Past the hard limit nothing is acknowledged: the session has to stop, and this does
     * nothing, so that no caller can lift the limit by asking.
     */
    fun acknowledge() {
        if (level() == Level.HARD) return
        base = maxOf(base, projectedBytes, receivedBytes)
    }
}
