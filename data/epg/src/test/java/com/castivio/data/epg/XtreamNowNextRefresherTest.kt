package com.castivio.data.epg

import com.castivio.core.common.AppDispatchers
import com.castivio.core.common.AppError
import com.castivio.core.common.Outcome
import com.castivio.data.networking.XtreamHttpApi
import com.castivio.data.parsing.XtreamEpgEntry
import com.castivio.domain.ChannelRef
import com.castivio.domain.EpgProgramme
import com.castivio.domain.EpgSummary
import com.castivio.domain.EpgWriter
import com.castivio.domain.PlaylistSource
import com.castivio.domain.ProviderSource
import com.castivio.domain.SourceKind
import com.castivio.domain.SourceRepository
import com.castivio.domain.SyncState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cheap guide path: one small request per visible channel, stored under the
 * channel's guide id.
 */
class XtreamNowNextRefresherTest {

    private val now = 1_784_980_800_000L

    @Test
    fun `short epg is fetched per channel and stored under the guide id`() = runBlocking {
        val writer = RecordingEpgWriter()
        val api = FakeShortEpg(
            mapOf(
                "11" to listOf(entry("", "Cup Final", now)),
                "12" to listOf(entry("atlas.1", "Evening News", now)),
            ),
        )

        val written = refresher(writer, api).refresh(
            listOf(
                ChannelRef(mediaId = "a", providerRef = "11", epgChannelId = "nova.1"),
                ChannelRef(mediaId = "b", providerRef = "12", epgChannelId = "atlas.1"),
            ),
        )

        assertEquals(2, written)
        // The response's own channel_id is empty for the first one, so the channel's
        // guide id is used — a programme filed under the wrong id is invisible,
        // because every read joins on the guide id.
        assertEquals(listOf("nova.1", "atlas.1"), writer.programmes.map { it.channelId })
        assertEquals(listOf("11", "12"), api.requested)
        assertEquals(2, writer.finished?.programmes)
    }

    /**
     * **The page asks deeply; the rows do not.**
     *
     * The whole performance contract of the guide page is that these two paths issue
     * different requests through one object, and the only thing that separates them is
     * the count. If `fetch` ever went out at the row limit the page would show four
     * programmes; if `refresh` ever went out at the page limit, browsing a channel list
     * would download a week per row. Asserting the numbers is asserting that neither
     * happened.
     */
    @Test
    fun `the guide page asks for a week and the rows ask for a label`() = runBlocking {
        val writer = RecordingEpgWriter()
        val api = FakeShortEpg(mapOf("11" to listOf(entry("nova.1", "Cup Final", now))))
        val channel = ChannelRef(mediaId = "a", providerRef = "11", epgChannelId = "nova.1")

        refresher(writer, api).fetch(channel)
        refresher(writer, api).refresh(listOf(channel))

        assertEquals(
            listOf(XtreamHttpApi.FULL_EPG_LIMIT, XtreamHttpApi.SHORT_EPG_LIMIT),
            api.limits,
        )
    }

    /**
     * **One channel, one request — and nothing when there is nothing to address.**
     *
     * A channel whose provider shipped no stream id cannot be asked about, and the page
     * must not issue a request that can only fail. Zero, and no call.
     */
    @Test
    fun `a channel with no provider id is never asked about`() = runBlocking {
        val writer = RecordingEpgWriter()
        val api = FakeShortEpg(emptyMap())

        val written = refresher(writer, api)
            .fetch(ChannelRef(mediaId = "a", providerRef = null, epgChannelId = "nova.1"))

        assertEquals(0, written)
        assertTrue(api.requested.isEmpty())
    }

    /**
     * **A provider with a short guide is not a failure.**
     *
     * The page asks for two hundred and a provider holding an hour answers with one.
     * That one is stored and reported, because "fewer days" is the answer to the
     * question rather than an error — it is what lets a page show one day for one
     * provider and seven for another without a branch anywhere.
     */
    @Test
    fun `a short answer is stored rather than refused`() = runBlocking {
        val writer = RecordingEpgWriter()
        val api = FakeShortEpg(mapOf("11" to listOf(entry("nova.1", "Cup Final", now))))

        val written = refresher(writer, api)
            .fetch(ChannelRef(mediaId = "a", providerRef = "11", epgChannelId = "nova.1"))

        assertEquals(1, written)
        assertEquals(listOf("nova.1"), writer.programmes.map { it.channelId })
    }

    /**
     * **And a provider that cannot be asked at all answers quietly.**
     *
     * An M3U source has no `get_short_epg`; its guide comes from XMLTV. The page shows
     * whatever is stored and says nothing about a request that was never sensible to
     * make, exactly as `refresh` already does.
     */
    @Test
    fun `an m3u provider yields nothing without erroring`() = runBlocking {
        val writer = RecordingEpgWriter()
        val api = FakeShortEpg(mapOf("11" to listOf(entry("nova.1", "Cup Final", now))))

        val written = refresher(writer, api, kind = SourceKind.M3U_URL)
            .fetch(ChannelRef(mediaId = "a", providerRef = "11", epgChannelId = "nova.1"))

        assertEquals(0, written)
        assertTrue(api.requested.isEmpty())
    }

