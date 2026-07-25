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
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
        assertEquals(listOf("Nova Sports", "Radio Mars"), writer.items.map { it.title })
        // The radio category is recognised as radio, not as live TV.
        assertEquals(MediaKind.RADIO, writer.items.last().kind)
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
    )

    private fun stored(id: String, etag: String?, items: Int) = ProviderSource(
        id = id,
        kind = SourceKind.M3U_URL,
        label = "test",
        url = server.url("/p.m3u").toString(),
        sync = SyncState(etag = etag, lastImportAtMs = now - 1, itemCount = items),
    )

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

    private class FakeSourceRepository : SourceRepository {
        private val state = MutableStateFlow<Map<String, ProviderSource>>(emptyMap())

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
