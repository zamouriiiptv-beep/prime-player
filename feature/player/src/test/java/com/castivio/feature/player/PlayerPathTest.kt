package com.castivio.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.castivio.playback.api.AspectMode
import com.castivio.playback.api.EngineFactory
import com.castivio.playback.api.EngineId
import com.castivio.playback.api.EngineMemory
import com.castivio.playback.api.FallbackPolicy
import com.castivio.playback.api.MediaKind
import com.castivio.playback.api.MediaRequest
import com.castivio.playback.api.DecoderReport
import com.castivio.playback.api.OpeningMarks
import com.castivio.playback.api.PlaybackDiagnosis
import com.castivio.playback.api.PlaybackEngine
import com.castivio.playback.api.PlaybackError
import com.castivio.playback.api.PlaybackSample
import com.castivio.playback.api.PlaybackState
import com.castivio.playback.api.Track
import com.castivio.playback.api.TrackSet
import com.castivio.playback.api.VideoOutput
import com.castivio.data.subtitles.SubtitleCue
import com.castivio.data.subtitles.SubtitleFailure
import com.castivio.data.subtitles.SubtitleAllowance
import com.castivio.data.subtitles.SubtitleOffer
import com.castivio.data.subtitles.SubtitleQuery
import com.castivio.data.subtitles.SubtitleResult
import com.castivio.data.subtitles.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The performance contract, as assertions.
 *
 * ## `playerTest` and `runCurrent`, never `runTest` and `advanceUntilIdle`
 *
 * Both exist because the view model owns a ticker that never stops on its own, and both
 * were found the first time CI actually ran this file.
 *
 * `onFirstFrame` starts the position ticker: `while (true) { … delay(TICK_MS) }`. That is
 * correct — a player that stopped telling the time would be the defect — but it means the
 * scheduler always has another task queued once a frame has arrived. Anything that runs
 * the scheduler "until idle" therefore never comes back:
 *
 *  - **`runTest` itself drains the scheduler when the body ends.** So a plain `runTest`
 *    hangs *after* the last assertion has passed, which is a hang with no failing test
 *    and no timeout to explain it — a drain is a loop, not a suspension, so nothing can
 *    cancel it. [playerTest] clears the [ViewModelStore] first, cancelling
 *    `viewModelScope` exactly as the framework does when a real player screen goes away.
 *  - **`advanceUntilIdle` hangs the same way inside a test**, and is wrong a second time
 *    over: before a first frame it runs the opening budget, a `delay` of
 *    [FallbackPolicy.OPEN_DEADLINE_MS], and switching to the backup engine is the exact
 *    thing several of these tests assert does not happen.
 *
 * `runCurrent` runs everything already queued and moves the clock by nothing, which is
 * what "let the pending work settle" was always supposed to mean. Where a test genuinely
 * wants time to pass it says so with `advanceTimeBy`, and that stays readable precisely
 * because nothing else moves the clock behind its back.
 *
 * ## Why this file exists rather than a comment
 *
 * "Nothing unnecessary before the first frame" is the requirement the whole player was
 * built to, and it is the requirement most likely to be broken by a reasonable-looking
 * change six months from now — somebody adds a poster to the loading screen, somebody
 * prefetches the guide "while we're waiting", somebody starts the statistics sampler at
 * open so the panel is instant when it opens. Every one of those is a defensible idea and
 * every one of them costs the user the thing they actually notice.
 *
 * So the rule is machinery. The fakes below **record every call**, and the tests assert on
 * what was and was not touched before the frame arrived. A poster added to the loading
 * screen fails here, with a message that says why.
 *
 * ## Why it runs on the JVM
 *
 * Because the view model was written so it could. It depends on `EngineFactory` and
 * `ProgrammeSource`, both of which are interfaces in modules with no Android in them, so
 * the entire fallback sequence — open, deadline, switch, remember — is testable in
 * milliseconds with no decoder, no surface and no emulator.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerPathTest {

    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private var created = 0
    private lateinit var engines: FakeFactory
    private lateinit var guide: RecordingGuide
    private lateinit var memory: RecordingMemory
    private lateinit var subtitles: RememberedStyle
    private lateinit var hunt: FakeSubtitles
    private lateinit var allowance: CountingAllowance

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        engines = FakeFactory()
        guide = RecordingGuide()
        memory = RecordingMemory()
        subtitles = RememberedStyle()
        hunt = FakeSubtitles()
        allowance = CountingAllowance()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /**
     * A view model held in a real [ViewModelStore], so that [playerTest] can end it.
     *
     * A plain constructor call cannot be stopped: `onCleared` is protected and
     * `viewModelScope` outlives the test, which is exactly the leak that hung CI. Going
     * through the store is also the lifecycle the app itself uses, so what these tests
     * exercise is what actually runs. Each call gets its own key, because a test that
     * opens two players is testing two players.
     */
    private fun model(): PlayerViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                PlayerViewModel(engines, memory, guide, subtitles, hunt, allowance) as T
        }
        return ViewModelProvider(store, factory)["player-${created++}", PlayerViewModel::class.java]
    }

    /**
     * `runTest`, with the view models shut down before it drains the scheduler.
     *
     * This is the fix for a hang that stopped CI dead. `runTest` finishes a test by running
     * the scheduler until it is idle — and `onFirstFrame` starts the position ticker, which
     * is `while (true) { … delay(TICK_MS) }` and correct: a player that stopped telling the
     * time would be the defect. Nothing cancelled it, so from the first test that reached a
     * frame the scheduler always had another task and the drain never ended. No assertion
     * failed and no timeout fired, because a drain is a loop and not a suspension; the job
     * simply went silent for twenty-three minutes until the runner killed it.
     *
     * Clearing the store cancels `viewModelScope`, which is what the framework does when a
     * real player screen goes away. It happens in a `finally` so that a failing assertion
     * still reports its own message rather than being buried under a hang.
     */
    private fun playerTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            store.clear()
        }
    }

    /* ------------------------------------------------------------- the critical path */

    /**
     * Opening a source touches the engine and nothing else.
     *
     * The strongest claim in the file, and deliberately phrased as a list of things that
     * did *not* happen. The guide was not asked, statistics were not sampled, and the
     * second engine was not built — that last one matters because warming it would be the
     * most tempting optimisation on this screen and would double the memory and the CPU of
     * every channel change to save a case that almost never happens.
     */
    @Test
    fun `opening a channel asks the engine for a picture and asks nothing else`() = playerTest {
        val model = model()
        model.open(liveRequest())
        runCurrent()

        assertEquals("one engine was built, and it was the primary", 1, engines.built.size)
        assertEquals(EngineId.PRIMARY, engines.built.single())
        assertEquals("the URL was opened", 1, engines.last.opened.size)

        assertEquals(
            "the guide was consulted before there was a picture — it belongs after the frame",
            0,
            guide.calls,
        )
        assertEquals(
            "statistics were sampled before the user asked for them",
            0,
            engines.last.samples,
        )
        assertFalse(
            "the backup engine was warmed — that is double the memory and the CPU of every " +
                "channel change, to save a case that almost never happens",
            engines.built.contains(EngineId.BACKUP),
        )
    }

    /**
     * The title is on screen while the picture is not.
     *
     * The loading state carries exactly what the opener already knew, and this asserts the
     * carrying rather than the drawing — the layout gate checks that it is composed. What
     * matters here is that no lookup stands between the request and the text.
     */
    @Test
    fun `the title is available before the first frame, from the request alone`() = playerTest {
        val model = model()
        val request = liveRequest()
        model.open(request)
        runCurrent()

        val state = model.state.value!!
        assertTrue("the picture has not arrived", state.picture is Picture.Opening)
        assertEquals("and the title is already there", request.title, state.request.title)
        assertEquals("with no lookup behind it", 0, guide.calls)
    }

    /**
     * The guide arrives after the frame, and only then.
     *
     * Two assertions in sequence, because the interesting claim is the *order*: zero before,
     * one after. Asserting only the second would pass on a player that fetched the guide
     * first and rendered it late.
     */
    @Test
    fun `the guide is fetched after the first frame and never before it`() = playerTest {
        val model = model()
        model.open(liveRequest())
        runCurrent()
        assertEquals("before the frame", 0, guide.calls)

        engines.last.renderFirstFrame()
        runCurrent()

        assertEquals("after the frame", 1, guide.calls)
        assertEquals("and it filled the strip", "Evening news", model.state.value?.programme?.now)
    }

    /**
     * A channel with no guide plays exactly the same.
     *
     * The property that makes EPG genuinely optional rather than merely late. A provider
     * with no XMLTV, a channel the guide does not carry, an import that has not run — all
     * of them land here, and none of them may affect the picture.
     */
    @Test
    fun `a channel whose guide never answers still plays`() = playerTest {
        guide.answer = null
        val model = model()
        model.open(liveRequest())
        engines.last.renderFirstFrame()
        runCurrent()

        assertTrue("the picture is up", model.state.value?.picture is Picture.Playing)
        assertNull("and the strip has no words, which is a state and not a failure",
            model.state.value?.programme)
    }

    /**
     * Statistics do not exist until they are asked for.
     *
     * A sampler started at open would be a timer running for the whole of every session to
     * serve a panel almost nobody opens, and its first tick would land during the opening
     * sequence.
     */
    @Test
    fun `nothing is sampled until the panel is opened, and nothing after it closes`() = playerTest {
        val model = model()
        model.open(vodRequest())
        engines.last.renderFirstFrame()
        advanceTimeBy(5_000)
        assertEquals("no panel, no samples", 0, engines.last.samples)

        model.setStatistics(true)
        advanceTimeBy(3_500)
        val whileOpen = engines.last.samples
        assertTrue("the panel samples while it is open", whileOpen > 0)

        model.setStatistics(false)
        advanceTimeBy(5_000)
        assertEquals(
            "the sampler outlived the panel it was opened for",
            whileOpen,
            engines.last.samples,
        )
    }

    /* ------------------------------------------------------------------ the fallback */

    /**
     * A refusal the other engine could fix goes to the other engine, once.
     *
     * "Once" is the assertion that matters. Two engines built, not three, and no card in
     * between — the switch is silent because the user did not ask for it and cannot act on
     * it.
     */
    @Test
    fun `a decoder refusal switches to the backup exactly once`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()

        engines.last.fail(PlaybackError.DECODER_INIT)
        runCurrent()

        assertEquals("two engines, in order", listOf(EngineId.PRIMARY, EngineId.BACKUP), engines.built)
        assertTrue("and it is still opening rather than showing a card",
            model.state.value?.picture is Picture.Opening)
        assertTrue("with the switch reported", model.state.value?.switching == true)
    }

    /**
     * And when the backup refuses too, that is a card and not a third attempt.
     *
     * The fallback is a budget. This is the assertion that it is spent rather than renewed,
     * and that the sentence changes: what was "this engine could not read it" becomes "no
     * engine can", which is a different card with a different button.
     */
    @Test
    fun `when the backup refuses as well the user gets a card, not a third engine`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.fail(PlaybackError.DECODER_INIT)
        runCurrent()
        engines.last.fail(PlaybackError.DECODER_INIT)
        runCurrent()

        assertEquals("no third engine was built", 2, engines.built.size)
        val picture = model.state.value?.picture
        assertTrue("a card", picture is Picture.Failed)
        picture as Picture.Failed
        assertEquals(
            "the reason the engine gave is the reason the user is told -- no renaming",
            PlaybackError.DECODER_INIT,
            picture.reason,
        )
        assertFalse(
            "offering the backup here would lead straight back to this card",
            picture.canTryBackup,
        )
    }

    /**
     * Protected content never offers the backup, and never switches to it.
     *
     * The rule stated twice on purpose — once about the automatic path and once about the
     * button — because they are two ways a user's time gets spent on a switch that cannot
     * possibly work.
     */
    @Test
    fun `a DRM failure neither switches engines nor offers to`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.fail(PlaybackError.DRM)
        runCurrent()

        assertEquals("no switch was attempted", 1, engines.built.size)
        val picture = model.state.value?.picture as Picture.Failed
        assertEquals(PlaybackError.DRM, picture.reason)
        assertFalse("and no button offers one", picture.canTryBackup)
    }

    /**
     * An unsupported format is now exactly what the backup is for.
     *
     * This test used to assert the opposite, and it was right to. While the backup was a
     * second Media3 profile it could only walk the same device's MediaCodec list, so a
     * format the platform had no decoder for failed identically on both engines and
     * offering the switch would have been a lie. `:playback:engine-vlc` brings its own
     * decoders, which is the single fact that reverses it.
     *
     * Kept as a switching test rather than deleted, because the claim it guards — that
     * this reason is routed deliberately and not by a default branch — is the same claim
     * either way. The DRM test above is now the case that stands for "no engine can help",
     * and it is a better example: the device lacks the keys, and no decoder anywhere
     * changes that.
     */
    @Test
    fun `an unsupported format switches to the backup, which has its own decoders`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.fail(PlaybackError.UNSUPPORTED_FORMAT)
        runCurrent()

        assertEquals(
            "the backup decodes in software, so this is the one failure it exists for",
            listOf(EngineId.PRIMARY, EngineId.BACKUP),
            engines.built,
        )
        assertTrue(
            "and it is still opening on the backup rather than showing a card",
            model.state.value?.picture is Picture.Opening,
        )
        assertTrue("with the switch reported", model.state.value?.switching == true)
    }

    /**
     * A source that says nothing at all is still bounded.
     *
     * The failure a retry count does not cover: a host that accepts the connection and then
     * sends nothing produces no error, so nothing triggers a fallback and the user watches a
     * spinner until a read timeout somewhere decides to fire. The deadline is what makes
     * that case finite.
     */
    @Test
    fun `a source that never opens falls over when the budget runs out`() = playerTest {
        val model = model()
        model.open(vodRequest())
        advanceTimeBy(FallbackPolicy.OPEN_DEADLINE_MS - 100)
        assertEquals("still on the first engine inside the budget", 1, engines.built.size)

        advanceTimeBy(200)
        runCurrent()
        assertEquals("and on the second once it is spent", 2, engines.built.size)
    }

    /**
     * A frame cancels the deadline.
     *
     * Otherwise a stream that opened in 2.9 seconds would be torn down and reopened at 3.0,
     * which is the worst possible outcome: the user waited, got a picture, and lost it.
     */
    @Test
    fun `a frame inside the budget cancels the switch`() = playerTest {
        val model = model()
        model.open(vodRequest())
        advanceTimeBy(FallbackPolicy.OPEN_DEADLINE_MS - 500)
        engines.last.renderFirstFrame()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals("no second engine", 1, engines.built.size)
        assertTrue(model.state.value?.picture is Picture.Playing)
    }

    /* --------------------------------------------- the failures found on a device */

    /**
     * A timeout is reported as a timeout.
     *
     * The regression. The budget expiring used to be reported as `DECODER`, and once the
     * backup had also timed out a second mapping renamed it `UNSUPPORTED_FORMAT` — so a
     * source that simply never answered was described to the user as an unplayable codec,
     * with nothing anywhere having examined the format.
     */
    @Test
    fun `a source that never opens is reported as a timeout, not as a codec problem`() = playerTest {
        val model = model()
        model.open(vodRequest())
        advanceTimeBy(FallbackPolicy.OPEN_DEADLINE_MS + 100)
        runCurrent()
        // The budget switched to the backup. Let that one expire too.
        advanceTimeBy(FallbackPolicy.OPEN_DEADLINE_MS + 100)
        runCurrent()

        val picture = model.state.value?.picture
        assertTrue("a card", picture is Picture.Failed)
        picture as Picture.Failed
        assertEquals(
            "silence must not be reported as an unsupported format",
            PlaybackError.TIMEOUT,
            picture.reason,
        )
        assertEquals(
            "and the report says what actually happened",
            PlaybackError.TIMEOUT,
            model.state.value?.diagnosis?.reason,
        )
        assertEquals(
            FallbackPolicy.OPEN_DEADLINE_MS,
            model.state.value?.diagnosis?.timedOutAfterMs,
        )
    }

    /**
     * An unidentified failure reaches the user with the backup offered.
     *
     * The other half of the dead-button regression. `UNKNOWN` is the one reason the machine
     * does not spend the fallback on, so it is the one case where the card can offer it —
     * and before the split there was no such case at all.
     */
    @Test
    fun `an unidentified failure offers the backup instead of guessing`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.fail(PlaybackError.UNKNOWN)
        runCurrent()

        assertEquals("the machine did not switch on its own", 1, engines.built.size)
        val picture = model.state.value?.picture as Picture.Failed
        assertEquals("and it stays unknown", PlaybackError.UNKNOWN, picture.reason)
        assertTrue("with the backup offered to the person", picture.canTryBackup)

        model.tryBackup()
        runCurrent()
        assertEquals(
            "pressing it builds the backup engine, not another primary",
            listOf(EngineId.PRIMARY, EngineId.BACKUP),
            engines.built,
        )
    }

    /**
     * Retry does not silently re-run the primary.
     *
     * The defect: `retry()` reset `backupTried` and re-derived the engine from memory.
     * Memory only records an engine after a frame renders, so a source that had never
     * played had nothing remembered and the derivation returned `PRIMARY` — meaning Retry,
     * pressed after the fallback had already failed, ran the primary a second time. On a
     * device that is indistinguishable from "both engines failed", which is exactly what
     * was reported.
     */
    @Test
    fun `retry stays on the engine the fallback moved to`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.fail(PlaybackError.DECODER_INIT)
        runCurrent()
        assertEquals(listOf(EngineId.PRIMARY, EngineId.BACKUP), engines.built)

        engines.last.fail(PlaybackError.DECODER_INIT)
        runCurrent()

        engines.built.clear()
        model.retry()
        runCurrent()

        assertEquals(
            "retry re-ran the primary, which is the attempt that already failed",
            listOf(EngineId.BACKUP),
            engines.built,
        )
    }

    /**
     * The engine that is running is on the state, always.
     *
     * Not cosmetic: the badge, the remembered-engine store and every log line key off it,
     * and during the device investigation there was no way to tell from the screen which
     * engine had produced a failure.
     */
    @Test
    fun `the state names the engine that is actually running`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        assertEquals(EngineId.PRIMARY, model.state.value?.engine)

        engines.last.fail(PlaybackError.DECODER_INIT)
        runCurrent()

        // Asserted before the frame so that a failure says which half broke. "expected
        // BACKUP but was PRIMARY" on the last line alone cannot distinguish "the switch
        // never happened" from "it happened and the state did not follow", and those have
        // nothing to do with each other.
        assertEquals(
            "the refusal did not reach the backup engine",
            listOf(EngineId.PRIMARY, EngineId.BACKUP),
            engines.built,
        )

        engines.last.renderFirstFrame()
        runCurrent()

        assertEquals(
            "the backup produced the frame, so it is the engine the state must name",
            EngineId.BACKUP,
            model.state.value?.engine,
        )
    }

    /**
     * The diagnosis reaches the state, and survives the engine being released.
     *
     * The fallback releases the engine that failed. A card that asked the engine for its
     * diagnosis at draw time would be asking an object that no longer exists, which is why
     * the report is copied onto the state at the moment of failure.
     */
    @Test
    fun `the diagnosis is carried on the state, not fetched from a released engine`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.diagnose(PlaybackError.DECODER_INIT, "c2.android.avc.decoder")
        engines.last.fail(PlaybackError.DECODER_INIT)
        runCurrent()

        val report = model.state.value?.diagnosis
        assertTrue("a report reached the state", report != null)
        assertEquals("c2.android.avc.decoder", report?.decoder?.codecName)
        assertTrue(
            "and it renders the decoder name for a person to read",
            report?.render()?.contains("c2.android.avc.decoder") == true,
        )
    }

    /* ------------------------------------------------------------------- leaving */

    /**
     * Back out of the player and the sound stops.
     *
     * The bug this is written for: the player is shown by swapping a composable in and
     * out, not by a navigation destination, so the view model is the activity's and
     * outlives the screen. Nothing cleared it, `onCleared` never ran, and leaving detached
     * the surface but not the decoder — the library screen came back with a film still
     * playing behind it.
     *
     * Asserted on the engine rather than on the state, because "released" is the part the
     * user can hear.
     */
    @Test
    fun `leaving the player releases the engine`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()

        val playing = engines.last
        assertFalse("still playing before the exit", playing.released)

        model.leave()
        runCurrent()

        assertTrue("the engine was still decoding after the user left", playing.released)
        assertNull("and the screen has nothing left to draw", model.state.value)
    }

    /** Leaving twice is what a fast double press is, and it must not throw. */
    @Test
    fun `leaving twice is harmless`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()

        model.leave()
        model.leave()
        runCurrent()

        assertNull(model.state.value)
    }

    /* ---------------------------------------------------------------- the picture */

    /**
     * The shape of the picture reaches the state.
     *
     * Without it the screen cannot letterbox: `FIT` is the only mode that is relative to
     * the source, and it is the default and the one that was wrong on a device — a 16:9
     * film stretched across a 21:9 phone, which is what "zoomed and cropped" was.
     */
    @Test
    fun `the picture's shape reaches the state`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        assertNull("nothing is known before the decoder says", model.state.value?.videoAspectRatio)

        engines.last.declareShape(16f / 9f)
        engines.last.renderFirstFrame()
        runCurrent()

        assertEquals(16f / 9f, model.state.value?.videoAspectRatio ?: 0f, 0.001f)
    }

    /** A sound file has no shape, and saying so is the answer the screen needs. */
    @Test
    fun `a sound file reports no shape at all`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.declareShape(null)
        engines.last.renderFirstFrame()
        runCurrent()

        assertNull(
            "a null ratio means do not letterbox, not letterbox against nothing",
            model.state.value?.videoAspectRatio,
        )
    }

    /**
     * The chosen fit is on the state, not only on the engine.
     *
     * It used to be only on the engine, which is why the setting existed and did nothing:
     * a `SurfaceView` is scaled by its own size, so the screen is the half that has to
     * know, and the screen reads the state.
     */
    @Test
    fun `the chosen fit is recorded where the screen can read it`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        assertEquals(AspectMode.FIT, model.state.value?.aspect)

        model.setAspect(AspectMode.RATIO_4_3)
        runCurrent()

        assertEquals(AspectMode.RATIO_4_3, model.state.value?.aspect)
    }

    /**
     * The button steps through the four fits and comes back round.
     *
     * A cycle rather than a menu because it is one press on a control that lives in a row
     * with six others. Four, not five: [AspectMode.ZOOM] would need the surface drawn
     * larger than the frame and clipped, and a `SurfaceView` is composited by the system
     * rather than drawn by Compose, so it is not offered at all.
     */
    @Test
    fun `the fit cycles through the four the player offers`() {
        val seen = generateSequence(AspectMode.FIT) { nextAspect(it) }
            .take(ASPECT_CYCLE.size)
            .toList()

        assertEquals("every offered fit must be reachable", ASPECT_CYCLE, seen)
        assertEquals("and the cycle must close", AspectMode.FIT, nextAspect(seen.last()))
        assertFalse(
            "zoom cannot be drawn correctly on a SurfaceView, so it is not offered",
            AspectMode.ZOOM in ASPECT_CYCLE,
        )
    }

    /* --------------------------------------------------------------------- the jumps */

    /**
     * A jump is measured from where the engine is, not from what the screen last drew.
     *
     * The defect this replaces: the target was computed from [PlayerState.positionMs], a
     * value the ticker refreshes four times a second. Forty seconds into a film the drawn
     * position can be anything up to a quarter of a second behind, and — worse — before the
     * first tick it is still 0, so the very first press of either control was computed from
     * a position the stream had long left.
     */
    @Test
    fun `a jump is measured from the engine's own position`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()

        // The engine has moved and the ticker has not run, which is the ordinary case: a
        // press lands between two ticks far more often than on one.
        engines.last.positionMs = 40_000

        model.seekBy(10_000)

        assertEquals(
            "the jump was computed from the drawn position rather than from the stream",
            listOf(50_000L),
            engines.last.seeks,
        )
    }

    /**
     * Two presses inside one tick move twice.
     *
     * This is what "the forward and back buttons do nothing" was. Both presses read the same
     * base, so both asked for the same position and the second one was a no-op — and going
     * backwards it was worse than a no-op: near the start of a file the base clamps to 0, so
     * the control could be pressed all day and never ask for anything but 0.
     *
     * The engine's own position deliberately does not move between the presses. A real seek
     * is asynchronous, and the backup engine reports its old time until the seek lands, so
     * reading the engine alone would collapse the two presses exactly as the state did.
     */
    @Test
    fun `two jumps inside one tick move twice`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()
        engines.last.positionMs = 30_000

        model.seekBy(10_000)
        model.seekBy(10_000)
        model.seekBy(10_000)

        assertEquals(
            "the presses collapsed onto one target — the second and third did nothing",
            listOf(40_000L, 50_000L, 60_000L),
            engines.last.seeks,
        )
    }

    /** And backwards, from the start, where the old arithmetic could only ever ask for 0. */
    @Test
    fun `jumping back never asks for a negative position`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()
        engines.last.positionMs = 4_000

        model.seekBy(-10_000)

        assertEquals(listOf(0L), engines.last.seeks)
    }

    /** A jump forward stops at the end of the file rather than off it. */
    @Test
    fun `a jump forward is bounded by the duration`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()
        engines.last.durationMs = 62_000
        engines.last.positionMs = 58_000

        model.seekBy(10_000)

        assertEquals(listOf(62_000L), engines.last.seeks)
    }

    /**
     * The timeline shows the jump on the press, and hands itself back when the seek lands.
     *
     * A control that moves the stream and not the scrubber reads as a control that did
     * nothing, which is half of what was reported. The hand-back is the other half: the aim
     * is a display, not a claim, and the engine is believed again the moment it agrees.
     */
    @Test
    fun `the timeline moves on the press and returns to the engine when the seek lands`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()
        engines.last.positionMs = 30_000

        model.seekBy(10_000)
        assertEquals("the scrubber did not move on the press", 40_000L, model.state.value?.positionMs)

        // The engine has not caught up yet, and the timeline must not fall back to where
        // the stream was before the jump.
        advanceTimeBy(PlayerViewModel.TICK_MS + 1)
        assertEquals(40_000L, model.state.value?.positionMs)

        engines.last.positionMs = 40_120
        advanceTimeBy(PlayerViewModel.TICK_MS)
        assertEquals(
            "once the seek has landed the engine is the only thing worth reading",
            40_120L,
            model.state.value?.positionMs,
        )
    }

    /**
     * A seek that never lands expires rather than freezing the timeline.
     *
     * The aim exists to cover a lag of a few hundred milliseconds. A source that quietly
     * refuses the seek has no lag to cover, and holding its aim would leave the scrubber
     * parked on a position the stream is not at for the rest of the film.
     */
    @Test
    fun `an aim that is never reached gives the timeline back`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()
        engines.last.positionMs = 30_000

        model.seekBy(10_000)
        advanceTimeBy(PlayerViewModel.TICK_MS * (PlayerViewModel.SEEK_SETTLE_TICKS + 1))

        assertEquals(
            "the aim outlived its budget and the timeline stayed on it",
            30_000L,
            model.state.value?.positionMs,
        )
    }

    /**
     * A source that cannot be sought is left alone — and the press is still recorded.
     *
     * Live without a buffer is the real case. The control does nothing either way; the
     * difference is that the state now says which of the reasons it was. A jump control can
     * fail in three unrelated ways that look identical on a device — the press never
     * arriving, the source refusing, or the engine taking a position and staying put — and
     * counting the ask *before* the check is what separates the first from the other two.
     */
    @Test
    fun `a source that cannot be sought records the press and does not seek`() = playerTest {
        val model = model()
        model.open(liveRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()
        engines.last.isSeekable = false

        model.seekBy(10_000)
        model.seekTo(90_000)

        assertTrue("an unseekable source was asked to seek", engines.last.seeks.isEmpty())
        assertEquals(
            "the presses were not recorded, so a refusal is indistinguishable from a " +
                "press that never arrived",
            2,
            model.state.value?.seekRequests,
        )
        assertNull("nothing was aimed at, so nothing may be reported", model.state.value?.lastSeekMs)
    }

    /** And a jump that is accepted says where it went, for the same panel. */
    @Test
    fun `an accepted jump records where it was aimed`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()
        engines.last.positionMs = 30_000

        model.seekBy(10_000)

        assertEquals(1, model.state.value?.seekRequests)
        assertEquals(40_000L, model.state.value?.lastSeekMs)
    }

    /**
     * Whether the source can be sought at all reaches the panel.
     *
     * The only place a person can be told, and the reason it is on the state rather than
     * read from the engine at draw time: the engine that knows is released the moment a
     * fallback switches.
     */
    @Test
    fun `whether the source can be sought reaches the state`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()

        assertTrue("a seekable source was reported as fixed", model.state.value?.seekable == true)

        engines.last.isSeekable = false
        advanceTimeBy(PlayerViewModel.TICK_MS + 1)

        assertFalse(
            "the panel would go on saying a refused source can be sought",
            model.state.value?.seekable == true,
        )
    }

    /* ------------------------------------------------------------------ the last frame */

    /**
     * A triangle at the end of a film starts it again.
     *
     * The control showed a play triangle when the film ended — correctly — and pressing it
     * called `play()` on an engine already sitting on the last frame. Neither engine treats
     * that as "start again": Media3 sets `playWhenReady` on a timeline it has run to the end
     * of and nothing moves, LibVLC ignores it. The right button, doing nothing, every time.
     */
    @Test
    fun `pressing play at the end of a film starts it again`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()
        engines.last.finish()
        runCurrent()
        assertEquals(Picture.Ended, model.state.value?.picture)

        model.playPause()

        assertEquals("the film was not sent back to the start", listOf(0L), engines.last.seeks)
        assertTrue("and nothing asked it to play again", engines.last.playing)
    }

    /**
     * And a jump backwards out of the last frame plays, rather than stepping a still.
     *
     * The same defect one state along: an engine that has ended stays stopped when it is
     * seeked, so the jump control at the end of a film would move the position and leave the
     * picture frozen.
     */
    @Test
    fun `jumping back from the end resumes the film`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()
        engines.last.positionMs = 600_000
        engines.last.finish()
        runCurrent()

        model.seekBy(-10_000)

        assertEquals(listOf(590_000L), engines.last.seeks)
        assertTrue("the picture would have stayed frozen on the last frame", engines.last.playing)
    }

    /**
     * The play control asks the engine what it is doing, not the screen.
     *
     * `picture` is the last transition the engine announced, and it can be wrong about now:
     * a stall announces buffering, a transition the engine did not classify announces
     * nothing at all, and the screen then goes on showing a pause bar over a film that is
     * not running. Deciding from that snapshot means pressing the control calls `pause()`
     * on something already paused — a no-op — and it stays dead for as long as the
     * announcement is stale. Which is exactly "I paused it and it would not start again".
     */
    @Test
    fun `the play control asks the engine and not the screen`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()
        assertEquals(Picture.Playing, model.state.value?.picture)

        // Stopped without saying so. The screen still reads Playing.
        engines.last.playing = false

        model.playPause()

        assertTrue(
            "the control paused something already paused, and there is no way out of that",
            engines.last.playing,
        )
    }

    /** And the ordinary direction, so the fix is not simply "always play". */
    @Test
    fun `the play control pauses a film that is running`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()

        model.playPause()

        assertTrue(engines.last.paused)
        assertFalse(engines.last.playing)
    }

    /**
     * The screen is told what the engine has been asked to do, four times a second.
     *
     * The icon used to read `picture`, which is the last transition the engine announced
     * and can be stale — a pause bar over a paused film, and the control that should have
     * started it offering to stop it. The intent is published so the icon can read the
     * same thing the control acts on.
     */
    @Test
    fun `the play intent reaches the screen`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()
        assertEquals(true, model.state.value?.playRequested)

        model.playPause()

        assertEquals("the icon would still show a pause bar", false, model.state.value?.playRequested)
    }

    /**
     * A run of presses reports how far the run went, not how far one press goes.
     *
     * Pressing forward four times is one intention — "about a minute on" — and a mark that
     * said the same ten seconds four times running answers a question nobody asked.
     */
    @Test
    fun `a run of jumps reports the distance of the run`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()
        engines.last.positionMs = 30_000

        model.seekBy(10_000)
        assertEquals(10_000L, model.state.value?.lastJumpMs)
        model.seekBy(10_000)
        assertEquals(20_000L, model.state.value?.lastJumpMs)
        model.seekBy(10_000)
        assertEquals(30_000L, model.state.value?.lastJumpMs)
    }

    /** Turning round starts the count again: two directions are two intentions. */
    @Test
    fun `changing direction starts the count again`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()
        engines.last.positionMs = 60_000

        model.seekBy(10_000)
        model.seekBy(10_000)
        model.seekBy(-10_000)

        assertEquals(-10_000L, model.state.value?.lastJumpMs)
    }

    /**
     * And a drag is not a jump, so it clears the mark rather than raising the last one.
     *
     * The mark is keyed on the count of seeks, and a scrub asks for a great many. Without
     * this, dragging the bar would flash "+30 s" from a jump made a minute earlier.
     */
    @Test
    fun `dragging the bar clears the jump mark`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()
        model.seekBy(10_000)
        assertEquals(10_000L, model.state.value?.lastJumpMs)

        model.seekTo(90_000)

        assertNull(model.state.value?.lastJumpMs)
    }

    /* ------------------------------------------------------------------- the captions */

    /**
     * The words reach the screen.
     *
     * There was no path for them at all: a text track was selected, decoded, and thrown
     * away, because the screen has no `PlayerView` and so no `SubtitleView`. This is the
     * whole of the fix, from the engine's side.
     */
    @Test
    fun `caption lines reach the state`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()
        assertTrue(model.state.value?.cues.orEmpty().isEmpty())

        engines.last.say("مرحبًا", "كيف حالك؟")
        runCurrent()

        assertEquals(listOf("مرحبًا", "كيف حالك؟"), model.state.value?.cues)
    }

    /** And they go away again, which is the half that leaves words frozen on screen. */
    @Test
    fun `caption lines clear when the moment passes`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        engines.last.say("مرحبًا")
        runCurrent()

        engines.last.say()
        runCurrent()

        assertTrue(model.state.value?.cues.orEmpty().isEmpty())
    }

    /**
     * The viewer's choice is remembered, and read once rather than per film.
     *
     * Once because reading it is a disk read and the critical path is the one place in this
     * class where a disk read is a defect. Remembered because a setting that resets with
     * every film is not a setting.
     */
    @Test
    fun `the caption settings are kept between films`() = playerTest {
        subtitles.held = SubtitleStyle(size = SubtitleSize.Large, ink = SubtitleInk.Amber)

        val model = model()
        model.open(vodRequest())
        runCurrent()

        assertEquals(SubtitleSize.Large, model.state.value?.subtitleStyle?.size)
        assertEquals(SubtitleInk.Amber, model.state.value?.subtitleStyle?.ink)

        model.switchTo(liveRequest())
        runCurrent()

        assertEquals(
            "a new film started from the defaults, so the setting was not a setting",
            SubtitleSize.Large,
            model.state.value?.subtitleStyle?.size,
        )
    }

    /** Changing one is applied at once and written through, not saved on the way out. */
    @Test
    fun `changing a caption setting applies it and stores it`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()

        model.setSubtitleStyle(SubtitleStyle(place = SubtitlePlace.Top))

        assertEquals(SubtitlePlace.Top, model.state.value?.subtitleStyle?.place)
        assertEquals("the choice was not written", 1, subtitles.writes)
        assertEquals(SubtitlePlace.Top, subtitles.held.place)
    }

    /* --------------------------------------------------------------- the subtitle hunt */

    /**
     * A search asks for the language that was chosen, and reports what came back.
     *
     * The language reaching the request is the half of a picker that stops being true
     * silently: the sheet would still highlight Arabic and the results would still be
     * whatever the server felt like sending.
     */
    @Test
    fun `a search asks in the chosen language and reports the results`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        hunt.offers = SubtitleResult.Found(listOf(offer("road-ar.srt")))

        model.findSubtitles(SubtitleLanguage.Arabic)
        runCurrent()

        assertEquals(listOf(listOf("ar")), hunt.searches)
        val stage = model.state.value?.subtitleSearch?.hunt
        assertTrue(stage is SubtitleHunt.Offers && stage.offers.size == 1)
    }

    /**
     * The search asks for the film, and never for the last segment of the URL.
     *
     * The defect, at the level it was introduced. `search` used to build its query from
     * `url.substringAfterLast('/')`, so a stream at `…/user/pass/502` asked OpenSubtitles
     * for "502" and was answered with episode 502 of five unrelated series. The URL is a
     * route to bytes; it never said anything about what the bytes are.
     */
    @Test
    fun `a search asks for the title and not for the stream number`() = playerTest {
        val model = model()
        model.open(
            PlayerRequest(
                url = "http://provider.tv/live/user/pass/502",
                title = "The Matrix",
                kind = MediaKind.VOD,
            ),
        )
        runCurrent()

        model.findSubtitles(SubtitleLanguage.Arabic)
        runCurrent()

        assertEquals(listOf(SubtitleQuery(title = "The Matrix")), hunt.asked)
    }

    /**
     * The box opens holding the name of what is playing, cleaned.
     *
     * What a provider calls a file is not what a catalogue calls a film, and the difference
     * is the whole search: `The.Matrix.1999.1080p.BluRay.x264-GROUP.mkv` matches nothing
     * anywhere. It is also what the viewer reads to decide whether to correct it, which is
     * why the cleaning happens before the box and not on the way out of it.
     */
    @Test
    fun `the search box opens with the name of what is playing`() = playerTest {
        val model = model()
        model.open(
            PlayerRequest(
                url = "content://media/external/video/42",
                title = "The.Matrix.1999.1080p.BluRay.x264-GROUP.mkv",
                kind = MediaKind.VOD,
            ),
        )
        runCurrent()

        assertEquals("The Matrix 1999", model.state.value?.subtitleSearch?.query)
    }

    /**
     * The listing found on a device, in the box and in the request.
     *
     * A provider's row is written to be clicked: the name, the year, the star, the genre.
     * Sent whole it matched nothing, so the sheet said "no subtitles available" for a film
     * that has them — and the box showed the viewer the whole listing, which told them
     * nothing about why.
     */
    @Test
    fun `a shop listing reaches the box and the request as a name`() = playerTest {
        val model = model()
        model.open(
            PlayerRequest(
                url = "http://provider.tv/vod/77",
                title = "PURSUIT -- 2026 Jason Statham Full Action Movie",
                kind = MediaKind.VOD,
            ),
        )
        runCurrent()

        assertEquals("Pursuit 2026", model.state.value?.subtitleSearch?.query)

        model.findSubtitles(SubtitleLanguage.Arabic)
        runCurrent()

        assertEquals(listOf(SubtitleQuery("Pursuit", year = 2026)), hunt.asked)
    }

    /**
     * The box carries the year, and the year reaches the request.
     *
     * A box reading `The Matrix` cannot be checked by the person reading it, and it is not
     * an identification either — it is what *The Matrix Reloaded* and *The Matrix
     * Resurrections* would also produce. The year is what separates them, so it is written
     * where a viewer can see it and correct it, and it is what the search is run with.
     */
    @Test
    fun `the box carries the year into the request`() = playerTest {
        val model = model()
        model.open(
            PlayerRequest(
                url = "content://media/external/video/42",
                title = "The.Matrix.1999.1080p.BluRay.mkv",
                kind = MediaKind.VOD,
            ),
        )
        runCurrent()

        model.findSubtitles(SubtitleLanguage.Arabic)
        runCurrent()

        assertEquals("The Matrix 1999", model.state.value?.subtitleSearch?.query)
        assertEquals(listOf(SubtitleQuery("The Matrix", year = 1999)), hunt.asked)
    }

    /**
     * The next film gets its own box, and its own results.
     *
     * A search sheet still holding the last film's name is worse than one holding nothing:
     * it is confidently wrong, and a viewer who presses search gets subtitles for whatever
     * they were watching before. Everything about the search is rebuilt with the request.
     */
    @Test
    fun `changing the video recalculates the box`() = playerTest {
        val model = model()
        model.open(
            PlayerRequest(
                url = "http://provider.tv/vod/1",
                title = "PURSUIT -- 2026 Jason Statham Full Action Movie",
                kind = MediaKind.VOD,
            ),
        )
        runCurrent()
        model.findSubtitles(SubtitleLanguage.Arabic)
        runCurrent()

        model.switchTo(
            PlayerRequest(
                url = "http://provider.tv/vod/2",
                title = "The.Matrix.1999.1080p.BluRay.mkv",
                kind = MediaKind.VOD,
            ),
        )
        runCurrent()

        assertEquals("The Matrix 1999", model.state.value?.subtitleSearch?.query)
        assertEquals(
            "the previous film's results were still on the sheet",
            SubtitleHunt.Idle,
            model.state.value?.subtitleSearch?.hunt,
        )
    }

    /** An episode carries its numbers into the box, in the one form the box can be typed in. */
    @Test
    fun `an episode opens with its season and episode`() = playerTest {
        val model = model()
        model.open(
            PlayerRequest(
                url = "http://provider.tv/series/9",
                title = "Friends",
                subtitle = "Season 5 · Episode 2",
                kind = MediaKind.VOD,
            ),
        )
        runCurrent()

        assertEquals("Friends S05E02", model.state.value?.subtitleSearch?.query)

        model.findSubtitles(SubtitleLanguage.Arabic)
        runCurrent()

        assertEquals(listOf(SubtitleQuery("Friends", season = 5, episode = 2)), hunt.asked)
    }

    /**
     * Typing a different title searches for that title instead.
     *
     * The escape hatch, and the reason the box is editable rather than a label: no amount of
     * parsing rescues a film whose provider named it in one language and whose subtitles are
     * catalogued in another. Typing is the answer, and it has to reach the request.
     */
    @Test
    fun `what the viewer types is what is searched for`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()

        model.setSubtitleQuery("Casablanca 1942")
        model.findSubtitles(SubtitleLanguage.English)
        runCurrent()

        assertEquals(listOf(SubtitleQuery("Casablanca", year = 1942)), hunt.asked)
    }

    /** And an edit alone is not a search: a request per keystroke is a spent allowance. */
    @Test
    fun `typing does not search`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()

        model.setSubtitleQuery("Casa")
        model.setSubtitleQuery("Casablanca")
        runCurrent()

        assertTrue("a search ran while the viewer was still typing", hunt.asked.isEmpty())
    }

    /**
     * Opening the sheet runs the search, once.
     *
     * The title is known, the language is chosen and there is nothing to wait for, so a
     * screen whose first state is an empty list and a button makes the viewer ask for the
     * only thing it does. Coming back to results already found shows them rather than
     * spending a second request out of a metered daily allowance to fetch the same rows.
     */
    @Test
    fun `opening the search runs it, and reopening does not run it again`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()

        model.openSheet(Sheet.SubtitleSearch)
        runCurrent()
        assertEquals(1, hunt.asked.size)

        model.openSheet(null)
        model.openSheet(Sheet.SubtitleSearch)
        runCurrent()

        assertEquals("the same search ran twice", 1, hunt.asked.size)
    }

    /** With nothing to search for, opening the sheet asks nothing and says so. */
    @Test
    fun `opening the search with an empty box asks nothing`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        model.setSubtitleQuery("")

        model.openSheet(Sheet.SubtitleSearch)
        runCurrent()

        assertTrue(hunt.asked.isEmpty())
        assertEquals(SubtitleHunt.Idle, model.state.value?.subtitleSearch?.hunt)
    }

    /** And a build with no credentials asks nothing either, however it is opened. */
    @Test
    fun `an unconfigured build never searches`() = playerTest {
        hunt.available = false
        val model = model()
        model.open(vodRequest())
        runCurrent()

        model.openSheet(Sheet.SubtitleSearch)
        runCurrent()

        assertTrue(hunt.asked.isEmpty())
    }

    /** "Any language" sends no filter at all, which is what the row promises. */
    @Test
    fun `any language sends no filter`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()

        model.findSubtitles(SubtitleLanguage.Any)
        runCurrent()

        assertEquals(listOf(emptyList<String>()), hunt.searches)
    }

    /** A refusal is a sentence for the sheet, not an exception and not a broken player. */
    @Test
    fun `a refused search is reported and playback is untouched`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()
        hunt.offers = SubtitleResult.Refused(SubtitleFailure.NETWORK)

        model.findSubtitles(SubtitleLanguage.Arabic)
        runCurrent()

        assertEquals(
            SubtitleHunt.Failed(SubtitleFailure.NETWORK),
            model.state.value?.subtitleSearch?.hunt,
        )
        assertEquals("the film stopped because a subtitle search failed", Picture.Playing, model.state.value?.picture)
    }

    /* ------------------------------------------------------------- the day's downloads */

    /**
     * Nothing that is not a download counts as one.
     *
     * The defect, at its root. The player told people the day's downloads were used up when
     * they had downloaded nothing at all — because nothing was counting downloads, and the
     * sentence was a mistranslation of an HTTP status arriving on a *search*. This is the
     * whole of the path a viewer takes before any file is asked for, and it must leave the
     * number where it found it.
     */
    @Test
    fun `opening, searching, and getting results spend nothing`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        hunt.offers = SubtitleResult.Found(listOf(offer("road-ar.srt")))

        model.openSheet(Sheet.SubtitleSearch)
        runCurrent()
        model.setSubtitleQuery("Casablanca 1942")
        model.findSubtitles(SubtitleLanguage.English)
        runCurrent()

        assertTrue("a search returned results", model.state.value?.subtitleSearch?.hunt is SubtitleHunt.Offers)
        assertEquals("something that was not a download was counted as one", 0, allowance.recorded)
        assertEquals(0, allowance.spentToday())
        assertEquals(0, model.state.value?.subtitleSearch?.spentToday)
    }

    /**
     * A refused download costs nothing, whatever refused it.
     *
     * Every one of these is a download that did not happen — a bad gateway, a missing file,
     * a throttle, an unreadable file — and a tally that charged for them would run out
     * without a single subtitle ever reaching the screen.
     */
    @Test
    fun `a failed download does not spend the day`() = playerTest {
        listOf(
            SubtitleFailure.NETWORK,
            SubtitleFailure.UNREADABLE,
            SubtitleFailure.RATE_LIMITED,
            SubtitleFailure.REFUSED,
            SubtitleFailure.NOT_CONFIGURED,
        ).forEach { reason ->
            val model = model()
            model.open(vodRequest())
            runCurrent()
            hunt.track = SubtitleResult.Refused(reason)

            model.useSubtitle(offer("road-ar.srt"))
            runCurrent()

            assertEquals("$reason was counted as a download", 0, allowance.recorded)
            assertEquals("$reason moved the day's count", 0, allowance.spentToday())
        }
    }

    /**
     * A cancelled download costs nothing either.
     *
     * Changing channel mid-download cancels the job before the result is applied, and an
     * operation that never finished is not one that happened. Counting at the request would
     * have charged for this; counting where the cues are put to use does not.
     */
    @Test
    fun `a cancelled download does not spend the day`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        hunt.track = SubtitleResult.Found(track())
        hunt.hold = true

        model.useSubtitle(offer("road-ar.srt"))
        runCurrent()
        model.switchTo(liveRequest())
        hunt.release()
        runCurrent()

        assertEquals(0, allowance.recorded)
        assertEquals(0, allowance.spentToday())
    }

    /** A completed one costs exactly one, and the state says so afterwards. */
    @Test
    fun `a completed download spends one`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        hunt.track = SubtitleResult.Found(track())

        model.useSubtitle(offer("road-ar.srt"))
        runCurrent()

        assertEquals(1, allowance.recorded)
        assertEquals(1, allowance.spentToday())
        assertEquals(1, model.state.value?.subtitleSearch?.spentToday)
    }

    /**
     * Two presses on one row are one operation.
     *
     * A press is a request for an operation, not an operation. The second is ignored while
     * the first is in flight — which also stops it cancelling a download that had nearly
     * finished in order to start the same one again.
     */
    @Test
    fun `pressing the same result twice spends one`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        hunt.track = SubtitleResult.Found(track())
        hunt.hold = true

        model.useSubtitle(offer("road-ar.srt"))
        model.useSubtitle(offer("road-ar.srt"))
        runCurrent()
        hunt.release()
        runCurrent()

        assertEquals("one row, two presses, two downloads", 1, allowance.recorded)
        assertEquals("the second press started a second request", 1, hunt.downloads)
    }

    /**
     * The provider's word beats ours, and only from a download.
     *
     * A `406` on a download request is OpenSubtitles saying the day is gone — which it knows
     * better than this device does, because the same account is spent from other devices. It
     * is the one refusal allowed to move the number, and it does not increment it so much as
     * settle it.
     */
    @Test
    fun `the provider saying the day is gone is believed`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        hunt.track = SubtitleResult.Refused(SubtitleFailure.OUT_OF_DOWNLOADS)

        model.useSubtitle(offer("road-ar.srt"))
        runCurrent()

        assertEquals("a refusal was counted as a completed download", 0, allowance.recorded)
        assertEquals(1, allowance.declaredSpent)
        assertEquals(5, allowance.spentToday())
    }

    /**
     * With the day genuinely gone, nothing is even asked for.
     *
     * The sentence is about a number this application holds, so it can be said before a
     * request rather than in place of a status code — and a viewer who is out is not made to
     * wait for a round trip to be told.
     */
    @Test
    fun `a spent day refuses without asking`() = playerTest {
        allowance.startAt(5)
        val model = model()
        model.open(vodRequest())
        runCurrent()

        model.useSubtitle(offer("road-ar.srt"))
        runCurrent()

        assertEquals("a request went out on a spent day", 0, hunt.downloads)
        assertEquals(
            SubtitleHunt.Failed(SubtitleFailure.OUT_OF_DOWNLOADS),
            model.state.value?.subtitleSearch?.hunt,
        )
    }

    /** And a spent day still searches. A quota is on downloads, not on looking. */
    @Test
    fun `a spent day can still search`() = playerTest {
        allowance.startAt(5)
        val model = model()
        model.open(vodRequest())
        runCurrent()

        model.findSubtitles(SubtitleLanguage.Arabic)
        runCurrent()

        assertEquals(1, hunt.asked.size)
        assertTrue(model.state.value?.subtitleSearch?.hunt is SubtitleHunt.Offers)
    }

    /**
     * What the screen shows is what is stored, on every open.
     *
     * The defect stated as a claim about the state: with nothing downloaded today, the sheet
     * cannot be in the condition that draws "today's downloads are used up".
     */
    @Test
    fun `the sheet reports the stored count and not a guess`() = playerTest {
        allowance.startAt(2)
        val model = model()
        model.open(vodRequest())
        runCurrent()

        val search = model.state.value?.subtitleSearch
        assertEquals(2, search?.spentToday)
        assertEquals(5, search?.dailyLimit)
        assertFalse("two of five is not a spent day", search?.spent ?: true)
    }

    @Test
    fun `nothing downloaded today is never a spent day`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()

        assertEquals(0, model.state.value?.subtitleSearch?.spentToday)
        assertFalse(model.state.value?.subtitleSearch?.spent ?: true)
    }

    /**
     * A downloaded subtitle is shown, and the film's own is not shown underneath it.
     *
     * The engine goes on decoding whatever text track the container carries — nothing asks
     * it to stop — so letting both reach the layer would draw two subtitles at once: the
     * film's own, and the one the viewer went and found *because* the film's own was wrong.
     */
    @Test
    fun `a downloaded subtitle is used and the engine's own is ignored`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()
        hunt.track = SubtitleResult.Found(track())

        model.useSubtitle(offer("road-ar.srt"))
        runCurrent()
        engines.last.positionMs = 1_500
        advanceTimeBy(PlayerViewModel.CAPTION_TICK_MS + 1)

        assertEquals("road-ar.srt", model.state.value?.downloadedSubtitle)
        assertEquals(listOf("مرحبًا"), model.state.value?.cues)

        engines.last.say("the film's own line")
        runCurrent()

        assertEquals(
            "the film's own subtitle drew over the downloaded one",
            listOf("مرحبًا"),
            model.state.value?.cues,
        )
    }

    /** And the sheet closes when it lands, because the viewer's question has been answered. */
    @Test
    fun `applying a subtitle closes the sheet`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        model.openSheet(Sheet.SubtitleSearch)
        hunt.track = SubtitleResult.Found(track())

        model.useSubtitle(offer("one.srt"))
        runCurrent()

        assertNull(model.state.value?.sheet)
    }

    /** A download that fails leaves the film's own subtitles alone. */
    @Test
    fun `a failed download changes nothing about the film`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()
        hunt.track = SubtitleResult.Refused(SubtitleFailure.OUT_OF_DOWNLOADS)

        model.useSubtitle(offer("one.srt"))
        runCurrent()

        assertNull(model.state.value?.downloadedSubtitle)
        assertEquals(
            SubtitleHunt.Failed(SubtitleFailure.OUT_OF_DOWNLOADS),
            model.state.value?.subtitleSearch?.hunt,
        )

        // And the film's own words get through again, because nothing was switched off.
        engines.last.say("the film's own line")
        runCurrent()
        assertEquals(listOf("the film's own line"), model.state.value?.cues)
    }

    /**
     * The caption follows the film, and the sync shifts it.
     *
     * Positive is later: a subtitle that arrives before the words are spoken is pushed back,
     * so the cue that covered 1.0–2.0 seconds now covers 1.5–2.5. The whole control is this
     * subtraction, and it is only possible because the cues are ours rather than the
     * engine's — neither engine can shift a subtitle's timing after the fact.
     */
    @Test
    fun `the sync shifts a downloaded subtitle in both directions`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()
        hunt.track = SubtitleResult.Found(track())
        model.useSubtitle(offer("one.srt"))
        runCurrent()

        // 1.2s is inside the first cue, which runs from 1.0 to 2.0.
        engines.last.positionMs = 1_200
        advanceTimeBy(PlayerViewModel.CAPTION_TICK_MS + 1)
        assertEquals(listOf("مرحبًا"), model.state.value?.cues)

        // Pushed half a second later, 1.2s is now before it starts.
        model.nudgeSubtitles(PlayerViewModel.SUBTITLE_STEP_MS)
        advanceTimeBy(PlayerViewModel.CAPTION_TICK_MS + 1)
        assertEquals(500L, model.state.value?.subtitleOffsetMs)
        assertTrue(model.state.value?.cues.orEmpty().isEmpty())

        // Back to where it started, and the line returns.
        model.nudgeSubtitles(-PlayerViewModel.SUBTITLE_STEP_MS)
        advanceTimeBy(PlayerViewModel.CAPTION_TICK_MS + 1)
        assertEquals(0L, model.state.value?.subtitleOffsetMs)
        assertEquals(listOf("مرحبًا"), model.state.value?.cues)
    }

    /**
     * And the other direction, which is the one a name match usually needs.
     *
     * A subtitle that arrives after the words have been spoken is pulled earlier: at 0.6s
     * nothing is showing, and shifted half a second early the cue that starts at 1.0s is.
     */
    @Test
    fun `a negative sync pulls a late subtitle forward`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()
        hunt.track = SubtitleResult.Found(track())
        model.useSubtitle(offer("one.srt"))
        runCurrent()

        engines.last.positionMs = 600
        advanceTimeBy(PlayerViewModel.CAPTION_TICK_MS + 1)
        assertTrue("nothing is spoken yet at 0.6s", model.state.value?.cues.orEmpty().isEmpty())

        model.nudgeSubtitles(-PlayerViewModel.SUBTITLE_STEP_MS)
        advanceTimeBy(PlayerViewModel.CAPTION_TICK_MS + 1)

        assertEquals(-500L, model.state.value?.subtitleOffsetMs)
        assertEquals(listOf("مرحبًا"), model.state.value?.cues)
    }

    /** The shift is bounded: a viewer correcting a subtitle is not rewriting it. */
    @Test
    fun `the sync cannot be pushed past its limit`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        hunt.track = SubtitleResult.Found(track())
        model.useSubtitle(offer("one.srt"))
        runCurrent()

        repeat(TOO_MANY) { model.nudgeSubtitles(PlayerViewModel.SUBTITLE_STEP_MS) }

        assertEquals(PlayerViewModel.SUBTITLE_SHIFT_LIMIT_MS, model.state.value?.subtitleOffsetMs)
    }

    /** Removing it hands the film's own subtitles back, and forgets the shift with it. */
    @Test
    fun `removing a downloaded subtitle restores the film's own`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()
        hunt.track = SubtitleResult.Found(track())
        model.useSubtitle(offer("one.srt"))
        runCurrent()
        model.nudgeSubtitles(PlayerViewModel.SUBTITLE_STEP_MS)

        model.clearDownloadedSubtitle()
        runCurrent()

        assertNull(model.state.value?.downloadedSubtitle)
        assertEquals(0L, model.state.value?.subtitleOffsetMs)

        engines.last.say("the film's own line")
        runCurrent()
        assertEquals(listOf("the film's own line"), model.state.value?.cues)
    }

    /**
     * A build with no credentials says so, and never asks.
     *
     * The sheet reads this to decide whether to offer the row at all, so it has to be on the
     * state from the moment the player opens rather than discovered by a request that fails.
     */
    @Test
    fun `an unconfigured build reports the search as unavailable`() = playerTest {
        hunt.available = false

        val model = model()
        model.open(vodRequest())
        runCurrent()

        assertFalse(model.state.value?.subtitleSearch?.available ?: true)
    }

    /** Opening another film drops the subtitle that was found for the last one. */
    @Test
    fun `a new film starts with no downloaded subtitle`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        hunt.track = SubtitleResult.Found(track())
        model.useSubtitle(offer("one.srt"))
        runCurrent()
        assertEquals("one.srt", model.state.value?.downloadedSubtitle)

        model.switchTo(liveRequest())
        runCurrent()

        assertNull(model.state.value?.downloadedSubtitle)
    }

    /**
     * Nothing about the search happens before the first frame.
     *
     * The critical-path rule, applied to the newest thing on the screen. A search that
     * warmed itself on open would be a network call, and a login, in front of the picture.
     */
    @Test
    fun `opening a film searches for nothing`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()

        assertTrue("a subtitle search ran without being asked for", hunt.searches.isEmpty())
        assertEquals(0, hunt.downloads)
        assertEquals(SubtitleHunt.Idle, model.state.value?.subtitleSearch?.hunt)
    }

    private fun offer(name: String) = SubtitleOffer(
        fileId = 11,
        language = "ar",
        name = name,
        downloads = 12,
        matchesThisFile = true,
    )

    /** Two lines, a second each, with a silence between them. */
    private fun track() = SubtitleTrack(
        listOf(
            SubtitleCue(1_000, 2_000, listOf("مرحبًا")),
            SubtitleCue(3_000, 4_000, listOf("وداعًا")),
        ),
    )

    /** More presses than the limit can absorb, so the clamp is what is being read. */
    private val TOO_MANY = 40

    /* ---------------------------------------------------------------- the background */

    /**
     * The sound stops when the application does.
     *
     * Not a release: the user has not left the player, they have taken a call, and they
     * expect the same frame when they come back. What they do not expect is a film they
     * cannot see carrying on over whatever they went to do.
     */
    @Test
    fun `going to the background pauses the film`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()

        model.pauseForBackground()

        assertTrue("the film played on behind another application", engines.last.paused)
        assertFalse("the engine was released, so coming back would reopen the file", engines.last.released)
    }

    /** And a film already paused is left alone, so returning does not fight the user. */
    @Test
    fun `going to the background does not touch a film that is already paused`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()
        engines.last.pauseFrom()
        runCurrent()
        engines.last.paused = false

        model.pauseForBackground()

        assertFalse(engines.last.paused)
    }

    /**
     * A film whose last announcement was buffering is paused all the same.
     *
     * The rule paused only a picture announced as playing, and the backup engine announced
     * buffering for the rest of a film after its first fill report -- so the film went on
     * playing behind whatever the viewer had gone to do.
     */
    @Test
    fun `going to the background pauses a film that last announced buffering`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()
        engines.last.stall()
        runCurrent()
        assertEquals(Picture.Buffering, model.state.value?.picture)

        model.pauseForBackground()

        assertTrue("a film announced as buffering played on behind another application", engines.last.paused)
        assertFalse(engines.last.released)
    }

    /**
     * A channel is stopped for the background, and opened again on the way back.
     *
     * Paused, it was a connection held open behind another application and a picture minutes
     * behind the broadcast when it resumed; on the backup engine it came back as sound with no
     * picture. Each half is called twice, as two observers of one lifecycle would call it.
     */
    @Test
    fun `going to the background stops a channel and coming back opens it again`() = playerTest {
        val model = model()
        model.open(liveRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()
        val first = engines.last

        model.pauseForBackground()
        model.pauseForBackground()
        runCurrent()

        assertTrue("the channel went on streaming behind another application", first.released)
        assertEquals(Picture.Opening, model.state.value?.picture)

        model.returnFromBackground()
        model.returnFromBackground()
        runCurrent()

        assertEquals("the channel was not opened again, once, on its engine", listOf(EngineId.PRIMARY, EngineId.PRIMARY), engines.built)
        assertEquals(1, engines.last.opened.size)
        assertEquals(liveRequest().url, engines.last.opened.single().url)
    }

    /**
     * A film comes back paused, with its surface handed back to the engine.
     *
     * The system destroys the surface while the application is away and makes another, and
     * the backup engine does not follow that by itself: it came back with sound and no picture.
     */
    @Test
    fun `coming back gives a film its surface again and does not start it`() = playerTest {
        val model = model()
        val surface = VideoOutput.Platform("surface")
        model.setOutput(surface)
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()
        model.pauseForBackground()
        engines.last.outputs.clear()

        model.returnFromBackground()
        runCurrent()

        assertEquals("the surface was not handed back", listOf<VideoOutput?>(null, surface), engines.last.outputs)
        assertFalse("the film started itself on the way back", engines.last.playing)
        assertEquals("the film was opened again", 1, engines.built.size)
    }

    /** A start that no stop came before is the observer being added, not a return. */
    @Test
    fun `a start that did not follow a stop changes nothing`() = playerTest {
        val model = model()
        model.setOutput(VideoOutput.Platform("surface"))
        model.open(liveRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()
        engines.last.outputs.clear()

        model.returnFromBackground()
        runCurrent()

        assertEquals("a second engine was built for a return that never happened", 1, engines.built.size)
        assertTrue("the surface was handed over again", engines.last.outputs.isEmpty())
    }

    /* ------------------------------------------------------------------- the surface */

    /**
     * The compact surface going away after the large one took the picture leaves it there.
     *
     * The two swap in one frame, in an order that is not ours to choose. A release that
     * detached whatever was attached took the picture from the surface just given it.
     */
    @Test
    fun `a surface released after its successor attached leaves the picture there`() = playerTest {
        val model = model()
        val compact = VideoOutput.Platform("compact")
        val large = VideoOutput.Platform("large")
        model.setOutput(compact)
        model.open(liveRequest())
        runCurrent()

        model.setOutput(large)
        model.releaseOutput(compact)

        assertEquals("the old surface took the picture with it", large, engines.last.outputs.last())
    }

    /** And a surface released while it holds the picture gives it up. */
    @Test
    fun `a surface released while it holds the picture detaches it`() = playerTest {
        val model = model()
        val surface = VideoOutput.Platform("surface")
        model.setOutput(surface)
        model.open(liveRequest())
        runCurrent()

        model.releaseOutput(surface)

        assertNull("the engine kept a surface that is going away", engines.last.outputs.last())
    }

    /** The surface is handed over once, not on every recomposition of the screen. */
    @Test
    fun `the same surface is not handed to the engine twice`() = playerTest {
        val model = model()
        val surface = VideoOutput.Platform("surface")
        model.open(liveRequest())
        runCurrent()

        model.setOutput(surface)
        model.setOutput(surface)

        assertEquals(listOf<VideoOutput?>(surface), engines.last.outputs)
    }

    /* ---------------------------------------------------------------- the spinner */

    /**
     * A buffering announcement over a clock that is moving is taken down.
     *
     * An engine that announced buffering and never took it back left the spinner over a
     * picture that went on playing. The clock is the evidence: it moves only while frames are
     * being shown.
     */
    @Test
    fun `a buffering announcement over a moving clock is cleared`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()
        engines.last.stall()
        runCurrent()
        assertEquals(Picture.Buffering, model.state.value?.picture)

        repeat(2) {
            engines.last.positionMs += PlayerViewModel.TICK_MS
            advanceTimeBy(PlayerViewModel.TICK_MS)
            runCurrent()
        }

        assertEquals("the spinner stayed up over a moving picture", Picture.Playing, model.state.value?.picture)
    }

    /** A real stall stops the clock, and keeps its spinner. */
    @Test
    fun `a stall whose clock has stopped keeps its spinner`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()
        engines.last.stall()
        runCurrent()

        repeat(4) {
            advanceTimeBy(PlayerViewModel.TICK_MS)
            runCurrent()
        }

        assertEquals(Picture.Buffering, model.state.value?.picture)
    }

    /** A jump moves the clock at once and buffers after it, and that buffering is real. */
    @Test
    fun `a jump is not taken for a moving picture`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()

        model.seekBy(10_000)
        engines.last.stall()
        engines.last.positionMs = 10_000
        advanceTimeBy(PlayerViewModel.TICK_MS)
        runCurrent()

        assertEquals("a jump was read as playback", Picture.Buffering, model.state.value?.picture)
    }

    /* --------------------------------------------------------------- the chrome clock */

    /**
     * Every action restarts the clock on the controls.
     *
     * The chrome hid four seconds after it *appeared*, whatever happened in between — so a
     * row could go out from under a thumb already travelling toward it, and the press that
     * followed landed on the picture instead of the control. That is indistinguishable, from
     * the far side of a screen, from a button that does nothing.
     *
     * Asserted as a number that moves rather than as a timer, because the timer belongs to
     * the screen: this is the signal the screen keys its clock on, and what a test can hold.
     */
    @Test
    fun `every control restarts the clock on the chrome`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()

        val marks = mutableListOf<Int>()
        fun mark() = marks.add(model.state.value?.interactions ?: -1)

        mark()
        model.playPause(); mark()
        model.seekBy(10_000); mark()
        model.seekTo(20_000); mark()
        model.setSpeed(1.5f); mark()
        model.setAspect(AspectMode.RATIO_4_3); mark()
        model.openSheet(Sheet.Audio); mark()
        model.showControls(true); mark()

        assertEquals(
            "a control was pressed and the clock on the chrome did not restart: $marks",
            marks.sorted(),
            marks,
        )
        assertEquals("every one of those had to count", marks.first() + 7, marks.last())
    }

    /* -------------------------------------------------------------------- the memory */

    /**
     * A source pays the fallback once.
     *
     * The engine that produced the frame is written down, and the next open reads it. This
     * is the difference between "one channel in this bundle is slow" and "one channel in
     * this bundle was slow once".
     */
    @Test
    fun `the engine that worked is remembered and used first next time`() = playerTest {
        val model = model()
        val request = vodRequest()
        model.open(request)
        runCurrent()
        engines.last.fail(PlaybackError.DECODER_INIT)
        runCurrent()
        engines.last.renderFirstFrame()
        runCurrent()

        assertEquals(
            "the backup is what worked, so the backup is what is written down",
            EngineId.BACKUP,
            memory.preferred(FallbackPolicy.sourceKey(request.url)),
        )

        engines.built.clear()
        val second = model()
        second.open(request)
        runCurrent()

        assertEquals(
            "the second play opens straight on the engine that worked — no wasted attempt",
            listOf(EngineId.BACKUP),
            engines.built,
        )
    }

    /**
     * Changing channel releases the old engine before it builds the new one.
     *
     * Two players alive at once is how a channel change becomes a stutter on a low-memory
     * box, and how the old one holding a decoder the new one wants becomes a failure.
     */
    @Test
    fun `a channel change releases the previous engine first`() = playerTest {
        val model = model()
        model.open(liveRequest())
        engines.last.renderFirstFrame()
        runCurrent()
        val first = engines.last

        model.switchTo(liveRequest(url = "http://provider.tv/live/2.ts", title = "Second"))
        runCurrent()

        assertTrue("the previous engine was released", first.released)
        assertEquals("and exactly one new one was built", 2, engines.built.size)
        assertEquals("with the new title already on screen", "Second", model.state.value?.request?.title)
    }

    /* ---------------------------------------------------------- the opening, stage by stage */

    /**
     * A channel change is timed from the press, and every part the application owns is a
     * figure of its own.
     *
     * The fakes spend real time -- a release that takes 40ms, a build that takes 30 -- and the
     * assertions are lower bounds, because the claim is that each figure measures its own
     * stage. An upper bound would be a claim about how busy the machine running the test is.
     */
    @Test
    fun `a channel change is timed from the press, stage by stage`() = playerTest {
        val model = model()
        model.open(liveRequest())
        engines.last.renderFirstFrame()
        runCurrent()

        engines.last.releaseTakesMs = 40
        engines.buildTakesMs = 30
        val next = liveRequest(url = "http://provider.tv/live/2.ts", title = "Second")
        Thread.sleep(20)
        model.switchTo(next)
        runCurrent()

        val asked = model.opening!!
        assertTrue("the press reached the player 20ms after it was made: ${asked.render()}", asked.pressToOpenMs >= 20)
        assertTrue("letting the previous engine go is its own figure: ${asked.render()}", asked.releaseMs >= 40)
        assertTrue("and so is building the next one: ${asked.render()}", asked.setupMs >= 30)
        assertTrue(
            "the engine is asked to open only after all three: ${asked.render()}",
            asked.prepareMs >= asked.pressToOpenMs + asked.releaseMs + asked.setupMs,
        )
        assertNull("there is no frame yet, and none is claimed", asked.firstFrameMs)

        engines.last.renderFirstFrame()
        runCurrent()

        val shown = model.opening!!
        assertEquals(EngineId.PRIMARY, shown.engine)
        assertFalse("nothing fell over", shown.fallback)
        assertTrue("the frame comes after the prepare: ${shown.render()}", shown.firstFrameMs!! >= shown.prepareMs)
        assertEquals(shown.firstFrameMs!! - shown.prepareMs, shown.prepareToFrameMs)
    }

    /** What the engine saw of its own opening reaches the record whole. */
    @Test
    fun `the engine's account of the opening is carried whole`() = playerTest {
        val model = model()
        model.open(liveRequest())
        runCurrent()
        val marks = OpeningMarks(
            serverAnsweredMs = 120,
            firstBytesMs = 160,
            tracksKnownMs = 420,
            decoderReadyMs = 470,
            decoderInitMs = 45,
            firstFrameMs = 610,
        )
        engines.last.marks = marks
        engines.last.renderFirstFrame()
        runCurrent()

        val opening = model.opening!!
        assertEquals(marks, opening.marks)
        val line = opening.render()
        assertTrue(line, line.contains("server 120ms"))
        assertTrue(line, line.contains("decoder 470ms (built in 45ms)"))
        assertTrue(line, line.contains("frame 610ms"))
    }

    /**
     * A fallback is one wait, timed from the one press, and it says it was a fallback.
     *
     * Timed again from the moment the primary gave up, the backup's figures would describe a
     * fast open and hide the three seconds the viewer spent in front of the first engine.
     */
    @Test
    fun `a fallback is timed from the press and says it was one`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        Thread.sleep(30)
        engines.last.fail(PlaybackError.DECODER_INIT)
        runCurrent()
        assertEquals("the backup was built", listOf(EngineId.PRIMARY, EngineId.BACKUP), engines.built)

        engines.last.renderFirstFrame()
        runCurrent()

        val opening = model.opening!!
        assertEquals(EngineId.BACKUP, opening.engine)
        assertTrue("it says the frame came after a fallback", opening.fallback)
        assertTrue("and the wait for the primary is inside it: ${opening.render()}", opening.prepareMs >= 30)
        assertTrue(opening.render(), opening.render().contains("fallback=yes"))
    }

    /**
     * An opening that ends on a card is recorded too, with how far it got.
     *
     * A channel that never opens is the one the figures matter most for, and the first-frame
     * line can never describe it: whether the server ever answered is the difference between a
     * channel the network lost and one the decoder refused.
     */
    @Test
    fun `an opening that ends on a card is recorded with how far it got`() = playerTest {
        val model = model()
        model.open(liveRequest())
        runCurrent()
        engines.last.marks = OpeningMarks(serverAnsweredMs = 90)
        engines.last.fail(PlaybackError.UNKNOWN)
        runCurrent()

        assertTrue(model.state.value?.picture is Picture.Failed)
        val opening = model.opening!!
        assertNull("there was no frame", opening.firstFrameMs)
        assertEquals("but the server had answered, and the record says so", 90L, opening.marks.serverAnsweredMs)
    }
    /**
     * The time of a press is not part of what a request is.
     *
     * Pressing the channel that is already playing builds a second request, a moment later. It
     * has always been nothing to do, and a press time that took part in equality would turn
     * it into a full reopen of the channel being watched.
     */
    @Test
    fun `pressing the channel that is already playing is still nothing to do`() = playerTest {
        val model = model()
        model.open(liveRequest())
        runCurrent()
        Thread.sleep(2)
        val again = liveRequest()
        assertTrue("made by a later press", again.pressedAtNanos > model.state.value!!.request.pressedAtNanos)

        model.open(again)
        runCurrent()

        assertEquals("one engine, and the channel was not opened again", 1, engines.built.size)
        assertEquals(1, engines.last.opened.size)
    }

    /**
     * A request opened again without a press is timed from the open.
     *
     * The compact player comes back with the request it had when the viewer returns to the
     * board. Timed from the press that made that request, the opening would claim the minutes
     * the viewer spent elsewhere.
     */
    @Test
    fun `a request opened again without a press is timed from the open`() = playerTest {
        val model = model()
        val request = liveRequest()
        model.open(request)
        runCurrent()
        model.leave()
        Thread.sleep(30)

        model.open(request)
        runCurrent()

        assertEquals("no press stood in front of this open", 0L, model.opening!!.pressToOpenMs)
    }

    /** The line written for every opening carries figures, and nothing of the address. */
    @Test
    fun `the opening's line carries no address and no account`() = playerTest {
        val model = model()
        model.open(liveRequest(url = "http://provider.tv/live/subscriber/secret/1.ts?token=abc"))
        engines.last.renderFirstFrame()
        runCurrent()

        val line = model.opening!!.render()
        for (part in listOf("http", "provider.tv", "subscriber", "secret", "token")) {
            assertFalse("'$part' in: $line", line.contains(part))
        }
    }

    /* ---------------------------------------------------- keeping the engine between channels */

    private fun secondChannel() = liveRequest(url = "http://provider.tv/live/2.ts", title = "Second")

    /**
     * A channel change keeps an engine that offers to be kept.
     *
     * Nothing is released and nothing is built: the engine that played the last channel is
     * handed the next one, the overlay stays up until that channel's own frame, and the figures
     * say the engine was kept.
     */
    @Test
    fun `a channel change keeps an engine that offers to be kept`() = playerTest {
        engines.primaryReusable = true
        val model = model()
        model.open(liveRequest())
        engines.last.renderFirstFrame()
        runCurrent()
        val first = engines.last

        val next = secondChannel()
        model.switchTo(next)
        runCurrent()

        assertEquals("no second engine was built", listOf(EngineId.PRIMARY), engines.built)
        assertFalse("and the first was not released", first.released)
        assertEquals("it was handed the next channel", next.url, first.opened.last().url)
        assertTrue(
            "the last channel's frame is not the next one's",
            model.state.value?.picture is Picture.Opening,
        )
        assertEquals("Second", model.state.value?.request?.title)
        assertTrue("the figures say the engine was kept", model.opening!!.reused)
        assertEquals("and that nothing was built for it", 0L, model.opening!!.setupMs)

        first.renderFirstFrame()
        runCurrent()

        assertTrue(model.state.value?.picture is Picture.Playing)
        assertEquals("the next channel's guide, after its own frame", 2, guide.calls)
    }

    /** A kept engine keeps its surface: handing it the same one again is a detach and an attach. */
    @Test
    fun `a kept engine keeps the surface it has`() = playerTest {
        engines.primaryReusable = true
        val model = model()
        val surface = VideoOutput.Platform("surface")
        model.setOutput(surface)
        model.open(liveRequest())
        engines.last.renderFirstFrame()
        runCurrent()

        model.switchTo(secondChannel())
        runCurrent()

        assertEquals(
            "attached once, and never detached or attached again",
            listOf<VideoOutput?>(surface),
            engines.last.outputs,
        )
    }

    /**
     * A kept engine gives every channel its own budget.
     *
     * The last channel's deadline goes with the last channel. One that survived would hand a
     * channel the viewer had only just pressed to the backup, on the strength of how long the
     * channel before it took.
     */
    @Test
    fun `a kept engine gives every channel its own deadline`() = playerTest {
        engines.primaryReusable = true
        val model = model()
        model.open(liveRequest())
        runCurrent()
        advanceTimeBy(2_000)

        model.switchTo(secondChannel())
        runCurrent()
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals("the first channel's deadline did not fire on the second", listOf(EngineId.PRIMARY), engines.built)

        advanceTimeBy(FallbackPolicy.OPEN_DEADLINE_MS)
        runCurrent()
        assertEquals("the second channel's own did", listOf(EngineId.PRIMARY, EngineId.BACKUP), engines.built)
    }

    /**
     * Pressing through channels faster than they open leaves only the last one's attempt.
     *
     * Every channel passed on the way is handed to the engine and taken back again, and none
     * of them may come back later as a deadline, a fallback or a guide.
     */
    @Test
    fun `rapid switching on a kept engine leaves only the last channel's attempt`() = playerTest {
        engines.primaryReusable = true
        val model = model()
        model.open(liveRequest())
        runCurrent()
        model.switchTo(secondChannel())
        runCurrent()
        model.switchTo(liveRequest(url = "http://provider.tv/live/3.ts", title = "Third"))
        runCurrent()

        val engine = engines.last
        assertEquals("one engine", 1, engines.built.size)
        assertEquals("handed each channel in turn", 3, engine.opened.size)
        assertEquals("Third", model.state.value?.request?.title)

        engine.renderFirstFrame()
        runCurrent()
        advanceTimeBy(FallbackPolicy.OPEN_DEADLINE_MS * 2)
        runCurrent()

        assertTrue(model.state.value?.picture is Picture.Playing)
        assertEquals("no channel passed on the way fell anything over", 1, engines.built.size)
        assertEquals("and only the last one's guide was asked for", 1, guide.calls)
    }

    /** A film is never opened on the engine a channel was playing on. */
    @Test
    fun `an engine is not kept from a channel for a film`() = playerTest {
        engines.primaryReusable = true
        val model = model()
        model.open(liveRequest())
        engines.last.renderFirstFrame()
        runCurrent()
        val first = engines.last

        model.open(vodRequest())
        runCurrent()

        assertTrue("the channel's engine was released", first.released)
        assertEquals("and the film's was built", 2, engines.built.size)
        assertFalse("whose figures claim no kept engine", model.opening!!.reused)
    }

    /** An engine that has failed is not kept: the next channel would inherit the failure. */
    @Test
    fun `an engine that failed is not kept for the next channel`() = playerTest {
        engines.primaryReusable = true
        val model = model()
        model.open(liveRequest())
        runCurrent()
        engines.last.fail(PlaybackError.UNKNOWN)
        runCurrent()
        val failed = engines.last

        model.switchTo(secondChannel())
        runCurrent()

        assertTrue("the engine that failed was released", failed.released)
        assertEquals(2, engines.built.size)
    }

    /** A channel remembered for the backup is opened on the backup, whatever was playing before. */
    @Test
    fun `an engine is not kept for a channel that belongs to the other engine`() = playerTest {
        engines.primaryReusable = true
        val next = secondChannel()
        memory.remember(FallbackPolicy.sourceKey(next.url), EngineId.BACKUP)
        val model = model()
        model.open(liveRequest())
        engines.last.renderFirstFrame()
        runCurrent()
        val first = engines.last

        model.switchTo(next)
        runCurrent()

        assertTrue("the primary was released", first.released)
        assertEquals(listOf(EngineId.PRIMARY, EngineId.BACKUP), engines.built)
    }

    /**
     * The fallback is the same on a kept engine: the next channel's failure hands it to the
     * backup, which is built only once the kept engine has been let go.
     */
    @Test
    fun `a channel on a kept engine still falls over to the backup`() = playerTest {
        engines.primaryReusable = true
        val model = model()
        model.open(liveRequest())
        engines.last.renderFirstFrame()
        runCurrent()
        val kept = engines.last
        model.switchTo(secondChannel())
        runCurrent()

        kept.fail(PlaybackError.DECODER_INIT)
        runCurrent()

        assertTrue("the kept engine was released", kept.released)
        assertEquals(listOf(EngineId.PRIMARY, EngineId.BACKUP), engines.built)
        assertEquals("and before the backup was built", 1, engines.mostAlive)

        engines.last.renderFirstFrame()
        runCurrent()
        assertEquals(EngineId.BACKUP, model.state.value?.engine)
        assertTrue(model.opening!!.fallback)
        assertFalse(model.opening!!.reused)
    }

    /**
     * Whatever is kept and whatever is not, two engines are never alive at once.
     *
     * An engine that does not offer to be kept -- LibVLC -- is released before the next is
     * built, which is the whole defence against two pictures, two soundtracks and two decoders
     * on a box that has one of each.
     */
    @Test
    fun `two engines are never alive at once`() = playerTest {
        val model = model()
        model.open(liveRequest())
        engines.last.renderFirstFrame()
        runCurrent()
        model.switchTo(secondChannel())
        runCurrent()
        engines.last.fail(PlaybackError.DECODER_INIT)
        runCurrent()
        model.open(vodRequest())
        runCurrent()

        assertEquals(4, engines.built.size)
        assertEquals("the most ever alive together", 1, engines.mostAlive)
    }

    /** Leaving lets a kept engine go like any other: the sound must not outlive the player. */
    @Test
    fun `leaving the player releases a kept engine`() = playerTest {
        engines.primaryReusable = true
        val model = model()
        model.open(liveRequest())
        runCurrent()
        model.switchTo(secondChannel())
        runCurrent()

        model.leave()

        assertTrue(engines.last.released)
    }

    /* ------------------------------------------------- the opening's second of silence */

    private val second = PlayerViewModel.SPINNER_DELAY_MS

    private fun PlayerViewModel.now(): PlayerState = state.value!!

    /**
     * A new channel takes the last one's picture away at the press, and says nothing yet.
     *
     * Read without letting anything run: the picture is an opening -- which is what lays the
     * player's ground over the last channel's frozen frame -- in the same call that took the
     * press, and the opening is a new one, numbered, with no spinner on it.
     */
    @Test
    fun `a new channel hides the last one at once and shows no spinner yet`() = playerTest {
        engines.primaryReusable = true
        val model = model()
        model.open(liveRequest())
        engines.last.renderFirstFrame()
        runCurrent()
        val before = model.now()
        assertTrue(before.picture is Picture.Playing)

        model.switchTo(secondChannel())

        val after = model.now()
        assertTrue("the last channel's picture is gone the moment the press arrives", after.picture is Picture.Opening)
        assertFalse("and nothing says so yet", after.longWait)
        assertEquals("it is a new opening", before.attempt + 1, after.attempt)
    }

    @Test
    fun `there is no spinner in the first second`() = playerTest {
        val model = model()
        model.open(liveRequest())
        runCurrent()

        advanceTimeBy(second - 100)
        runCurrent()

        assertTrue(model.now().picture is Picture.Opening)
        assertFalse("a second has not passed", model.now().longWait)
    }

    /** The common case now that the engine is kept: a title, then the picture, nothing turning between. */
    @Test
    fun `a picture inside the second never shows a spinner`() = playerTest {
        val model = model()
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch { model.state.collect { it?.let { s -> seen += s.longWait } } }
        model.open(liveRequest())
        runCurrent()

        advanceTimeBy(second - 400)
        engines.last.renderFirstFrame()
        runCurrent()
        advanceTimeBy(second * 3)
        runCurrent()

        assertTrue(model.now().picture is Picture.Playing)
        assertFalse("a spinner was shown: $seen", seen.any { it })
    }

    /**
     * A second without a frame says so, and goes on saying so until the frame.
     *
     * Not until the second is over: the spinner's end is the picture, or a card, and never a
     * timer. Nothing the engine reports before its first frame -- the connection, the
     * preparation -- is a picture either; the engine stays opening until it has one.
     */
    @Test
    fun `the spinner comes after a second and stays until the first frame`() = playerTest {
        val model = model()
        model.open(liveRequest())
        runCurrent()

        advanceTimeBy(second + 1)
        runCurrent()
        assertTrue(model.now().picture is Picture.Opening)
        assertTrue("a second without a frame says so", model.now().longWait)

        advanceTimeBy(second)
        runCurrent()
        assertTrue("and goes on saying so: the second is not what ends it", model.now().longWait)

        engines.last.renderFirstFrame()
        runCurrent()
        assertTrue(model.now().picture is Picture.Playing)
        assertFalse("the first frame ends it", model.now().longWait)
    }

    /** A timer started for the last channel cannot put a spinner on the next one early. */
    @Test
    fun `a timer left over from the last channel does nothing to the next`() = playerTest {
        engines.primaryReusable = true
        val model = model()
        model.open(liveRequest())
        runCurrent()
        advanceTimeBy(second * 7 / 10)

        model.switchTo(secondChannel())
        runCurrent()
        // Past the point where the first channel's timer would have fired, short of the second's.
        advanceTimeBy(second * 5 / 10)
        runCurrent()
        assertFalse("the first channel's wait is not the second channel's", model.now().longWait)

        advanceTimeBy(second * 6 / 10)
        runCurrent()
        assertTrue("the second channel's own wait", model.now().longWait)
    }

    /**
     * Pressing through channels leaves one wait: the last channel's, which only its own frame
     * ends -- on the one engine the whole time.
     */
    @Test
    fun `rapid switching leaves only the last channel's wait`() = playerTest {
        engines.primaryReusable = true
        val model = model()
        model.open(liveRequest())
        runCurrent()
        advanceTimeBy(400)
        model.switchTo(secondChannel())
        runCurrent()
        advanceTimeBy(400)
        model.switchTo(liveRequest(url = "http://provider.tv/live/3.ts", title = "Third"))
        runCurrent()
        val third = model.now().attempt

        advanceTimeBy(second - 100)
        runCurrent()
        assertFalse(model.now().longWait)
        advanceTimeBy(200)
        runCurrent()
        assertTrue(model.now().longWait)
        assertEquals("the spinner is the third channel's", third, model.now().attempt)

        engines.last.renderFirstFrame()
        runCurrent()
        assertFalse(model.now().longWait)
        assertEquals("and the engine was kept throughout", listOf(EngineId.PRIMARY), engines.built)
    }

    /**
     * The fallback carries the wait on: no picture of either engine's until the backup's own
     * first frame, and the spinner, already up, stays up until then.
     */
    @Test
    fun `the fallback keeps the wait until the backup's first frame`() = playerTest {
        val model = model()
        model.open(liveRequest())
        runCurrent()
        advanceTimeBy(second + 1)
        runCurrent()
        val waiting = model.now()
        assertTrue(waiting.longWait)

        engines.last.fail(PlaybackError.DECODER_INIT)
        runCurrent()

        val switching = model.now()
        assertEquals(listOf(EngineId.PRIMARY, EngineId.BACKUP), engines.built)
        assertTrue("still no picture of any engine's", switching.picture is Picture.Opening)
        assertTrue("and still saying so", switching.longWait)
        assertEquals("the same opening", waiting.attempt, switching.attempt)

        engines.last.renderFirstFrame()
        runCurrent()
        assertFalse(model.now().longWait)
        assertEquals(EngineId.BACKUP, model.now().engine)
    }

    /** A fallback inside the second does not start the second again: it is the press's. */
    @Test
    fun `a fallback inside the second keeps the press's second`() = playerTest {
        val model = model()
        model.open(liveRequest())
        runCurrent()
        advanceTimeBy(300)
        engines.last.fail(PlaybackError.DECODER_INIT)
        runCurrent()

        advanceTimeBy(second - 400)
        runCurrent()
        assertFalse(model.now().longWait)
        advanceTimeBy(200)
        runCurrent()
        assertTrue("timed from the press, not from the switch", model.now().longWait)

        engines.last.renderFirstFrame()
        runCurrent()
        assertFalse(model.now().longWait)
    }

    /** A picture that fails after it appeared is a new wait on the backup, with its own second. */
    @Test
    fun `a fallback after the picture is a new wait`() = playerTest {
        val model = model()
        model.open(liveRequest())
        engines.last.renderFirstFrame()
        runCurrent()
        val playing = model.now()

        engines.last.fail(PlaybackError.DECODER_INIT)
        runCurrent()

        assertTrue(model.now().picture is Picture.Opening)
        assertEquals(playing.attempt + 1, model.now().attempt)
        advanceTimeBy(second - 100)
        runCurrent()
        assertFalse(model.now().longWait)
        advanceTimeBy(200)
        runCurrent()
        assertTrue(model.now().longWait)
    }

    /** A card ends the wait. The spinner never turns on behind it, and never turns for ever. */
    @Test
    fun `a failure ends the wait with the card`() = playerTest {
        val model = model()
        model.open(liveRequest())
        runCurrent()
        advanceTimeBy(second + 1)
        runCurrent()

        engines.last.fail(PlaybackError.UNKNOWN)
        runCurrent()
        assertTrue(model.now().picture is Picture.Failed)
        assertFalse(model.now().longWait)

        advanceTimeBy(second * 5)
        runCurrent()
        assertTrue(model.now().picture is Picture.Failed)
        assertFalse(model.now().longWait)
        assertEquals("the card the engine gave", PlaybackError.UNKNOWN, (model.now().picture as Picture.Failed).reason)
        assertEquals("and nothing switched behind it", listOf(EngineId.PRIMARY), engines.built)
    }

    @Test
    fun `a failure inside the second shows the card and never a spinner`() = playerTest {
        val model = model()
        model.open(liveRequest())
        runCurrent()
        advanceTimeBy(300)

        engines.last.fail(PlaybackError.UNKNOWN)
        runCurrent()
        advanceTimeBy(second * 2)
        runCurrent()

        assertTrue(model.now().picture is Picture.Failed)
        assertFalse(model.now().longWait)
    }

    /** Both engines silent: the spinner turns through the switch and ends on the timeout card. */
    @Test
    fun `both engines timing out ends on the card`() = playerTest {
        val model = model()
        model.open(liveRequest())
        runCurrent()

        advanceTimeBy(FallbackPolicy.OPEN_DEADLINE_MS + 1)
        runCurrent()
        assertEquals(listOf(EngineId.PRIMARY, EngineId.BACKUP), engines.built)
        assertTrue("through the switch", model.now().longWait)

        advanceTimeBy(FallbackPolicy.OPEN_DEADLINE_MS + 1)
        runCurrent()
        assertEquals(PlaybackError.TIMEOUT, (model.now().picture as Picture.Failed).reason)
        assertFalse(model.now().longWait)
    }

    /** Retry is a press, so it is a new opening with its own second. */
    @Test
    fun `a retry is a new opening with its own second`() = playerTest {
        val model = model()
        model.open(liveRequest())
        runCurrent()
        engines.last.fail(PlaybackError.UNKNOWN)
        runCurrent()
        val failed = model.now().attempt

        model.retry()
        runCurrent()

        assertTrue(model.now().picture is Picture.Opening)
        assertEquals(failed + 1, model.now().attempt)
        assertFalse(model.now().longWait)
        advanceTimeBy(second + 1)
        runCurrent()
        assertTrue(model.now().longWait)
    }

    /** Coming back from the background opens the channel again, behind a second of its own. */
    @Test
    fun `a return from the background is a new opening with its own second`() = playerTest {
        val model = model()
        model.open(liveRequest())
        engines.last.renderFirstFrame()
        runCurrent()
        model.pauseForBackground()
        runCurrent()
        assertFalse(model.now().longWait)
        val away = model.now().attempt

        model.returnFromBackground()
        runCurrent()

        assertEquals(away + 1, model.now().attempt)
        assertTrue(model.now().picture is Picture.Opening)
        advanceTimeBy(second - 100)
        runCurrent()
        assertFalse(model.now().longWait)
        advanceTimeBy(200)
        runCurrent()
        assertTrue(model.now().longWait)
    }

    /* ------------------------------------------------- a backup that starts before it draws */

    /**
     * A channel the primary could not open, on a backup that is running at once and draws late.
     *
     * LibVLC has the input open inside a second, then fills its cache and waits for a keyframe.
     * Timed to its picture at three seconds, the backup put the timeout card in front of a
     * channel that was about to play; timed to its start, as it always was, it does not.
     */
    @Test
    fun `a channel on a backup that started is not timed out for a slow picture`() = playerTest {
        val model = model()
        model.open(liveRequest())
        runCurrent()
        advanceTimeBy(FallbackPolicy.OPEN_DEADLINE_MS + 1)
        runCurrent()
        assertEquals("the primary still has its three seconds", listOf(EngineId.PRIMARY, EngineId.BACKUP), engines.built)
        val backup = engines.last

        advanceTimeBy(600)
        backup.begin()
        runCurrent()
        advanceTimeBy(FallbackPolicy.OPEN_DEADLINE_MS)
        runCurrent()
        assertTrue("no card over a backup that is running: ${model.now().picture}", model.now().picture is Picture.Opening)
        assertTrue("the spinner says it is still coming", model.now().longWait)

        advanceTimeBy(1_500)
        backup.renderFirstFrame()
        runCurrent()
        assertTrue(model.now().picture is Picture.Playing)
        assertEquals(EngineId.BACKUP, model.now().engine)
        assertFalse(model.now().longWait)
        assertEquals("and nothing else was tried", listOf(EngineId.PRIMARY, EngineId.BACKUP), engines.built)
    }

    /** The same for a film the primary could not decode: a second of cache is not a timeout. */
    @Test
    fun `a film on a backup that started is not timed out for a slow picture`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.fail(PlaybackError.DECODER_INIT)
        runCurrent()
        val backup = engines.last
        assertEquals(listOf(EngineId.PRIMARY, EngineId.BACKUP), engines.built)
        assertEquals(MediaKind.VOD, backup.opened.single().kind)

        advanceTimeBy(800)
        backup.begin()
        runCurrent()
        advanceTimeBy(FallbackPolicy.OPEN_DEADLINE_MS)
        runCurrent()
        assertTrue("no card over a film that is running: ${model.now().picture}", model.now().picture is Picture.Opening)

        advanceTimeBy(2_500)
        backup.renderFirstFrame()
        runCurrent()
        assertTrue(model.now().picture is Picture.Playing)
        assertEquals(EngineId.BACKUP, model.now().engine)
        assertFalse(model.now().longWait)
    }

    /**
     * And for an episode the backup is remembered for, which opens on it directly -- the path
     * where the backup's first timeout was always the card, with nothing after it to try.
     */
    @Test
    fun `an episode opened on the backup is not timed out for a slow picture`() = playerTest {
        val episode = episodeRequest()
        memory.remember(FallbackPolicy.sourceKey(episode.url), EngineId.BACKUP)
        val model = model()
        model.open(episode)
        runCurrent()
        assertEquals(listOf(EngineId.BACKUP), engines.built)
        val backup = engines.last
        assertEquals(MediaKind.SERIES_EPISODE, backup.opened.single().kind)

        advanceTimeBy(1_200)
        backup.begin()
        runCurrent()
        advanceTimeBy(6_800)
        runCurrent()
        assertTrue("no card at eight seconds over a running episode: ${model.now().picture}", model.now().picture is Picture.Opening)
        assertTrue(model.now().longWait)

        backup.renderFirstFrame()
        runCurrent()
        assertTrue(model.now().picture is Picture.Playing)
        assertEquals(EngineId.BACKUP, model.now().engine)
        assertFalse(model.now().longWait)
        assertEquals(listOf(EngineId.BACKUP), engines.built)
    }

    /** A backup that never starts is the silence the three seconds are for, as it always was. */
    @Test
    fun `a backup that never starts still has its card at three seconds`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.fail(PlaybackError.DECODER_INIT)
        runCurrent()

        advanceTimeBy(FallbackPolicy.OPEN_DEADLINE_MS - 1)
        runCurrent()
        assertTrue(model.now().picture is Picture.Opening)

        advanceTimeBy(2)
        runCurrent()
        val picture = model.now().picture
        assertTrue("a card: $picture", picture is Picture.Failed)
        assertEquals(PlaybackError.TIMEOUT, (picture as Picture.Failed).reason)
        assertEquals(FallbackPolicy.OPEN_DEADLINE_MS, model.now().diagnosis?.timedOutAfterMs)
        assertFalse(model.now().longWait)
    }

    /**
     * A backup that started and never draws still ends, on the timeout card and at the bound
     * for a picture: never at three seconds, never for ever. A picture after the card is still
     * this opening's, as it is after any timeout.
     */
    @Test
    fun `a backup that started and never draws ends on the timeout card at the picture bound`() = playerTest {
        val model = model()
        model.open(liveRequest())
        runCurrent()
        engines.last.fail(PlaybackError.DECODER_INIT)
        runCurrent()
        val backup = engines.last
        backup.begin()
        runCurrent()

        advanceTimeBy(FallbackPolicy.PICTURE_DEADLINE_MS - 1)
        runCurrent()
        assertTrue("still waiting inside the bound: ${model.now().picture}", model.now().picture is Picture.Opening)
        assertTrue(model.now().longWait)

        advanceTimeBy(2)
        runCurrent()
        val picture = model.now().picture
        assertTrue("a card: $picture", picture is Picture.Failed)
        picture as Picture.Failed
        assertEquals(PlaybackError.TIMEOUT, picture.reason)
        assertFalse("the fallback is spent", picture.canTryBackup)
        assertFalse("and the spinner went with the wait", model.now().longWait)
        assertEquals("the report says how long", FallbackPolicy.PICTURE_DEADLINE_MS, model.now().diagnosis?.timedOutAfterMs)
        assertEquals(listOf(EngineId.PRIMARY, EngineId.BACKUP), engines.built)

        backup.renderFirstFrame()
        runCurrent()
        assertTrue(model.now().picture is Picture.Playing)
    }

    /**
     * A card the viewer is being asked about is not answered by the budget.
     *
     * An unidentified failure is the one the machine leaves to a person, with the backup on the
     * card. The deadline went on running behind it, and at three seconds took the card away and
     * switched engines anyway.
     */
    @Test
    fun `an unidentified failure is left to the viewer when the budget runs out`() = playerTest {
        val model = model()
        model.open(liveRequest())
        runCurrent()
        engines.last.fail(PlaybackError.UNKNOWN)
        runCurrent()

        advanceTimeBy(FallbackPolicy.PICTURE_DEADLINE_MS + 1)
        runCurrent()

        assertEquals("nothing switched on its own", listOf(EngineId.PRIMARY), engines.built)
        val picture = model.now().picture
        assertTrue("the card is still up: $picture", picture is Picture.Failed)
        picture as Picture.Failed
        assertEquals(PlaybackError.UNKNOWN, picture.reason)
        assertTrue("still offering the backup", picture.canTryBackup)
        assertFalse(model.now().switching)
    }

    /** A backup that failed says why, and the budget running out behind its card does not rename it. */
    @Test
    fun `a backup's own failure keeps its reason when the budget runs out`() = playerTest {
        val model = model()
        model.open(vodRequest())
        runCurrent()
        engines.last.fail(PlaybackError.DECODER_INIT)
        runCurrent()
        val backup = engines.last
        backup.begin()
        advanceTimeBy(1_000)
        backup.fail(PlaybackError.DECODING)
        runCurrent()

        advanceTimeBy(FallbackPolicy.PICTURE_DEADLINE_MS)
        runCurrent()

        val picture = model.now().picture
        assertTrue("a card: $picture", picture is Picture.Failed)
        picture as Picture.Failed
        assertEquals("the reason the backup gave, not TIMEOUT", PlaybackError.DECODING, picture.reason)
        assertFalse(picture.canTryBackup)
        assertNull("and no timeout in the report", model.now().diagnosis?.timedOutAfterMs)
    }

    /**
     * The backup's longer wait belongs to its channel. Pressing on while it waits for a picture
     * lets it go, with nothing left behind to time out the next channel, and one engine alive.
     */
    @Test
    fun `a slow backup's wait goes with its channel`() = playerTest {
        val model = model()
        model.open(liveRequest())
        runCurrent()
        engines.last.fail(PlaybackError.DECODER_INIT)
        runCurrent()
        val backup = engines.last
        backup.begin()
        advanceTimeBy(FallbackPolicy.OPEN_DEADLINE_MS + 1)
        runCurrent()

        model.switchTo(secondChannel())
        runCurrent()
        assertTrue("the backup was let go", backup.released)
        engines.last.renderFirstFrame()
        runCurrent()

        advanceTimeBy(FallbackPolicy.PICTURE_DEADLINE_MS)
        runCurrent()
        assertTrue(model.now().picture is Picture.Playing)
        assertEquals("Second", model.now().request.title)
        assertEquals(listOf(EngineId.PRIMARY, EngineId.BACKUP, EngineId.PRIMARY), engines.built)
        assertEquals("one engine alive at a time", 1, engines.mostAlive)
    }

    /* ------------------------------------------------------------------------ fakes */

    private fun liveRequest(
        url: String = "http://provider.tv/live/1.ts?token=abc",
        title: String = "Al Aoula",
    ) = PlayerRequest(
        url = url,
        title = title,
        kind = MediaKind.LIVE,
        channelNumber = "104",
        epgChannelId = "aloula.ma",
    )

    private fun vodRequest() = PlayerRequest(
        url = "http://provider.tv/movie/9.mkv",
        title = "The Road to Chefchaouen",
        kind = MediaKind.VOD,
    )

    private fun episodeRequest() = PlayerRequest(
        url = "http://provider.tv/series/12.mkv",
        title = "Dar Lkhir",
        subtitle = "S01 E04",
        kind = MediaKind.SERIES_EPISODE,
    )

    /** Records which engines were built, so "the backup was warmed" is expressible. */
    private class FakeFactory : EngineFactory {
        val built = mutableListOf<EngineId>()
        lateinit var last: FakeEngine

        /** How long building takes, in real time, so the setup figure has something to measure. */
        var buildTakesMs = 0L

        /** Whether the primaries it builds offer to be kept, as Media3 does. A backup never does. */
        var primaryReusable = false

        /** Every engine built, so "two alive at once" is expressible. */
        private val all = mutableListOf<FakeEngine>()

        /** The most engines that were ever alive together: built, and not yet released. */
        var mostAlive = 0
            private set

        override fun create(id: EngineId, kind: MediaKind): PlaybackEngine {
            built += id
            if (buildTakesMs > 0) Thread.sleep(buildTakesMs)
            return FakeEngine().also {
                it.reusable = primaryReusable && id == EngineId.PRIMARY
                // The backup is LibVLC, which is running before it draws: see [FakeEngine.begin].
                it.startsBeforePicture = id == EngineId.BACKUP
                last = it
                all += it
                mostAlive = maxOf(mostAlive, all.count { engine -> !engine.released })
            }
        }
    }

    private class FakeEngine : PlaybackEngine {
        private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        override val state: StateFlow<PlaybackState> = _state.asStateFlow()

        private val _tracks = MutableStateFlow(TrackSet())
        override val tracks: StateFlow<TrackSet> = _tracks.asStateFlow()

        private val _firstFrame = MutableStateFlow<Long?>(null)
        override val firstFrameAtMs: StateFlow<Long?> = _firstFrame.asStateFlow()

        /**
         * Whether the start is its own moment, as LibVLC's `Playing` comes before its picture.
         * Off, the start is the contract's default -- the first frame -- which is what Media3
         * inherits.
         */
        var startsBeforePicture = false

        private val _started = MutableStateFlow<Long?>(null)
        override val startedAtMs: StateFlow<Long?>
            get() = if (startsBeforePicture) _started.asStateFlow() else super.startedAtMs

        private val _videoAspectRatio = MutableStateFlow<Float?>(null)
        override val videoAspectRatio: StateFlow<Float?> = _videoAspectRatio.asStateFlow()

        private val _cues = MutableStateFlow<List<String>>(emptyList())
        override val cues: StateFlow<List<String>> = _cues.asStateFlow()

        private val _diagnosis = MutableStateFlow<PlaybackDiagnosis?>(null)
        override val diagnosis: StateFlow<PlaybackDiagnosis?> = _diagnosis.asStateFlow()

        val opened = mutableListOf<MediaRequest>()
        var samples = 0
        var released = false

        /** Every position asked for, in order. The jump controls are a claim about these. */
        val seeks = mutableListOf<Long>()

        /**
         * Where the engine says it is, which a test moves by hand.
         *
         * Deliberately *not* moved by [seekTo]. A real seek is asynchronous — the backup
         * engine goes on reporting its old time until the seek lands — and that lag is the
         * thing the jump controls have to survive, so the fake reproduces it rather than
         * hiding it.
         */
        override var positionMs: Long = 0
        override val bufferedPositionMs: Long get() = positionMs
        override var durationMs: Long? = null
        override var isSeekable: Boolean = true

        /**
         * What the engine is actually doing, which is not what it last announced.
         *
         * Kept separate from [_state] on purpose: the defect the play control had was a
         * screen deciding from a stale announcement, so a fake whose two answers cannot
         * disagree would be a fake that cannot reproduce it.
         */
        override val isPlayRequested: Boolean get() = playing

        /** Every surface handed over, in order, and every detach as a null. */
        val outputs = mutableListOf<VideoOutput?>()

        override fun setVideoOutput(output: VideoOutput?) {
            outputs += output
        }

        override var reusable = false

        override fun open(media: MediaRequest) {
            opened += media
            // What an engine that can be kept promises, and what a fresh one is anyway: each
            // source starts with no frame, no captions, no shape, no tracks and no report.
            _started.value = null
            _firstFrame.value = null
            _cues.value = emptyList()
            _videoAspectRatio.value = null
            _tracks.value = TrackSet()
            _diagnosis.value = null
            _state.value = PlaybackState.Opening
        }

        /** Whether the engine has been told to run, so "start again" is assertable. */
        var playing = false
        var paused = false

        override fun play() {
            playing = true
            paused = false
        }

        override fun pause() {
            paused = true
            playing = false
        }

        override fun stop() = Unit
        override fun seekTo(positionMs: Long) {
            seeks += positionMs
        }
        override fun selectTrack(track: Track) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun setAspect(mode: com.castivio.playback.api.AspectMode) = Unit

        override fun sample(): PlaybackSample {
            samples++
            return PlaybackSample()
        }

        /** What the engine says about its own opening, which a test sets by hand. */
        var marks = OpeningMarks()

        override fun openingMarks(): OpeningMarks = marks

        /** How long letting go takes, in real time, so the release figure has something to measure. */
        var releaseTakesMs = 0L

        override fun release() {
            if (releaseTakesMs > 0) Thread.sleep(releaseTakesMs)
            released = true
        }

        /**
         * The input is running and nothing is drawn yet: LibVLC's `Playing`, ahead of its video
         * output. Still opening, as LibVLC is -- it announces nothing more until the picture.
         */
        fun begin() {
            _started.value = 1_000
        }

        fun renderFirstFrame() {
            if (_started.value == null) _started.value = 1_000
            _firstFrame.value = 1_000
            playing = true
            _state.value = PlaybackState.Playing(positionMs = 0, durationMs = null, bitrateBps = 0)
        }

        /** What the decoder hands over when a text track has words for this moment. */
        fun say(vararg lines: String) {
            _cues.value = lines.toList()
        }

        /** What a real engine reports once the decoder knows the shape of the picture. */
        fun declareShape(ratio: Float?) {
            _videoAspectRatio.value = ratio
        }

        /** The film reaches its end, which is where the play control had nothing to do. */
        fun finish() {
            _state.value = PlaybackState.Ended
        }

        /** The user paused, as the engine would report it. */
        fun pauseFrom() {
            _state.value = PlaybackState.Paused(positionMs = positionMs)
        }

        /** The buffer ran low and the engine says so; it has not been told to stop. */
        fun stall() {
            _state.value = PlaybackState.Buffering(bufferedMs = 0, bitrateBps = 0)
        }

        fun fail(reason: PlaybackError) {
            _state.value = PlaybackState.Failed(reason)
        }

        /** What a real engine would have gathered from the exception before failing. */
        fun diagnose(reason: PlaybackError, codecName: String) {
            _diagnosis.value = PlaybackDiagnosis(
                engine = EngineId.PRIMARY,
                reason = reason,
                decoder = DecoderReport(codecName = codecName),
            )
        }
    }

    /**
     * The caption settings, in memory.
     *
     * A real store is `SharedPreferences` and this is not testing Android's ability to
     * write a string. What these tests are about is that the choice reaches the state, is
     * written once when it changes, and comes back on the next film.
     */
    private class RememberedStyle : SubtitleStyleStore {
        var held = SubtitleStyle()
        var writes = 0

        override fun read(): SubtitleStyle = held

        override fun write(style: SubtitleStyle) {
            held = style
            writes++
        }
    }

    /**
     * The subtitle search, answering from a literal.
     *
     * No credentials, no network and no device: the view model takes a [SubtitleSource]
     * rather than the API for exactly this reason. `offers` and `track` are what it will
     * answer with, and `searches` records what it was asked — because "the language reached
     * the request" is the half of a language picker that can silently stop being true.
     */
    private class FakeSubtitles : SubtitleSource {
        override var available = true
        var offers: SubtitleResult<List<SubtitleOffer>> = SubtitleResult.Found(emptyList())
        var track: SubtitleResult<SubtitleTrack> = SubtitleResult.Found(SubtitleTrack(emptyList()))
        val searches = mutableListOf<List<String>>()

        /**
         * Holds a download open, so a test can be inside one.
         *
         * Two of the claims here are about what happens *during* a download — a second press
         * arriving, a channel change cancelling it — and neither can be made against a fake
         * that has already returned by the time the next line runs.
         */
        var hold = false
        private var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        fun release() {
            gate?.complete(Unit)
        }

        /**
         * What was actually looked for, which is the claim the search now stands or falls on.
         *
         * Recorded separately from the languages because they fail differently: a wrong
         * language returns subtitles nobody can read, and a wrong query returns subtitles for
         * another programme — and the second one looked like it was working.
         */
        val asked = mutableListOf<SubtitleQuery>()
        var downloads = 0

        override suspend fun search(
            url: String,
            query: SubtitleQuery,
            languages: List<String>,
        ): SubtitleResult<List<SubtitleOffer>> {
            searches += languages
            asked += query
            return offers
        }

        override suspend fun download(offer: SubtitleOffer): SubtitleResult<SubtitleTrack> {
            downloads++
            if (hold) {
                kotlinx.coroutines.CompletableDeferred<Unit>().also { gate = it }.await()
            }
            return track
        }
    }

    /**
     * The day's tally, in memory, counting every touch of it.
     *
     * [recorded] is separate from [spentToday] and that is the point: most of the claims
     * below are that the number did *not* move, and a fake that only exposed the total could
     * not tell "never called" from "called and it made no difference".
     */
    private class CountingAllowance(
        override val dailyLimit: Int = 5,
        private var count: Int = 0,
    ) : SubtitleAllowance {
        var recorded = 0
        var declaredSpent = 0

        override fun spentToday(): Int = count

        override fun recordDownload() {
            recorded++
            count++
        }

        override fun markSpent() {
            declaredSpent++
            count = dailyLimit
        }

        /** Put the day's count where a test needs it, as yesterday's downloads would. */
        fun startAt(spent: Int) {
            count = spent
        }
    }

    /** Counts, because the claim is about *when* it was called and not about what it said. */
    private class RecordingGuide : ProgrammeSource {
        var calls = 0
        var answer: Programme? = Programme(
            now = "Evening news",
            window = "20:00 – 20:45",
            next = "Deep sea",
            progress = 0.4f,
        )

        override suspend fun now(channelId: String): Programme? {
            calls++
            return answer
        }
    }

    private class RecordingMemory : EngineMemory {
        private val entries = mutableMapOf<String, EngineId>()
        override fun preferred(sourceKey: String): EngineId? = entries[sourceKey]
        override fun remember(sourceKey: String, engine: EngineId) {
            if (engine == EngineId.BACKUP) entries[sourceKey] = engine else entries.remove(sourceKey)
        }
    }
}
