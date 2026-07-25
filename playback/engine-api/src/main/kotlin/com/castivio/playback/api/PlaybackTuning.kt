package com.castivio.playback.api

/**
 * Buffering policy, expressed as data so the engine implementation contains no
 * tuning decisions and the values can be tested without a player.
 *
 * ExoPlayer's defaults optimise for smoothness on mobile networks: 2.5 seconds
 * buffered before playback starts. For live TV that reads as a sluggish zap.
 * Castivio starts playing at half a second and lets the buffer fill behind the
 * picture — the difference between "instant" and "slow" is almost entirely
 * this one number.
 */
data class PlaybackTuning(
    /** Buffered media required before playback begins. The zap-speed dial. */
    val bufferForPlaybackMs: Int,
    /** Required after a rebuffer — slightly higher, to avoid stutter loops. */
    val bufferForPlaybackAfterRebufferMs: Int,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    /**
     * Live must favour time over bytes: a high-bitrate stream should not be
     * allowed to satisfy the buffer with fewer seconds of media.
     */
    val prioritizeTimeOverSizeThresholds: Boolean,
    /** Keep a second player prepared on the neighbouring channel. */
    val prewarmNeighbours: Boolean,
) {
    companion object {
        /** Live TV on a capable device: fastest start, neighbours pre-warmed. */
        val LIVE_FAST = PlaybackTuning(
            bufferForPlaybackMs = 500,
            bufferForPlaybackAfterRebufferMs = 1_000,
            minBufferMs = 5_000,
            maxBufferMs = 20_000,
            prioritizeTimeOverSizeThresholds = true,
            prewarmNeighbours = true,
        )

        /**
         * Live TV on a low-memory box: same fast start, no second player.
         * 20-40 MB for a pre-warmed neighbour is not affordable at 128 MB heap.
         */
        val LIVE_LEAN = LIVE_FAST.copy(
            maxBufferMs = 12_000,
            prewarmNeighbours = false,
        )

        /**
         * On-demand content. Startup latency matters less than uninterrupted
         * playback, and seeking benefits from a deeper buffer.
         */
        val VOD = PlaybackTuning(
            bufferForPlaybackMs = 1_000,
            bufferForPlaybackAfterRebufferMs = 2_000,
            minBufferMs = 15_000,
            maxBufferMs = 50_000,
            prioritizeTimeOverSizeThresholds = false,
            prewarmNeighbours = false,
        )

        /** Unstable connections: absorb jitter, accept a slower start. */
        val RESILIENT = VOD.copy(
            bufferForPlaybackMs = 2_500,
            minBufferMs = 30_000,
            maxBufferMs = 60_000,
        )
    }
}

/** How a failure should be handled, decided from the error rather than blanket-retried. */
data class RetryPolicy(
    val maxAttempts: Int = 4,
    val initialDelayMs: Long = 400,
    val multiplier: Float = 2f,
    val maxDelayMs: Long = 8_000,
) {
    fun delayFor(attempt: Int): Long =
        (initialDelayMs * Math.pow(multiplier.toDouble(), (attempt - 1).toDouble()))
            .toLong().coerceAtMost(maxDelayMs)

    /** Only transient transport failures are worth retrying. */
    fun shouldRetry(error: PlaybackError, attempt: Int): Boolean =
        attempt < maxAttempts && when (error) {
            PlaybackError.NETWORK, PlaybackError.UNKNOWN -> true
            PlaybackError.UNSUPPORTED_FORMAT, PlaybackError.DECODER -> false // try a fallback stream instead
            PlaybackError.DRM, PlaybackError.NOT_FOUND -> false
        }
}
