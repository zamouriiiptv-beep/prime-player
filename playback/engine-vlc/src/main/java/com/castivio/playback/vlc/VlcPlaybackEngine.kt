package com.castivio.playback.vlc

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import com.castivio.playback.api.AspectMode
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

    private val _diagnosis = MutableStateFlow<PlaybackDiagnosis?>(null)
    override val diagnosis: StateFlow<PlaybackDiagnosis?> = _diagnosis.asStateFlow()

    private var openedSource: String? = null
    private var openedAtMs: Long = 0
    private var isReleased = false
    private var currentSurfaceView: SurfaceView? = null

    private val libVLC: LibVLC by lazy {
        val options = ArrayList<String>().apply {
            add("--no-drop-late-frames")
            add("--no-skip-frames")
            add("--audio-time-stretch")
            add("--http-reconnect")
            add("-vvv")
        }
        LibVLC(appContext, options)
    }

    private val mediaPlayer: MediaPlayer by lazy {
        MediaPlayer(libVLC).apply {
            setEventListener(eventListener)
        }
    }

    private var currentMedia: Media? = null

    @Volatile
    var aspect: AspectMode = AspectMode.FIT
        private set

    private val eventListener = MediaPlayer.EventListener { event ->
        if (isReleased) return@EventListener
        when (event.type) {
            MediaPlayer.Event.Opening -> {
                _state.value = PlaybackState.Opening
            }
            MediaPlayer.Event.Buffering -> {
                if (_firstFrameAtMs.value == null) {
                    _state.value = PlaybackState.Opening
                } else {
                    val percent = event.buffering
                    val bufferedAheadMs = ((percent / 100f) * tuning.bufferForPlaybackMs).toLong().coerceAtLeast(0L)
                    _state.value = PlaybackState.Buffering(
                        bufferedMs = bufferedAheadMs,
                        bitrateBps = 0,
                    )
                }
            }
            MediaPlayer.Event.Playing -> {
                if (_firstFrameAtMs.value == null) {
                    _firstFrameAtMs.value = SystemClock.elapsedRealtime()
                }
                pushPlaying()
                updateTracks()
            }
            MediaPlayer.Event.Paused -> {
                if (_state.value is PlaybackState.Playing) {
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
                if (event.voutCount > 0 && _firstFrameAtMs.value == null) {
                    _firstFrameAtMs.value = SystemClock.elapsedRealtime()
                    pushPlaying()
                }
            }
            MediaPlayer.Event.ESAdded,
            MediaPlayer.Event.ESDeleted,
            MediaPlayer.Event.ESSelected -> {
                updateTracks()
            }
            MediaPlayer.Event.TimeChanged -> {
                if (_state.value is PlaybackState.Playing) {
                    pushPlaying()
                }
            }
            MediaPlayer.Event.EncounteredError -> {
                val errorReason = if (_firstFrameAtMs.value == null) {
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
                currentSurfaceView = view
                vout.setVideoView(view)
                vout.attachViews()
            }
        }
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
        openedSource = safeSource(media.url)
        _diagnosis.value = null
        _firstFrameAtMs.value = null
        _tracks.value = TrackSet()
        _state.value = PlaybackState.Opening

        val vlcMedia = Media(libVLC, Uri.parse(media.url)).apply {
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

        currentMedia?.release()
        currentMedia = vlcMedia

        mediaPlayer.media = vlcMedia
        mediaPlayer.play()

        if (media.startPositionMs > 0 && media.kind != MediaKind.LIVE) {
            mediaPlayer.time = media.startPositionMs
        }
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
            _firstFrameAtMs.value = null
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

    private fun pushPlaying() {
        _state.value = PlaybackState.Playing(
            positionMs = positionMs,
            durationMs = durationMs,
            bitrateBps = 0,
        )
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

    override fun release() {
        if (isReleased) return
        isReleased = true

        runCatching {
            val vout = mediaPlayer.vlcVout
            if (vout.areViewsAttached()) {
                vout.detachViews()
            }
        }
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

        runCatching {
            mediaPlayer.release()
        }

        runCatching {
            libVLC.release()
        }

        _state.value = PlaybackState.Idle
    }

    private fun classifyOpenError(error: Throwable): PlaybackError = when (error) {
        is SecurityException -> PlaybackError.PERMISSION
        is java.io.FileNotFoundException -> PlaybackError.NOT_FOUND
        is java.net.SocketTimeoutException -> PlaybackError.TIMEOUT
        is java.net.UnknownHostException,
        is java.net.ConnectException,
        is java.net.SocketException -> PlaybackError.NETWORK
        is java.io.IOException -> PlaybackError.SOURCE
        else -> PlaybackError.UNKNOWN
    }

    private fun safeSource(url: String): String {
        return url.substringBefore('?').substringBefore('#').take(120)
    }

    private companion object {
        const val TAG = "VlcPlaybackEngine"
    }
}