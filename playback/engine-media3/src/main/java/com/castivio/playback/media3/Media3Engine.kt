package com.castivio.playback.media3

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.castivio.playback.api.AspectMode
import com.castivio.playback.api.EngineId
import com.castivio.playback.api.MediaKind
import com.castivio.playback.api.MediaRequest
import com.castivio.playback.api.PlaybackEngine
import com.castivio.playback.api.PlaybackError
import com.castivio.playback.api.PlaybackSample
import com.castivio.playback.api.PlaybackState
import com.castivio.playback.api.PlaybackTuning
import com.castivio.playback.api.Track
import com.castivio.playback.api.TrackSet
import com.castivio.playback.api.TrackType
import com.castivio.playback.api.VideoOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ExoPlayer, behind Castivio's engine contract.
 *
 * ## The whole file is about one number
 *
 * The time between a URL arriving and a frame appearing. Everything below is either on
 * that path and made as short as possible, or off it and kept off it. Where a choice
 * exists between a feature and a millisecond, the millisecond wins — that is not a
 * preference, it is the product's stated contract, and the places it is exercised are
 * marked so a later change has to argue with a comment rather than with nothing.
 *
 * The three that matter most:
 *
 * **`bufferForPlaybackMs` is 500, not ExoPlayer's 2500.** This single figure is most of
 * the perceived difference between a fast player and a slow one. The buffer fills behind
 * the picture instead of in front of it.
 *
 * **No track enumeration before the first frame.** The track lists are built from
 * `onTracksChanged`, which the player raises when it already knows — nothing here asks.
 *
 * **No statistics until [sample] is called.** There is no timer, no counter and no
 * listener maintaining a bitrate. `sample` reads what the player is already holding.
 *
 * ## Two profiles, one class
 *
 * [EngineProfile.PRIMARY] and [EngineProfile.BACKUP] differ only in how they are
 * constructed — decoders, extractors and how forgiving the reader is. They are not two
 * classes because they are not two behaviours: the same code plays the stream, and what
 * changes is what it is willing to accept. A second class would be a second place for the
 * state mapping to drift.
 *
 * The backup is genuinely a different engine in the way that matters — it decodes in
 * software when hardware refuses, and reads MPEG-TS that the strict path rejects, which
 * together are the overwhelming majority of "this channel will not play" on IPTV. What it
 * is not is a second decoding library: there is no libVLC here, and adding one is a
 * dependency decision rather than an implementation detail.
 */
