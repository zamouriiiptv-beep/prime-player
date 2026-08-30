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
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.castivio.playback.api.AspectMode
import com.castivio.playback.api.DecoderReport
import com.castivio.playback.api.FormatReport
import com.castivio.playback.api.EngineId
import com.castivio.playback.api.MediaKind
import com.castivio.playback.api.MediaRequest
import com.castivio.playback.api.PlaybackDiagnosis
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

    private val _diagnosis = MutableStateFlow<PlaybackDiagnosis?>(null)
    override val diagnosis: StateFlow<PlaybackDiagnosis?> = _diagnosis.asStateFlow()

    /** The source currently open, already stripped of its query. For the report only. */
    private var openedSource: String? = null

    /** When [open] was called, so the start-up figure is a measurement and not a guess. */
    private var openedAtMs: Long = 0

    private var videoFormat: Format? = null
    private var audioFormat: Format? = null

    /**
     * Whether this source has a picture at all. Null until the container has said.
     *
     * It exists because "the first frame" is a video idea and a music file does not have
     * one. See [markOpened].
     */
    private var hasVideoTrack: Boolean? = null

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
        // The listener is attached in the `init` block at the bottom of this class, not
        // here. See the comment there — attaching it here is what stopped this engine from
        // ever being constructible.
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
            // Classified from the throwable rather than defaulted. A SecurityException on a
            // content URI and an unknown scheme are different problems with different
            // answers, and both used to arrive here as UNKNOWN and leave as "unsupported".
            val reason = classify(error)
            Log.e(TAG, "$reason opening ${safeSource(media.url)}", error)
            _diagnosis.value = PlaybackDiagnosis(
                engine = profile.id,
                reason = reason,
                causes = chain(error),
                source = safeSource(media.url),
            )
            _state.value = PlaybackState.Failed(reason, error)
        }
    }

    private fun openInternal(media: MediaRequest) {
        openedAtMs = SystemClock.elapsedRealtime()
        openedSource = safeSource(media.url)
        _diagnosis.value = null
        _firstFrameAtMs.value = null
        _tracks.value = TrackSet()
        videoFormat = null
        audioFormat = null
        hasVideoTrack = null
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
        hasVideoTrack = null
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
        override fun onRenderedFirstFrame() = markOpened()

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

                // Audio-only has no first frame to wait for, so ready *is* opened. For
                // video this stays exactly as it was: ready without a frame is still a
                // black screen, and the overlay stays up until the picture arrives.
                Player.STATE_READY -> when {
                    _firstFrameAtMs.value != null -> pushPlaying()
                    isAudioOnly() -> markOpened()
                }

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
                    // Selected first, but any format is better than none: a decoder that
                    // would not initialise never got its track selected, and that is
                    // precisely the failure whose format the report has to carry.
                    when (group.type) {
                        C.TRACK_TYPE_AUDIO -> if (selected || audioFormat == null) audioFormat = format
                        C.TRACK_TYPE_VIDEO -> if (selected || videoFormat == null) videoFormat = format
                    }
                }
            }
            _tracks.value = TrackSet(audio = audio, subtitle = subtitle, video = video)

            // Now we know whether there is a picture to wait for. The two callbacks can
            // arrive in either order, so the audio-only case is completed from whichever
            // of them lands second: `STATE_READY` checks this flag, and this checks the
            // state. Without the second half, a container that declares its tracks after
            // becoming ready would sit on the loading overlay until the budget expired.
            hasVideoTrack = video.isNotEmpty()
            if (isAudioOnly() && player.playbackState == Player.STATE_READY) markOpened()
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

        /**
         * Where the whole diagnosis is assembled.
         *
         * Everything below is read out of the exception and the player at the one moment
         * it all still exists — the engine is released as soon as the fallback switches,
         * and a report gathered afterwards would be a report of nothing.
         */
        override fun onPlayerError(error: PlaybackException) {
            val decoderFailure = error.findCause<MediaCodecRenderer.DecoderInitializationException>()
            val reason = map(error, decoderFailure)

            val report = PlaybackDiagnosis(
                engine = profile.id,
                reason = reason,
                errorCode = error.errorCode,
                errorCodeName = error.errorCodeName,
                rendererName = (error as? ExoPlaybackException)?.takeIf {
                    it.type == ExoPlaybackException.TYPE_RENDERER
                }?.rendererName,
                causes = chain(error),
                decoder = decoderFailure?.let {
                    DecoderReport(
                        codecName = it.codecInfo?.name,
                        mimeType = it.mimeType,
                        diagnosticInfo = it.diagnosticInfo,
                        secureDecoderRequired = it.secureDecoderRequired,
                        // Non-null means the renderer already walked on to another decoder
                        // and that one failed too -- which is precisely the evidence for
                        // whether the backup engine's one real difference can help here.
                        triedAnotherDecoder = it.fallbackDecoderInitializationException != null,
                    )
                },
                video = videoFormat?.asReport(),
                audio = audioFormat?.asReport(),
                source = openedSource,
            )
            _diagnosis.value = report

            Log.w(TAG, "${profile.id} failed: ${error.errorCodeName} -> $reason\n${report.render()}", error)
            _state.value = PlaybackState.Failed(reason, error)
        }
    }

    /**
     * The listener is attached here, below its own declaration, and that is not tidiness —
     * it is the fix for a crash that made this engine impossible to construct.
     *
     * Kotlin initialises properties in declaration order. `player` is declared near the
     * top and runs `buildPlayer()` during construction, while `listener` is declared
     * immediately above this block and is therefore still null at that moment.
     * `buildPlayer()` used to end with `addListener(listener)`, which handed ExoPlayer a
     * null and died inside `Assertions.checkNotNull`. **Every** `Media3Engine(...)` threw.
     *
     * It did not present as a crash, because `PlayerViewModel.start` builds the engine
     * inside `runCatching` and turns a failure into an error card. So every source failed
     * on both engines with one unexplained reason — which is the symptom the whole codec
     * investigation began from, and the file's format was never involved at all.
     *
     * An `init` block here cannot have that problem: everything above it is initialised by
     * the time it runs. Nothing is missed in the gap, because a player with no media
     * loaded emits nothing. `EngineProfileTest` constructs both profiles and the factory's
     * two engines, so a return to the old order fails there rather than on a device.
     */
    init {
        player.addListener(listener)
    }

    /**
     * The source is open and playing — the moment the whole opening budget is measured to.
     *
     * For video that is [Player.Listener.onRenderedFirstFrame], and nothing else will do:
     * a decoder can be ready while the surface is still black, and the black is what the
     * user is looking at.
     *
     * **A music file never fires it.** There is no video renderer, so there is no first
     * frame, so this used to stay null forever — the loading overlay never lifted, the
     * three-second budget always expired, and *every* audio file was handed to the backup
     * engine as a timeout. Nothing was wrong with the file or the decoder; the player was
     * waiting for a picture that a sound file is never going to produce.
     *
     * So for audio-only sources the equivalent moment is `STATE_READY`: the container is
     * parsed, the decoder is fed and the audio is about to be heard. That is the same
     * promise the first frame makes for video — "it has started" — and it is only ever
     * used where there is genuinely no picture to wait for.
     */
    private fun markOpened() {
        if (_firstFrameAtMs.value == null) {
            _firstFrameAtMs.value = SystemClock.elapsedRealtime()
        }
        pushPlaying()
    }

    /** True once the container has declared its tracks and none of them is a picture. */
    private fun isAudioOnly(): Boolean = hasVideoTrack == false

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
     * ExoPlayer's error, as one of the reasons the product reasons about.
     *
     * ## The rule
     *
     * **Nothing becomes [PlaybackError.UNSUPPORTED_FORMAT] unless the platform said so.**
     * Two codes say it and nothing else does. Everything unrecognised stays
     * [PlaybackError.UNKNOWN] and is reported as unknown, because a wrong name on a failure
     * is worse than no name: it sends the user looking for a different file and whoever
     * reads the report looking in the wrong subsystem.
     *
     * A [MediaCodecRenderer.DecoderInitializationException] anywhere in the chain outranks
     * the code, because it is the more specific fact: the platform listed a decoder and the
     * decoder refused, which is a different situation from having no decoder at all and is
     * the one case the backup engine is actually built for.
     */
    @androidx.annotation.VisibleForTesting
    internal fun map(
        error: PlaybackException,
        decoderFailure: MediaCodecRenderer.DecoderInitializationException?,
    ): PlaybackError {
        if (decoderFailure != null) return PlaybackError.DECODER_INIT
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            -> PlaybackError.NOT_FOUND

            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            -> PlaybackError.PERMISSION

            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            -> PlaybackError.NETWORK

            // Deliberately not NETWORK. `IO_UNSPECIFIED` is what a ContentDataSource
            // failure arrives as, and telling a user to check their connection because a
            // local file would not open is the kind of wrong advice this pass exists to
            // remove.
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            -> PlaybackError.SOURCE

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            -> PlaybackError.CONTAINER

            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            -> PlaybackError.DECODER_INIT

            // The decoder never refused anything: it decoded the frame, and the platform's
            // AudioTrack refused to take the result. On a real device this is overwhelmingly
            // a passthrough codec — AC-3, E-AC-3 — with no receiver downstream to decode it,
            // which `MediaCodecRenderer.DecoderInitializationException` never sees because
            // nothing about the decoder failed. `findCause` correctly returns null for this,
            // and before this pass that null sent every such failure straight to `UNKNOWN`,
            // which is also the one classification this exact case does not deserve: the
            // backup engine's `EXTENSION_RENDERER_MODE_PREFER` is the one thing in this file
            // built specifically to fix it, by decoding the same bitstream to PCM in software
            // instead of asking AudioTrack to take the encoded format again.
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            -> PlaybackError.DECODER_INIT

            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            -> PlaybackError.DECODING

            // The only code that means what the word means.
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            -> {
                val isAudioTrack = decoderFailure?.mimeType?.startsWith("audio/") == true ||
                    (error as? ExoPlaybackException)?.rendererFormat?.sampleMimeType?.startsWith("audio/") == true ||
                    audioFormat?.sampleMimeType?.startsWith("audio/") == true
                if (profile == EngineProfile.PRIMARY && isFfmpegAvailable && isAudioTrack) {
                    PlaybackError.DECODER_INIT
                } else {
                    PlaybackError.UNSUPPORTED_FORMAT
                }
            }

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
    }

    /**
     * A throwable from [open], classified.
     *
     * These are the failures that happen before ExoPlayer has an error code of its own: a
     * revoked content URI, a scheme nothing claims. Previously all of them were UNKNOWN.
     */
    private fun classify(error: Throwable): PlaybackError = when {
        error.chainHas<SecurityException>() -> PlaybackError.PERMISSION
        error.chainHas<java.io.FileNotFoundException>() -> PlaybackError.NOT_FOUND
        error.chainHas<java.io.IOException>() -> PlaybackError.SOURCE
        else -> PlaybackError.UNKNOWN
    }

    /**
     * The chain, outermost first.
     *
     * The answer is almost never in the top frame. `ExoPlaybackException` says "a renderer
     * failed"; four links down is a `MediaCodec.CodecException` with a vendor string that
     * names the actual problem. Bounded, because a cyclic `cause` is a real thing and a
     * report that never finishes rendering is a report nobody reads.
     */
    private fun chain(error: Throwable): List<String> = buildList {
        var current: Throwable? = error
        var depth = 0
        val seen = mutableSetOf<Throwable>()
        while (current != null && depth < MAX_CAUSES && seen.add(current)) {
            add("${current.javaClass.name}: ${current.message ?: "(no message)"}")
            current = current.cause
            depth++
        }
    }

    private inline fun <reified T : Throwable> Throwable.findCause(): T? {
        var current: Throwable? = this
        var depth = 0
        while (current != null && depth < MAX_CAUSES) {
            if (current is T) return current
            current = current.cause
            depth++
        }
        return null
    }

    private inline fun <reified T : Throwable> Throwable.chainHas(): Boolean = findCause<T>() != null

    /** A format, flattened for the report. Nothing here is computed; it is all declared. */
    private fun Format.asReport() = FormatReport(
        sampleMimeType = sampleMimeType,
        codecs = codecs,
        width = width.takeIf { it != Format.NO_VALUE },
        height = height.takeIf { it != Format.NO_VALUE },
        frameRate = frameRate.takeIf { it != Format.NO_VALUE.toFloat() && it > 0f },
        channelCount = channelCount.takeIf { it != Format.NO_VALUE },
        sampleRateHz = sampleRate.takeIf { it != Format.NO_VALUE },
        bitrate = bitrate.takeIf { it != Format.NO_VALUE },
    )

    /**
     * A source, safe to put in a report.
     *
     * The query is dropped. An Xtream URL carries the subscriber's username, password and
     * session token in it, and a diagnostic the user is invited to copy and paste to
     * somebody else must not carry credentials out of the device.
     */
    private fun safeSource(url: String): String = url.substringBefore('?').take(URL_IN_LOG)

    // `internal`, not `private`: `isFfmpegAvailable` below is a claim about the classpath
    // that EngineProfileTest checks, and a member of a private companion cannot be read
    // even from the same module. Everything here stays inside :playback:engine-media3.
    internal companion object {
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

        /** Deep enough for any real chain, shallow enough that a cycle cannot hang it. */
        const val MAX_CAUSES = 12

        val isFfmpegAvailable: Boolean by lazy {
            runCatching {
                Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer")
            }.isSuccess
        }
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
