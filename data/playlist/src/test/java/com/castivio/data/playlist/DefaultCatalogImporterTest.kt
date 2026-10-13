package com.castivio.data.playlist

import com.castivio.core.common.AppDispatchers
import com.castivio.core.common.AppError
import com.castivio.data.networking.HttpStreamSource
import com.castivio.data.parsing.SourceIds
import com.castivio.data.parsing.XtreamImportEngine
import com.castivio.data.parsing.XtreamUrls
import com.castivio.domain.CatalogItem
import com.castivio.domain.CatalogWriter
import com.castivio.domain.ImportMode
import com.castivio.domain.ImportProgress
import com.castivio.domain.ImportSummary
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaKind
import com.castivio.domain.PlaylistSource
import com.castivio.domain.ProviderSource
import com.castivio.domain.SourceKind
import com.castivio.domain.SourceRepository
import com.castivio.domain.SyncState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.Reader
import java.io.StringReader
import java.util.concurrent.TimeUnit

/**
 * The import pipeline end to end: HTTP, parsing, writing, and the sync state that
 * decides whether any of it has to happen next time.
 */
class DefaultCatalogImporterTest {

    private val now = 1_784_980_800_000L

    private lateinit var server: MockWebServer
    private lateinit var writer: RecordingWriter
    private lateinit var sources: FakeSourceRepository

