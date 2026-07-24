package com.castivio.playback.api

import kotlinx.coroutines.flow.StateFlow

/**
 * The boundary between Castivio's player UI and whatever actually decodes video.
 *
 * IPTV streams are not well-behaved: providers ship broken HLS manifests, odd
 * audio codecs and MPEG-TS that one engine rejects and another plays happily.
 * Binding the UI to ExoPlayer directly would make that unfixable, so the player
 * screen talks only to this interface. Swapping engines — or falling back
 * per-stream when one fails — touches no UI code.
 */
interface PlaybackEngine {

    val state: StateFlow<PlaybackState>
    val tracks: StateFlow<TrackSet>

    fun open(media: MediaRequest)
    fun play()
    fun pause()
    fun stop()

    /** No-op for live streams without a seekable window. */
    fun seekTo(positionMs: Long)

    fun selectTrack(track: Track)
    fun setSpeed(speed: Float)
    fun setAspect(mode: AspectMode)

    fun release()
}

data class MediaRequest(
    val url: String,
    val kind: MediaKind,
    val headers: Map<String, String> = emptyMap(),
    val userAgent: String? = null,
    /** Non-null only when the provider exposes an archive for this stream. */
    val timeshift: TimeshiftWindow? = null,
)

enum class MediaKind { LIVE, VOD, SERIES_EPISODE }

/**
 * Present only when the provider genuinely supports catch-up. The player renders
 * no rewind affordance when this is null — Castivio never shows a disabled control.
 */
data class TimeshiftWindow(val durationMs: Long, val startEpochMs: Long)

sealed interface PlaybackState {
    data object Idle : PlaybackState
    data object Opening : PlaybackState
    /** [bufferedMs] and [bitrateBps] are surfaced to the user; support asks for them. */
    data class Buffering(val bufferedMs: Long, val bitrateBps: Long) : PlaybackState
    data class Playing(val positionMs: Long, val durationMs: Long?, val bitrateBps: Long) : PlaybackState
    data class Paused(val positionMs: Long) : PlaybackState
    data class Failed(val reason: PlaybackError, val cause: Throwable? = null) : PlaybackState
    data object Ended : PlaybackState
}

enum class PlaybackError { NETWORK, UNSUPPORTED_FORMAT, DECODER, DRM, NOT_FOUND, UNKNOWN }

data class TrackSet(
    val audio: List<Track> = emptyList(),
    val subtitle: List<Track> = emptyList(),
    val video: List<Track> = emptyList(),
)

data class Track(
    val id: String,
    val type: TrackType,
    val label: String,
    val language: String? = null,
    val channels: Int? = null,
    val selected: Boolean = false,
)

enum class TrackType { AUDIO, SUBTITLE, VIDEO }

enum class AspectMode { FIT, FILL, RATIO_16_9, RATIO_4_3, ZOOM }
