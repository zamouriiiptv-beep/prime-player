package com.castivio.feature.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.playback.api.AspectMode
import com.castivio.playback.api.EngineId
import com.castivio.playback.api.EngineMemory
import com.castivio.playback.api.FallbackPolicy
import com.castivio.playback.api.MediaRequest
import com.castivio.playback.api.PlaybackEngine
import com.castivio.playback.api.PlaybackDiagnosis
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

    /** Whether the current attempt ended by the budget expiring rather than by an error. */
    private var timedOut = false

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
        timedOut = false
        engineId = FallbackPolicy.first(FallbackPolicy.sourceKey(request.url), memory)
        Log.i(TAG, "opening on $engineId")
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
        // Building an engine constructs decoders and renderers, and on an unusual device
        // that can fail outright. A player that dies here takes the application with it
        // and tells the user nothing; a player that catches it can at least say which
        // engine refused and offer the other one.
        val created = runCatching { engines.create(id, kind) }.getOrElse { error ->
            Log.e(TAG, "could not build the $id engine", error)
            _state.value = _state.value?.copy(
                picture = Picture.Failed(PlaybackError.UNKNOWN, canTryBackup = id == EngineId.PRIMARY),
                switching = false,
            )
            return
        }
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
            if (created.firstFrameAtMs.value == null) {
                Log.w(TAG, "$id produced no frame in ${FallbackPolicy.OPEN_DEADLINE_MS}ms")
                timedOut = true
                fallOver(PlaybackError.TIMEOUT)
            }
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
                // Automatic only where the evidence is specific. `decideAutomatically`
                // deliberately excludes UNKNOWN: spending the single fallback attempt on a
                // failure that did not identify itself is a guess, and it was that guess
                // which made the backup button unreachable in every case where it mattered.
                if (!backupTried && FallbackPolicy.decideAutomatically(playback.reason)) {
                    fallOver(playback.reason)
                    return
                }
                val untried = !backupTried && engineId == EngineId.PRIMARY
                Picture.Failed(
                    // Reported as it happened. There is no second mapping that turns an
                    // exhausted failure into a different one -- that mapping existed, it
                    // renamed everything unclassified to "format not supported", and it is
                    // the reason a perfectly ordinary MP4 was described as an unplayable
                    // codec.
                    reason = playback.reason,
                    canTryBackup = untried && FallbackPolicy.canBackupHelp(playback.reason),
                )
            }
        }

        _state.value = current.copy(
            picture = picture,
            diagnosis = engine?.diagnosis?.value ?: timeoutDiagnosis(picture),
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
                picture = Picture.Failed(reason, canTryBackup = false),
                switching = false,
                diagnosis = engine?.diagnosis?.value ?: current.diagnosis,
            )
            return
        }
        backupTried = true
        stopEngine()
        _state.value = current.copy(switching = true, picture = Picture.Opening)
        start(current.request, EngineId.BACKUP)
    }

    /**
     * The button on the error card, and now genuinely reachable.
     *
     * It appears when the machine declined to spend the fallback on its own — which after
     * `decideAutomatically` means an unidentified failure, the exact case a person should
     * be deciding. The path it takes is the automatic one; what differs is who chose.
     */
    fun tryBackup() {
        val current = _state.value ?: return
        backupTried = true
        stopEngine()
        _state.value = current.copy(picture = Picture.Opening, switching = true)
        start(current.request, EngineId.BACKUP)
    }

    /**
     * Start again, on the engine we are actually on.
     *
     * ## What this used to do, and why it was wrong
     *
     * It reset `backupTried` and re-derived the engine from memory. Memory only records an
     * engine *after a frame renders*, so a source that had never played had nothing
     * remembered, and the derivation returned `PRIMARY` — meaning Retry, pressed after the
     * fallback had already been tried and failed, silently repeated the identical primary
     * attempt. On a device that looks exactly like "both engines failed" when in fact one
     * of them was run twice and the other once.
     *
     * Now it retries what is in front of the user. If the fallback has been spent, Retry
     * retries the fallback; the way back to the primary is a new open, not this button.
     */
    fun retry() {
        val current = _state.value ?: return
        val request = current.request
        val on = engineId
        val spent = backupTried
        stopEngine()
        backupTried = spent
        Log.i(TAG, "retry on $on (fallback already spent: $spent)")
        _state.value = current.copy(picture = Picture.Opening, engine = on, switching = false)
        start(request, on)
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

    /**
     * The report for a failure that produced no exception at all.
     *
     * A budget that expired has nothing to read out of the engine — that is what makes it
     * a distinct reason — so the report is written here, and says the one thing that is
     * true: nothing was reported and no frame arrived.
     */
    private fun timeoutDiagnosis(picture: Picture): PlaybackDiagnosis? {
        if (!timedOut || picture !is Picture.Failed) return null
        return PlaybackDiagnosis(
            engine = engineId,
            reason = PlaybackError.TIMEOUT,
            timedOutAfterMs = FallbackPolicy.OPEN_DEADLINE_MS,
        )
    }

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
        const val TAG = "CastivioPlayer"

        /** Four times a second: enough for a moving position, cheap enough to ignore. */
        const val TICK_MS = 250L
        const val STATS_INTERVAL_MS = 1_000L
    }
}
