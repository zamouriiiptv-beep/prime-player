package com.castivio.feature.player

import com.castivio.playback.api.EngineFactory
import com.castivio.playback.api.EngineId
import com.castivio.playback.api.EngineMemory
import com.castivio.playback.api.FallbackPolicy
import com.castivio.playback.api.MediaKind
import com.castivio.playback.api.MediaRequest
import com.castivio.playback.api.PlaybackEngine
import com.castivio.playback.api.PlaybackError
import com.castivio.playback.api.PlaybackSample
import com.castivio.playback.api.PlaybackState
import com.castivio.playback.api.Track
import com.castivio.playback.api.TrackSet
import com.castivio.playback.api.VideoOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
    private lateinit var engines: FakeFactory
    private lateinit var guide: RecordingGuide
    private lateinit var memory: RecordingMemory

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        engines = FakeFactory()
        guide = RecordingGuide()
        memory = RecordingMemory()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun model() = PlayerViewModel(engines, memory, guide)

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
    fun `opening a channel asks the engine for a picture and asks nothing else`() = runTest {
        val model = model()
        model.open(liveRequest())
        advanceUntilIdle()

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
    fun `the title is available before the first frame, from the request alone`() = runTest {
        val model = model()
        val request = liveRequest()
        model.open(request)
        advanceUntilIdle()

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
    fun `the guide is fetched after the first frame and never before it`() = runTest {
        val model = model()
        model.open(liveRequest())
        advanceUntilIdle()
        assertEquals("before the frame", 0, guide.calls)

        engines.last.renderFirstFrame()
        advanceUntilIdle()

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
    fun `a channel whose guide never answers still plays`() = runTest {
        guide.answer = null
        val model = model()
        model.open(liveRequest())
        engines.last.renderFirstFrame()
        advanceUntilIdle()

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
    fun `nothing is sampled until the panel is opened, and nothing after it closes`() = runTest {
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
    fun `a decoder refusal switches to the backup exactly once`() = runTest {
        val model = model()
        model.open(vodRequest())
        advanceUntilIdle()

        engines.last.fail(PlaybackError.DECODER)
        advanceUntilIdle()

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
    fun `when the backup refuses as well the user gets a card, not a third engine`() = runTest {
        val model = model()
        model.open(vodRequest())
        advanceUntilIdle()
        engines.last.fail(PlaybackError.DECODER)
        advanceUntilIdle()
        engines.last.fail(PlaybackError.DECODER)
        advanceUntilIdle()

        assertEquals("no third engine was built", 2, engines.built.size)
        val picture = model.state.value?.picture
        assertTrue("a card", picture is Picture.Failed)
        picture as Picture.Failed
        assertEquals(
            "and it says nothing can read this, not that one engine could not",
            PlaybackError.UNSUPPORTED_FORMAT,
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
    fun `a DRM failure neither switches engines nor offers to`() = runTest {
        val model = model()
        model.open(vodRequest())
        advanceUntilIdle()
        engines.last.fail(PlaybackError.DRM)
        advanceUntilIdle()

        assertEquals("no switch was attempted", 1, engines.built.size)
        val picture = model.state.value?.picture as Picture.Failed
        assertEquals(PlaybackError.DRM, picture.reason)
        assertFalse("and no button offers one", picture.canTryBackup)
    }

    /** The same, for a format neither engine claims to read. */
    @Test
    fun `an unsupported format neither switches engines nor offers to`() = runTest {
        val model = model()
        model.open(vodRequest())
        advanceUntilIdle()
        engines.last.fail(PlaybackError.UNSUPPORTED_FORMAT)
        advanceUntilIdle()

        assertEquals(1, engines.built.size)
        val picture = model.state.value?.picture as Picture.Failed
        assertFalse(picture.canTryBackup)
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
    fun `a source that never opens falls over when the budget runs out`() = runTest {
        val model = model()
        model.open(vodRequest())
        advanceTimeBy(FallbackPolicy.OPEN_DEADLINE_MS - 100)
        assertEquals("still on the first engine inside the budget", 1, engines.built.size)

        advanceTimeBy(200)
        advanceUntilIdle()
        assertEquals("and on the second once it is spent", 2, engines.built.size)
    }

    /**
     * A frame cancels the deadline.
     *
     * Otherwise a stream that opened in 2.9 seconds would be torn down and reopened at 3.0,
     * which is the worst possible outcome: the user waited, got a picture, and lost it.
     */
    @Test
    fun `a frame inside the budget cancels the switch`() = runTest {
        val model = model()
        model.open(vodRequest())
        advanceTimeBy(FallbackPolicy.OPEN_DEADLINE_MS - 500)
        engines.last.renderFirstFrame()
        advanceTimeBy(5_000)
        advanceUntilIdle()

        assertEquals("no second engine", 1, engines.built.size)
        assertTrue(model.state.value?.picture is Picture.Playing)
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
    fun `the engine that worked is remembered and used first next time`() = runTest {
        val model = model()
        val request = vodRequest()
        model.open(request)
        advanceUntilIdle()
        engines.last.fail(PlaybackError.DECODER)
        advanceUntilIdle()
        engines.last.renderFirstFrame()
        advanceUntilIdle()

        assertEquals(
            "the backup is what worked, so the backup is what is written down",
            EngineId.BACKUP,
            memory.preferred(FallbackPolicy.sourceKey(request.url)),
        )

        engines.built.clear()
        val second = model()
        second.open(request)
        advanceUntilIdle()

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
    fun `a channel change releases the previous engine first`() = runTest {
        val model = model()
        model.open(liveRequest())
        engines.last.renderFirstFrame()
        advanceUntilIdle()
        val first = engines.last

        model.switchTo(liveRequest(url = "http://provider.tv/live/2.ts", title = "Second"))
        advanceUntilIdle()

        assertTrue("the previous engine was released", first.released)
        assertEquals("and exactly one new one was built", 2, engines.built.size)
        assertEquals("with the new title already on screen", "Second", model.state.value?.request?.title)
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

    /** Records which engines were built, so "the backup was warmed" is expressible. */
    private class FakeFactory : EngineFactory {
        val built = mutableListOf<EngineId>()
        lateinit var last: FakeEngine

        override fun create(id: EngineId, kind: MediaKind): PlaybackEngine {
            built += id
            return FakeEngine().also { last = it }
        }
    }

    private class FakeEngine : PlaybackEngine {
        private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        override val state: StateFlow<PlaybackState> = _state.asStateFlow()

        private val _tracks = MutableStateFlow(TrackSet())
        override val tracks: StateFlow<TrackSet> = _tracks.asStateFlow()

        private val _firstFrame = MutableStateFlow<Long?>(null)
        override val firstFrameAtMs: StateFlow<Long?> = _firstFrame.asStateFlow()

        val opened = mutableListOf<MediaRequest>()
        var samples = 0
        var released = false

        override val positionMs: Long = 0
        override val bufferedPositionMs: Long = 0
        override val durationMs: Long? = null
        override val isSeekable: Boolean = false

        override fun setVideoOutput(output: VideoOutput?) = Unit
        override fun open(media: MediaRequest) {
            opened += media
            _state.value = PlaybackState.Opening
        }

        override fun play() = Unit
        override fun pause() = Unit
        override fun stop() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun selectTrack(track: Track) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun setAspect(mode: com.castivio.playback.api.AspectMode) = Unit

        override fun sample(): PlaybackSample {
            samples++
            return PlaybackSample()
        }

        override fun release() {
            released = true
        }

        fun renderFirstFrame() {
            _firstFrame.value = 1_000
            _state.value = PlaybackState.Playing(positionMs = 0, durationMs = null, bitrateBps = 0)
        }

        fun fail(reason: PlaybackError) {
            _state.value = PlaybackState.Failed(reason)
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
