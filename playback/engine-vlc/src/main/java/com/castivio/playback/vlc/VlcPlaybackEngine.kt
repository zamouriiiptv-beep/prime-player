package com.castivio.playback.vlc

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import android.view.View
import com.castivio.core.common.FrameSize
import com.castivio.playback.api.AspectMode
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
import java.io.FileNotFoundException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/**
 * LibVLC implementation of [PlaybackEngine].
 * Acts as the authoritative BACKUP playback engine for streams and formats
 * that Media3 cannot decode.
 */
class VlcPlaybackEngine(
    context: Context,
    private val tuning: PlaybackTuning,
) : PlaybackEngine {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _tracks = MutableStateFlow(TrackSet())
    override val tracks: StateFlow<TrackSet> = _tracks.asStateFlow()

    private val _firstFrameAtMs = MutableStateFlow<Long?>(null)
    override val firstFrameAtMs: StateFlow<Long?> = _firstFrameAtMs.asStateFlow()

    /** LibVLC said `Playing`, or drew before it did: the input is running, which is not a picture. */
    private val _startedAtMs = MutableStateFlow<Long?>(null)
    override val startedAtMs: StateFlow<Long?> = _startedAtMs.asStateFlow()

    private val _videoAspectRatio = MutableStateFlow<Float?>(null)
    override val videoAspectRatio: StateFlow<Float?> = _videoAspectRatio.asStateFlow()

    private val _videoSize = MutableStateFlow<FrameSize?>(null)
    override val videoSize: StateFlow<FrameSize?> = _videoSize.asStateFlow()

    /**
     * Always empty, and that is the implementation rather than a gap in it.
     *
     * LibVLC draws captions into the video output itself, before a frame ever reaches the
     * surface Castivio gave it. There is nothing here to hand to the screen, and a second
     * layer drawn over the top would show every line twice — once burnt into the picture
     * and once in Castivio's own type.
     *
     * The cost is that the viewer's size, colour and backdrop settings do not reach the
     * backup engine's captions; they are the ones VLC drew. That is a real limitation and
     * it is stated here rather than hidden: the alternative is not rendering them at all.
     */
    override val cues: StateFlow<List<String>> = MutableStateFlow<List<String>>(emptyList())

    private val _diagnosis = MutableStateFlow<PlaybackDiagnosis?>(null)
    override val diagnosis: StateFlow<PlaybackDiagnosis?> = _diagnosis.asStateFlow()

    private var openedSource: String? = null
    private var openedAtMs: Long = 0

    /** The open's own steps, as far as LibVLC reports them. See [openingMarks]. Zero is "not yet". */
    private var filledAtMs: Long = 0
    private var tracksAtMs: Long = 0

    /** The playback clock first moved after the video output appeared: the nearest this engine sees to a picture shown. */
    private var clockAtMs: Long = 0

    private var isReleased = false
    private var currentSurfaceView: SurfaceView? = null

    /** The process's one LibVLC -- see [sharedLibVlc]. Never released by an engine. */
    private val libVLC: LibVLC by lazy { sharedLibVlc(appContext) }

    private val mediaPlayer: MediaPlayer by lazy {
        MediaPlayer(libVLC).apply {
            setEventListener(eventListener)
        }
    }

    private var currentMedia: Media? = null

    /**
     * Held open for as long as LibVLC is reading from it. Only ever set for `content://`
     * sources — see [newMedia] — and closed with the media it belongs to.
     */
    private var currentDescriptor: AssetFileDescriptor? = null

    @Volatile
    var aspect: AspectMode = AspectMode.FIT
        private set

    private val eventListener = MediaPlayer.EventListener { event ->
        if (isReleased) return@EventListener
        when (event.type) {
            MediaPlayer.Event.Opening -> {
                _state.value = PlaybackState.Opening
            }
            // **A buffering report is not always a stall.**
            //
            // LibVLC reports how full its input buffer is, 0 to 100, and it reports it again
            // while a stream plays. Every report after the first frame used to become
            // `Buffering` -- and nothing turned it back: `Playing` is sent once, when playback
            // starts, and the clock's ticks refreshed only a state that already said
            // `Playing`. So one report at 100 % left the spinner over a picture that went on
            // playing for as long as the channel was on.
            //
            // A full buffer is the end of buffering, and a picture that is moving is playing
            // -- see `TimeChanged` below. What stays is the real thing: a buffer below full
            // after the picture started.
            MediaPlayer.Event.Buffering -> {
                val percent = event.buffering
                if (filledAtMs == 0L && percent > 0f) filledAtMs = SystemClock.elapsedRealtime()
                when {
                    _firstFrameAtMs.value == null -> _state.value = PlaybackState.Opening
                    percent >= FULL_BUFFER -> if (mediaPlayer.isPlaying) pushPlaying()
                    else -> {
                        val bufferedAheadMs = ((percent / 100f) * tuning.bufferForPlaybackMs).toLong().coerceAtLeast(0L)
                        _state.value = PlaybackState.Buffering(
                            bufferedMs = bufferedAheadMs,
                            bitrateBps = 0,
                        )
                    }
                }
            }
            // **Playing is the input running, not a picture.** LibVLC says it as soon as the
            // stream is open, before anything has been buffered or decoded, and taking it as the
            // first frame lifted the loading screen over a surface with nothing new on it -- the
            // channel before, frozen, or black. The first frame is the video output appearing
            // (`Vout`); a sound with no picture is opened by its clock (`TimeChanged`). It is still
            // the start, which is what the fallback deadline waits for: see [startedAtMs].
            MediaPlayer.Event.Playing -> {
                markStarted()
                if (_firstFrameAtMs.value != null) pushPlaying()
                updateTracks()
            }
            // Whatever was announced before it: a pause during a rebuffer is still a pause.
            MediaPlayer.Event.Paused -> {
                if (_firstFrameAtMs.value != null) {
                    _state.value = PlaybackState.Paused(positionMs)
                }
            }
            MediaPlayer.Event.Stopped -> {
                _state.value = PlaybackState.Idle
            }
            MediaPlayer.Event.EndReached -> {
                _state.value = PlaybackState.Ended
            }
            MediaPlayer.Event.Vout -> {
                updateVideoShape()
                if (event.voutCount > 0) markOpened()
            }
            MediaPlayer.Event.ESAdded,
            MediaPlayer.Event.ESDeleted,
            MediaPlayer.Event.ESSelected -> {
                if (tracksAtMs == 0L && event.type == MediaPlayer.Event.ESAdded) tracksAtMs = SystemClock.elapsedRealtime()
                updateTracks()
                updateVideoShape()
            }
            // The clock moved, so the picture is moving: a stream announced as buffering is
            // playing again. A real stall does not move the clock and is left as it was.
            MediaPlayer.Event.TimeChanged -> {
                // A sound has no first frame: its clock moving is the moment it is heard. A
                // source with a picture announced its video track long before its clock moved,
                // so it goes on waiting for `Vout`.
                if (_firstFrameAtMs.value == null && hasNoPicture()) markOpened()
                if (clockAtMs == 0L && _firstFrameAtMs.value != null) clockAtMs = SystemClock.elapsedRealtime()
                val now = _state.value
                if (now is PlaybackState.Playing || (now is PlaybackState.Buffering && mediaPlayer.isPlaying)) {
                    pushPlaying()
                }
            }
            MediaPlayer.Event.EncounteredError -> {
                // Classified by whether the input had started, as it always was: that is what
                // the first frame used to mean here, and a later first frame must not turn a
                // decoding failure into a source one.
                val errorReason = if (_startedAtMs.value == null) {
                    PlaybackError.SOURCE
                } else {
                    PlaybackError.DECODING
                }
                val report = PlaybackDiagnosis(
                    engine = EngineId.BACKUP,
                    reason = errorReason,
                    errorCode = event.type,
                    errorCodeName = "LIBVLC_PLAYBACK_ERROR",
                    causes = listOf("LibVLC encountered playback error (event type ${event.type})"),
                    source = openedSource,
                )
                _diagnosis.value = report
                _state.value = PlaybackState.Failed(errorReason, RuntimeException("LibVLC playback error"))
            }
        }
    }

    override fun setVideoOutput(output: VideoOutput?) {
        if (isReleased) return
        when (output) {
            null -> {
                val vout = mediaPlayer.vlcVout
                if (vout.areViewsAttached()) {
                    vout.detachViews()
                }
                currentSurfaceView?.removeOnLayoutChangeListener(windowSizer)
                currentSurfaceView = null
            }
            is VideoOutput.Platform -> {
                val view = output.view
                require(view is SurfaceView) {
                    "VlcPlaybackEngine draws into a SurfaceView; received ${view.javaClass.name}"
                }
                if (currentSurfaceView === view && mediaPlayer.vlcVout.areViewsAttached()) {
                    return
                }
                val vout = mediaPlayer.vlcVout
                if (vout.areViewsAttached()) {
                    vout.detachViews()
                }
                currentSurfaceView?.removeOnLayoutChangeListener(windowSizer)
                currentSurfaceView = view
                vout.setVideoView(view)
                vout.attachViews()
                // **LibVLC scales to the window it is told about, and it was told about none.**
                //
                // A `SurfaceView` handed to LibVLC without its size leaves the video output
                // scaling to whatever it last believed the window was. The surface is resized
                // as the picture's shape arrives and again when the player is enlarged, so the
                // picture was drawn to the wrong box -- stretched out of shape, and softer than
                // the screen it was on. The size is given now and given again on every change,
                // which is what LibVLC's own player does with its surface.
                view.addOnLayoutChangeListener(windowSizer)
                if (view.width > 0 && view.height > 0) vout.setWindowSize(view.width, view.height)
            }
        }
    }

    /** Tells LibVLC the window's size whenever the surface is laid out at a new one. */
    private val windowSizer = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        val width = right - left
        val height = bottom - top
        if (isReleased || width <= 0 || height <= 0) return@OnLayoutChangeListener
        if (width == oldRight - oldLeft && height == oldBottom - oldTop) return@OnLayoutChangeListener
        runCatching { mediaPlayer.vlcVout.setWindowSize(width, height) }
    }

    override fun open(media: MediaRequest) {
        if (isReleased) return
        runCatching { openInternal(media) }.onFailure { error ->
            val reason = classifyOpenError(error)
            Log.e(TAG, "Error opening ${safeSource(media.url)}: $reason", error)
            _diagnosis.value = PlaybackDiagnosis(
                engine = EngineId.BACKUP,
                reason = reason,
                causes = listOf("${error.javaClass.name}: ${error.message ?: "(no message)"}"),
                source = safeSource(media.url),
            )
            _state.value = PlaybackState.Failed(reason, error)
        }
    }

    private fun openInternal(media: MediaRequest) {
        openedAtMs = SystemClock.elapsedRealtime()
        filledAtMs = 0
        tracksAtMs = 0
        clockAtMs = 0
        openedSource = safeSource(media.url)
        _diagnosis.value = null
        _startedAtMs.value = null
        _firstFrameAtMs.value = null
        _videoAspectRatio.value = null
        _videoSize.value = null
        _tracks.value = TrackSet()
        _state.value = PlaybackState.Opening

        val previousDescriptor = currentDescriptor
        val vlcMedia = newMedia(media.url).apply {
            addOption(":network-caching=${tuning.bufferForPlaybackMs}")
            if (media.kind == MediaKind.LIVE) {
                addOption(":live-caching=${tuning.bufferForPlaybackMs}")
                addOption(":clock-jitter=0")
                addOption(":clock-synchro=0")
            }
            media.userAgent?.let { addOption(":http-user-agent=$it") }
            media.headers.forEach { (headerName, headerValue) ->
                when {
                    headerName.equals("User-Agent", ignoreCase = true) -> {
                        addOption(":http-user-agent=$headerValue")
                    }
                    headerName.equals("Referer", ignoreCase = true) -> {
                        addOption(":http-referrer=$headerValue")
                    }
                    headerName.equals("Cookie", ignoreCase = true) -> {
                        addOption(":http-cookies=$headerValue")
                    }
                }
            }
        }

        val previousMedia = currentMedia
        currentMedia = vlcMedia

        mediaPlayer.media = vlcMedia
        mediaPlayer.play()

        // After the swap, never before it: the old media is only safe to let go once the
        // player is holding the new one, and its descriptor only once the media is gone.
        previousMedia?.release()
        runCatching { previousDescriptor?.close() }
    }

    /**
     * A `Media` for this URL, opening a descriptor first when the URL is a `content://`.
     *
     * LibVLC resolves a URI through its own access modules — file, http, rtsp and the
     * rest — and Android's content providers are not among them. Handed a `content://`
     * URI it simply fails to open, which arrives here as event 266 and is reported as
     * [PlaybackError.SOURCE] with nothing more specific to say. That is what happened to
     * every local song and video the moment the backup engine was asked to play one, and
     * the local library is precisely where those URLs come from.
     *
     * The fix is the one VLC itself uses: ask the resolver for the file and hand LibVLC
     * the descriptor. `AssetFileDescriptor` rather than a bare `FileDescriptor` because it
     * carries the offset and length, which a provider serving a file out of a larger
     * container needs. The descriptor is held for the life of the media and closed with
     * it — LibVLC reads from it for the whole of playback, so closing early would end the
     * track partway through.
     */
    private fun newMedia(url: String): Media {
        val uri = Uri.parse(url)
        if (!requiresDescriptor(url)) return Media(libVLC, uri)

        val descriptor = appContext.contentResolver.openAssetFileDescriptor(uri, "r")
            ?: throw FileNotFoundException("the provider returned no descriptor for $uri")

        // The caller holds the previous one and closes it after the swap, so this only
        // ever overwrites — closing here would pull the descriptor out from under a media
        // that is still attached to the player.
        currentDescriptor = descriptor
        return Media(libVLC, descriptor)
    }

    private fun closeDescriptor() {
        runCatching { currentDescriptor?.close() }
        currentDescriptor = null
    }

    override fun play() {
        if (!isReleased) {
            mediaPlayer.play()
        }
    }

    override fun pause() {
        if (!isReleased) {
            mediaPlayer.pause()
        }
    }

    override fun stop() {
        if (!isReleased) {
            mediaPlayer.stop()
            currentMedia?.release()
            currentMedia = null
            closeDescriptor()
            _startedAtMs.value = null
            _firstFrameAtMs.value = null
            _videoAspectRatio.value = null
            _videoSize.value = null
            _state.value = PlaybackState.Idle
        }
    }

    override fun seekTo(positionMs: Long) {
        if (!isReleased && mediaPlayer.isSeekable) {
            mediaPlayer.time = positionMs.coerceAtLeast(0)
        }
    }

    override fun selectTrack(track: Track) {
        if (isReleased) return
        val trackId = track.id.toIntOrNull() ?: return
        when (track.type) {
            TrackType.AUDIO -> {
                mediaPlayer.audioTrack = trackId
                updateTracks()
            }
            TrackType.SUBTITLE -> {
                mediaPlayer.spuTrack = trackId
                updateTracks()
            }
            TrackType.VIDEO -> Unit
        }
    }

    override fun setSpeed(speed: Float) {
        if (!isReleased && speed > 0f) {
            mediaPlayer.rate = speed
        }
    }

    override fun setAspect(mode: AspectMode) {
        aspect = mode
        if (isReleased) return
        when (mode) {
            AspectMode.FIT -> {
                mediaPlayer.aspectRatio = null
                mediaPlayer.scale = 0f
            }
            AspectMode.FILL -> {
                mediaPlayer.aspectRatio = null
                mediaPlayer.scale = 0f
            }
            AspectMode.ZOOM -> {
                mediaPlayer.aspectRatio = null
                mediaPlayer.scale = 1.25f
            }
            AspectMode.RATIO_16_9 -> {
                mediaPlayer.aspectRatio = "16:9"
                mediaPlayer.scale = 0f
            }
            AspectMode.RATIO_4_3 -> {
                mediaPlayer.aspectRatio = "4:3"
                mediaPlayer.scale = 0f
            }
        }
    }

    override val positionMs: Long
        get() = if (!isReleased) mediaPlayer.time.coerceAtLeast(0) else 0L

    override val bufferedPositionMs: Long
        get() = positionMs

    override val durationMs: Long?
        get() = if (!isReleased) mediaPlayer.length.takeIf { it > 0 } else null

    override val isSeekable: Boolean
        get() = !isReleased && mediaPlayer.isSeekable

    override val isPlayRequested: Boolean
        get() = !isReleased && mediaPlayer.isPlaying

    private fun pushPlaying() {
        _state.value = PlaybackState.Playing(
            positionMs = positionMs,
            durationMs = durationMs,
            bitrateBps = 0,
        )
    }

    /** The input is running: `Playing`, or the picture when that came first. Once per open. */
    private fun markStarted() {
        if (_startedAtMs.value == null) _startedAtMs.value = SystemClock.elapsedRealtime()
    }

    /** The source is on the screen, or in the ear: what the loading screen waits for. */
    private fun markOpened() {
        if (_firstFrameAtMs.value != null) return
        markStarted()
        _firstFrameAtMs.value = SystemClock.elapsedRealtime()
        pushPlaying()
    }

    /** True when no video track is selected: a radio station, a song. */
    private fun hasNoPicture(): Boolean = runCatching { mediaPlayer.currentVideoTrack }.getOrNull() == null

    /**
     * The shape of the picture, as LibVLC currently understands it.
     *
     * `sarNum`/`sarDen` is the sample aspect ratio and it is not decoration: an anamorphic
     * source declares square dimensions plus a correction, and a player that reads only
     * the dimensions shows the film squashed. Null for a sound file, which has no video
     * track to ask — the same answer the screen wants there anyway.
     */
    private fun updateVideoShape() {
        if (isReleased) return
        // The null check is on the track itself, so the reads below are on something the
        // compiler knows is there. Checking the width and height instead left `track`
        // nullable and the sample-aspect reads would not compile.
        val track = runCatching { mediaPlayer.currentVideoTrack }.getOrNull()
        if (track == null || track.width <= 0 || track.height <= 0) {
            _videoAspectRatio.value = null
            _videoSize.value = null
            return
        }
        _videoSize.value = FrameSize.of(track.width, track.height)
        val sarNum = track.sarNum.takeIf { it > 0 } ?: 1
        val sarDen = track.sarDen.takeIf { it > 0 } ?: 1
        _videoAspectRatio.value =
            (track.width.toFloat() * sarNum) / (track.height.toFloat() * sarDen)
    }

    private fun updateTracks() {
        if (isReleased) return
        val currentAudio = mediaPlayer.audioTrack
        val audioTracks = mediaPlayer.audioTracks?.map { trackDesc ->
            Track(
                id = trackDesc.id.toString(),
                type = TrackType.AUDIO,
                label = trackDesc.name ?: "Audio ${trackDesc.id}",
                selected = trackDesc.id == currentAudio,
            )
        } ?: emptyList()

        val currentSpu = mediaPlayer.spuTrack
        val spuTracks = mediaPlayer.spuTracks?.map { trackDesc ->
            Track(
                id = trackDesc.id.toString(),
                type = TrackType.SUBTITLE,
                label = trackDesc.name ?: "Subtitle ${trackDesc.id}",
                selected = trackDesc.id == currentSpu,
            )
        } ?: emptyList()

        _tracks.value = TrackSet(
            audio = audioTracks,
            subtitle = spuTracks,
            video = emptyList(),
        )
    }

    override fun sample(): PlaybackSample? {
        if (isReleased || _state.value is PlaybackState.Idle) return null
        val started = _firstFrameAtMs.value
        return PlaybackSample(
            bufferedMs = 0L,
            droppedFrames = 0,
            startupMs = started?.let { it - openedAtMs },
            engine = EngineId.BACKUP,
        )
    }

    /**
     * LibVLC's account of the open, which is coarser than ExoPlayer's.
     *
     * It reports no server answer and no decoder of its own. The first report of a buffer with
     * anything in it is the nearest it comes to the first bytes -- the input is open, reading and
     * demultiplexing -- and the first elementary stream it announces is the tracks being known.
     * The first frame is the video output appearing, as it is everywhere else in this engine.
     */
    override fun openingMarks(): OpeningMarks {
        fun since(at: Long): Long? = if (at == 0L) null else at - openedAtMs
        return OpeningMarks(
            firstBytesMs = since(filledAtMs),
            tracksKnownMs = since(tracksAtMs),
            firstFrameMs = _firstFrameAtMs.value?.let { it - openedAtMs },
            readyMs = _startedAtMs.value?.let { it - openedAtMs },
            clockMovingMs = since(clockAtMs),
            // The video output appearing, which LibVLC says before it says anything of a picture: see above.
            firstFrameExact = false,
        )
    }

    override fun release() {
        if (isReleased) return
        isReleased = true

        runCatching {
            val vout = mediaPlayer.vlcVout
            if (vout.areViewsAttached()) {
                vout.detachViews()
            }
        }
        currentSurfaceView?.removeOnLayoutChangeListener(windowSizer)
        currentSurfaceView = null

        mediaPlayer.setEventListener(null)

        runCatching {
            if (mediaPlayer.isPlaying) {
                mediaPlayer.stop()
            }
        }

        runCatching {
            currentMedia?.release()
            currentMedia = null
        }

        closeDescriptor()

        runCatching {
            mediaPlayer.release()
        }

        _state.value = PlaybackState.Idle
    }

    private fun classifyOpenError(error: Throwable): PlaybackError = when (error) {
        is SecurityException -> PlaybackError.PERMISSION
        is FileNotFoundException -> PlaybackError.NOT_FOUND
        is java.net.SocketTimeoutException -> PlaybackError.TIMEOUT
        is java.net.UnknownHostException,
        is java.net.ConnectException,
        is java.net.SocketException -> PlaybackError.NETWORK
        is java.io.IOException -> PlaybackError.SOURCE
        else -> PlaybackError.UNKNOWN
    }

    /** An Xtream address carries the account in its path; see [SourceRedaction]. */
    private fun safeSource(url: String): String = SourceRedaction.safe(url)

    internal companion object {
        const val TAG = "VlcPlaybackEngine"

        /** What LibVLC reports for a full input buffer: the end of buffering, not a stall. */
        const val FULL_BUFFER = 100f

        @Volatile
        private var shared: LibVLC? = null

        /**
         * One LibVLC for the whole process, built the first time the backup engine is needed.
         *
         * An engine is built fresh for every source, and every one of them used to build a
         * LibVLC of its own -- loading its modules and initialising the library, on the main
         * thread, in front of the picture of every channel the backup played -- and released
         * it again on the way out. LibVLC is made to be shared: what belongs to one source is
         * the `MediaPlayer` and its `Media`, and those are still per engine.
         *
         * No verbose logging, which it had: LibVLC's debug output names every address it
         * opens -- the account in the path included -- and costs a stick a share of its one
         * processor for as long as anything plays.
         */
        fun sharedLibVlc(context: Context): LibVLC =
            shared ?: synchronized(this) {
                shared ?: LibVLC(
                    context.applicationContext,
                    arrayListOf(
                        "--no-drop-late-frames",
                        "--no-skip-frames",
                        "--audio-time-stretch",
                        "--http-reconnect",
                    ),
                ).also { shared = it }
            }

        /** Android's own provider scheme, which LibVLC has no access module for. */
        const val SCHEME_CONTENT = "content"

        /**
         * Whether LibVLC has to be handed a descriptor rather than this URL.
         *
         * A plain string test and not `Uri.parse`, so the rule can be asserted by an
         * ordinary JVM test with no Android and no native library — which is the only kind
         * of test this engine can have, since everything else about it needs a device.
         *
         * The rule itself is narrow: `content://` is Android's provider scheme and LibVLC
         * has no access module for it. Everything else — `file`, `http`, `https`, `rtsp`,
         * `udp` — it opens natively, and routing those through a descriptor would lose the
         * streaming behaviour that is the reason to use it.
         */
        internal fun requiresDescriptor(url: String): Boolean =
            url.startsWith("$SCHEME_CONTENT://", ignoreCase = true)
    }
}