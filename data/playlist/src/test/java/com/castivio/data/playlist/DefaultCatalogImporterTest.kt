package com.castivio.data.playlist

import com.castivio.core.common.AppDispatchers
import com.castivio.core.common.AppError
import com.castivio.data.networking.HttpStatusException
import com.castivio.data.networking.HttpStreamSource
import com.castivio.data.parsing.SourceIds
import com.castivio.data.parsing.XtreamImportEngine
import com.castivio.data.parsing.XtreamUrls
import com.castivio.core.platform.PerfSection
import com.castivio.domain.CatalogItem
import com.castivio.domain.CatalogWriter
import com.castivio.domain.FetchDiagnosis
import com.castivio.domain.FetchRoute
import com.castivio.domain.FetchStage
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
import com.castivio.domain.asPlaylistSource
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

    /** Every panel account an API was built for, in order. */
    private val accounts = mutableListOf<PlaylistSource.Xtream>()

    /** Every section import the importer said was starting, in order. */
    private val started = mutableListOf<PerfSection>()

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

    // ---------------------------------------------------- what the screen is told
    //
    // The same failure as the log line, as figures for the error screen: a viewer with a
    // television and no computer has no logcat. Five breakdowns used to reach that screen
    // as one sentence; each of these is one of them, told apart.

    @Test
    fun `a status the app has no name for is shown as the answer it was`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(458).setBody("blocked"))
        val source = PlaylistSource.M3u(server.url(PANEL_LINK).toString())

        val failed = importer().import(source).toList().last() as ImportProgress.Failed

        assertEquals(AppError.UNKNOWN, failed.error)
        assertEquals(
            FetchDiagnosis(
                FetchStage.ANSWER,
                httpStatus = 458,
                exception = null,
                bytesReceived = null,
                route = FetchRoute.M3U,
            ),
            failed.diagnosis,
        )
        assertNoSecretIn(failed.diagnosis)
    }

    @Test
    fun `a provider that cannot be reached is shown as no answer`() = runBlocking {
        val source = PlaylistSource.M3u("http://127.0.0.1:1$PANEL_LINK")

        val failed = importer().import(source).toList().last() as ImportProgress.Failed

        val diagnosis = failed.diagnosis!!
        assertEquals(FetchStage.CONNECT, diagnosis.stage)
        assertNull("nothing answered", diagnosis.httpStatus)
        assertEquals("ConnectException", diagnosis.exception)
        assertNull("no body was opened", diagnosis.bytesReceived)
        assertNoSecretIn(diagnosis)
    }

    /**
     * The link is never sent, and the exception that says so quotes it. The screen gets
     * the class and nothing of the message.
     */
    @Test
    fun `a link the http client will not send is shown as never sent`() = runBlocking {
        val source = PlaylistSource.M3u("htp://127.0.0.1$PANEL_LINK")

        val failed = importer().import(source).toList().last() as ImportProgress.Failed

        assertEquals(
            FetchDiagnosis(FetchStage.PREPARE, exception = "IllegalArgumentException", route = FetchRoute.M3U),
            failed.diagnosis,
        )
        assertNoSecretIn(failed.diagnosis)
    }

    @Test
    fun `a playlist cut off half way says how much was read and how much was kept`() = runBlocking {
        val body = playlist(2_500)
        server.enqueue(
            MockResponse()
                .setBody(body)
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        val source = PlaylistSource.M3u(server.url(PANEL_LINK).toString())

        val failed = importer().import(source).toList().last() as ImportProgress.Failed

        val diagnosis = failed.diagnosis!!
        assertEquals(FetchStage.READ, diagnosis.stage)
        assertEquals(200, diagnosis.httpStatus)
        assertTrue("an exception says why the read stopped", diagnosis.exception != null)
        val bytes = diagnosis.bytesReceived!!
        assertTrue("part of the body arrived: $bytes", bytes in 1 until body.length)
        // Half the file is about 1,250 entries: one batch of a thousand committed, the rest
        // read and rolled back with the batch in flight.
        assertEquals(1_000, diagnosis.itemsSaved)
        assertTrue("read ${diagnosis.itemsRead}", diagnosis.itemsRead in 1_001 until 2_500)
        assertNoSecretIn(diagnosis)
    }

    @Test
    fun `a playlist the database will not keep is shown as a saving failure`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/x-mpegurl").setBody(playlist(3)))
        val source = PlaylistSource.M3u(server.url(PANEL_LINK).toString())

        val failed = importer(writer = ThrowingWriter()).import(source).toList().last() as ImportProgress.Failed

        val diagnosis = failed.diagnosis!!
        assertEquals(FetchStage.SAVE, diagnosis.stage)
        assertEquals(200, diagnosis.httpStatus)
        assertEquals("IllegalStateException", diagnosis.exception)
        assertEquals("every entry was read", 3, diagnosis.itemsRead)
        assertEquals("none was kept", 0, diagnosis.itemsSaved)
        assertTrue(diagnosis.bytesReceived!! > 0)
        assertNoSecretIn(diagnosis)
    }

    // ------------------------------------------ a playlist read through its panel
    //
    // A `get.php?username=…&password=…` link names a panel account. The subscription stays
    // the playlist the user added -- its link, its id -- and is read through the panel's
    // API, one section at a time, with the playlist itself tried once when the API says no
    // for a reason that is not the network.

    @Test
    fun `a playlist that names a panel account is read through the api, under its own id`() = runBlocking {
        val link = server.url(PANEL_LINK).toString()
        val source = routed(link)

        val progress = importer().importKind(source, MediaKind.LIVE).toList()

        assertEquals("the playlist was never downloaded", 0, server.requestCount)
        assertEquals(
            listOf(PlaylistSource.Xtream(server.url("/").toString().trimEnd('/'), "u7sam9", "p4ss9w0rd")),
            accounts,
        )
        val playlistId = SourceIds.of(PlaylistSource.M3u(link))
        assertEquals(listOf(playlistId), writer.began.map { it.first })
        assertEquals(2, writer.items.size)
        assertTrue(progress.last() is ImportProgress.Done)
        val recorded = sources.get(playlistId)!!.sync
        assertNull("the api path never marks a playlist catalogue", recorded.contentHash)
        assertEquals(now, recorded.lastImportAtMs)
    }

    @Test
    fun `a playlist that names no account is downloaded as before`() = runBlocking {
        server.enqueue(MockResponse().setBody(playlist(3)))
        val source = routed(server.url("/lists/family.m3u").toString())

        val progress = importer().importKind(source, MediaKind.LIVE).toList()

        assertNull(source.xtream)
        assertEquals(1, server.requestCount)
        assertTrue("no panel was asked", accounts.isEmpty())
        assertEquals(3, (progress.last() as ImportProgress.Done).totalItems)
    }

    /**
     * What the link encoded, the API is sent encoded the same way: the decoded password
     * goes back through the encoder every panel request uses.
     */
    @Test
    fun `credentials with special characters reach the panel as the link encoded them`() = runBlocking {
        val encodedPassword = "p%40ss%20w%C3%B6rd%2B%26%23"
        val link = server.url("/get.php?username=sam&password=$encodedPassword&type=m3u_plus").toString()

        importer().importKind(routed(link), MediaKind.LIVE).toList()

        val account = accounts.single()
        assertEquals("p@ss wörd+&#", account.password)
        val asked = XtreamUrls.api(account.host, account.username, account.password, action = "get_live_categories")
        assertTrue(asked, asked.contains("password=$encodedPassword&"))
    }

    /**
     * Rows a cut-short playlist import left are keyed differently from the API's, so the
     * first section read through the panel replaces them; later sections append, or each
     * would prune the one before.
     */
    @Test
    fun `the first section read through the panel replaces, the next ones append`() = runBlocking {
        val link = server.url(PANEL_LINK).toString()
        sources.save(stored(SourceIds.of(PlaylistSource.M3u(link)), etag = null, items = 0).copy(url = link, sync = SyncState()))

        importer().importKind(routed(link), MediaKind.LIVE).toList()
        importer().importKind(routed(link), MediaKind.MOVIE).toList()

        assertEquals(listOf(ImportMode.REPLACE, ImportMode.APPEND), writer.began.map { it.second })
    }

    /**
     * The channels in one reply, for an Xtream account and — through the very same path —
     * for a playlist read through its panel, the conversion the user never sees. Films are
     * still asked for category by category: they are a later step.
     */
    @Test
    fun `channels are asked for in one reply, whichever way the subscription was added`() = runBlocking {
        val panel = OneReplyXtreamApi()
        val account = PlaylistSource.Xtream("http://panel:8080", "user", "pass")
        val link = server.url(PANEL_LINK).toString()

        importer(xtreamApi = panel).importKind(account, MediaKind.LIVE).toList()
        importer(xtreamApi = panel).importKind(routed(link), MediaKind.LIVE).toList()
        importer(xtreamApi = panel).importKind(account, MediaKind.MOVIE).toList()

        assertEquals(listOf(MediaKind.LIVE, MediaKind.LIVE), panel.wholeSectionAsks)
        assertTrue("a live category was asked for on its own", panel.categoryAsks.none { it.first == MediaKind.LIVE })
        assertEquals("two subscriptions, a channel and a station each", 4, writer.items.size)
        assertEquals(2, writer.items.count { it.kind == MediaKind.RADIO })
        assertTrue(logged.any { it.contains("LIVE: one reply, 2 rows") })
        assertNothingFrom(link)
    }

    @Test
    fun `a panel that refuses its api is followed by the playlist, once`() = runBlocking {
        val link = server.url(PANEL_LINK).toString()
        val id = SourceIds.of(PlaylistSource.M3u(link))
        sources.save(stored(id, etag = null, items = 0).copy(url = link, sync = SyncState()))
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/x-mpegurl").setBody(playlist(3)))
        val panel = FailingXtreamApi(HttpStatusException(403))

        val progress = importer(xtreamApi = panel).importKind(routed(link), MediaKind.LIVE).toList()

        assertEquals("one try at the api", 1, panel.calls)
        assertEquals("one try at the playlist", 1, server.requestCount)
        assertEquals(3, (progress.last() as ImportProgress.Done).totalItems)
        // The catalogue came from the file, so the subscription is read that way from now
        // on: the two readings key channels differently and must not be mixed.
        assertTrue(sources.get(id)!!.sync.contentHash!!.startsWith("crc32:"))
        assertNull((sources.get(id)!!.asPlaylistSource() as PlaylistSource.M3u).xtream)
        assertTrue(logged.toString(), logged.any { it.startsWith("Xtream import failed stage=ANSWER error=UNAUTHORIZED http=403") })
        assertNothingFrom(link)
    }

    /**
     * The panel delivered Live and refuses Films. Reading the playlist now would replace the
     * channels it delivered with rows keyed another way, and the favourites made on them
     * would point at nothing — so the refusal is reported as it is.
     */
    @Test
    fun `a panel that has delivered a section is not swapped for the playlist`() = runBlocking {
        val link = server.url(PANEL_LINK).toString()
        val id = SourceIds.of(PlaylistSource.M3u(link))
        sources.save(stored(id, etag = null, items = 2).copy(url = link, sync = SyncState(lastImportAtMs = now, itemCount = 2)))

        val failed = importer(xtreamApi = FailingXtreamApi(HttpStatusException(403)))
            .importKind(routed(link), MediaKind.MOVIE).toList().last() as ImportProgress.Failed

        assertEquals(AppError.UNAUTHORIZED, failed.error)
        assertEquals(FetchRoute.XTREAM, failed.diagnosis!!.route)
        assertEquals("the playlist was never asked", 0, server.requestCount)
        assertNull("still read through the panel", sources.get(id)!!.sync.contentHash)
    }

    /** The playlist is on the same server: asking it too would double the wait for nothing. */
    @Test
    fun `a panel that cannot be reached is not followed by the playlist`() = runBlocking {
        val link = server.url(PANEL_LINK).toString()
        val cases = listOf(
            java.net.ConnectException("failed to connect to $link") to AppError.NETWORK_UNAVAILABLE,
            java.net.SocketTimeoutException("timeout reading $link") to AppError.TIMEOUT,
        )

        for ((cause, error) in cases) {
            val failed = importer(xtreamApi = FailingXtreamApi(cause))
                .importKind(routed(link), MediaKind.LIVE).toList().last() as ImportProgress.Failed

            assertEquals(error, failed.error)
            val diagnosis = failed.diagnosis!!
            assertEquals(FetchRoute.XTREAM, diagnosis.route)
            assertEquals(FetchStage.CONNECT, diagnosis.stage)
            assertEquals(cause.javaClass.simpleName, diagnosis.exception)
            assertNull("no playlist after a network failure", diagnosis.after)
            assertNoSecretIn(diagnosis)
        }
        assertEquals("the playlist was never asked", 0, server.requestCount)
        assertNothingFrom(link)
    }

    @Test
    fun `when the playlist fails too, the failure says what both said`() = runBlocking {
        val link = server.url(PANEL_LINK).toString()
        server.enqueue(MockResponse().setResponseCode(458))

        val failed = importer(xtreamApi = FailingXtreamApi(HttpStatusException(403)))
            .importKind(routed(link), MediaKind.LIVE).toList().last() as ImportProgress.Failed

        val diagnosis = failed.diagnosis!!
        assertEquals(FetchRoute.M3U, diagnosis.route)
        assertEquals(FetchStage.ANSWER, diagnosis.stage)
        assertEquals(458, diagnosis.httpStatus)
        val panel = diagnosis.after!!
        assertEquals(FetchRoute.XTREAM, panel.route)
        assertEquals(FetchStage.ANSWER, panel.stage)
        assertEquals(403, panel.httpStatus)
        assertEquals(1, server.requestCount)
        assertNoSecretIn(diagnosis)
        assertNothingFrom(link)
    }

    /** The database is the same for both readings, so the playlist would fail the same way. */
    @Test
    fun `a database failure on the panel's path is not followed by the playlist`() = runBlocking {
        val link = server.url(PANEL_LINK).toString()

        val failed = importer(writer = ThrowingWriter())
            .importKind(routed(link), MediaKind.LIVE).toList().last() as ImportProgress.Failed

        assertEquals(FetchStage.SAVE, failed.diagnosis!!.stage)
        assertEquals(FetchRoute.XTREAM, failed.diagnosis!!.route)
        assertEquals("IllegalStateException", failed.diagnosis!!.exception)
        assertEquals(0, server.requestCount)
    }

    /**
     * An Xtream subscription's own refusal, which used to read as "no network": the status
     * travels in an IOException, and IOException was the first thing matched.
     */
    @Test
    fun `a refusal by an xtream panel reads as a refusal, not as no network`() = runBlocking {
        val source = PlaylistSource.Xtream("http://panel:8080", "user", "pass")

        val failed = importer(xtreamApi = FailingXtreamApi(HttpStatusException(401)))
            .importKind(source, MediaKind.LIVE).toList().last() as ImportProgress.Failed

        assertEquals(AppError.UNAUTHORIZED, failed.error)
        assertEquals(
            FetchDiagnosis(
                stage = FetchStage.ANSWER,
                httpStatus = 401,
                exception = "HttpStatusException",
                route = FetchRoute.XTREAM,
            ),
            failed.diagnosis,
        )
        assertEquals("an xtream subscription has no playlist to try", 0, server.requestCount)
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
     * Which way the streams came is part of the reading: the panel and the log both say
     * it, so a measurement taken category by category is never mistaken for one taken in
     * one reply, or the other way round.
     */
    @Test
    fun `an xtream import says which way its streams came`() = runBlocking {
        val source = PlaylistSource.Xtream("http://panel:8080", "user", "pass")

        importer(xtreamApi = FakeXtreamApi()).importKind(source, MediaKind.LIVE).toList()

        assertTrue(logged.joinToString("\n"), logged.any { it.contains("import LIVE: LIVE: asked per category") })
    }

    // ----------------------------------------------------------- the measurement build

    /**
     * **Every section import says it is starting.** That is what gives a load nobody pressed
     * for -- a section opened again after Refresh forgot its marks -- a run of its own on
     * the panel, instead of its numbers landing in the last one's finished report.
     */
    @Test
    fun `every section import says it is starting, as the section it is`() = runBlocking {
        val panel = PlaylistSource.Xtream("http://panel:8080", "user", "pass")

        importer(xtreamApi = OneReplyXtreamApi()).importKind(panel, MediaKind.LIVE).toList()
        importer(xtreamApi = OneReplyXtreamApi()).importKind(panel, MediaKind.RADIO).toList()
        importer(xtreamApi = FakeXtreamApi()).importKind(panel, MediaKind.MOVIE).toList()

        assertEquals(listOf(PerfSection.CHANNELS, PerfSection.RADIO, PerfSection.MOVIES), started)
    }

    /** Each pass over the streams reaches the log with its times, beside the way they came. */
    @Test
    fun `each pass of a section import is logged with its times`() = runBlocking {
        val panel = PlaylistSource.Xtream("http://panel:8080", "user", "pass")

        importer(xtreamApi = OneReplyXtreamApi()).importKind(panel, MediaKind.LIVE).toList()

        val passes = logged.filter { it.startsWith("import LIVE: PassTimes(") }
        assertEquals(logged.joinToString("\n"), 1, passes.size)
        assertTrue(passes.single().contains("oneReply=true"))
        assertTrue(passes.single().contains("requests=1"))
    }

    /**
     * The records are counted from the passes whose rows make the catalogue. A reply that
     * broke after handing a batch over was followed by a walk that wrote every row again;
     * counting both would put those rows in the reading twice.
     */
    @Test
    fun `a reply that broke halfway is not counted beside the walk that replaced it`() = runBlocking {
        val panel = PlaylistSource.Xtream("http://panel:8080", "user", "pass")

        importer(xtreamApi = BrokenReplyXtreamApi(streams = 1_200)).importKind(panel, MediaKind.LIVE).toList()

        val records = logged.single { it.startsWith("import LIVE: RecordCounts(") }
        assertTrue(records, records.contains("parsed=1200, filed=1200, orphans=0"))
        assertTrue(records, records.contains("written={LIVE=1200}"))
    }

    /** A reply that arrived whole is the catalogue, and its orphans are counted with it. */
    @Test
    fun `a reply that arrived whole is counted, orphans and all`() = runBlocking {
        val panel = PlaylistSource.Xtream("http://panel:8080", "user", "pass")

        importer(xtreamApi = OneReplyXtreamApi(orphans = 3)).importKind(panel, MediaKind.LIVE).toList()

        val records = logged.single { it.startsWith("import LIVE: RecordCounts(") }
        assertTrue(records, records.contains("parsed=5, filed=2, orphans=3"))
        assertTrue(records, records.contains("orphanCategories=[(9, 3)]"))
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
        xtreamApiFactory = { account ->
            accounts += account
            xtreamApi
        },
        dispatchers = TestDispatchers,
        clock = { now },
        // Captured rather than sent to `android.util.Log`, which this module's unit tests
        // only have as stubs that throw.
        log = { logged += it },
        // Watched rather than handed to the stopwatch, for the same reason: its clock is
        // Android's.
        importStarted = { started += it },
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

    /**
     * The subscription as the section loader would hand it over: a stored playlist row,
     * read back by `asPlaylistSource`, which decides whether it is read through a panel.
     */
    private fun routed(link: String): PlaylistSource.M3u = ProviderSource(
        id = SourceIds.of(PlaylistSource.M3u(link)),
        kind = SourceKind.M3U_URL,
        label = "Playlist 1",
        url = link,
    ).asPlaylistSource() as PlaylistSource.M3u

    /** The screen's figures are photographed and sent on, so the account is never in them. */
    private fun assertNoSecretIn(diagnosis: FetchDiagnosis?) {
        val shown = diagnosis.toString()
        for (secret in listOf("u7sam9", "p4ss9w0rd", "get.php", "127.0.0.1", "username", "password")) {
            assertFalse("'$secret' reached the screen: $shown", shown.contains(secret, ignoreCase = true))
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

    /**
     * [FakeXtreamApi]'s panel, answering the live section in one reply and keeping count.
     *
     * The counts are behind a lock: the engine asks for categories from several workers at
     * once, and a plain list loses an entry when two of them add at the same moment.
     */
    private class OneReplyXtreamApi(
        /** Streams in the reply naming category 9, which the panel does not list. */
        private val orphans: Int = 0,
    ) : XtreamImportEngine.Api {
        private val panel = FakeXtreamApi()
        val wholeSectionAsks: MutableList<MediaKind> = java.util.Collections.synchronizedList(mutableListOf())
        val categoryAsks: MutableList<Pair<MediaKind, String>> = java.util.Collections.synchronizedList(mutableListOf())

        override fun categories(kind: MediaKind): Reader = panel.categories(kind)

        override fun streams(kind: MediaKind, categoryId: String): Reader {
            categoryAsks += kind to categoryId
            return panel.streams(kind, categoryId)
        }

        override fun allStreams(kind: MediaKind): Reader? {
            wholeSectionAsks += kind
            val hidden = (1..orphans).joinToString("") { """,{"stream_id":"9$it","name":"Hidden $it","category_id":"9"}""" }
            return StringReader(
                """[{"stream_id":"21","name":"Radio Mars","category_id":"2"},""" +
                    """{"stream_id":"11","name":"Nova Sports","category_id":"1"}$hidden]""",
            )
        }

        override fun series(categoryId: String): Reader = panel.series(categoryId)
        override fun seriesInfo(seriesId: String): Reader = panel.seriesInfo(seriesId)
        override fun streamUrl(kind: MediaKind, streamId: String, extension: String?): String =
            panel.streamUrl(kind, streamId, extension)
    }

    /**
     * One category of [streams] channels, whose reply breaks a little before its end: after
     * the engine has handed a full batch of it over, so the walk that follows writes those
     * rows a second time.
     */
    private class BrokenReplyXtreamApi(streams: Int) : XtreamImportEngine.Api {
        private val list = (1..streams).joinToString(",", "[", "]") { """{"stream_id":"$it","name":"Channel $it","category_id":"1"}""" }

        override fun categories(kind: MediaKind): Reader =
            StringReader(if (kind == MediaKind.LIVE) """[{"category_id":"1","category_name":"Sports"}]""" else "[]")

        override fun streams(kind: MediaKind, categoryId: String): Reader = StringReader(list)

        override fun allStreams(kind: MediaKind): Reader = object : Reader() {
            private val delegate = StringReader(list)
            private val breakAfter = list.length * 9 / 10
            private var served = 0

            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                if (served >= breakAfter) throw java.io.IOException("connection reset")
                val read = delegate.read(cbuf, off, minOf(len, breakAfter - served))
                if (read > 0) served += read
                return read
            }

            override fun close() = delegate.close()
        }

        override fun series(categoryId: String): Reader = StringReader("[]")
        override fun seriesInfo(seriesId: String): Reader = StringReader("{}")
        override fun streamUrl(kind: MediaKind, streamId: String, extension: String?): String =
            XtreamUrls.stream("http://panel:8080", "user", "pass", kind, streamId, extension)
    }

    /** A panel whose API throws [failure] on every call, and counts them. */
    private class FailingXtreamApi(private val failure: Exception) : XtreamImportEngine.Api {
        var calls = 0

        override fun categories(kind: MediaKind): Reader = fail()
        override fun streams(kind: MediaKind, categoryId: String): Reader = fail()
        override fun series(categoryId: String): Reader = fail()
        override fun seriesInfo(seriesId: String): Reader = fail()
        override fun streamUrl(kind: MediaKind, streamId: String, extension: String?): String =
            "http://panel/$streamId"

        private fun fail(): Nothing {
            calls++
            throw failure
        }
    }

    private class RecordingWriter : CatalogWriter {
        val items = mutableListOf<CatalogItem>()
        val groups = mutableListOf<MediaGroup>()
        var finished: ImportSummary? = null
        var aborted = false

        /** Every import begun, with the source id and the mode it was written in. */
        val began = mutableListOf<Pair<String, ImportMode>>()

        override fun begin(sourceId: String, mode: ImportMode) {
            began += sourceId to mode
        }
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
