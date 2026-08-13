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

    /**
     * When the first frame reached the screen, on the platform's monotonic clock, or
     * null while none has.
     *
     * The single most important signal in the player, and the reason it is a first-class
     * part of the contract rather than something inferred. `Playing` is not it: a decoder
     * can report ready, and the surface can still be black for another two hundred
     * milliseconds. What the user calls "the channel opened" is this, so this is what the
     * loading overlay waits for, what the fallback deadline is measured against, and what
     * the start-up figure in the statistics panel reports.
     */
    val firstFrameAtMs: StateFlow<Long?>

    /**
     * Where to draw. Null detaches, which is what leaving the screen must do before the
     * view goes away.
     */
    fun setVideoOutput(output: VideoOutput?)

    /**
     * The clock, polled rather than pushed.
     *
     * A position that moves every frame is 25 emissions a second through a `StateFlow`,
     * every one of which recomposes whatever reads it, in order to move a caption that
     * changes once a second. The screen ticks four times a second and reads these; the
     * engine keeps no timer of its own.
     */
    val positionMs: Long
    val bufferedPositionMs: Long

    /** Null for a live stream with no known end. */
    val durationMs: Long?

    /** False for live without a DVR window — the seek controls are not drawn at all. */
    val isSeekable: Boolean

    fun open(media: MediaRequest)
    fun play()
    fun pause()
    fun stop()

    /** No-op for live streams without a seekable window. */
    fun seekTo(positionMs: Long)

    fun selectTrack(track: Track)
    fun setSpeed(speed: Float)
    fun setAspect(mode: AspectMode)

    /**
     * Everything the statistics panel shows, read at the moment it is asked for.
     *
     * A pull and not a flow, and that is the performance contract in one method
     * signature. A `StateFlow<PlaybackStats>` would have to be *maintained* — sampled on
     * a timer, updated on every renderer callback — from the moment the engine starts,
     * which puts codec strings, bitrate arithmetic and dropped-frame counters on the path
     * to the first frame in order to serve a panel almost nobody opens.
     *
     * Nothing calls this until the user opens the panel. Null before there is anything to
     * report.
     */
    fun sample(): PlaybackSample?

    fun release()
}

/**
 * A reading of the engine, taken on demand.
 *
 * Every field is something the engine already knows — none of it is computed, fetched or
 * measured specially — so taking a reading is cheap and taking none costs nothing.
 * [startupMs] is the one figure that is not instantaneous: it is the gap between the open
 * call and the first frame, kept because it is the number this whole player is judged by.
 */
data class PlaybackSample(
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Float? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val bitrateBps: Long? = null,
    val bufferedMs: Long = 0,
    val droppedFrames: Int = 0,
    val startupMs: Long? = null,
    val engine: EngineId = EngineId.PRIMARY,
)

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
