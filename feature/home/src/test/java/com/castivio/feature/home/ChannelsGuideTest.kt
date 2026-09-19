package com.castivio.feature.home

import com.castivio.domain.CatalogRepository
import com.castivio.domain.Channel
import com.castivio.domain.ChannelRef
import com.castivio.domain.EpgCoverage
import com.castivio.domain.EpgRepository
import com.castivio.domain.FavoritesRepository
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaItem
import com.castivio.domain.MediaKind
import com.castivio.domain.NowNext
import com.castivio.domain.NowNextRefresher
import com.castivio.domain.Page
import com.castivio.domain.PageRequest
import com.castivio.domain.Programme
import com.castivio.domain.time.TimeAnchorSource
import com.castivio.domain.time.TimeReading
import com.castivio.domain.time.TimeTrust
import com.castivio.domain.time.TrustedTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * What is on, and what it costs to find out.
 *
 * ## The defect these exist for
 *
 * The guide's whole write path shipped — importer, refresher, parser, writer — and nothing
 * ever called it. The `programme` table was empty on every device, so `NOW` and `NEXT` were
 * blank on a screen whose reading path was working perfectly. Every assertion below is
 * about the *order* of the calls rather than their result, because the order is what the
 * performance contract is: storage first, one request second, and never on the way to
 * anything a viewer is waiting for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelsGuideTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /**
     * **A channel whose guide is already down costs nothing.**
     *
     * The first rule of this path and the reason it is safe to run on every press: the
     * store is asked before the provider is, so a second visit to a channel is an indexed
     * read and no network at all.
     */
    @Test
    fun `stored programmes are drawn without a request`() = runTest(dispatcher) {
        val epg = FakeEpg(GUIDE_ID to listOf(onNow(), onNext()))
        val refresher = CountingRefresher()
        val model = viewModel(epg, refresher)

        model.select(channel(), 1)
        advanceUntilIdle()

        assertEquals("what is on", NOW_TITLE, model.preview.value.guide?.now?.title)
        assertEquals("what is next", NEXT_TITLE, model.preview.value.guide?.next?.title)
        assertEquals("requests", 0, refresher.calls)
    }

    /**
     * **An empty store costs exactly one request, for exactly one channel.**
     *
     * `NowNextRefresher` will take forty channels and the panel shows one, so the
     * assertion is on the size of the list as much as on the number of calls: forty would
     * be thirty-nine responses nobody looks at, on a radio a stream is playing over.
     */
    @Test
    fun `an empty store fetches once for the chosen channel alone`() = runTest(dispatcher) {
        val epg = FakeEpg()
        val refresher = CountingRefresher(onRefresh = { epg.put(GUIDE_ID, listOf(onNow(), onNext())) })
        val model = viewModel(epg, refresher)

        model.select(channel(), 1)
        advanceUntilIdle()

        assertEquals("requests", 1, refresher.calls)
        assertEquals("channels asked for", 1, refresher.lastBatch.size)
        assertEquals("which channel", MEDIA_ID, refresher.lastBatch.single().mediaId)
        assertEquals("drawn after the write", NOW_TITLE, model.preview.value.guide?.now?.title)
    }

    /**
     * **A provider that answers with nothing is not asked again on the next press.**
     *
     * The throttle governs the empty answer only, which is the case that would otherwise
     * repeat forever: a channel with rows in storage never reaches it.
     */
    @Test
    fun `the same channel is not re-asked inside the window`() = runTest(dispatcher) {
        val epg = FakeEpg()
        val refresher = CountingRefresher()
        val clock = FixedClock(NOW_MS)
        val model = viewModel(epg, refresher, clock)

        model.select(channel(), 1)
        advanceUntilIdle()
        // Away and back: `select` is idempotent on the same id, so the selection has to
        // actually move for the second press to reach the guide at all.
        model.select(channel(id = "other", guideId = "other.guide"), 2)
        advanceUntilIdle()
        clock.epochMs = NOW_MS + ChannelsViewModel.ASK_AGAIN_MS - 1
        model.select(channel(), 3)
        advanceUntilIdle()

        assertEquals("one request each, and no third", 2, refresher.calls)
    }

    /** And past the window it is asked again, because a channel can gain a guide. */
    @Test
    fun `past the window the provider is asked again`() = runTest(dispatcher) {
        val epg = FakeEpg()
        val refresher = CountingRefresher()
        val clock = FixedClock(NOW_MS)
        val model = viewModel(epg, refresher, clock)

        model.select(channel(), 1)
        advanceUntilIdle()
        model.select(channel(id = "other", guideId = "other.guide"), 2)
        advanceUntilIdle()
        clock.epochMs = NOW_MS + ChannelsViewModel.ASK_AGAIN_MS + 1
        model.select(channel(), 3)
        advanceUntilIdle()

        assertEquals(3, refresher.calls)
    }

    /**
     * **A channel with no guide id is not abandoned before the query.**
     *
     * `loadGuide` read `channel.epgChannelId ?: return`, so a provider that ships no guide
     * id left every one of its channels blank however good the data behind them was. The
     * refresher already files such a channel under its media id; this reads back the same
     * way, which is following the writer's order rather than inventing a second one.
     */
    @Test
    fun `a channel with no guide id is read by its media id`() = runTest(dispatcher) {
        val epg = FakeEpg(MEDIA_ID to listOf(onNow(channelId = MEDIA_ID)))
        val refresher = CountingRefresher()
        val model = viewModel(epg, refresher)

        model.select(channel(guideId = null), 1)
        advanceUntilIdle()

        assertEquals("drawn from the media id", NOW_TITLE, model.preview.value.guide?.now?.title)
        assertEquals("and without a request", 0, refresher.calls)
    }

    /**
     * **A failed request is one channel's guide, not an error state.**
     *
     * Nothing is thrown, nothing already drawn is cleared, and the stream the press opened
     * is untouched — which is the property that keeps live playback independent of the
     * guide rather than merely tolerant of it.
     */
    @Test
    fun `a network failure neither throws nor clears what is held`() = runTest(dispatcher) {
        val epg = FakeEpg(GUIDE_ID to listOf(onNow()))
        val refresher = CountingRefresher(onRefresh = { throw IllegalStateException("no route") })
        val model = viewModel(epg, refresher)

        model.select(channel(), 1)
        advanceUntilIdle()
        // Stored rows were found first, so the failure path is not even reached; the
        // assertion is that the panel is intact and the test did not blow up.
        assertEquals(NOW_TITLE, model.preview.value.guide?.now?.title)

        // And on a channel with nothing stored, the failure is silent.
        model.select(channel(id = "dry", guideId = "dry.guide"), 2)
        advanceUntilIdle()
        assertNull("no programme invented", model.preview.value.guide?.now)
    }

    /**
     * **A slow answer may not land on a channel the viewer has already left.**
     *
     * The race the old code guarded and this one has to keep guarding, now over a network
     * round trip rather than a database read: a viewer moves faster than a provider
     * answers, and a late reply writing its programme under the new channel's name is the
     * kind of lie that is caught instantly and trusted never again.
     */
    @Test
    fun `a late answer does not touch the channel now selected`() = runTest(dispatcher) {
        val epg = FakeEpg()
        lateinit var holder: ChannelsViewModel
        val refresher = CountingRefresher(onRefresh = { batch ->
            // The reply arrives for the first channel while the second is on screen.
            if (batch.single().mediaId == MEDIA_ID) {
                holder.select(channel(id = "second", guideId = "second.guide"), 2)
                epg.put(GUIDE_ID, listOf(onNow()))
            }
        })
        holder = viewModel(epg, refresher)

        holder.select(channel(), 1)
        advanceUntilIdle()

        assertNull(
            "the first channel's programme must not appear under the second",
            holder.preview.value.guide?.now,
        )
        assertEquals("second", holder.preview.value.channel?.id)
    }

    /* ------------------------------------------------------------------ helpers */

    /**
     * A holder with someone watching it.
     *
     * `preview` is shared `WhileSubscribed`, so with no collector it stands at its initial
     * value and every assertion below would read an empty panel however well the path
     * worked. The background collector is the screen: it is what makes `preview.value` the
     * thing a viewer would be looking at. It is launched on `backgroundScope`, which
     * `runTest` cancels for us, so a flow that never completes cannot hang the test.
     */
    private fun TestScope.viewModel(
        epg: EpgRepository,
        refresher: NowNextRefresher,
        clock: TrustedTime = FixedClock(NOW_MS),
    ) = ChannelsViewModel(
        epg = epg,
        catalog = FakeCatalog(),
        nowNext = refresher,
        favorites = NoFavorites(),
        clock = clock,
    ).also { holder -> backgroundScope.launch { holder.preview.collect { } } }

    private fun channel(id: String = MEDIA_ID, guideId: String? = GUIDE_ID) = Channel(
        id = id,
        title = "UK| SKY NEWS HD",
        artworkUrl = null,
        number = null,
        groupId = "news",
        streamUrl = "http://example.invalid/$id",
        epgChannelId = guideId,
    )

    private fun onNow(channelId: String = GUIDE_ID) = Programme(
        channelId = channelId,
        title = NOW_TITLE,
        description = null,
        startMs = NOW_MS - 10 * 60 * 1000L,
        stopMs = NOW_MS + 50 * 60 * 1000L,
    )

    private fun onNext(channelId: String = GUIDE_ID) = Programme(
        channelId = channelId,
        title = NEXT_TITLE,
        description = null,
        startMs = NOW_MS + 50 * 60 * 1000L,
        stopMs = NOW_MS + 110 * 60 * 1000L,
    )

    /**
     * A clock that stands still until a test moves it.
     *
     * `TrustedTime` answers with a reading *and* the reason it should be believed;
     * `nowMs()` is its convenience over that, so the fake implements the real method and
     * inherits the convenience rather than overriding it.
     */
    private class FixedClock(var epochMs: Long) : TrustedTime {
        override fun now(): TimeReading = TimeReading(epochMs, TimeTrust.DEVICE)
        override fun anchor(epochMs: Long, source: TimeAnchorSource): TimeReading {
            this.epochMs = epochMs
            return now()
        }
    }

    /** A store that answers by key, and can gain rows mid-test as a write would. */
    private class FakeEpg(vararg seed: Pair<String, List<Programme>>) : EpgRepository {
        private val rows = HashMap<String, List<Programme>>(seed.toMap())

        fun put(key: String, programmes: List<Programme>) { rows[key] = programmes }

        override suspend fun programmes(channelId: String, fromMs: Long, toMs: Long): List<Programme> =
            rows[channelId].orEmpty().filter { it.stopMs > fromMs && it.startMs < toMs }

        override suspend fun nowNext(channelIds: List<String>, atMs: Long): Map<String, NowNext> =
            emptyMap()

        override suspend fun window(
            channelIds: List<String>,
            fromMs: Long,
            toMs: Long,
        ): Map<String, List<Programme>> = emptyMap()

        override fun coverage(): Flow<EpgCoverage> = flowOf(EpgCoverage(0, 0, null, null, null))

        override suspend fun hasFreshGuide(atMs: Long, minimumHorizonMs: Long): Boolean = false
    }

    /** Counts what the panel asked the provider for, and lets a test answer for it. */
    private class CountingRefresher(
        private val onRefresh: (List<ChannelRef>) -> Unit = {},
    ) : NowNextRefresher {
        var calls = 0
            private set
        var lastBatch: List<ChannelRef> = emptyList()
            private set

        override suspend fun refresh(channels: List<ChannelRef>): Int {
            calls++
            lastBatch = channels
            onRefresh(channels)
            return 0
        }
    }

    /**
     * The catalogue, answering only the one question this path asks it.
     *
     * `channelRefs` is the whole of the interaction: the provider's stream id, which
     * `Channel` does not carry and a guide request is addressed by. Every other member is
     * present because the interface has it, and errors if a test ever reaches one.
     */
    private class FakeCatalog : CatalogRepository {
        override suspend fun channelRefs(mediaIds: List<String>): List<ChannelRef> =
            mediaIds.map { ChannelRef(mediaId = it, providerRef = "stream-$it", epgChannelId = null) }

        override fun groups(kind: MediaKind): Flow<List<MediaGroup>> = flowOf(emptyList())
        override fun count(kind: MediaKind, groupId: String?): Flow<Int> = flowOf(0)
        override suspend fun page(request: PageRequest): Page<MediaItem> = error("not asked")
        override suspend fun item(id: String): MediaItem? = error("not asked")
        override suspend fun search(query: String, limit: Int): List<MediaItem> = error("not asked")
        override suspend fun search(query: String, kind: MediaKind, limit: Int): List<MediaItem> =
            error("not asked")
    }

    private class NoFavorites : FavoritesRepository {
        override fun isFavorite(mediaId: String): Flow<Boolean> = flowOf(false)
        override suspend fun toggle(mediaId: String): Boolean = false
        override fun count(): Flow<Int> = flowOf(0)
    }

    private companion object {
        const val MEDIA_ID = "media-1"
        const val GUIDE_ID = "sky.news.uk"
        const val NOW_TITLE = "Sky News Tonight"
        const val NEXT_TITLE = "Sky News at Ten"

        /** A fixed instant, so `isLiveAt` is arithmetic rather than a race with the clock. */
        const val NOW_MS = 1_789_000_000_000L
    }
}
