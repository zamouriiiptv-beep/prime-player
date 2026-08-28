package com.castivio.playback.api

/**
 * Fallback policy between playback engines.
 *
 * Media3 is our PRIMARY engine: hardware-accelerated, efficient, supports modern formats.
 * LibVLC is our BACKUP engine: software decoding fallback with vast codec support.
 *
 * This policy governs when failure on PRIMARY can be alleviated by switching to BACKUP.
 */
object FallbackPolicy {

    /**
     * Given the engine that just failed and the error it encountered, return the
     * engine to fall back to, or null if no fallback can help.
     */
    fun nextEngine(current: EngineId, error: PlaybackError): EngineId? = when (current) {
        EngineId.PRIMARY -> if (canBackupHelp(error)) EngineId.BACKUP else null
        EngineId.BACKUP -> null
    }

    /**
     * Determines whether the BACKUP engine (LibVLC) has a realistic chance of
     * recovering playback where the PRIMARY engine (Media3) failed.
     */
    fun canBackupHelp(error: PlaybackError): Boolean = when (error) {
        PlaybackError.DECODER_INIT -> true
        PlaybackError.DECODING -> true
        PlaybackError.CONTAINER -> true
        PlaybackError.TIMEOUT -> true
        PlaybackError.UNKNOWN -> true
        PlaybackError.UNSUPPORTED_FORMAT -> true

        PlaybackError.DRM -> false
        PlaybackError.NETWORK -> false
        PlaybackError.NOT_FOUND -> false
        PlaybackError.PERMISSION -> false
        PlaybackError.SOURCE -> false
    }
}