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
     * The picture's width divided by its height, or null while there is no picture.
     *
     * Null for a music file, and null before the decoder has said — those are the same
     * thing to a screen that has to decide how big to make the surface, and both mean
     * "nothing to shape yet".
     *
     * Corrected for non-square pixels. Anamorphic sources declare a sample aspect ratio and
     * a player that ignores it shows a 4:3 picture of a 16:9 film, which is the same defect
     * as having no aspect handling at all, arrived at more carefully.
     *
     * On the contract because the screen cannot lay out a picture whose shape it does not
     * know, and [sample] is explicitly not the way to ask: that is a pull for a panel
     * nobody has opened, and this is needed on the first frame of every source.
     */
    val videoAspectRatio: StateFlow<Float?>

    /**
     * Why the last failure happened, in full, or null while nothing has failed.
     *
     * Separate from [PlaybackState.Failed] because the two have different audiences. The
     * state carries a [PlaybackError] because that is what the screen branches on; this
     * carries the evidence, because an enum value is not a diagnosis and the difference
     * cost a real debugging round on a real device.
     *
     * Cleared on every open, so a report always describes the attempt in front of you.
     */
    val diagnosis: StateFlow<PlaybackDiagnosis?>

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

/**
 * Why playback stopped, at the granularity the product can actually act on.
 *
 * ## Why this list grew
 *
 * It used to be six values, and one of them — `DECODER` — was doing the work of five. The
 * consequence was found on a device: a real MP4 failed, and the card said "format not
 * supported" because the taxonomy had nowhere else to put it. It had not been established
 * that anything was unsupported; that was simply the label the last branch reached.
 *
 * So every distinct thing that can go wrong now has a name, and the rule is:
 *
 * > nothing is called [UNSUPPORTED_FORMAT] unless the platform said so.
 *
 * [UNKNOWN] stays unknown. An unclassified failure relabelled as a codec problem sends the
 * user looking for a different file when the fault may be a permission, a timeout or a
 * data source — and sends whoever reads the report looking in the wrong place.
 */
enum class PlaybackError {
    /** The transport failed: no route, a refused connection, a timeout in the stack. */
    NETWORK,

    /** The host or the file answered "not here". */
    NOT_FOUND,

    /**
     * The bytes could not be opened for reading — as distinct from not existing.
     *
     * A `content://` URI whose grant was revoked, a file the process may not read, a
     * cleartext link on a network-security config that forbids one. The stream is there and
     * we are not allowed to have it.
     */
    PERMISSION,

    /**
     * A data source failed for a reason that is neither the network nor a permission.
     *
     * A scheme nothing claims, a local read that failed mid-file, a provider that closed
     * the descriptor. Kept apart from [NETWORK] because the user's next move differs: one
     * is "check the connection" and the other is "this file is not readable".
     */
    SOURCE,

    /** The container or manifest could not be parsed. The bytes arrived and made no sense. */
    CONTAINER,

    /**
     * A decoder exists for this format and would not start.
     *
     * `MediaCodec` configure or start failed — a busy decoder, an out-of-resources device,
     * a profile the codec advertises and does not honour. **This is not "unsupported"**:
     * the platform listed a decoder, and the decoder refused. It is the case where trying
     * the next decoder in the device's list is worth doing, which is exactly what the
     * backup engine changes.
     */
    DECODER_INIT,

    /** A decoder started and then failed on the stream. */
    DECODING,

    /**
     * The platform has no decoder for this format at all.
     *
     * Only ever set from a platform answer that says so — `ERROR_CODE_DECODING_FORMAT_
     * UNSUPPORTED`, or a decoder query that came back empty. Never a default.
     */
    UNSUPPORTED_FORMAT,

    /** Protected content this device cannot get keys for. */
    DRM,

    /**
     * No frame arrived inside the opening budget, and nothing reported an error.
     *
     * Its own reason because it is its own situation: the engine said nothing at all. It
     * used to be reported as a decoder failure, which meant a slow source was described to
     * the user as an unplayable one.
     */
    TIMEOUT,

    /** Something failed and did not identify itself. Left exactly that way. */
    UNKNOWN,
}

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
