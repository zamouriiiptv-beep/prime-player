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
import androidx.media3.common.text.CueGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.castivio.core.common.FrameSize
import com.castivio.playback.api.AspectMode
import com.castivio.playback.api.DecoderReport
import com.castivio.playback.api.FormatReport
import com.castivio.playback.api.EngineId
import com.castivio.playback.api.MediaKind
import com.castivio.playback.api.MediaRequest
import com.castivio.playback.api.OpeningMarks
import com.castivio.playback.api.PlaybackDiagnosis
import com.castivio.playback.api.PlaybackEngine
import com.castivio.playback.api.PlaybackError
import com.castivio.playback.api.PlaybackSample
import com.castivio.playback.api.PlaybackState
import com.castivio.playback.api.PlaybackTuning
import com.castivio.playback.api.SourceRedaction
import com.castivio.playback.api.Track
import com.castivio.playback.api.TrackSet
import com.castivio.playback.api.TrackType
import com.castivio.playback.api.VideoOutput
import java.util.concurrent.atomic.AtomicLong
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
 * The product's backup engine is LibVLC now -- see `Media3EngineFactory` -- so the backup
 * profile here, with its forgiving transport-stream reader, is constructed by tests and
 * nothing else. The primary reads transport streams strictly; see `buildPlayer` for why it
 * stays that way for now.
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

    private val _videoAspectRatio = MutableStateFlow<Float?>(null)
    override val videoAspectRatio: StateFlow<Float?> = _videoAspectRatio.asStateFlow()

    private val _videoSize = MutableStateFlow<FrameSize?>(null)
    override val videoSize: StateFlow<FrameSize?> = _videoSize.asStateFlow()

    private val _cues = MutableStateFlow<List<String>>(emptyList())
    override val cues: StateFlow<List<String>> = _cues.asStateFlow()

    private val _diagnosis = MutableStateFlow<PlaybackDiagnosis?>(null)
    override val diagnosis: StateFlow<PlaybackDiagnosis?> = _diagnosis.asStateFlow()

    /** The source currently open, already stripped of its query. For the report only. */
    private var openedSource: String? = null

    /** When [open] was called, so the start-up figure is a measurement and not a guess. */
    private var openedAtMs: Long = 0

    /**
     * The server's half of the open in progress: when it answered, and when its first bytes
     * arrived. See [openingMarks].
     *
     * One object per open, written by the loader's thread -- the HTTP source reports from
     * wherever it is reading -- and replaced, never cleared, by the next open. A report that
     * is still on its way for the source before lands in that source's object, which nothing
     * reads any more, rather than in this one.
     */
    @Volatile
    private var transferMarks: TransferMarks? = null

    /**
     * Whether what the player reports now is about the source open now.
     *
     * An engine kept for the next channel is handed it while the last one is still in flight on
     * the playback thread, and what the player had already said about it -- a frame rendered,
     * an error, a caption, its tracks -- is on its way to this thread when the next source is
     * set. Each of those would be read as the new channel's: a frame lifting the loading overlay
     * over a picture that is not there yet, an error sending a healthy channel to the backup.
     *
     * So an open closes this before it sets the source, and a message sent through the player
     * behind the new source opens it again. The playback thread handles its messages in order,
     * so everything it said about the old source arrives before the message and everything about
     * the new one after it. Every callback below that reports on the source checks it.
     */
    private var current = true

    /** Which open closed the gate last, so a message from an earlier open cannot open it. */
    private var generation = 0

    /** The device's half, which the player reports on this thread with its other callbacks. */
    private var tracksKnownAt: Long = 0
    private var decoderReadyAt: Long = 0
    private var decoderInitMs: Long? = null
    private var decoderKept = false

    /** Whether the source open now is a live one: the live window can be fallen behind. */
    private var liveRequest = false

    private var videoFormat: Format? = null
    private var audioFormat: Format? = null

    /**
     * The picture as the viewer sees it, which is not what the format says.
     *
     * A phone records portrait by writing a landscape frame plus a rotation in the
     * container, so the coded size of the file on screen was 720×1280 while every pixel of
     * it was drawn 1280×720. `onVideoSizeChanged` is the callback that has already applied
     * that rotation; the format has not. The statistics panel is read by someone looking at
     * the picture, so it is given the size of the picture.
     */
    private var displayWidth: Int? = null
    private var displayHeight: Int? = null

    /**
     * Whether this source has a picture at all. Null until the container has said.
     *
     * It exists because "the first frame" is a video idea and a music file does not have
     * one. See [markOpened].
     */
    private var hasVideoTrack: Boolean? = null

    /**
     * The HTTP source, kept so that an open can give it the request's own headers.
     *
     * Providers redirect http to https and back, sometimes twice. Refusing to follow is the
     * single most common cause of a stream that "does not exist" and plays perfectly in every
     * other application.
     */
    private val http = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
        .setReadTimeoutMs(READ_TIMEOUT_MS)

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
            // **Not on the primary until a television has said so.**
            //
            // The primary opens every channel, and it keeps the strict reader the channels
            // that play today are played with. `FLAG_DETECT_ACCESS_UNITS` is documented as a
            // flag to leave off for a stream that carries access unit delimiters -- many IPTV
            // channels do -- and on one it splits every picture into a delimiter-only sample
            // and the picture. Whether a box's decoder minds is a question for the box. The
            // channels the strict reader cannot open still go to the backup engine, as they
            // always have.
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
        // Closed before anything else, so nothing the player says about the source before
        // this one is taken as being about this one. See [current].
        current = false
        val mine = ++generation
        openedAtMs = SystemClock.elapsedRealtime()
        transferMarks = TransferMarks(media.url)
        tracksKnownAt = 0
        decoderReadyAt = 0
        decoderInitMs = null
        decoderKept = false
        openedSource = safeSource(media.url)
        _diagnosis.value = null
        _firstFrameAtMs.value = null
        _tracks.value = TrackSet()
        videoFormat = null
        audioFormat = null
        hasVideoTrack = null
        _videoAspectRatio.value = null
        _videoSize.value = null
        // A fresh engine starts with these empty, and a kept one has to: the caption on screen
        // when the channel changed is the last channel's, and the callback that would have
        // cleared it is one of the ones the gate turns away.
        _cues.value = emptyList()
        displayWidth = null
        displayHeight = null
        _state.value = PlaybackState.Opening
        liveRequest = media.kind == MediaKind.LIVE

        // The request's headers and user agent, which the backup engine was always given and
        // this one was not, so that both honour the same request. Nothing in the catalogue
        // fills either yet, so today both are empty and the platform's default agent is
        // sent, as before.
        val agent = media.headers.entries.firstOrNull { it.key.equals(USER_AGENT, ignoreCase = true) }
        http.setUserAgent(media.userAgent ?: agent?.value)
        http.setDefaultRequestProperties(media.headers.filterKeys { !it.equals(USER_AGENT, ignoreCase = true) })

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
        // A track chosen on the last channel was a choice about that channel. A fresh engine
        // has none, and this one is handed the next channel as if it were fresh.
        val choices = player.trackSelectionParameters
        if (choices.overrides.isNotEmpty()) {
            player.trackSelectionParameters = choices.buildUpon().clearOverrides().build()
        }
        // Behind the new source on the playback thread, so it arrives here after everything
        // the player said about the old one and before anything it says about this one.
        player.createMessage { _, payload ->
            if (payload != mine) return@createMessage
            current = true
            // The last source can fail in the moment it is replaced, and an error stops the
            // player -- after `prepare` below had already been turned away, because the stop had
            // not reached this thread yet. Everything about the old source has arrived by now,
            // so a player left idle by it is prepared here, for this one.
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
        }
            .setPayload(mine)
            .setLooper(player.applicationLooper)
            .send()
        player.prepare()
        player.playWhenReady = true
    }

    /**
     * Kept for the next channel, rather than released and built again. See
     * [PlaybackEngine.reusable] for what that promises, and [current] for how it is kept.
     *
     * Setting a new source is what ExoPlayer is built for: the playback thread stops the old
     * one, disables the renderers and keeps their decoders, and a decoder that can take the
     * next stream's format is used again rather than built. None of that waits on the main
     * thread, which is where releasing a player and building one both do their waiting.
     */
    override val reusable: Boolean get() = true

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
        _videoAspectRatio.value = null
        _videoSize.value = null
        _cues.value = emptyList()
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
            width = shownSize(displayWidth, video?.width),
            height = shownSize(displayHeight, video?.height),
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

    /**
     * The open in progress, step by step, from what the player and its HTTP source reported.
     *
     * Read once by the player, when the first frame arrives or the source is given up. Every
     * step is timed where it happened -- the server's on the loader's thread, the decoder's on
     * the playback thread -- except the tracks, which are timed as their callback arrives here.
     */
    override fun openingMarks(): OpeningMarks {
        fun since(at: Long): Long? = if (at == 0L) null else at - openedAtMs
        val transfer = transferMarks
        return OpeningMarks(
            serverAnsweredMs = transfer?.let { since(it.answeredAt.get()) },
            firstBytesMs = transfer?.let { since(it.firstBytesAt.get()) },
            tracksKnownMs = since(tracksKnownAt),
            decoderReadyMs = since(decoderReadyAt),
            decoderInitMs = decoderInitMs,
            decoderKept = decoderKept,
            firstFrameMs = _firstFrameAtMs.value?.let { it - openedAtMs },
        )
    }

    override fun release() {
        player.removeListener(listener)
        player.removeAnalyticsListener(analytics)
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
            if (current) markOpened()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!current) return
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

        /**
         * Started, or stopped — and which of the two kinds of stopping it was.
         *
         * The old guard recorded a pause only when the last thing announced was `Playing`.
         * A rebuffer announces `Buffering`, so a pause during one was never recorded at
         * all: the screen went on showing a pause bar over a film that was not running,
         * and pressing it called `pause()` on something already paused. A no-op, a control
         * that appeared dead, and no way out of it but leaving the player.
         *
         * `playWhenReady` is what separates the two: a stall keeps it true and the user
         * setting it false is the only thing that does not. So a stop with it false is a
         * pause, whatever the engine happened to be announcing a moment earlier, and a
         * stop with it true is a stall — which the buffering state already covers.
         */
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!current || _firstFrameAtMs.value == null) return
            when {
                isPlaying -> pushPlaying()
                !player.playWhenReady -> _state.value = PlaybackState.Paused(player.currentPosition)
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
            if (!current) return
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
            if (tracksKnownAt == 0L && tracks.groups.isNotEmpty()) tracksKnownAt = SystemClock.elapsedRealtime()

            // Now we know whether there is a picture to wait for. The two callbacks can
            // arrive in either order, so the audio-only case is completed from whichever
            // of them lands second: `STATE_READY` checks this flag, and this checks the
            // state. Without the second half, a container that declares its tracks after
            // becoming ready would sit on the loading overlay until the budget expired.
            hasVideoTrack = video.isNotEmpty()
            if (isAudioOnly() && player.playbackState == Player.STATE_READY) markOpened()
        }

        /**
         * The caption lines for this moment, as words.
         *
         * Everything the cue carries about *where* it should go is dropped, deliberately.
         * A source's cue positions were authored for a cinema screen or a television, and
         * honouring them on a phone in a hand puts a caption over the controls the viewer
         * is reaching for. Where a caption sits is the viewer's setting.
         *
         * Blank cues are dropped rather than rendered: a subtitle track that carries empty
         * lines to clear the screen would otherwise draw an empty backdrop over the film.
         */
        override fun onCues(cueGroup: CueGroup) {
            if (!current) return
            _cues.value = cueGroup.cues
                .mapNotNull { it.text?.toString()?.trim() }
                .filter { it.isNotEmpty() }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (!current) return
            // The shape the surface has to be given. Reported here rather than read off
            // the format, because this is the callback that also carries the pixel aspect
            // ratio — an anamorphic source declares square dimensions and a correction,
            // and a player that takes only the dimensions shows the film squashed.
            _videoAspectRatio.value = if (videoSize.width > 0 && videoSize.height > 0) {
                videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            } else {
                null
            }

            if (videoSize.width > 0 && videoSize.height > 0) {
                displayWidth = videoSize.width
                displayHeight = videoSize.height
                // The size as it is drawn -- rotation applied -- which is what a quality is a
                // class of. Null again on the next open, so a kept engine never reports the
                // last channel's picture as the next one's.
                _videoSize.value = FrameSize.of(videoSize.width, videoSize.height)
            }

            // Kept so a container that declared no format at all still has one to read a
            // codec name from.
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
            // The last source's error is not this one's: see [current].
            if (!current) return
            // **Behind the live window is a position, not a failure.**
            //
            // A live playlist moves on while the player waits -- a rebuffer, a slow segment,
            // a phone put down -- and the position the player holds falls out of it. That is
            // what this code means, and it arrived here as an unidentified failure: a card
            // over a channel that was working, offering the backup engine for a problem of
            // where to stand. The answer is the one the platform documents: go back to the
            // live edge and prepare again, on the same engine.
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && liveRequest) {
                Log.i(TAG, "${profile.id} fell behind the live window -- back to the edge")
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
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
     * The HTTP source's account of each transfer, for the two marks that are the server's.
     *
     * It reports every transfer it makes -- an HLS playlist's segments as well as the address
     * itself -- and the marks are about the first answer to the address that was opened, so a
     * transfer for anything else is not one. After the first bytes each report costs one read
     * of an atomic: this is called for every read the source makes, and it must not be what
     * makes reading slower.
     */
    private val transfers = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            val marks = transferMarks ?: return
            if (marks.answeredAt.get() == 0L && marks.isFor(dataSpec)) {
                marks.answeredAt.compareAndSet(0, SystemClock.elapsedRealtime())
            }
        }

        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            val marks = transferMarks ?: return
            if (marks.firstBytesAt.get() == 0L && bytesTransferred > 0 && marks.isFor(dataSpec)) {
                marks.firstBytesAt.compareAndSet(0, SystemClock.elapsedRealtime())
            }
        }

        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    }

    /**
     * The player's account of its video decoder, for the marks that are the device's.
     *
     * A decoder that has to be built reports when it was ready and how long building it took,
     * both timed on the playback thread. A decoder that is kept reports nothing of its own: the
     * new format reaching it, with the verdict that it can be kept, is the moment it is ready.
     */
    private val analytics = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            if (!current || decoderReadyAt != 0L) return
            decoderReadyAt = initializedTimestampMs
            decoderInitMs = initializationDurationMs
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            // Null when there was no decoder to keep: one is being built, and says so above.
            val verdict = decoderReuseEvaluation ?: return
            if (!current || decoderReadyAt != 0L || verdict.result == DecoderReuseEvaluation.REUSE_RESULT_NO) return
            decoderReadyAt = eventTime.realtimeMs
            decoderKept = true
        }
    }

    /** One open's server marks. Zero is "not yet", which `elapsedRealtime` never is on a running device. */
    private class TransferMarks(private val url: String) {
        val answeredAt = AtomicLong(0)
        val firstBytesAt = AtomicLong(0)

        fun isFor(dataSpec: DataSpec): Boolean = dataSpec.uri.toString() == url
    }

    /**
     * The listener is attached here, below its own declaration, and that is not tidiness —
     * it is the fix for a crash that made this engine impossible to construct.
     *
     * Kotlin initialises properties in declaration order. `player` is declared near the
     * top and runs `buildPlayer()` during construction, while `listener` is declared
     * above this block -- with `transfers` and `analytics`, which are attached here for the
     * same reason -- and is therefore still null at that moment.
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
        player.addAnalyticsListener(analytics)
        // `http` is built before `transfers` exists, so handing it over in the builder would
        // have handed it a null -- which this setter accepts without a word, and the server's
        // marks would simply never have been taken.
        http.setTransferListener(transfers)
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
    override val isPlayRequested: Boolean get() = player.playWhenReady

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
     * An Xtream URL carries the subscriber's username and password in its path and a session
     * token in its query, and a diagnostic the user is invited to copy and paste to somebody
     * else must not carry credentials out of the device -- see [SourceRedaction].
     */
    private fun safeSource(url: String): String = SourceRedaction.safe(url)

    // `internal`, not `private`: `isFfmpegAvailable` below is a claim about the classpath
    // that EngineProfileTest checks, and a member of a private companion cannot be read
    // even from the same module. Everything here stays inside :playback:engine-media3.
    internal companion object {
        const val TAG = "CastivioEngine"

        /**
         * Which of the two sizes a person is actually looking at.
         *
         * The displayed one whenever the decoder has reported it, because that is the one
         * with the container's rotation applied: a phone records portrait by writing a
         * landscape frame plus a rotation, so a file whose format says 720×1280 is drawn
         * 1280×720 and the panel said the wrong pair of numbers. The format is the fallback
         * and not the other way round, which is the whole rule and the reason it is a named
         * function rather than an `?:` buried in a builder.
         */
        internal fun shownSize(displayed: Int?, coded: Int?): Int? =
            displayed?.takeIf { it > 0 } ?: coded?.takeIf { it > 0 }

        const val USER_AGENT = "User-Agent"

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