@OptIn(UnstableApi::class)
class Media3Engine(
    context: Context,
    private val profile: EngineProfile,
    private val tuning: PlaybackTuning,
) : PlaybackEngine {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _tracks = MutableStateFlow(TrackSet())
    override val tracks: StateFlow<TrackSet> = _tracks.asStateFlow()

    private val _firstFrameAtMs = MutableStateFlow<Long?>(null)
    override val firstFrameAtMs: StateFlow<Long?> = _firstFrameAtMs.asStateFlow()

    /** When [open] was called, so the start-up figure is a measurement and not a guess. */
    private var openedAtMs: Long = 0

    private var videoFormat: Format? = null
    private var audioFormat: Format? = null

    /**
     * Built eagerly with the engine, because the first thing that happens to an engine is
     * that something opens a URL on it. Deferring construction would move the renderer and
     * decoder setup — tens of milliseconds of it — from where nobody is waiting to the
     * exact moment somebody is.
     */
    private val player: ExoPlayer = buildPlayer()

    private fun buildPlayer(): ExoPlayer {
        val renderers = DefaultRenderersFactory(appContext).apply {
            when (profile) {
                // Hardware only, and fail rather than crawl: a software fallback that
                // engages silently turns a fast channel into a hot phone at four frames a
                // second, and the user has no way to know why. When hardware refuses, the
                // backup engine is the answer, and it is chosen deliberately.
                EngineProfile.PRIMARY -> {
                    setEnableDecoderFallback(false)
                    setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                }
                // Everything the primary refused. Decoder fallback walks down the device's
                // decoder list instead of giving up on the first one, which is what plays
                // the odd profiles providers ship.
                EngineProfile.BACKUP -> {
                    setEnableDecoderFallback(true)
                    setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                }
            }
        }

        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                tuning.minBufferMs,
                tuning.maxBufferMs,
                tuning.bufferForPlaybackMs,
                tuning.bufferForPlaybackAfterRebufferMs,
            )
            .setPrioritizeTimeOverSizeThresholds(tuning.prioritizeTimeOverSizeThresholds)
            .build()

        val http = DefaultHttpDataSource.Factory()
            // Providers redirect http to https and back, sometimes twice. Refusing to
            // follow is the single most common cause of a stream that "does not exist"
            // and plays perfectly in every other application.
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)

        val extractors = DefaultExtractorsFactory().apply {
            if (profile == EngineProfile.BACKUP) {
                // An IPTV transport stream frequently declares nothing useful in its PMT.
                // The strict reader believes the declaration and finds no tracks; this one
                // looks for the streams that are actually there.
                setTsExtractorFlags(
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
                        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                        DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS,
                )
                setConstantBitrateSeekingEnabled(true)
            }
        }

        val sources = DefaultMediaSourceFactory(
            DefaultDataSource.Factory(appContext, http),
            extractors,
        )

        return ExoPlayer.Builder(appContext)
            .setRenderersFactory(renderers)
            .setLoadControl(load)
            .setMediaSourceFactory(sources)
            .setTrackSelector(DefaultTrackSelector(appContext))
            .build()
            .also { it.addListener(listener) }
    }

    override fun setVideoOutput(output: VideoOutput?) {
        when (output) {
            null -> player.clearVideoSurface()
            is VideoOutput.Platform -> {
                val view = output.view
                require(view is SurfaceView) {
                    "Media3Engine draws into a SurfaceView; it was handed a ${view.javaClass.name}"
                }
                player.setVideoSurfaceView(view)
            }
        }
    }

    /**
     * The critical path, in full.
     *
     * Six statements, and nothing in them touches the network except the player itself.
     * No metadata lookup, no guide, no artwork, no probe of the URL to decide anything —
     * a HEAD request "just to check" would put a whole round trip in front of the picture
     * to learn something the player is about to find out anyway.
     */
    override fun open(media: MediaRequest) {
        // Wrapped, because everything below can throw on a real device and none of it is
        // worth ending the process for: a content URI whose permission was revoked while
        // the list was on screen, a scheme no data source claims, a malformed link out of
        // a provider. Each of those is a card the user can act on, and a crash is not.
        runCatching { openInternal(media) }.onFailure { error ->
            Log.e(TAG, "open failed for ${media.url.take(URL_IN_LOG)}", error)
            _state.value = PlaybackState.Failed(PlaybackError.UNKNOWN, error)
        }
    }

    private fun openInternal(media: MediaRequest) {
        openedAtMs = SystemClock.elapsedRealtime()
        _firstFrameAtMs.value = null
        _tracks.value = TrackSet()
        videoFormat = null
        audioFormat = null
        _state.value = PlaybackState.Opening

        val item = MediaItem.Builder()
            .setUri(media.url)
            // Live wants the edge, not the start of the window. Without this a channel
            // with a long DVR window opens minutes behind and the user thinks the app is
            // showing them the wrong programme.
            .apply {
                if (media.kind == MediaKind.LIVE) {
                    setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder()
                            .setTargetOffsetMs(LIVE_TARGET_OFFSET_MS)
                            .build(),
                    )
                }
            }
            .build()

        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    override fun play() {
        player.playWhenReady = true
    }

    override fun pause() {
        player.playWhenReady = false
    }

    override fun stop() {
        player.stop()
        player.clearMediaItems()
        _state.value = PlaybackState.Idle
        _firstFrameAtMs.value = null
    }

    override fun seekTo(positionMs: Long) {
        if (player.isCurrentMediaItemSeekable) player.seekTo(positionMs)
    }

    override fun selectTrack(track: Track) {
        val groups = player.currentTracks.groups
        for (group in groups) {
            for (i in 0 until group.length) {
                if (trackId(group, i) != track.id) continue
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                    .build()
                return
            }
        }
    }

    override fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
    }

    override fun setAspect(mode: AspectMode) {
        // The scaling a `SurfaceView` does is the view's, not the player's, so this is
        // recorded and read by the composition rather than pushed into ExoPlayer.
        aspect = mode
    }

    /** Read by the player screen to size its surface. */
    @Volatile
    var aspect: AspectMode = AspectMode.FIT
        private set

    /**
     * One reading, from what the player already holds.
     *
     * Every line is a field access. Nothing here starts a measurement, and nothing here
     * runs unless the user has opened the panel.
     */
    override fun sample(): PlaybackSample? {
        if (_state.value is PlaybackState.Idle) return null
        val video = videoFormat
        val audio = audioFormat
        val started = _firstFrameAtMs.value
        return PlaybackSample(
            width = video?.width?.takeIf { it > 0 },
            height = video?.height?.takeIf { it > 0 },
            frameRate = video?.frameRate?.takeIf { it > 0f },
            videoCodec = video?.sampleMimeType,
            audioCodec = audio?.sampleMimeType,
            bitrateBps = currentBitrate(),
            bufferedMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0),
            droppedFrames = droppedFrames,
            startupMs = started?.let { it - openedAtMs },
            engine = profile.id,
        )
    }

    override fun release() {
        player.removeListener(listener)
        player.release()
        _state.value = PlaybackState.Idle
    }

    /* ------------------------------------------------------------------- the player */

    private var droppedFrames: Int = 0

    private fun currentBitrate(): Long? {
        val declared = videoFormat?.bitrate ?: Format.NO_VALUE
        if (declared != Format.NO_VALUE) return declared.toLong()
        val estimate = player.currentTracks.groups.firstOrNull()?.mediaTrackGroup
            ?.getFormat(0)?.averageBitrate ?: Format.NO_VALUE
        return estimate.takeIf { it != Format.NO_VALUE }?.toLong()
    }

    private val listener = object : Player.Listener {

        /**
         * The moment that matters. Everything the loading state is waiting for happens
         * here, and it is the platform telling us rather than us inferring it.
         */
        override fun onRenderedFirstFrame() {
            if (_firstFrameAtMs.value == null) {
                _firstFrameAtMs.value = SystemClock.elapsedRealtime()
            }
            pushPlaying()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING ->
                    // Opening and rebuffering are the same ExoPlayer state and two
                    // different screens: one has no picture behind it and one does. The
                    // first frame is what separates them, so it is what is asked.
                    _state.value = if (_firstFrameAtMs.value == null) {
                        PlaybackState.Opening
                    } else {
                        PlaybackState.Buffering(
                            bufferedMs = (player.bufferedPosition - player.currentPosition)
                                .coerceAtLeast(0),
                            bitrateBps = currentBitrate() ?: 0,
                        )
                    }

                Player.STATE_READY -> if (_firstFrameAtMs.value != null) pushPlaying()

                Player.STATE_ENDED -> _state.value = PlaybackState.Ended
                Player.STATE_IDLE -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying && _state.value is PlaybackState.Playing) {
                _state.value = PlaybackState.Paused(player.currentPosition)
            } else if (isPlaying && _firstFrameAtMs.value != null) {
                pushPlaying()
            }
        }

        /**
         * The track lists, built from what the player has already parsed.
         *
         * This is the callback and not a query: `onTracksChanged` fires when the container
         * has declared its tracks, so reading them here costs nothing, whereas asking for
         * them earlier would either block or return nothing.
         */
        override fun onTracksChanged(tracks: Tracks) {
            val audio = mutableListOf<Track>()
            val subtitle = mutableListOf<Track>()
            val video = mutableListOf<Track>()

            for (group in tracks.groups) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val selected = group.isTrackSelected(i)
                    val entry = Track(
                        id = trackId(group, i),
                        type = when (group.type) {
                            C.TRACK_TYPE_AUDIO -> TrackType.AUDIO
                            C.TRACK_TYPE_TEXT -> TrackType.SUBTITLE
                            else -> TrackType.VIDEO
                        },
                        label = format.label ?: format.language ?: describe(format),
                        language = format.language,
                        channels = format.channelCount.takeIf { it != Format.NO_VALUE },
                        selected = selected,
                    )
                    when (entry.type) {
                        TrackType.AUDIO -> audio += entry
                        TrackType.SUBTITLE -> subtitle += entry
                        TrackType.VIDEO -> video += entry
                    }
                    if (selected) {
                        when (group.type) {
                            C.TRACK_TYPE_AUDIO -> audioFormat = format
                            C.TRACK_TYPE_VIDEO -> videoFormat = format
                        }
                    }
                }
            }
            _tracks.value = TrackSet(audio = audio, subtitle = subtitle, video = video)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            // Kept so the statistics panel can report a resolution even for a container
            // that declared none in its format.
            val current = videoFormat
            if (current == null && videoSize.width > 0) {
                videoFormat = Format.Builder()
                    .setWidth(videoSize.width)
                    .setHeight(videoSize.height)
                    .build()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val mapped = map(error)
            // The one line that answers "why did this channel not play". `errorCodeName`
            // is ExoPlayer's own name for the code, so the log says
            // ERROR_CODE_DECODING_FORMAT_UNSUPPORTED rather than the number 4003.
            Log.w(TAG, "${profile.id} engine failed: ${error.errorCodeName} -> $mapped", error)
            _state.value = PlaybackState.Failed(mapped, error)
        }
    }

    private fun pushPlaying() {
        _state.value = PlaybackState.Playing(
            positionMs = player.currentPosition,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET },
            bitrateBps = currentBitrate() ?: 0,
        )
    }

    override val positionMs: Long get() = player.currentPosition
    override val bufferedPositionMs: Long get() = player.bufferedPosition
    override val durationMs: Long? get() = player.duration.takeIf { it != C.TIME_UNSET }
    override val isSeekable: Boolean get() = player.isCurrentMediaItemSeekable

    private fun describe(format: Format): String =
        format.sampleMimeType?.substringAfterLast('/')?.uppercase() ?: UNKNOWN_TRACK

    private fun trackId(group: Tracks.Group, index: Int): String =
        "${group.type}:${group.mediaTrackGroup.id}:$index"

    /**
     * ExoPlayer's error, as one of the six the product reasons about.
     *
     * The mapping is the whole error taxonomy of the player, so it is exhaustive over the
     * codes that actually occur rather than a `when` with a large `else`. Getting
     * `UNSUPPORTED` and `DECODER` the wrong way round would offer the backup engine where
     * it cannot help, or withhold it where it can.
     */
    private fun map(error: PlaybackException): PlaybackError = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        -> PlaybackError.NOT_FOUND

        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        -> PlaybackError.NETWORK

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        -> PlaybackError.DECODER

        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> PlaybackError.DECODER

        PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
        PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
        PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
        PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
        PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION,
        PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
        PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
        PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED,
        -> PlaybackError.DRM

        else -> PlaybackError.UNKNOWN
    }

    private companion object {
        const val TAG = "CastivioEngine"

        /** Enough of a URL to identify the stream, not enough to put a token in a log. */
        const val URL_IN_LOG = 120

        /**
         * Short on purpose, and shorter than the fallback deadline.
         *
         * A host that has not completed a TCP handshake in two seconds is not busy, it is
         * gone, and the default thirty would hold a channel change hostage for half a
         * minute. The read timeout is longer because a stream that has started arriving
         * and paused is a different situation from one that never began.
         */
        const val CONNECT_TIMEOUT_MS = 2_500
        const val READ_TIMEOUT_MS = 8_000

        /** How far behind the live edge to sit. Low enough to feel live, high enough not to stall. */
        const val LIVE_TARGET_OFFSET_MS = 3_000L

        const val UNKNOWN_TRACK = "—"
    }
}

/**
 * The two ways this engine can be built.
 *
 * Not a boolean, because the pair is named everywhere else in the product and a
 * `isBackup: Boolean` at a construction site says nothing about which one is which.
 */
enum class EngineProfile(val id: EngineId) {
    PRIMARY(EngineId.PRIMARY),
    BACKUP(EngineId.BACKUP),
}
