package com.castivio.feature.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.domain.PlayerPulse
import com.castivio.playback.api.AspectMode
import com.castivio.playback.api.EngineId
import com.castivio.playback.api.EngineMemory
import com.castivio.playback.api.FallbackPolicy
import com.castivio.playback.api.MediaRequest
import com.castivio.playback.api.OpeningMarks
import com.castivio.playback.api.PlaybackEngine
import com.castivio.playback.api.PlaybackDiagnosis
import com.castivio.playback.api.PlaybackError
import com.castivio.playback.api.PlaybackState
import com.castivio.playback.api.Track
import com.castivio.playback.api.VideoOutput
import com.castivio.playback.api.EngineFactory
import com.castivio.data.subtitles.SubtitleAllowance
import com.castivio.data.subtitles.SubtitleFailure
import com.castivio.data.subtitles.SubtitleOffer
import com.castivio.data.subtitles.SubtitleQuery
import com.castivio.data.subtitles.SubtitleResult
import com.castivio.data.subtitles.SubtitleTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    private val subtitles: SubtitleStyleStore,
    private val hunt: SubtitleSource,
    /**
     * The day's completed downloads.
     *
     * On the constructor rather than reached for, because "how many have actually been
     * downloaded today" is a fact this screen reports and a fact its tests have to be able
     * to set. The defect it exists for was a screen reporting a number nobody was keeping.
     */
    private val allowance: SubtitleAllowance,
) : ViewModel() {

    private val _state = MutableStateFlow<PlayerState?>(null)
    val state: StateFlow<PlayerState?> = _state.asStateFlow()

    /**
     * What the player is doing, as the board beside it needs to say it -- see [PlayerState.pulse].
     *
     * Its own flow, and distinct, because the state is copied four times a second by the ticker
     * and a screen that only wants to know *which* channel is playing, and whether it has a
     * picture, must not wake for a position. It changes when a channel is asked for, gets its
     * first frame, learns its size and stops, and at no other time.
     */
    val pulse: Flow<PlayerPulse?> = _state.map { it?.pulse }.distinctUntilChanged()

    private var engine: PlaybackEngine? = null
    private var engineId: EngineId = EngineId.PRIMARY
    private var output: VideoOutput? = null

    /** Cancelled by the first frame. Never allowed to outlive an open. */
    private var deadline: Job? = null

    /** The opening's silence, [SPINNER_DELAY_MS], which turns the spinner on when it ends. See [beginWait]. */
    private var waitJob: Job? = null

    /** How many openings this player has begun; the number of the current one is on the state. */
    private var attempts = 0
    private var collector: Job? = null
    private var ticker: Job? = null
    private var sampler: Job? = null
    private var guideJob: Job? = null
    private var shapeJob: Job? = null
    private var sizeJob: Job? = null
    private var cueJob: Job? = null
    private var huntJob: Job? = null
    private var captionJob: Job? = null

    /** Set when the primary has already been tried and refused, so a second failure is final. */
    private var backupTried = false

    /** Whether the current attempt ended by the budget expiring rather than by an error. */
    private var timedOut = false

    /** Which of the two bounds in [deadlineFor] it was, for the report. */
    private var timedOutAfterMs = FallbackPolicy.OPEN_DEADLINE_MS

    /**
     * The viewer's caption settings, read once.
     *
     * Once and not per open: this is a disk read, and the critical path is the one place in
     * this class where a disk read is a defect rather than a cost. A player built for one
     * film keeps the same choice for every film after it, which is also what a person
     * expects of a setting.
     */
    private var style: SubtitleStyle = subtitles.read()

    /**
     * The downloaded subtitle, when there is one, and the reason it is not on the state.
     *
     * Fifteen hundred cues, and `PlayerState` is copied four times a second by the ticker.
     * Carrying the track on it would put the whole thing through every `equals` the
     * recomposition machinery performs, to serve a layer that needs one line of it. What
     * reaches the screen is the caption for this moment, like every other caption.
     */
    private var downloaded: SubtitleTrack? = null

    /** Where a jump was aimed, held until the engine's own clock gets there. */
    private var seekTarget: Long? = null

    /**
     * When the current source was asked for, on a clock that only measures.
     *
     * The press, for an open the viewer pressed for -- see [PlayerRequest.pressedAtNanos] --
     * and the moment of asking for one they did not: a return from the background, a retry.
     * Every figure in [OpeningTiming] that belongs to the application is measured from here,
     * fallback included, because this is where the viewer started waiting.
     */
    private var askedAtNanos: Long = 0

    /** The press the last open was timed from, so a request opened again is not timed from it twice. */
    private var lastPress: Long = Long.MIN_VALUE

    /** This attempt's own figures, for [OpeningTiming]. See [timing]. */
    private var pressToOpenNanos: Long = 0
    private var releaseNanos: Long = 0
    private var setupNanos: Long = 0
    private var preparedAtNanos: Long = 0

    /** When this attempt's first frame arrived, or null while it has none. */
    private var frameAtNanos: Long? = null

    /** Whether this attempt is on the engine the last channel played on. See [keeps]. */
    private var reused = false

    /*
     * The opening panel's own state -- temporary, for development: see [OpeningDiagnostics]. Untouched, and never
     * allocated, while the panel is off.
     */
    private var diagnosticJob: Job? = null
    private val mainThread by lazy { MainThreadWatch() }
    private var openingId = 0
    private var openingCase = OpeningCase.FIRST
    private var failedAtNanos: Long? = null

    /** Whether this attempt's failure has been written down, so a card is logged once. */
    private var failureNoted = false

    /**
     * The opening in progress, or the last one, stage by stage.
     *
     * Not on [PlayerState], which is copied four times a second for a screen that never draws
     * this. It goes to the log once per attempt, and is kept here so the tests can read what
     * the log was told.
     */
    internal var opening: OpeningTiming? = null
        private set

    /**
     * Whether the application is in the background, as this player was told.
     *
     * A foreground is only a return when a background came before it: an observer added to a
     * lifecycle that is already started is told `ON_START` at once, and treating that as a
     * return would reattach a surface the engine was given a moment ago.
     */
    private var inBackground = false

    /** A live stream stopped for the background, to be opened again when the viewer returns. */
    private var reopenOnReturn = false

    /** Where the engine's clock was at the last tick, to tell a moving picture from a stall. */
    private var lastTickPosition: Long? = null

    /** How many more ticks that aim is believed for. A seek that never lands must expire. */
    private var seekTicks = 0

    /**
     * Open a source. The whole of the critical path.
     *
     * Called once per media item; a channel change calls [switchTo] instead, which is the
     * same path on the engine the last channel played on when that one can be kept, and with
     * it released first when it cannot.
     */
    fun open(request: PlayerRequest) {
        if (_state.value?.request == request && engine != null) return
        val openedAt = System.nanoTime()
        val next = FallbackPolicy.first(FallbackPolicy.sourceKey(request.url), memory)
        val kept = engine?.takeIf { keeps(it, next, request) }
        // For the opening panel: what this opening is, read before the engine and the state are replaced.
        val case = openingCaseOf(hadEngine = engine != null, kept = kept != null)
        if (kept != null) releaseSource() else release()
        timeFrom(pressOf(request, openedAt), openedAt, releasedAt = System.nanoTime())

        backupTried = false
        timedOut = false
        reopenOnReturn = false
        engineId = next
        Log.i(TAG, if (kept != null) "opening on $engineId, the engine kept from the last channel" else "opening on $engineId")
        downloaded = null
        // Worked out once, here, from the request and never from the URL: the title came
        // from the catalogue row the viewer pressed, which is the only thing in the player
        // that knows what this is.
        //
        // **Not for a channel.** A channel is opened with nothing about subtitles: working a search out of its name and
        // reading the day's download count from storage are for a film's sheet, and on a channel -- which offers no
        // subtitles at all -- they would only stand between the press and the engine.
        val subtitleSearch = if (request.isLive) {
            SubtitleSearch()
        } else {
            val looking = SubtitleQuery.of(request.title, request.subtitle)
            SubtitleSearch(
                // Fixed for the life of the build: whether this APK was compiled with
                // credentials. Asked once, here, rather than by the sheet at draw time.
                available = hunt.available,
                // Name, year and episode — everything worked out, in the one place it is
                // held. A box reading "Pursuit" cannot be checked by the person reading it;
                // "Pursuit 2026" can, and it is what tells the search which Pursuit.
                query = looking.text,
                // Read from where it is kept, on every open, so the sheet can never claim a
                // day is used up while the stored count for today is zero.
                spentToday = allowance.spentToday(),
                dailyLimit = allowance.dailyLimit,
            )
        }
        _state.value = PlayerState(
            request = request,
            engine = engineId,
            attempt = beginWait(since = askedAtNanos),
            subtitleStyle = style,
            subtitleSearch = subtitleSearch,
        )
        if (kept != null) reopen(kept, request) else start(request, engineId)
        diagnose(case)
    }

    /**
     * The next channel, as fast as the previous one can be let go.
     *
     * On the same engine when it can be kept -- see [keeps] -- and otherwise stop and release
     * first, and synchronously: two ExoPlayers alive at once on a low-memory box is how a
     * channel change becomes a stutter, and the old one holding a decoder the new one wants
     * is how it becomes a failure. Either way there is one engine, and nothing between the
     * last channel and the next — no guide, no artwork, no metadata. The next channel's first
     * frame is the only thing being optimised for.
     */
    fun switchTo(request: PlayerRequest) = open(request)

    /**
     * Whether the engine playing now can be handed [request] instead of being released.
     *
     * A channel change on the primary released its ExoPlayer -- the main thread waits for the
     * decoders to be let go -- built another and gave it the surface, and the new one built its
     * decoders again; the last of those had already been built once for the channel before.
     * None of it is needed to change channel, so an engine that offers to be kept
     * ([PlaybackEngine.reusable]) is kept, on three conditions:
     *
     * - **the next source would be opened on this engine anyway.** A channel remembered for the
     *   backup is still opened on the backup.
     * - **both are channels.** A film carries a position, a speed and a fit of its own, and
     *   the tuning a channel was built with is not a film's.
     * - **it has not failed.** An engine that has failed is in a state nobody knows, and keeping
     *   one is how a channel change inherits the last channel's error.
     *
     * The backup never offers, so a channel the fallback moved there is released as before.
     */
    private fun keeps(running: PlaybackEngine, next: EngineId, request: PlayerRequest): Boolean {
        val now = _state.value ?: return false
        return running.reusable &&
            next == engineId &&
            request.isLive && now.request.isLive &&
            running.state.value !is PlaybackState.Failed &&
            running.diagnosis.value == null &&
            now.picture !is Picture.Failed
    }

    /**
     * The next channel on the engine that played the last one.
     *
     * What [start] does, less what a channel change costs most: nothing is released, nothing is
     * built and the surface stays where it is. The engine's listeners stay as well -- they
     * listen to the engine, not to the channel, and the engine resets every flow when it is
     * opened -- so what is new is what belongs to an attempt: its deadline and its figures.
     */
    private fun reopen(kept: PlaybackEngine, request: PlayerRequest) {
        frameAtNanos = null
        failureNoted = false
        reused = true
        setupNanos = 0
        preparedAtNanos = System.nanoTime()
        kept.open(request.media())
        opening = timing()
        deadline = deadlineFor(kept, engineId)
    }

    private fun start(request: PlayerRequest, id: EngineId) {
        val kind = request.kind
        val building = System.nanoTime()
        frameAtNanos = null
        failureNoted = false
        reused = false
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

        // The measured size, beside the shape's and for the same reason: it arrives with the
        // picture and has nothing to do with opening, so it stays out of the combine that decides
        // whether to fall over.
        sizeJob = viewModelScope.launch {
            created.videoSize.collect { size ->
                _state.value = _state.value?.copy(videoSize = size)
            }
        }

        // Its own collector, beside the shape's and for the same reason. Captions arrive
        // with the frames and have nothing to do with opening; putting them in the combine
        // that carries the opening state would put a caption change on the path that
        // decides whether to fall over to the backup engine.
        cueJob = viewModelScope.launch {
            created.cues.collect { lines ->
                // Ignored while a downloaded subtitle is in use. The engine goes on
                // decoding whatever text track the container carries, and letting both
                // reach the layer would draw two subtitles at once — the film's own and
                // the one the viewer went and found because the film's own was wrong.
                if (downloaded == null) _state.value = _state.value?.copy(cues = lines)
            }
        }

        // Everything above is the application's: from here on the time is the source's and
        // the device's, which is why this is the line the opening is divided at.
        preparedAtNanos = System.nanoTime()
        setupNanos = preparedAtNanos - building
        created.open(request.media())
        opening = timing()

        deadline = deadlineFor(created, id)
    }

    /** What the engine is given: the address and how to ask for it, and nothing about the title. */
    private fun PlayerRequest.media() = MediaRequest(
        url = url,
        kind = kind,
        headers = headers,
        userAgent = userAgent,
    )

    /**
     * This attempt's budget, on [running]. Every open has its own, a kept engine's included.
     *
     * Two bounds from the open, and either one ending is the same TIMEOUT it always was.
     * [FallbackPolicy.OPEN_DEADLINE_MS] is for the engine to start, which for Media3 is its
     * first frame, so for the primary nothing comes after it. LibVLC starts before it draws,
     * and a backup that has started is given until [FallbackPolicy.PICTURE_DEADLINE_MS] for
     * its picture rather than a card at three seconds over a source that is about to play.
     */
    private fun deadlineFor(running: PlaybackEngine, id: EngineId): Job = viewModelScope.launch {
        delay(FallbackPolicy.OPEN_DEADLINE_MS)
        // Still not started. Not an error — the engine has not said anything — but the
        // budget is spent, and a source that has not opened in three seconds is not
        // about to.
        if (running.startedAtMs.value == null) {
            Log.w(TAG, "$id did not start in ${FallbackPolicy.OPEN_DEADLINE_MS}ms")
            timeOut(after = FallbackPolicy.OPEN_DEADLINE_MS)
            return@launch
        }
        delay(FallbackPolicy.PICTURE_DEADLINE_MS - FallbackPolicy.OPEN_DEADLINE_MS)
        if (running.firstFrameAtMs.value == null) {
            Log.w(TAG, "$id started but drew nothing in ${FallbackPolicy.PICTURE_DEADLINE_MS}ms")
            timeOut(after = FallbackPolicy.PICTURE_DEADLINE_MS)
        }
    }

    /**
     * The budget ran out [after] that long, which is the TIMEOUT path: the backup, or a card.
     *
     * Not over a card that is already up. The engine has said what went wrong, and its silence
     * afterwards is not a second failure: a timeout there replaced the reason with TIMEOUT, and
     * spent the fallback on its own when the card was offering it to the viewer.
     */
    private fun timeOut(after: Long) {
        if (_state.value?.picture is Picture.Failed) return
        timedOut = true
        timedOutAfterMs = after
        fallOver(PlaybackError.TIMEOUT)
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
                noteFailure(playback.reason)
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
            // The spinner belongs to the opening and goes with it: to the picture, to a card,
            // to the end of a source that ended before it began.
            longWait = settled.longWait && picture is Picture.Opening,
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
        frameAtNanos = System.nanoTime()
        deadline?.cancel()
        deadline = null
        val timing = timing().also { opening = it }
        Log.i(
            TAG,
            "first frame on $engineId ${timing.firstFrameMs}ms after the press" +
                (if (backupTried) " (after the fallback)" else "") + ": ${timing.render()}",
        )
        memory.remember(FallbackPolicy.sourceKey(current.request.url), engineId)
        // The picture is here, so the wait is over; the spinner goes with the opening below.
        waitJob?.cancel(); waitJob = null
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
            noteFailure(reason)
            waitJob?.cancel(); waitJob = null
            val card = Picture.Failed(reason, canTryBackup = false)
            _state.value = current.copy(
                picture = card,
                switching = false,
                longWait = false,
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
        // How far the primary got, read for the same reason and at the same moment as the
        // report: the marks belong to the engine about to be released. Whether it ever heard
        // from the server is the difference between a channel the network failed and one the
        // decoder did, and only this line can say which.
        val gaveUp = timing().copy(fallback = false)
        Log.i(
            TAG,
            "falling over to the backup after ${(System.nanoTime() - askedAtNanos) / NANOS_PER_MS}ms: " +
                "$reason | ${gaveUp.render()}",
        )
        // During an opening the viewer is still waiting for the channel they pressed, and the
        // backup carries on that wait, its second included. After a picture, the picture has
        // just gone and the wait begins again.
        val hadPicture = frameAtNanos != null
        val stopping = System.nanoTime()
        stopEngine()
        releaseNanos = System.nanoTime() - stopping
        _state.value = current.copy(
            switching = true,
            picture = Picture.Opening,
            diagnosis = report,
            attempt = if (hadPicture) beginWait(since = System.nanoTime()) else current.attempt,
            longWait = current.longWait && !hadPicture,
        )
        start(current.request, EngineId.BACKUP)
        // After a picture the viewer waits again, and that wait is an opening of its own; during one it is the same.
        if (hadPicture) diagnose(OpeningCase.FALLBACK)
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
        val pressed = System.nanoTime()
        stopEngine()
        timeFrom(pressed, pressed, releasedAt = System.nanoTime())
        _state.value = current.copy(
            picture = Picture.Opening,
            switching = true,
            attempt = beginWait(since = pressed),
            longWait = false,
        )
        start(current.request, EngineId.BACKUP)
        diagnose(OpeningCase.FALLBACK)
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
        val pressed = System.nanoTime()
        stopEngine()
        timeFrom(pressed, pressed, releasedAt = System.nanoTime())
        backupTried = spent
        Log.i(TAG, "retry on $on (fallback already spent: $spent)")
        _state.value = current.copy(
            picture = Picture.Opening,
            engine = on,
            switching = false,
            attempt = beginWait(since = pressed),
            longWait = false,
        )
        start(request, on)
        diagnose(OpeningCase.AFTER_FAILURE)
    }

    /* ------------------------------------------------------------------- controls */

    /**
     * Where the picture goes. A surface already holding it is not handed it again.
     *
     * The surface calls this whenever it recomposes, which is four times a second while
     * anything plays; passing the same surface back each time was four needless trips into
     * the engine a second.
     */
    fun setOutput(target: VideoOutput?) {
        if (target == output) return
        output = target
        engine?.setVideoOutput(target)
    }

    /**
     * A surface is going away. It gives up the picture only if it still has it.
     *
     * The compact player and the large one swap surfaces in one frame, and the order in which
     * the old one is released and the new one attached is not ours to choose. Releasing
     * unconditionally meant that when the old surface went second it took the picture from
     * the new one -- sound, and a black frame, until something attached it again.
     */
    fun releaseOutput(target: VideoOutput) {
        if (output == target) setOutput(null)
    }

    /**
     * Play, pause, or start the film again.
     *
     * ## The third case, which was missing
     *
     * A film that reaches its end leaves the engine in `Ended` and the control showing a
     * play triangle — so the obvious thing to do is press it, and pressing it called
     * `play()` on a player that is already at the last frame. Neither engine treats that as
     * "start again": Media3 sets `playWhenReady` on a timeline it has already run to the
     * end of and nothing moves, LibVLC ignores it outright. The button was the right button
     * and it did nothing, every time, on every file.
     *
     * A triangle at the end of a film means *replay* in every player anyone has used, so
     * that is what it does: back to the beginning, then play.
     */
    fun playPause() {
        val current = _state.value ?: return
        noteInteraction()
        val running = engine ?: return
        when {
            current.picture is Picture.Ended -> replay()
            // The *engine's* answer, not the screen's. `picture` is the last transition the
            // engine announced, and it can be wrong about now: a stall announces buffering,
            // a transition the engine did not classify announces nothing at all, and the
            // screen then goes on showing a pause bar over a film that is not running.
            // Pressing it called `pause()` on something already paused — a no-op — and the
            // control stayed dead for as long as the announcement was stale, which is
            // exactly "I paused it and then it would not start again".
            running.isPlayRequested -> {
                running.pause()
                _state.value = _state.value?.copy(playRequested = false)
            }

            else -> {
                running.play()
                _state.value = _state.value?.copy(playRequested = true)
            }
        }
    }

    /** From the top. Separate from a seek to zero because it must also start playing. */
    private fun replay() {
        val running = engine ?: return
        Log.i(TAG, "replaying from the start")
        clearAim()
        running.seekTo(0)
        running.play()
        _state.value = _state.value?.copy(positionMs = 0)
    }

    /**
     * The application went to the background, so the sound goes with it.
     *
     * Leaving the player releases the engine; this does not release a film, because the
     * user has not left the player — they have taken a call, or looked at a message, and they
     * expect to come back to the same frame. What they do not expect is a film they cannot
     * see carrying on playing over whatever they went to do. A channel is stopped instead,
     * and opened again on the way back: see the note inside.
     *
     * `ON_STOP` and not `ON_PAUSE` is what calls this, and the distinction is a split
     * screen: a player beside another application is paused by the system and still on
     * screen, and pausing there would stop a film somebody is watching.
     *
     * Nothing resumes a film automatically on the way back -- see [returnFromBackground]. A
     * film that starts itself the moment a phone is unlocked is how a player gets muted for
     * good. A channel has no frame to come back to, so it is the live picture that returns.
     */
    fun pauseForBackground() {
        val current = _state.value ?: return
        val running = engine ?: return
        inBackground = true

        // **Live stops; it is not paused.** A paused channel is a stream left open behind
        // another application -- holding the provider's connection and, on the backup
        // engine, losing its picture when the system takes the surface away -- and a channel
        // resumed from where it was paused is minutes behind the broadcast. It is opened
        // again, on the same engine, when the viewer comes back.
        if (current.request.isLive) {
            Log.i(TAG, "the application went to the background — stopping the channel")
            reopenOnReturn = true
            endDiagnostic()
            stopEngine()
            ticker?.cancel(); ticker = null
            // Nothing is opening while the application is away; the return begins a new wait.
            waitJob?.cancel(); waitJob = null
            _state.value = current.copy(picture = Picture.Opening, switching = false, longWait = false)
            return
        }

        // **Whatever the screen last said.** This paused only a picture announced as
        // `Playing`, so a film that was announced as buffering -- which is what the backup
        // engine announced for the rest of a film after its first buffering report -- went
        // on playing behind whatever the viewer had gone to do. The engine's own answer is
        // what decides, as it does for the play control.
        val picture = current.picture
        if (picture is Picture.Paused || picture is Picture.Ended || picture is Picture.Failed) return
        if (!running.isPlayRequested) return
        Log.i(TAG, "the application went to the background — pausing")
        running.pause()
        _state.value = current.copy(playRequested = false)
    }

    /**
     * The viewer is back.
     *
     * A channel stopped for the background is opened again, on the engine it was on. A film
     * stays paused -- nothing resumes on its own -- and is given its surface again: the
     * system destroyed it and made another while the application was away, and the backup
     * engine does not follow that by itself, so it came back with sound and no picture.
     */
    fun returnFromBackground() {
        if (!inBackground) return
        inBackground = false
        val current = _state.value ?: return
        if (reopenOnReturn) {
            reopenOnReturn = false
            Log.i(TAG, "back from the background — opening the channel again on $engineId")
            val asked = System.nanoTime()
            // Nothing to let go: the channel was stopped on the way out.
            timeFrom(asked, asked, releasedAt = asked)
            _state.value = current.copy(
                picture = Picture.Opening,
                switching = false,
                attempt = beginWait(since = asked),
                longWait = false,
            )
            start(current.request, engineId)
            diagnose(OpeningCase.FIRST)
            return
        }
        val target = output ?: return
        engine?.let { running ->
            running.setVideoOutput(null)
            running.setVideoOutput(target)
        }
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
        noteInteraction()
        val running = engine ?: return
        if (!running.isSeekable) {
            Log.i(TAG, "the source is not seekable — a ${deltaMs}ms jump was ignored")
            return
        }
        val base = seekTarget ?: running.positionMs
        val ceiling = running.durationMs ?: _state.value?.durationMs
        var target = (base + deltaMs).coerceAtLeast(0)
        if (ceiling != null) target = target.coerceAtMost(ceiling)

        // How far this run of presses has moved, not how far one press moves.
        //
        // Pressing forward four times is one intention — "about a minute on" — and a mark
        // that said "+10 s" four times running would answer a question nobody asked. A run
        // is presses that arrive while the previous aim is still outstanding and in the
        // same direction; the aim expires on its own, so stopping for a moment starts the
        // count again, which is what a viewer means by stopping for a moment.
        val inRun = seekTarget != null
        val previous = _state.value?.lastJumpMs ?: 0L
        val sameWay = previous != 0L && (previous > 0) == (deltaMs > 0)
        val run = if (inRun && sameWay) previous + deltaMs else deltaMs

        aimAt(target, running)
        _state.value = _state.value?.copy(lastJumpMs = run)
    }

    fun seekTo(positionMs: Long) {
        countTheAsk()
        noteInteraction()
        val running = engine ?: return
        if (!running.isSeekable) {
            Log.i(TAG, "the source is not seekable — a seek to ${positionMs}ms was ignored")
            return
        }
        aimAt(positionMs.coerceAtLeast(0), running)
        _state.value = _state.value?.copy(lastJumpMs = null)
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
        // Jumping back out of the last frame is a request to watch that part, not to look
        // at it. An engine that has run to the end stays stopped when it is seeked, so the
        // jump controls at the end of a film would move the position and leave the picture
        // frozen — which is the same "the button does nothing" as before, one state later.
        val ended = _state.value?.picture is Picture.Ended
        running.seekTo(target)
        if (ended) running.play()
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

    /**
     * The user did something. The four-second clock on the chrome starts again.
     *
     * It did not, and that is a defect worth naming: the controls hid four seconds after
     * they *appeared*, no matter what happened in between. Tap the picture, reach for a
     * control, and the row could go out from under the thumb already travelling toward it
     * — the press then lands on the picture, which brings the chrome back, and the whole
     * thing reads as a button that does nothing.
     *
     * Every control in the player goes through one of the methods that calls this, so the
     * rule is "any interaction, not any particular one".
     */
    private fun noteInteraction() {
        val current = _state.value ?: return
        _state.value = current.copy(interactions = current.interactions + 1)
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
        noteInteraction()
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
        noteInteraction()
        engine?.setAspect(mode)
        _state.value = _state.value?.copy(aspect = mode)
    }

    /**
     * A caption setting changed. Applied now and remembered for the next film.
     *
     * Written straight through rather than on the way out: a player is left by the user
     * walking away from it, and a setting that waited for a tidy exit would be a setting
     * that is sometimes kept and sometimes not, in a way nobody could reproduce.
     */
    fun setSubtitleStyle(chosen: SubtitleStyle) {
        noteInteraction()
        style = chosen
        subtitles.write(chosen)
        _state.value = _state.value?.copy(subtitleStyle = chosen)
    }

    /* --------------------------------------------------------------- the subtitle hunt */

    /**
     * What the viewer has typed in the search box.
     *
     * Held and not searched with. A search on every keystroke would be a request per letter
     * to a service with a daily allowance, and the box is one line a viewer is often
     * correcting a whole word of — the press of the search key is the moment they meant it.
     */
    fun setSubtitleQuery(text: String) {
        val current = _state.value ?: return
        noteInteraction()
        _state.value = current.copy(subtitleSearch = current.subtitleSearch.copy(query = text))
    }

    /**
     * Look for a subtitle for what is in the box.
     *
     * Nothing here is on the critical path and nothing here can affect playback: a failure
     * is a sentence in a sheet. The search runs at most one at a time — pressing a second
     * language while the first is in flight cancels it, because the answer to a question
     * nobody is asking any more is not worth the wait it causes.
     *
     * What is searched with is [SubtitleSearch.asked]: everything worked out from the title
     * while the box is untouched, and what was typed once it is not.
     */
    fun findSubtitles(language: SubtitleLanguage = _state.value?.subtitleSearch?.language ?: SubtitleLanguage.Arabic) {
        val current = _state.value ?: return
        noteInteraction()

        val search = current.subtitleSearch.copy(language = language, hunt = SubtitleHunt.Searching)
        _state.value = current.copy(subtitleSearch = search)

        val query = search.asked
        // What was played, what was made of it, and what is about to be asked for. The three
        // together are the whole diagnosis of a search that finds nothing: a wrong parse and
        // a work the catalogue does not carry look identical from the sheet, and this is the
        // line that tells them apart. `OpenSubtitlesApi` logs the other half — the identifier
        // it resolved and how many results survived the filter — under the same tag.
        Log.i(
            TAG,
            "subtitle search: source=\"${current.request.title}\" -> title=\"${query.title}\" " +
                "year=${query.year} season=${query.season} episode=${query.episode} " +
                "attempts=${query.attempts().size} language=${search.language.code.ifBlank { "any" }}",
        )
        huntJob?.cancel()
        huntJob = viewModelScope.launch {
            val outcome = hunt.search(
                url = current.request.url,
                query = query,
                languages = search.codes,
            )
            val stage = when (outcome) {
                is SubtitleResult.Found -> SubtitleHunt.Offers(outcome.value)
                is SubtitleResult.Refused -> SubtitleHunt.Failed(outcome.reason)
            }
            _state.value = _state.value?.let { it.copy(subtitleSearch = it.subtitleSearch.copy(hunt = stage)) }
        }
    }

    /**
     * Take one of them, and show it.
     *
     * The track is applied by this class rather than handed to the engine, and that is the
     * decision the whole feature rests on. Media3 would take a side-loaded subtitle only as
     * part of a `MediaItem`, which means re-preparing the source and seeking back mid-film;
     * LibVLC would take a file, which means writing one. Neither would give a sync control,
     * because neither engine can shift a subtitle's timing after the fact.
     *
     * Holding the cues means the same downloaded subtitle draws over either engine, through
     * the same caption layer, with the same settings — and the sync below is a subtraction.
     *
     * ## This is the one place a download is counted
     *
     * The whole daily tally hangs off the `Found` branch below, after the cues are held and
     * the layer is drawing them. Nothing else in the codebase increments it: not the press
     * that got here, not the link request, not the file arriving, not a `200`. Those are
     * parts of one operation, and the operation is what counts.
     *
     * A second press while the first is still in flight is ignored rather than started,
     * which is what stops a double tap on one row from spending two of a day's five.
     */
    fun useSubtitle(offer: SubtitleOffer) {
        val current = _state.value ?: return
        // Already fetching this exact one. A press is not an operation; it is a request for
        // an operation that is already running, and starting a second would cancel a
        // download that had nearly finished in order to make the same one again.
        if ((current.subtitleSearch.hunt as? SubtitleHunt.Fetching)?.offer == offer) return

        noteInteraction()
        if (allowance.spent()) {
            // Refused here rather than at the server, so a viewer who is genuinely out is
            // told so without a request — and so the sentence is about a number this
            // application can show them rather than a status code it received.
            Log.i(
                TAG,
                "downloadStarted=false subtitleId=${offer.fileId} quotaIncremented=false " +
                    "reason=DAILY_LIMIT_REACHED dailyDownloadsBefore=${allowance.spentToday()}",
            )
            _state.value = current.copy(
                subtitleSearch = current.subtitleSearch.copy(
                    hunt = SubtitleHunt.Failed(SubtitleFailure.OUT_OF_DOWNLOADS),
                ),
            )
            return
        }

        _state.value = current.copy(
            subtitleSearch = current.subtitleSearch.copy(hunt = SubtitleHunt.Fetching(offer)),
        )

        huntJob?.cancel()
        huntJob = viewModelScope.launch {
            val before = allowance.spentToday()
            when (val outcome = hunt.download(offer)) {
                is SubtitleResult.Refused -> {
                    // A 406 is the provider stating that the day is gone, which it knows
                    // better than we do — an account is shared across devices, and ours is
                    // only a count of what happened here. Every other refusal changes
                    // nothing: a download that did not happen is not a download.
                    if (outcome.reason == SubtitleFailure.OUT_OF_DOWNLOADS) allowance.markSpent()
                    Log.w(
                        TAG,
                        "downloadSuccess=false subtitleId=${offer.fileId} reason=${outcome.reason} " +
                            "quotaIncremented=${outcome.reason == SubtitleFailure.OUT_OF_DOWNLOADS} " +
                            "dailyDownloadsBefore=$before dailyDownloadsAfter=${allowance.spentToday()}",
                    )
                    _state.value = _state.value?.let {
                        it.copy(subtitleSearch = it.subtitleSearch.copy(hunt = SubtitleHunt.Failed(outcome.reason)))
                    }
                }

                is SubtitleResult.Found -> {
                    downloaded = outcome.value
                    // Counted here: the file arrived, parsed, and is now what the caption
                    // layer draws from. That assignment is this application's "saved", since
                    // a subtitle deliberately never reaches the disk.
                    allowance.recordDownload()
                    Log.i(
                        TAG,
                        "downloadSuccess=true subtitleId=${offer.fileId} fileValid=true " +
                            "cues=${outcome.value.cues.size} quotaIncremented=true " +
                            "dailyDownloadsBefore=$before dailyDownloadsAfter=${allowance.spentToday()}",
                    )
                    _state.value = _state.value?.copy(
                        downloadedSubtitle = offer.name,
                        // A hash match was timed against these exact bytes, so it starts
                        // where it was uploaded. So does a name match — the offset is the
                        // viewer's correction, not a guess this class is entitled to make.
                        subtitleOffsetMs = 0,
                        cues = emptyList(),
                        sheet = null,
                        subtitleSearch = spending(),
                    )
                    startCaptions()
                }
            }
        }
    }

    /** The search as it stands, with the day's tally read back from where it is kept. */
    private fun spending(): SubtitleSearch =
        (_state.value?.subtitleSearch ?: SubtitleSearch()).copy(
            spentToday = allowance.spentToday(),
            dailyLimit = allowance.dailyLimit,
        )

    /**
     * Back to whatever the film itself carries.
     *
     * The engine never stopped decoding its own text track, so there is nothing to restart:
     * dropping the downloaded one lets the next cue through.
     */
    fun clearDownloadedSubtitle() {
        noteInteraction()
        captionJob?.cancel(); captionJob = null
        downloaded = null
        _state.value = _state.value?.copy(
            downloadedSubtitle = null,
            subtitleOffsetMs = 0,
            cues = emptyList(),
        )
    }

    /**
     * Shift the downloaded subtitle, half a second at a time.
     *
     * Positive is later. Half a second because that is the smallest step a viewer can
     * actually judge against dialogue — a tenth is below the threshold at which anyone can
     * tell whether the last press helped, and a whole second overshoots the common case.
     *
     * Bounded, because the control is a pair of buttons and a viewer holding one down is
     * not asking to move the subtitle to next Tuesday.
     */
    fun nudgeSubtitles(deltaMs: Long) {
        val current = _state.value ?: return
        if (downloaded == null) return
        noteInteraction()
        val shifted = (current.subtitleOffsetMs + deltaMs).coerceIn(-SUBTITLE_SHIFT_LIMIT_MS, SUBTITLE_SHIFT_LIMIT_MS)
        _state.value = current.copy(subtitleOffsetMs = shifted)
    }

    /**
     * The caption for this moment, from the downloaded track.
     *
     * Its own loop at a tenth of a second rather than the position ticker's quarter. The
     * ticker's rate is set by what a clock needs, and a caption that arrived up to 250ms
     * late would be visibly behind the dialogue on a line that is only spoken for one
     * second. This runs only while a downloaded subtitle is in use, and it does nothing
     * but read a position and look up a cue.
     */
    private fun startCaptions() {
        captionJob?.cancel()
        captionJob = viewModelScope.launch {
            while (true) {
                val track = downloaded
                val running = engine
                val current = _state.value
                if (track != null && running != null && current != null) {
                    val at = running.positionMs - current.subtitleOffsetMs
                    val lines = track.at(at)?.lines.orEmpty()
                    if (lines != current.cues) _state.value = current.copy(cues = lines)
                }
                delay(CAPTION_TICK_MS)
            }
        }
    }

    fun selectTrack(track: Track) {
        noteInteraction()
        engine?.selectTrack(track)
        _state.value = _state.value?.copy(sheet = null)
    }

    fun showControls(visible: Boolean) {
        val current = _state.value ?: return
        _state.value = current.copy(controls = visible, interactions = current.interactions + 1)
    }

    fun setLocked(locked: Boolean) {
        _state.value = _state.value?.copy(locked = locked, controls = !locked)
    }

    /**
     * Show a sheet, and — for the search — ask the question it exists to ask.
     *
     * Opening the subtitle search runs the search. A screen whose first state is an empty
     * list and a button is a screen that makes the viewer ask for the one thing it does; the
     * title is already known, the language is already chosen, and there is nothing to wait
     * for.
     *
     * Only from [SubtitleHunt.Idle], so coming back to results already found shows them
     * instead of fetching them again out of a metered allowance. A viewer who wants the
     * question asked again presses a language or the search key, both of which do.
     */
    fun openSheet(sheet: Sheet?) {
        noteInteraction()
        val current = _state.value ?: return
        _state.value = current.copy(sheet = sheet)

        if (sheet == Sheet.SubtitleSearch &&
            current.subtitleSearch.askable &&
            current.subtitleSearch.hunt == SubtitleHunt.Idle
        ) {
            findSubtitles()
        }
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
            timedOutAfterMs = timedOutAfterMs,
        )
    }

    /**
     * A new attempt, timed from [from]: the press, a retry or a return from the background.
     *
     * [openedAt] is when the player was asked, and [releasedAt] when the engine before it had
     * been let go, so the gap between the two is what the release cost.
     */
    private fun timeFrom(from: Long, openedAt: Long, releasedAt: Long) {
        askedAtNanos = from
        pressToOpenNanos = openedAt - from
        releaseNanos = releasedAt - openedAt
    }

    /**
     * What an open of [request] is timed from: the press that made it, once.
     *
     * The same request can be opened again with no press at all -- the compact player comes
     * back when the viewer returns to the board -- and timing that from a press minutes old
     * would report minutes nobody waited. So a press is measured from once, and an open after
     * that is measured from itself.
     */
    private fun pressOf(request: PlayerRequest, openedAt: Long): Long {
        val pressed = request.pressedAtNanos
        if (pressed == lastPress || pressed > openedAt) return openedAt
        lastPress = pressed
        return pressed
    }

    /**
     * A new opening: its number, and its silence before the spinner.
     *
     * Nothing on the picture says "opening" for the first [SPINNER_DELAY_MS] after [since]. A
     * channel whose first frame arrives inside that time -- most of them, now that the
     * engine is kept between channels -- shows its picture with nothing in front of it, and
     * one that does not is told so from then until its frame or its card. Counted from the
     * press rather than from here, because the wait is the viewer's.
     *
     * The timer is cancelled by whatever ends the wait, and it checks the opening it was
     * started for as well: a timer that outlived its channel must not put a spinner on the
     * next one. Never less than a millisecond, because the state carrying the new number is
     * set after this returns, and a timer that ran at once would look for it before it was
     * there.
     */
    private fun beginWait(since: Long): Int {
        waitJob?.cancel()
        val mine = ++attempts
        val waited = (System.nanoTime() - since) / NANOS_PER_MS
        waitJob = viewModelScope.launch {
            delay((SPINNER_DELAY_MS - waited).coerceIn(1, SPINNER_DELAY_MS))
            val now = _state.value ?: return@launch
            if (now.attempt == mine && now.picture is Picture.Opening) {
                _state.value = now.copy(longWait = true)
            }
        }
        return mine
    }

    /** The attempt in progress, as far as it has got. */
    private fun timing(): OpeningTiming = OpeningTiming(
        engine = engineId,
        fallback = backupTried,
        reused = reused,
        pressToOpenMs = pressToOpenNanos / NANOS_PER_MS,
        releaseMs = releaseNanos / NANOS_PER_MS,
        setupMs = setupNanos / NANOS_PER_MS,
        prepareMs = (preparedAtNanos - askedAtNanos) / NANOS_PER_MS,
        marks = engine?.openingMarks() ?: OpeningMarks(),
        firstFrameMs = frameAtNanos?.let { (it - askedAtNanos) / NANOS_PER_MS },
    )

    /* ------------------------------------------------- the opening panel (temporary, for development) */

    /**
     * What an opening is, for the panel's comparison: a first play, an engine kept, one built again, or one after a
     * failure. Read at the start of [open], before the engine and the state it looks at are replaced.
     */
    private fun openingCaseOf(hadEngine: Boolean, kept: Boolean): OpeningCase = when {
        _state.value?.picture is Picture.Failed -> OpeningCase.AFTER_FAILURE
        kept -> OpeningCase.REUSED
        hadEngine -> OpeningCase.REBUILT
        else -> OpeningCase.FIRST
    }

    /**
     * Begins the panel's reading of the attempt that has just begun, and keeps reading it every
     * [DIAGNOSTIC_TICK_MS] until it ends: a picture, a failure, or another opening.
     *
     * Everything it reads is already kept -- [timing], the engine's marks, the state -- so it adds nothing to the
     * opening itself. The reading belongs to this attempt by number: one that finds the state on another is
     * recorded as replaced, and what a late event says about the attempt before is never written to this one. Does
     * nothing while the panel is off.
     */
    private fun diagnose(case: OpeningCase) {
        if (!OpeningDiagnostics.enabled) return
        val state = _state.value ?: return
        val attempt = state.attempt
        val channel = state.request.title
        endDiagnostic()
        failedAtNanos = null
        openingCase = case
        val id = OpeningDiagnostics.nextId()
        openingId = id
        mainThread.start()
        diagnosticJob = viewModelScope.launch {
            while (true) {
                val reading = readOpening(id, attempt, channel)
                if (reading.status == OpeningStatus.REPLACED) {
                    OpeningDiagnostics.replaced(reading)
                    mainThread.stop()
                    return@launch
                }
                OpeningDiagnostics.publish(reading)
                if (!reading.running) {
                    mainThread.stop()
                    Log.i(TAG, "opening #$id ${reading.row()}")
                    return@launch
                }
                delay(DIAGNOSTIC_TICK_MS)
            }
        }
    }

    /** The opening as it stands: figures already kept, and a status from the state and the first frame. */
    private fun readOpening(id: Int, attempt: Int, channel: String): OpeningReading {
        val state = _state.value
        val failed = state?.picture as? Picture.Failed
        val now = System.nanoTime()
        val status = when {
            frameAtNanos != null -> OpeningStatus.SHOWN
            failed != null -> OpeningStatus.FAILED
            state == null || state.attempt != attempt -> OpeningStatus.REPLACED
            else -> OpeningStatus.WAITING
        }
        val endedAt = when (status) {
            OpeningStatus.SHOWN -> frameAtNanos ?: now
            // Where the failure was noticed, when it was written down; otherwise when it was first read.
            OpeningStatus.FAILED -> failedAtNanos ?: now.also { failedAtNanos = it }
            else -> now
        }
        return OpeningReading(
            id = id,
            attempt = attempt,
            channel = channel,
            case = if (backupTried) OpeningCase.FALLBACK else openingCase,
            status = status,
            totalMs = (endedAt - askedAtNanos) / NANOS_PER_MS,
            timing = timing(),
            stalls = mainThread.reading(),
            failure = failed?.reason?.name,
        )
    }

    /** Ends the reading in progress: an opening still running is recorded as replaced, never as a success. */
    private fun endDiagnostic() {
        if (!OpeningDiagnostics.enabled) return
        diagnosticJob?.cancel(); diagnosticJob = null
        if (openingId != 0) {
            OpeningDiagnostics.current.value
                ?.takeIf { it.id == openingId && it.running }
                ?.let(OpeningDiagnostics::replaced)
            openingId = 0
        }
        mainThread.stop()
    }

    /**
     * An attempt that ended on a card before it had a picture, written down once with how far
     * it got.
     *
     * A channel that never opens is the case the figures matter most for, and the one the
     * first-frame line can never describe. A failure after the picture is not an opening that
     * failed, and the engine's own report already describes it.
     */
    private fun noteFailure(reason: PlaybackError) {
        if (failureNoted || frameAtNanos != null) return
        failureNoted = true
        if (OpeningDiagnostics.enabled && failedAtNanos == null) failedAtNanos = System.nanoTime()
        val timing = timing().also { opening = it }
        Log.w(TAG, "no picture on $engineId: $reason | ${timing.render()}")
    }

    private fun startTicker() {
        ticker?.cancel()
        lastTickPosition = null
        ticker = viewModelScope.launch {
            while (true) {
                val e = engine
                val current = _state.value
                if (e != null && current != null) {
                    val duration = e.durationMs
                    val reported = e.positionMs
                    val position = settle(reported)
                    // **A picture whose clock moves is not buffering**, whatever was last
                    // announced. The spinner is for a stall, and a stall stops the clock;
                    // one that stayed up over moving video -- an engine's report that was
                    // never taken back -- is cleared here, on the evidence of the clock
                    // alone. A real stall does not move it and keeps its spinner.
                    // Not across a jump: a seek moves the clock at once and buffers after it,
                    // and that buffering is real.
                    val previous = lastTickPosition
                    lastTickPosition = reported
                    val step = if (previous == null) 0L else reported - previous
                    val moving = seekTarget == null && step in MOVING_MS..MOVING_MAX_MS && e.isPlayRequested
                    val picture = if (current.picture is Picture.Buffering && moving) Picture.Playing else current.picture
                    _state.value = current.copy(
                        picture = picture,
                        positionMs = position,
                        bufferedMs = (e.bufferedPositionMs - position).coerceAtLeast(0),
                        durationMs = duration,
                        seekable = e.isSeekable,
                        playRequested = e.isPlayRequested,
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
        // The shape and the captions listen to this engine too, and a fallback or a reopen
        // starts new listeners on the next one: these would otherwise go on collecting from
        // a released engine for the rest of the session.
        shapeJob?.cancel(); shapeJob = null
        sizeJob?.cancel(); sizeJob = null
        cueJob?.cancel(); cueJob = null
        engine?.setVideoOutput(null)
        engine?.release()
        engine = null
    }

    /**
     * Everything that belongs to the source rather than the engine: its budget, its clock, its
     * guide, its statistics and its subtitle. What a kept engine is cleared of between channels.
     */
    private fun releaseSource() {
        endDiagnostic()
        deadline?.cancel(); deadline = null
        waitJob?.cancel(); waitJob = null
        clearAim()
        ticker?.cancel(); ticker = null
        sampler?.cancel(); sampler = null
        guideJob?.cancel(); guideJob = null
        huntJob?.cancel(); huntJob = null
        captionJob?.cancel(); captionJob = null
    }

    private fun release() {
        stopEngine()
        releaseSource()
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

        /** How often the opening panel's reading is taken while a channel opens: ten times a second. */
        const val DIAGNOSTIC_TICK_MS = 100L

        /** Four times a second: enough for a moving position, cheap enough to ignore. */
        const val TICK_MS = 250L

        /**
         * How far the clock must move in one tick to count as a picture that is playing.
         *
         * Most of a tick: a stream playing at normal speed moves 250ms in one, and a stall
         * that is draining its last few frames does not.
         */
        const val MOVING_MS = 200L

        /** And at most a few ticks' worth: more than that in one tick is a jump, not playback. */
        const val MOVING_MAX_MS = 1_500L

        const val NANOS_PER_MS = 1_000_000L
        const val STATS_INTERVAL_MS = 1_000L

        /**
         * How long an opening says nothing before it says "opening": two seconds, from the press.
         *
         * The owner's figure, and inside the rule `UI_ARCHITECTURE.md` §10 sets for every
         * spinner -- never before 300 ms, so a fast action never flashes one. It was a second;
         * two keeps the spinner off the channels that open a little slowly, and a spinner that
         * appears and is gone again a moment later is worse than none.
         */
        const val SPINNER_DELAY_MS = 2_000L

        /** Close enough to call a seek landed: three ticks of ordinary playback. */
        const val SEEK_TOLERANCE_MS = 750L

        /** Two seconds. Long enough for a slow seek, short enough not to hold a timeline. */
        const val SEEK_SETTLE_TICKS = 8

        /**
         * How often a downloaded subtitle is looked up. A tenth of a second.
         *
         * Finer than the position ticker, and for a different reason: the ticker's rate is
         * what a moving clock needs, and a caption that appeared a quarter of a second late
         * would be visibly behind a line of dialogue that is only spoken for one.
         */
        const val CAPTION_TICK_MS = 100L

        /** Ten seconds either way. A viewer correcting a subtitle is not rewriting it. */
        const val SUBTITLE_SHIFT_LIMIT_MS = 10_000L

        /** The step the two buttons take. Small enough to judge, large enough to feel. */
        const val SUBTITLE_STEP_MS = 500L
    }
}
