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
    private var shapeJob: Job? = null

    /** Set when the primary has already been tried and refused, so a second failure is final. */
    private var backupTried = false

    /** Whether the current attempt ended by the budget expiring rather than by an error. */
    private var timedOut = false

    /** Where a jump was aimed, held until the engine's own clock gets there. */
    private var seekTarget: Long? = null

    /** How many more ticks that aim is believed for. A seek that never lands must expire. */
    private var seekTicks = 0

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

        // Its own collector rather than a third flow in the combine above. That combine is
        // the opening path and it is the piece most recently gone wrong; the shape of the
        // picture is not on the critical path — it arrives with the first frame at the
        // earliest — so it is kept out of the way of the thing that is.
        shapeJob = viewModelScope.launch {
            created.videoAspectRatio.collect { ratio ->
                _state.value = _state.value?.copy(videoAspectRatio = ratio)
            }
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

        // Re-read, because `onFirstFrame` writes to `_state` and the tail of this function
        // rebuilds the state from a snapshot. Built from `current`, that rebuild silently
        // discarded both of the things the first frame had just established: which engine
        // is playing, and that the switch is over. After a fallback the screen therefore
        // still said PRIMARY while the backup was decoding, and the "switching to the
        // backup" line never cleared — the same emission that carried the frame undid them.
        //
        // That is the defect this whole round exists to remove. "Which engine actually
        // ran?" is the first question asked of a failure, and the player was answering it
        // wrongly in exactly the case where the answer matters.
        val settled = _state.value ?: return

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

        _state.value = settled.copy(
            picture = picture,
            // Falls back to what is already on the state, because a healthy engine has no
            // diagnosis and must not erase the one belonging to the failure that put us
            // here. After a fallback the backup opens cleanly, and reading its empty
            // report over the primary's would throw away the only evidence that will ever
            // exist for that failure. What clears this is a new `open`, which builds a
            // fresh state — a report always describes the attempt in front of you.
            diagnosis = engine?.diagnosis?.value ?: timeoutDiagnosis(picture) ?: settled.diagnosis,
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
            val card = Picture.Failed(reason, canTryBackup = false)
            _state.value = current.copy(
                picture = card,
                switching = false,
                // `timeoutDiagnosis` belongs here as much as it does on the engine-state
                // path, and leaving it out left the one failure with no evidence at all.
                // A budget that expired has nothing to read out of the engine — that is
                // what makes it its own reason — so when both engines have gone silent the
                // card said TIMEOUT and the report underneath it was empty. The report is
                // the whole point: it has to say that nothing was reported and no frame
                // arrived, and after how long.
                diagnosis = engine?.diagnosis?.value ?: timeoutDiagnosis(card) ?: current.diagnosis,
            )
            return
        }
        backupTried = true
        // Read the report **before** releasing the engine that holds it. `stopEngine`
        // destroys that object, and the card is drawn later — so fetching at draw time
        // would be asking something that no longer exists. This copy is the entire reason
        // the diagnosis lives on the state instead of being pulled from the engine, and
        // without it the one failure worth explaining switched engines and left nothing
        // behind to explain it.
        val report = engine?.diagnosis?.value ?: current.diagnosis
        stopEngine()
        _state.value = current.copy(
            switching = true,
            picture = Picture.Opening,
            diagnosis = report,
        )
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

    /**
     * Jump, from where the engine actually is.
     *
     * ## Why the rendered position was the wrong base
     *
     * [PlayerState.positionMs] is a *drawn* value: the ticker refreshes it four times a
     * second, and everything in between is 250ms old. Computing a jump from it means every
     * press inside one tick starts from the same base and lands on the same target — so a
     * second press adds nothing, and pressing forward three times quickly moves ten seconds
     * rather than thirty. Backwards it does not even move once: near the start the base is
     * 0, the target clamps to 0, and the control does nothing at all.
     *
     * So the engine is asked, because the engine is the only thing that knows. [seekTarget]
     * carries the accumulation across the gap the engine has of its own: a seek is
     * asynchronous, and the backup engine keeps reporting its old time until the seek lands.
     * Without it a press a tenth of a second after the last one reads a position that has
     * not moved yet and recomputes the same target — the identical defect, one layer deeper.
     *
     * A source that cannot be sought is logged rather than silently dropped. That is the
     * other way for this control to do nothing, and it is not one that should have to be
     * guessed at from a device.
     */
    fun seekBy(deltaMs: Long) {
        countTheAsk()
        val running = engine ?: return
        if (!running.isSeekable) {
            Log.i(TAG, "the source is not seekable — a ${deltaMs}ms jump was ignored")
            return
        }
        val base = seekTarget ?: running.positionMs
        val ceiling = running.durationMs ?: _state.value?.durationMs
        var target = (base + deltaMs).coerceAtLeast(0)
        if (ceiling != null) target = target.coerceAtMost(ceiling)
        aimAt(target, running)
    }

    fun seekTo(positionMs: Long) {
        countTheAsk()
        val running = engine ?: return
        if (!running.isSeekable) {
            Log.i(TAG, "the source is not seekable — a seek to ${positionMs}ms was ignored")
            return
        }
        aimAt(positionMs.coerceAtLeast(0), running)
    }

    /**
     * Ask for a position, and show it before the engine has got there.
     *
     * The timeline moves on the press rather than on the next tick, which is what makes a
     * jump feel like a jump. It is not a lie about the stream: [settle] hands the display
     * back to the engine the moment its own clock arrives, and hands it back regardless
     * after [SEEK_SETTLE_TICKS] so that a seek which never lands cannot freeze the timeline
     * for the rest of the film.
     */
    private fun aimAt(target: Long, running: PlaybackEngine) {
        seekTarget = target
        seekTicks = SEEK_SETTLE_TICKS
        running.seekTo(target)
        _state.value = _state.value?.copy(positionMs = target, lastSeekMs = target)
    }

    /**
     * A press arrived. Recorded before every check there is, and that is the whole point.
     *
     * The jump controls can do nothing for three unrelated reasons — the press never
     * reaching this class, the source refusing to be sought, or the engine accepting a
     * position and staying where it was — and from a device they look identical. This
     * counter separates the first from the other two without a cable: the statistics panel
     * shows it, so a photograph of that panel answers the question.
     */
    private fun countTheAsk() {
        val current = _state.value ?: return
        _state.value = current.copy(seekRequests = current.seekRequests + 1)
    }

    /** Forget an aim: the source under it is gone, or has been jumped past by other means. */
    private fun clearAim() {
        seekTarget = null
        seekTicks = 0
    }

    /** The position to display: the aim while it is outstanding, the engine's own after. */
    private fun settle(reported: Long): Long {
        val aim = seekTarget ?: return reported
        seekTicks -= 1
        if (kotlin.math.abs(reported - aim) <= SEEK_TOLERANCE_MS || seekTicks <= 0) {
            clearAim()
            return reported
        }
        return aim
    }

    fun setSpeed(speed: Float) {
        engine?.setSpeed(speed)
        _state.value = _state.value?.copy(speed = speed)
    }

    /**
     * The picture's fit, on the state as well as on the engine.
     *
     * Both, because the two do different halves of it: the engine records the mode for
     * anything that scales inside itself, and the screen sizes the surface — which is
     * where the letterboxing actually happens for a `SurfaceView`. Recording it only on
     * the engine is why the setting existed and did nothing.
     */
    fun setAspect(mode: AspectMode) {
        engine?.setAspect(mode)
        _state.value = _state.value?.copy(aspect = mode)
    }

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
        // Past any aim a jump left outstanding: the live edge is wherever the stream has
        // got to, so an old target must not go on being displayed over it.
        clearAim()
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
                    val position = settle(e.positionMs)
                    _state.value = current.copy(
                        positionMs = position,
                        bufferedMs = (e.bufferedPositionMs - position).coerceAtLeast(0),
                        durationMs = duration,
                        seekable = e.isSeekable,
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
        clearAim()
        ticker?.cancel(); ticker = null
        sampler?.cancel(); sampler = null
        guideJob?.cancel(); guideJob = null
        shapeJob?.cancel(); shapeJob = null
    }

    /**
     * The user has left the player.
     *
     * This exists because leaving the screen was not enough to stop the sound. The player
     * is shown by swapping a composable in and out, not by a navigation destination, so
     * the view model is scoped to the activity and outlives the screen — nothing cleared
     * it, `onCleared` never ran, and the engine went on decoding with its surface detached.
     * What the user got was a library screen with a film still playing behind it.
     *
     * So the exit says so explicitly: release the engine, drop the state. Decoders, the
     * ticker, the guide and — on the backup — LibVLC and its descriptor all go with it.
     *
     * Called on the way out and nowhere else. Not from `onDispose`, which would also fire
     * on a rotation and stop playback for turning the phone.
     */
    fun leave() {
        Log.i(TAG, "leaving the player")
        release()
        _state.value = null
    }

    override fun onCleared() {
        release()
        super.onCleared()
    }

    /**
     * `internal` rather than `private` so the tick budget can be asserted rather than
     * repeated: a test that wrote 250 of its own would go on passing after the ticker
     * changed, which is the sort of test that is worse than none.
     */
    internal companion object {
        const val TAG = "CastivioPlayer"

        /** Four times a second: enough for a moving position, cheap enough to ignore. */
        const val TICK_MS = 250L
        const val STATS_INTERVAL_MS = 1_000L

        /** Close enough to call a seek landed: three ticks of ordinary playback. */
        const val SEEK_TOLERANCE_MS = 750L

        /** Two seconds. Long enough for a slow seek, short enough not to hold a timeline. */
        const val SEEK_SETTLE_TICKS = 8
    }
}
