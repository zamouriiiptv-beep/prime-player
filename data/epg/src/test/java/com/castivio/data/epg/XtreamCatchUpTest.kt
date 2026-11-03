package com.castivio.data.epg

import com.castivio.core.common.AppDispatchers
import com.castivio.core.common.AppError
import com.castivio.core.common.Outcome
import com.castivio.data.parsing.XtreamArchiveEntry
import com.castivio.domain.CatchUpChannel
import com.castivio.domain.CatchUpListing
import com.castivio.domain.CatchUpPolicy
import com.castivio.domain.CatchUpUnsupported
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catch-up on an Xtream panel: when it asks, what it keeps, and what it will not ask.
 *
 * No server: the panel is a fake that counts the questions it is asked, and the point of most of these tests is that the
 * count is zero or one.
 */
class XtreamCatchUpTest {

    private val now = 1_784_980_800_000L
    private val hour = CatchUpPolicy.HOUR_MS

    private val channel = CatchUpChannel(mediaId = "a", providerRef = "11", title = "Nova", archiveHours = 72)

    private fun entry(title: String, startAgoHours: Double, hours: Double = 1.0, archived: Boolean = true, serverStart: String = "2026-07-24 21:30:00") =
        XtreamArchiveEntry(
            title = title,
            description = null,
            startMs = now - (startAgoHours * hour).toLong(),
            stopMs = now - (startAgoHours * hour).toLong() + (hours * hour).toLong(),
            serverStart = serverStart,
            archived = archived,
        )

    @Test
    fun `the listing is the programmes that can be replayed, each addressed by the panel's own start`() = runBlocking {
        val api = FakeArchive(
            Outcome.Success(
                listOf(
                    entry("yesterday", startAgoHours = 26.0),
                    entry("this morning", startAgoHours = 5.0),
                    entry("too old", startAgoHours = 100.0),
                    entry("still on", startAgoHours = 0.5),
                    entry("no readable start", startAgoHours = 8.0, serverStart = "soon"),
                ),
            ),
        )

        val listing = gateway(api).listing(channel, now) as CatchUpListing.Programmes

        assertEquals(listOf("this morning", "yesterday"), listing.items.map { it.title })
        assertEquals("2026-07-24:21-30", listing.items.first().locator)
        assertEquals(listOf("11"), api.asked)
    }

    @Test
    fun `asking twice within the memory is one request`() = runBlocking {
        val api = FakeArchive(Outcome.Success(listOf(entry("this morning", startAgoHours = 5.0))))
        val catchUp = gateway(api)

        catchUp.listing(channel, now)
        val second = catchUp.listing(channel, now + 60_000) as CatchUpListing.Programmes

        assertEquals(1, second.items.size)
        assertEquals(1, api.asked.size)
    }

    @Test
    fun `a channel found with nothing archived is known empty, and the button can go`() = runBlocking {
        val api = FakeArchive(Outcome.Success(listOf(entry("unrecorded", startAgoHours = 5.0, archived = false).copy(archived = true, startMs = now + hour, stopMs = now + 2 * hour))))
        val catchUp = gateway(api)

        val listing = catchUp.listing(channel, now) as CatchUpListing.Programmes

        assertTrue(listing.items.isEmpty())
        assertTrue(catchUp.knownEmpty("a", now + 1_000))
        assertFalse("a channel never asked for is not empty", catchUp.knownEmpty("b", now))
    }

    @Test
    fun `a failure is reported and nothing about it is remembered`() = runBlocking {
        val api = FakeArchive(Outcome.Failure(AppError.TIMEOUT))
        val catchUp = gateway(api)

        val listing = catchUp.listing(channel, now)

        assertEquals(CatchUpListing.Failed(AppError.TIMEOUT), listing)
        assertFalse(catchUp.knownEmpty("a", now))
        catchUp.listing(channel, now + 1_000)
        assertEquals("a failure is not served from memory: it is asked again", 2, api.asked.size)
    }

    @Test
    fun `a subscription that is not an Xtream panel is answered not supported and nothing is asked`() = runBlocking {
        val api = FakeArchive(Outcome.Success(emptyList()))
        val portal = ProviderSource(id = "s", kind = SourceKind.PORTAL, label = "portal", url = "http://portal", sync = SyncState())

        val listing = gateway(api, stored = portal).listing(channel, now)

        assertEquals(CatchUpListing.NotSupported(CatchUpUnsupported.PROTOCOL), listing)
        assertTrue(api.asked.isEmpty())
    }

    @Test
    fun `a playlist read from its file has no archive to ask`() = runBlocking {
        val api = FakeArchive(Outcome.Success(emptyList()))
        val file = ProviderSource(
            id = "p",
            kind = SourceKind.M3U_URL,
            label = "list",
            url = "http://line.example.com:8080/get.php?username=sam&password=pw",
            sync = SyncState(contentHash = "crc32:1a2b3c4d", lastImportAtMs = now, itemCount = 100),
        )

        val listing = gateway(api, stored = file).listing(channel, now)

        assertEquals(CatchUpListing.NotSupported(CatchUpUnsupported.PROTOCOL), listing)
        assertTrue(api.asked.isEmpty())
    }

    @Test
    fun `a channel with no provider id cannot be addressed and nothing is asked`() = runBlocking {
        val api = FakeArchive(Outcome.Success(emptyList()))

        val listing = gateway(api).listing(channel.copy(providerRef = null), now)

        assertEquals(CatchUpListing.NotSupported(CatchUpUnsupported.NO_PROVIDER_REFERENCE), listing)
        assertEquals(CatchUpListing.NotSupported(CatchUpUnsupported.NO_PROVIDER_REFERENCE), gateway(api).listing(channel.copy(providerRef = " "), now))
        assertTrue(api.asked.isEmpty())
    }

    @Test
    fun `the address of a programme is made without asking the panel anything`() = runBlocking {
        val api = FakeArchive(Outcome.Success(listOf(entry("this morning", startAgoHours = 5.0, hours = 1.5))))
        val catchUp = gateway(api)
        val programme = (catchUp.listing(channel, now) as CatchUpListing.Programmes).items.single()
        val before = api.asked.size

        val url = catchUp.playbackUrl(channel, programme)

        assertEquals(Outcome.Success("http://panel:8080/timeshift/u/p/90/2026-07-24:21-30/11.ts"), url)
        assertEquals("no request to make the address", before, api.asked.size)
    }

    // ---------------------------------------------------------------------------------------- fakes

    private fun gateway(api: ArchiveSource, stored: ProviderSource = FakeSources.xtream()) = XtreamCatchUp(
        client = OkHttpClient(),
        sources = FakeSources(stored),
        dispatchers = TestDispatchers,
        apiFactory = { _, _ -> api },
    )

    private object TestDispatchers : AppDispatchers {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private class FakeArchive(private val result: Outcome<List<XtreamArchiveEntry>>) : ArchiveSource {
        val asked = mutableListOf<String>()

        override fun archiveListings(streamId: String): Outcome<List<XtreamArchiveEntry>> {
            asked += streamId
            return result
        }

        override fun archiveUrl(streamId: String, durationMinutes: Int, start: String): String =
            "http://panel:8080/timeshift/u/p/$durationMinutes/$start/$streamId.ts"
    }

    private class FakeSources(private val stored: ProviderSource) : SourceRepository {

        companion object {
            fun xtream() = ProviderSource(
                id = "src",
                kind = SourceKind.XTREAM,
                label = "test",
                url = "http://panel:8080",
                username = "u",
                password = "p",
                sync = SyncState(),
            )
        }

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
}
