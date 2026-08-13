package com.castivio.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.playback.api.AspectMode
import com.castivio.playback.api.EngineId
import com.castivio.playback.api.EngineMemory
import com.castivio.playback.api.FallbackPolicy
import com.castivio.playback.api.MediaRequest
import com.castivio.playback.api.PlaybackEngine
import com.castivio.playback.api.PlaybackError
import com.castivio.playback.api.PlaybackState
import com.castivio.playback.api.Track
import com.castivio.playback.api.VideoOutput
import com.castivio.playback.api.EngineFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The player's brain, and the place the performance contract is actually kept.
 *
 * ## The critical path, in one method
 *
 * [open] is six statements long and does exactly three things: choose an engine from
 * memory, attach the surface, open the URL. It does not fetch a guide, resolve artwork,
 * enumerate tracks, sample statistics, warm the second engine, look up the next episode or
 * check for a bookmark. Every one of those exists in this file, and every one of them is
 * behind either [onFirstFrame] or a user action.
 *
 * The rule is worth stating as a test rather than as a comment, and it is: `PlayerPathTest`
 * asserts that opening a source touches nothing but the engine.
 *
 * ## The fallback, and why it has a deadline rather than a retry count
 *
 * Engine 1 is given [FallbackPolicy.OPEN_DEADLINE_MS] to produce a frame. Two things can
 * end that wait early: a frame, which cancels the deadline and remembers the engine, or a
 * failure that the other engine could plausibly fix. Anything else — a failure neither can
 * fix, or a second failure after the switch — is a card, not another attempt.
 *
 * A deadline rather than a count because a count does not bound anything: three attempts
 * at a host that accepts connections and never sends bytes is three read timeouts, and the
 * user has been looking at a spinner for half a minute. The budget is time, because time is
 * what the user is spending.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val engines: EngineFactory,
    private val memory: EngineMemory,
    private val guide: ProgrammeSource,
) : ViewModel() {

    private val _state = MutableStateFlow<PlayerState?>(null)
    val state: StateFlow<PlayerState?> = _state.asStateFlow()

    private var engine: PlaybackEngine? = null
    private var engineId: EngineId = EngineId.PRIMARY
    private var output: VideoOutput? = null

    /** Cancelled by the first frame. Never allowed to outlive an open. */
    private var deadline: Job? = null
    private var collector: Job? = null
    private var ticker: Job? = null
    private var sampler: Job? = null
    private var guideJob: Job? = null

    /** Set when the primary has already been tried and refused, so a second failure is final. */
    private var backupTried = false

    /**
     * Open a source. The whole of the critical path.
     *
     * Called once per media item; a channel change calls [switchTo] instead, which is the
     * same path with the previous engine released first.
     */
    fun open(request: PlayerRequest) {
        if (_state.value?.request == request && engine != null) return
        release()

        backupTried = false
        engineId = FallbackPolicy.first(FallbackPolicy.sourceKey(request.url), memory)
        _state.value = PlayerState(request = request, engine = engineId)
        start(request, engineId)
    }

    /**
     * The next channel, as fast as the previous one can be let go.
     *
     * Stop and release first, and synchronously: two ExoPlayers alive at once on a
     * low-memory box is how a channel change becomes a stutter, and the old one holding a
     * decoder the new one wants is how it becomes a failure. Nothing between the release
     * and the open — no guide, no artwork, no metadata. The next channel's first frame is
     * the only thing being optimised for.
     */
    fun switchTo(request: PlayerRequest) = open(request)

    private fun start(request: PlayerRequest, id: EngineId) {
        val kind = request.kind
        val created = engines.create(id, kind)
        engine = created
        engineId = id

        // The surface is attached before the URL, so the first decoded frame has
        // somewhere to go. Attaching afterwards means the decoder renders into nothing
        // and the picture waits for the next keyframe.
        output?.let(created::setVideoOutput)

        collector = viewModelScope.launch {
            combine(created.state, created.firstFrameAtMs) { playback, frame -> playback to frame }
                .collect { (playback, frame) -> onEngineState(playback, frame != null) }
        }

        created.open(
            MediaRequest(
                url = request.url,
                kind = request.kind,
                headers = request.headers,
                userAgent = request.userAgent,
            ),
        )

        deadline = viewModelScope.launch {
            delay(FallbackPolicy.OPEN_DEADLINE_MS)
            // Still no frame. Not an error — the engine has not said anything — but the
            // budget is spent, and a source that has not opened in three seconds is not
            // about to.
            if (created.firstFrameAtMs.value == null) fallOver(PlaybackError.DECODER)
        }
    }

    private fun onEngineState(playback: PlaybackState, hasFrame: Boolean) {
        val current = _state.value ?: return

        if (hasFrame && deadline != null) onFirstFrame(current)

        val picture = when (playback) {
            is PlaybackState.Idle, is PlaybackState.Opening ->
                if (hasFrame) Picture.Buffering else Picture.Opening
            is PlaybackState.Buffering -> Picture.Buffering
            is PlaybackState.Playing -> Picture.Playing
            is PlaybackState.Paused -> Picture.Paused
            is PlaybackState.Ended -> Picture.Ended
            is PlaybackState.Failed -> {
                // A failure the other engine might fix takes the source there instead of
                // drawing a card. The card is what is left when there is nowhere to go.
                if (!backupTried && FallbackPolicy.canBackupHelp(playback.reason)) {
                    fallOver(playback.reason)
                    return
                }
                // Whether the card offers the backup is the same question the automatic
                // path asked, answered by the same function. With the automatic fallback
                // in place this is normally false by the time a card is drawn -- the
                // switch has already happened -- and it is true only where the machine
                // declined to spend the fallback and a person might still want to.
                val untried = !backupTried && engineId == EngineId.PRIMARY
                Picture.Failed(
                    reason = if (untried) playback.reason else FallbackPolicy.exhausted(playback.reason),
                    canTryBackup = untried && FallbackPolicy.canBackupHelp(playback.reason),
                )
            }
        }

        _state.value = current.copy(
            picture = picture,
            audioTracks = engine?.tracks?.value?.audio.orEmpty(),
            subtitleTracks = engine?.tracks?.value?.subtitle.orEmpty(),
            videoTracks = engine?.tracks?.value?.video.orEmpty(),
        )
    }

    /**
     * The moment the picture appears, and the only place work is unlocked.
     *
     * Everything deferred off the critical path starts here and nowhere earlier: the
     * guide, the position ticker. Note what still does not start — statistics, which wait
     * for the user, and the second engine, which is never warmed.
     */
    private fun onFirstFrame(current: PlayerState) {
        deadline?.cancel()
        deadline = null
        memory.remember(FallbackPolicy.sourceKey(current.request.url), engineId)
        _state.value = current.copy(engine = engineId, switching = false)
        startTicker()
        loadProgramme(current.request)
    }

    /**
     * Hand the source to the other engine. Once.
     *
     * [backupTried] is set before anything else so that a failure arriving from the old
     * engine while the new one is starting cannot start a third.
     */
    private fun fallOver(reason: PlaybackError) {
        val current = _state.value ?: return
        if (backupTried || engineId == EngineId.BACKUP) {
            _state.value = current.copy(
                picture = Picture.Failed(FallbackPolicy.exhausted(reason), canTryBackup = false),
                switching = false,
            )
            return
        }
        backupTried = true
        stopEngine()
        _state.value = current.copy(switching = true, picture = Picture.Opening)
        start(current.request, EngineId.BACKUP)
    }

    /** The button on the error card. The same path the automatic switch takes. */
    fun tryBackup() {
        val current = _state.value ?: return
        backupTried = true
        stopEngine()
        _state.value = current.copy(picture = Picture.Opening, switching = true)
        start(current.request, EngineId.BACKUP)
    }

    /** Start again on whichever engine this source is now remembered as needing. */
    fun retry() {
        val current = _state.value ?: return
        val request = current.request
        release()
        backupTried = false
        engineId = FallbackPolicy.first(FallbackPolicy.sourceKey(request.url), memory)
        _state.value = current.copy(picture = Picture.Opening, engine = engineId, switching = false)
        start(request, engineId)
    }

    /* ------------------------------------------------------------------- controls */

    fun setOutput(target: VideoOutput?) {
        output = target
        engine?.setVideoOutput(target)
    }

    fun playPause() {
        val current = _state.value ?: return
        if (current.picture is Picture.Playing) engine?.pause() else engine?.play()
    }

    fun seekBy(deltaMs: Long) {
        val current = _state.value ?: return
        engine?.seekTo((current.positionMs + deltaMs).coerceAtLeast(0))
    }

    fun seekTo(positionMs: Long) = engine?.seekTo(positionMs) ?: Unit

    fun setSpeed(speed: Float) {
        engine?.setSpeed(speed)
        _state.value = _state.value?.copy(speed = speed)
    }

    fun setAspect(mode: AspectMode) = engine?.setAspect(mode) ?: Unit

    fun selectTrack(track: Track) {
        engine?.selectTrack(track)
        _state.value = _state.value?.copy(sheet = null)
    }

    fun showControls(visible: Boolean) {
        _state.value = _state.value?.copy(controls = visible)
    }

    fun setLocked(locked: Boolean) {
        _state.value = _state.value?.copy(locked = locked, controls = !locked)
    }

    fun openSheet(sheet: Sheet?) {
        _state.value = _state.value?.copy(sheet = sheet)
    }

    /** Back to the live edge. A control like any other, in the tools row. */
    fun returnToLive() {
        val current = _state.value ?: return
        if (!current.request.isLive) return
        engine?.seekTo(Long.MAX_VALUE)
        _state.value = current.copy(behindLiveMs = 0)
    }

    /**
     * Statistics, which begin to exist when they are asked for.
     *
     * The sampler is the only thing in this class that polls, and it does not exist until
     * this method is called with `true`. Closing the panel cancels it, so a panel that was
     * opened once does not keep a timer alive for the rest of the session.
     */
    fun setStatistics(open: Boolean) {
        sampler?.cancel()
        sampler = null
        _state.value = _state.value?.copy(statistics = open, sample = null)
        if (!open) return
        sampler = viewModelScope.launch {
            while (true) {
                _state.value = _state.value?.copy(sample = engine?.sample())
                delay(STATS_INTERVAL_MS)
            }
        }
    }

    /* -------------------------------------------------------------------- the rest */

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (true) {
                val e = engine
                val current = _state.value
                if (e != null && current != null) {
                    val duration = e.durationMs
                    val position = e.positionMs
                    _state.value = current.copy(
                        positionMs = position,
                        bufferedMs = (e.bufferedPositionMs - position).coerceAtLeast(0),
                        durationMs = duration,
                        behindLiveMs = if (current.request.isLive && duration != null) {
                            (duration - position).coerceAtLeast(0)
                        } else {
                            0
                        },
                    )
                }
                delay(TICK_MS)
            }
        }
    }

    /**
     * The guide, after the picture.
     *
     * Deliberately fire-and-forget into a slot that is already the right height. If it
     * never answers, the strip stays a skeleton and the player is unaffected — which is
     * the property the whole arrangement exists to guarantee, and the reason live playback
     * has no dependency on EPG at all.
     */
    private fun loadProgramme(request: PlayerRequest) {
        guideJob?.cancel()
        val channelId = request.epgChannelId ?: return
        guideJob = viewModelScope.launch {
            val programme = guide.now(channelId) ?: return@launch
            _state.value = _state.value?.copy(programme = programme)
        }
    }

    private fun stopEngine() {
        deadline?.cancel(); deadline = null
        collector?.cancel(); collector = null
        engine?.setVideoOutput(null)
        engine?.release()
        engine = null
    }

    private fun release() {
        stopEngine()
        ticker?.cancel(); ticker = null
        sampler?.cancel(); sampler = null
        guideJob?.cancel(); guideJob = null
    }

    override fun onCleared() {
        release()
        super.onCleared()
    }

    private companion object {
        /** Four times a second: enough for a moving position, cheap enough to ignore. */
        const val TICK_MS = 250L
        const val STATS_INTERVAL_MS = 1_000L
    }
}