    @Test
    fun `channels with no provider id are skipped`() = runBlocking {
        val writer = RecordingEpgWriter()
        val api = FakeShortEpg(mapOf("11" to listOf(entry("nova.1", "Cup Final", now))))

        val written = refresher(writer, api).refresh(
            listOf(
                ChannelRef("a", providerRef = null, epgChannelId = "nova.1"),
                ChannelRef("b", providerRef = "", epgChannelId = "x"),
                ChannelRef("c", providerRef = "11", epgChannelId = "nova.1"),
            ),
        )

        assertEquals(1, written)
        assertEquals(listOf("11"), api.requested)
    }

    /** One channel's guide failing must not abandon the rest of the refresh. */
    @Test
    fun `a failure on one channel does not lose the others`() = runBlocking {
        val writer = RecordingEpgWriter()
        val api = FakeShortEpg(
            responses = mapOf("12" to listOf(entry("atlas.1", "Evening News", now))),
            failing = setOf("11"),
        )

        val written = refresher(writer, api).refresh(
            listOf(
                ChannelRef("a", "11", "nova.1"),
                ChannelRef("b", "12", "atlas.1"),
            ),
        )

        assertEquals(1, written)
        assertEquals("Evening News", writer.programmes.single().title)
    }

    /**
     * A request budget, not a row limit: a guide grid can show more rows than it is
     * reasonable to issue requests for on a TV box's radio.
     */
    @Test
    fun `the number of requests per refresh is capped`() = runBlocking {
        val writer = RecordingEpgWriter()
        val api = FakeShortEpg((0 until 200).associate { "$it" to listOf(entry("ch$it", "P$it", now)) })

        refresher(writer, api).refresh((0 until 200).map { ChannelRef("m$it", "$it", "ch$it") })

        assertEquals(XtreamNowNextRefresher.MAX_CHANNELS_PER_REFRESH, api.requested.size)
    }

    @Test
    fun `a non-xtream provider is a quiet no-op, not an error`() = runBlocking {
        val writer = RecordingEpgWriter()
        val api = FakeShortEpg(mapOf("11" to listOf(entry("nova.1", "Cup Final", now))))

        val written = refresher(writer, api, kind = SourceKind.M3U_URL)
            .refresh(listOf(ChannelRef("a", "11", "nova.1")))

        assertEquals(0, written)
        assertTrue("nothing may be requested", api.requested.isEmpty())
        assertTrue("nothing may be written", writer.programmes.isEmpty())
    }

    @Test
    fun `an empty request list does no work`() = runBlocking {
        val writer = RecordingEpgWriter()
        val api = FakeShortEpg(emptyMap())

        assertEquals(0, refresher(writer, api).refresh(emptyList()))
        assertTrue(writer.programmes.isEmpty())
    }

    // ------------------------------------------------------------------ fixtures

    private fun entry(channelId: String, title: String, startMs: Long) = XtreamEpgEntry(
        channelId = channelId,
        title = title,
        description = null,
        startMs = startMs,
        stopMs = startMs + 3_600_000L,
    )

    private fun refresher(
        writer: EpgWriter,
        api: ShortEpgSource,
        kind: SourceKind = SourceKind.XTREAM,
    ) = XtreamNowNextRefresher(
        client = OkHttpClient(),
        writerFactory = { writer },
        sources = FakeSources(kind),
        dispatchers = TestDispatchers,
        apiFactory = { _, _ -> api },
    )

    private object TestDispatchers : AppDispatchers {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private class FakeShortEpg(
        private val responses: Map<String, List<XtreamEpgEntry>>,
        private val failing: Set<String> = emptySet(),
    ) : ShortEpgSource {
        val requested = mutableListOf<String>()

        /** Every limit this double was asked for, so a test can assert which path ran. */
        val limits = mutableListOf<Int>()

        override fun shortEpg(providerRef: String, limit: Int): Outcome<List<XtreamEpgEntry>> {
            requested += providerRef
            limits += limit
            if (providerRef in failing) return Outcome.Failure(AppError.SERVER_ERROR)
            return Outcome.Success(responses[providerRef].orEmpty())
        }
    }

    private class FakeSources(kind: SourceKind) : SourceRepository {
        private val stored = ProviderSource(
            id = "src",
            kind = kind,
            label = "test",
            url = "http://panel:8080",
            username = "u",
            password = "p",
            sync = SyncState(),
        )

        override suspend fun register(source: PlaylistSource, label: String?): ProviderSource = stored
        override fun sources(): Flow<List<ProviderSource>> = flowOf(listOf(stored))
        override fun active(): Flow<ProviderSource?> = flowOf(stored)
        override suspend fun activeNow(): ProviderSource = stored
        override suspend fun get(id: String): ProviderSource = stored
        override suspend fun save(source: ProviderSource) = Unit
        override suspend fun setActive(id: String) = Unit
        override suspend fun recordCatalogueImport(id: String, sync: SyncState) = Unit
        override suspend fun recordEpgImport(id: String, atMs: Long) = Unit
        override suspend fun delete(id: String) = Unit
    }

    private class RecordingEpgWriter : EpgWriter {
        val programmes = mutableListOf<EpgProgramme>()
        var finished: EpgSummary? = null
        var aborted = false

        override fun begin(sourceId: String) = Unit
        override fun writeProgrammes(programmes: List<EpgProgramme>) { this.programmes += programmes }
        override fun commit() = Unit
        override fun finish(summary: EpgSummary) { finished = summary }
        override fun abort(cause: Throwable?) { aborted = true }
    }
}