    /** What the importer wrote to its log, in order. */
    private val logged = mutableListOf<String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        writer = RecordingWriter()
        sources = FakeSourceRepository()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a playlist url is fetched, parsed and recorded`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(playlist(3))
                .setHeader("ETag", "\"v1\"")
                .setHeader("Last-Modified", "Wed, 22 Jul 2026 10:00:00 GMT"),
        )
        val source = PlaylistSource.M3u(server.url("/playlist.m3u").toString())

        val progress = importer().import(source).toList()

        assertEquals(3, writer.items.size)
        assertEquals(listOf("Channel 0", "Channel 1", "Channel 2"), writer.items.map { it.title })
        assertTrue(progress.first() is ImportProgress.CheckingForChanges)
        assertEquals(3, (progress.last() as ImportProgress.Done).totalItems)

        val recorded = sources.get(SourceIds.of(source))!!.sync
        assertEquals("\"v1\"", recorded.etag)
        assertEquals("Wed, 22 Jul 2026 10:00:00 GMT", recorded.lastModified)
        assertEquals(3, recorded.itemCount)
        assertEquals(now, recorded.lastImportAtMs)
        assertTrue("a hash is stored even with validators", recorded.contentHash!!.startsWith("crc32:"))
    }

    /**
     * The cheapest possible refresh: one request, no download, no parse, no write.
     */
    @Test
    fun `an unchanged playlist is not re-imported`() = runBlocking {
        val source = PlaylistSource.M3u(server.url("/playlist.m3u").toString())
        sources.save(stored(SourceIds.of(source), etag = "\"v1\"", items = 3))
        server.enqueue(MockResponse().setResponseCode(304))

        val progress = importer().import(source).toList()

        assertTrue(progress.any { it is ImportProgress.UpToDate })
        assertTrue("nothing may be written", writer.items.isEmpty())
        assertTrue(writer.aborted)
        assertEquals("\"v1\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun `a provider error is reported, not swallowed`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val source = PlaylistSource.M3u(server.url("/gone.m3u").toString())

        val progress = importer().import(source).toList()

        assertEquals(AppError.NOT_FOUND, (progress.last() as ImportProgress.Failed).error)
        assertTrue(writer.items.isEmpty())
        assertTrue(writer.aborted)
        // A failed import must not be recorded as a successful one.
        assertNull(sources.get(SourceIds.of(source))?.sync?.lastImportAtMs)
    }

    @Test
    fun `credentials are unauthorised, not unknown`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val source = PlaylistSource.M3u(server.url("/p.m3u").toString())

        val progress = importer().import(source).toList()

        assertEquals(AppError.UNAUTHORIZED, (progress.last() as ImportProgress.Failed).error)
    }

    // -------------------------------------------------- why a playlist did not arrive
    //
    // A failed M3U import used to leave no trace: the status and the exception were
    // handed to the writer's `abort`, which ignored them. These say which of four
    // different problems it was, and that the log never carries the link that was
    // asked for -- the link is the account's username and password.

    @Test
    fun `a refused playlist is logged with the status it was refused with`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "text/html; charset=UTF-8")
                .setBody("<html>Access denied</html>"),
        )
        val source = PlaylistSource.M3u(server.url(PANEL_LINK).toString())

        val progress = importer().import(source).toList()

        assertEquals(AppError.UNAUTHORIZED, (progress.last() as ImportProgress.Failed).error)
        val line = outcome()
        assertTrue(line, line.contains("failed kind=REFUSED error=UNAUTHORIZED http=403 type=text/html stage=request"))
        assertTrue(line, line.contains("scheme=http"))
        assertNothingFrom(source.url)
    }

    @Test
    fun `a provider that cannot be reached is logged as a connection problem`() = runBlocking {
        val source = PlaylistSource.M3u("http://127.0.0.1:1$PANEL_LINK")

        val progress = importer().import(source).toList()

        assertEquals(AppError.NETWORK_UNAVAILABLE, (progress.last() as ImportProgress.Failed).error)
        val line = outcome()
        assertTrue(line, line.contains("kind=CONNECTION error=NETWORK_UNAVAILABLE http=- type=- stage=request"))
        assertTrue(line, line.contains("exception=ConnectException"))
        assertNothingFrom(source.url)
    }

    @Test
    fun `a provider that never starts answering is logged as a timeout`() = runBlocking {
        server.enqueue(MockResponse().setBody(playlist(3)).setHeadersDelay(5, TimeUnit.SECONDS))
        val source = PlaylistSource.M3u(server.url(PANEL_LINK).toString())

        val progress = importer().import(source).toList()

        assertEquals(AppError.TIMEOUT, (progress.last() as ImportProgress.Failed).error)
        val line = outcome()
        assertTrue(line, line.contains("kind=TIMEOUT error=TIMEOUT http=- type=- stage=request"))
        assertTrue(line, line.contains("exception=SocketTimeoutException"))
        assertNothingFrom(source.url)
    }

    @Test
    fun `a playlist cut off half way is logged as a read error, not a connection error`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "audio/x-mpegurl")
                    .setBody(playlist(200))
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
            )
            val source = PlaylistSource.M3u(server.url(PANEL_LINK).toString())

            val progress = importer().import(source).toList()

            assertTrue(progress.last() is ImportProgress.Failed)
            val line = outcome()
            assertTrue(line, line.contains("kind=READ"))
            assertTrue(line, line.contains("http=200 type=audio/x-mpegurl stage=body"))
            assertTrue("the writer must not keep a half-read playlist", writer.aborted)
            assertNothingFrom(source.url)
        }

    @Test
    fun `a playlist that cannot be written is a failure the screen can show, not a crash`() =
        runBlocking {
            server.enqueue(MockResponse().setHeader("Content-Type", "audio/x-mpegurl").setBody(playlist(3)))
            val source = PlaylistSource.M3u(server.url(PANEL_LINK).toString())
            val broken = ThrowingWriter()

            // Before, the writer's exception left this flow and took the screen's scope
            // down with it. `toList` completing at all is half of what is being checked.
            val progress = importer(writer = broken).import(source).toList()

            assertEquals(AppError.UNKNOWN, (progress.last() as ImportProgress.Failed).error)
            assertTrue(broken.aborted)
            val line = outcome()
            assertTrue(line, line.contains("kind=READ error=UNKNOWN http=200 type=audio/x-mpegurl stage=body"))
            assertTrue(line, line.contains("exception=IllegalStateException"))
            assertNothingFrom(source.url)
        }

    @Test
    fun `a playlist that arrives is logged with its status, type and size`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "audio/x-mpegurl; charset=utf-8")
                .setBody(playlist(3)),
        )
        val source = PlaylistSource.M3u(server.url(PANEL_LINK).toString())

        importer().import(source).toList()

        val line = outcome()
        assertTrue(line, line.startsWith("M3U import ok http=200 type=audio/x-mpegurl items=3 bytes="))
        assertTrue(line, line.endsWith("scheme=http took=0ms"))
        assertNothingFrom(source.url)
    }

    @Test
    fun `progress says how much of the playlist has arrived`() = runBlocking {
        server.enqueue(MockResponse().setBody(playlist(2_500)))
        val source = PlaylistSource.M3u(server.url(PANEL_LINK).toString())

        val progress = importer().import(source).toList()

        val importing = progress.filterIsInstance<ImportProgress.Importing>()
        assertEquals("one report per committed batch of a thousand", 2, importing.size)
        assertTrue(importing.all { it.bytes > 0 })
        assertEquals("never backwards", importing.map { it.bytes }.sorted(), importing.map { it.bytes })
    }

    @Test
    fun `up-to-date is answered with one small request`() = runBlocking {
        val source = PlaylistSource.M3u(server.url("/p.m3u").toString())
        sources.save(stored(SourceIds.of(source), etag = "\"v1\"", items = 10))
        server.enqueue(MockResponse().setResponseCode(304))

        assertTrue(importer().isUpToDate(source))
        val request = server.takeRequest()
        assertEquals("\"v1\"", request.getHeader("If-None-Match"))
        assertEquals("bytes=0-4095", request.getHeader("Range"))
    }

    @Test
    fun `up-to-date is false when there is nothing to compare against`() = runBlocking {
        val source = PlaylistSource.M3u(server.url("/p.m3u").toString())

        // Never imported.
        assertFalse(importer().isUpToDate(source))

        // Imported, but the provider sent no validators, so the only way to know is
        // to fetch it.
        sources.save(stored(SourceIds.of(source), etag = null, items = 10))
        assertFalse(importer().isUpToDate(source))

        // Xtream is category-addressable; nothing was downloaded wholesale, so the
        // question does not apply.
        assertFalse(importer().isUpToDate(PlaylistSource.Xtream("host", "u", "p")))
    }

    @Test
    fun `a local file is imported and fingerprinted`() = runBlocking {
        val source = PlaylistSource.LocalFile("content://downloads/playlist.m3u")
        val reader = FakeLocalReader(playlist(2))

        val progress = importer(localFiles = reader).import(source).toList()

        assertEquals(2, writer.items.size)
        assertEquals(2, (progress.last() as ImportProgress.Done).totalItems)
        val recorded = sources.get(SourceIds.of(source))!!.sync
        assertTrue(recorded.contentHash!!.startsWith("crc32:"))
        assertNull("a file has no validators", recorded.etag)
    }

    @Test
    fun `a file that has gone away is reported as missing`() = runBlocking {
        val source = PlaylistSource.LocalFile("content://downloads/deleted.m3u")

        val progress = importer(localFiles = FakeLocalReader(null)).import(source).toList()

        assertEquals(AppError.NOT_FOUND, (progress.last() as ImportProgress.Failed).error)
        assertTrue(writer.aborted)
    }

    @Test
    fun `an xtream source imports through the category api`() = runBlocking {
        val source = PlaylistSource.Xtream("http://panel:8080", "user", "pass")

        val progress = importer(xtreamApi = FakeXtreamApi()).import(source).toList()

        assertEquals(2, writer.items.size)

        // Sorted by `providerOrder` rather than taken in arrival order.
        //
        // Phase C fetches categories concurrently, so the sequence in which rows reach
        // the writer is whatever the network returned first and asserting it would be
        // asserting nothing real. What the user actually sees is unchanged and is what
        // is checked here: `providerOrder` is derived from the category's position in
        // the provider's own list, so the catalogue still reads in the provider's order
        // -- which for live television is the channel numbering -- however the replies
        // happened to land.
        val inProviderOrder = writer.items.sortedBy { it.providerOrder }
        assertEquals(listOf("Nova Sports", "Radio Mars"), inProviderOrder.map { it.title })
        // The radio category is recognised as radio, not as live TV.
        assertEquals(MediaKind.RADIO, inProviderOrder.last().kind)
        assertEquals(2, (progress.last() as ImportProgress.Done).totalItems)

        val recorded = sources.get(SourceIds.of(source))!!.sync
        assertEquals(2, recorded.itemCount)
        assertNull("an xtream catalogue has no single validator", recorded.etag)
    }

    /**
     * The id must survive a password change, or a user fixing their credentials
     * loses every favourite.
     */
    @Test
    fun `the same provider keeps its id when the password changes`() {
        val first = SourceIds.of(PlaylistSource.Xtream("http://panel:8080", "user", "old"))
        val second = SourceIds.of(PlaylistSource.Xtream("panel:8080/", "user", "new"))

        assertEquals(first, second)
    }

    // ------------------------------------------------------------------ fixtures

    private fun playlist(entries: Int): String = buildString {
        appendLine("#EXTM3U")
        for (i in 0 until entries) {
            appendLine("""#EXTINF:-1 tvg-id="ch$i" group-title="Sports",Channel $i""")
            appendLine("http://host/live/u/p/$i.ts")
        }
    }

    private fun importer(
        localFiles: LocalPlaylistReader = FakeLocalReader(null),
        xtreamApi: XtreamImportEngine.Api = FakeXtreamApi(),
        writer: CatalogWriter = this.writer,
    ) = DefaultCatalogImporter(
        http = HttpStreamSource(
            OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build(),
        ),
        writerFactory = { writer },
        sources = sources,
        localFiles = localFiles,
        xtreamApiFactory = { xtreamApi },
        dispatchers = TestDispatchers,
        clock = { now },
        // Captured rather than sent to `android.util.Log`, which this module's unit tests
        // only have as stubs that throw.
        log = { logged += it },
    )

    /** The importer's one-line outcome. Exactly one per playlist import. */
    private fun outcome(): String = logged.single { it.startsWith("M3U import") }

    /**
     * Nothing from the address may reach the log: not the user, not the password, not
     * the host, not the path. A log is what a user pastes into a support message.
     */
    private fun assertNothingFrom(url: String) {
        val parsed = url.toHttpUrl()
        val secrets = listOfNotNull(
            parsed.queryParameter("username"),
            parsed.queryParameter("password"),
            parsed.host,
            parsed.encodedPath,
            "username",
            "password",
        )
        for (line in logged) {
            for (secret in secrets) {
                assertFalse("'$secret' reached the log: $line", line.contains(secret, ignoreCase = true))
            }
        }
    }

    private fun stored(id: String, etag: String?, items: Int) = ProviderSource(
        id = id,
        kind = SourceKind.M3U_URL,
        label = "test",
        url = server.url("/p.m3u").toString(),
        sync = SyncState(etag = etag, lastImportAtMs = now - 1, itemCount = items),
    )

    private companion object {
        /** The shape every panel hands out, with an account the log must never show. */
        const val PANEL_LINK = "/get.php?username=u7sam9&password=p4ss9w0rd&type=m3u_plus&output=ts"
    }

    private object TestDispatchers : AppDispatchers {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private class FakeLocalReader(private val content: String?) : LocalPlaylistReader {
        override fun open(uri: String): OpenedPlaylist? {
            val body = content ?: return null
            val hashing = com.castivio.data.networking.HashingInputStream(body.byteInputStream())
            return OpenedPlaylist(hashing.reader(Charsets.UTF_8), hashing)
        }
    }

    private class FakeXtreamApi : XtreamImportEngine.Api {
        override fun categories(kind: MediaKind): Reader = StringReader(
            when (kind) {
                MediaKind.LIVE -> """[{"category_id":"1","category_name":"Sports"},
                    {"category_id":"2","category_name":"RADIO"}]""".trimIndent()
                else -> "[]"
            },
        )

        override fun streams(kind: MediaKind, categoryId: String): Reader = StringReader(
            when (categoryId) {
                "1" -> """[{"stream_id":"11","name":"Nova Sports","category_id":"1"}]"""
                "2" -> """[{"stream_id":"21","name":"Radio Mars","category_id":"2"}]"""
                else -> "[]"
            },
        )

        override fun series(categoryId: String): Reader = StringReader("[]")
        override fun seriesInfo(seriesId: String): Reader = StringReader("{}")
        override fun streamUrl(kind: MediaKind, streamId: String, extension: String?): String =
            XtreamUrls.stream("http://panel:8080", "user", "pass", kind, streamId, extension)
    }

    private class RecordingWriter : CatalogWriter {
        val items = mutableListOf<CatalogItem>()
        val groups = mutableListOf<MediaGroup>()
        var finished: ImportSummary? = null
        var aborted = false

        override fun begin(sourceId: String, mode: ImportMode) = Unit
        override fun writeGroups(groups: List<MediaGroup>) { this.groups += groups }
        override fun writeItems(items: List<CatalogItem>) { this.items += items }
        override fun commit() = Unit
        override fun finish(summary: ImportSummary) { finished = summary }
        override fun abort(cause: Throwable?) { aborted = true }
    }

    /** A writer whose database refuses the rows, the way a constraint violation would. */
    private class ThrowingWriter : CatalogWriter {
        var aborted = false

        override fun begin(sourceId: String, mode: ImportMode) = Unit
        override fun writeGroups(groups: List<MediaGroup>) = Unit
        override fun writeItems(items: List<CatalogItem>) {
            throw IllegalStateException("the database refused the rows")
        }
        override fun commit() = Unit
        override fun finish(summary: ImportSummary) = Unit
        override fun abort(cause: Throwable?) {
            aborted = true
        }
    }

    private class FakeSourceRepository : SourceRepository {
        private val state = MutableStateFlow<Map<String, ProviderSource>>(emptyMap())

        override suspend fun register(source: PlaylistSource, label: String?): ProviderSource {
            val registered = ProviderSource(
                id = SourceIds.of(source),
                kind = SourceIds.kindOf(source),
                label = label ?: SourceIds.labelOf(source),
                url = null,
            )
            save(registered)
            return registered
        }

        override fun sources(): Flow<List<ProviderSource>> = state.map { it.values.toList() }
        override fun active(): Flow<ProviderSource?> = state.map { it.values.firstOrNull { s -> s.isActive } }
        override suspend fun activeNow(): ProviderSource? = state.value.values.firstOrNull { it.isActive }
        override suspend fun get(id: String): ProviderSource? = state.value[id]

        override suspend fun save(source: ProviderSource) {
            state.value = state.value + (source.id to source)
        }

        override suspend fun setActive(id: String) {
            state.value = state.value.mapValues { (key, value) -> value.copy(isActive = key == id) }
        }

        override suspend fun recordCatalogueImport(id: String, sync: SyncState) {
            val existing = state.value[id] ?: ProviderSource(id, SourceKind.M3U_URL, "test", null)
            state.value = state.value + (id to existing.copy(sync = sync))
        }

        override suspend fun recordEpgImport(id: String, atMs: Long) {
            val existing = state.value[id] ?: return
            state.value = state.value + (id to existing.copy(sync = existing.sync.copy(lastEpgImportAtMs = atMs)))
        }

        override suspend fun delete(id: String) {
            state.value = state.value - id
        }
    }
}
