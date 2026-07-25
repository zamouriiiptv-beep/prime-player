package com.castivio.data.networking

import com.castivio.core.common.AppError
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Against a real server, because none of this is verifiable by inspection:
 * conditional requests, gzip bodies, status mapping and stream hashing are all
 * behaviour that only shows up on the wire.
 */
class HttpStreamSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: HttpStreamSource

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        source = HttpStreamSource(
            OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a playlist streams through with its validators`() {
        server.enqueue(
            MockResponse()
                .setBody("#EXTM3U\n#EXTINF:-1,Nova\nhttp://host/1.ts\n")
                .setHeader("ETag", "\"abc123\"")
                .setHeader("Last-Modified", "Wed, 22 Jul 2026 10:00:00 GMT"),
        )

        val result = source.stream(RemoteRequest(server.url("/playlist.m3u").toString())) { it.readText() }

        val success = result as RemoteResult.Success
        assertTrue(success.value.startsWith("#EXTM3U"))
        assertEquals("\"abc123\"", success.etag)
        assertEquals("Wed, 22 Jul 2026 10:00:00 GMT", success.lastModified)
        assertTrue(success.contentHash.startsWith("crc32:"))
    }

    /**
     * The most valuable behaviour in the sync layer: a 100 MB download becomes a
     * 200-byte response when the provider says nothing changed.
     */
    @Test
    fun `a stored etag turns a refresh into a not-modified`() {
        server.enqueue(MockResponse().setResponseCode(304))

        val result = source.stream(
            RemoteRequest(server.url("/playlist.m3u").toString(), etag = "\"abc123\""),
        ) { it.readText() }

        assertEquals(RemoteResult.NotModified, result)
        assertEquals("\"abc123\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun `a stored last-modified is sent when there is no etag`() {
        server.enqueue(MockResponse().setResponseCode(304))

        source.stream(
            RemoteRequest(server.url("/p.m3u").toString(), lastModified = "Wed, 22 Jul 2026 10:00:00 GMT"),
        ) { it.readText() }

        val request = server.takeRequest()
        assertEquals("Wed, 22 Jul 2026 10:00:00 GMT", request.getHeader("If-Modified-Since"))
    }

    /**
     * Guides are usually served as `.gz` files, which is a gzip *file* rather than
     * a gzipped *transfer* — providers mislabel both, so the magic bytes decide.
     */
    @Test
    fun `a gzipped body is decompressed regardless of headers`() {
        val xml = "<tv><programme title=\"Cup Final\"/></tv>"
        server.enqueue(
            MockResponse()
                .setBody(gzip(xml))
                // Deliberately not Content-Encoding: this is a .gz file being
                // served, exactly as real providers do it.
                .setHeader("Content-Type", "application/octet-stream"),
        )

        val result = source.stream(RemoteRequest(server.url("/epg.xml.gz").toString())) { it.readText() }

        assertEquals(xml, (result as RemoteResult.Success).value)
    }

    @Test
    fun `plain bodies are untouched`() {
        server.enqueue(MockResponse().setBody("not gzip at all"))

        val result = source.stream(RemoteRequest(server.url("/plain").toString())) { it.readText() }

        assertEquals("not gzip at all", (result as RemoteResult.Success).value)
    }

    /**
     * The hash is what lets a provider with no validators still skip an import.
     */
    @Test
    fun `the content hash changes only when the content does`() {
        server.enqueue(MockResponse().setBody("#EXTM3U\nA\n"))
        server.enqueue(MockResponse().setBody("#EXTM3U\nA\n"))
        server.enqueue(MockResponse().setBody("#EXTM3U\nB\n"))
        val url = server.url("/p.m3u").toString()

        val first = source.stream(RemoteRequest(url)) { it.readText() } as RemoteResult.Success
        val same = source.stream(RemoteRequest(url)) { it.readText() } as RemoteResult.Success
        val changed = source.stream(RemoteRequest(url)) { it.readText() } as RemoteResult.Success

        assertEquals(first.contentHash, same.contentHash)
        assertNotEquals(first.contentHash, changed.contentHash)
    }

    @Test
    fun `statuses map to errors a login screen can explain`() {
        val cases = mapOf(
            401 to AppError.UNAUTHORIZED,
            403 to AppError.UNAUTHORIZED,
            404 to AppError.NOT_FOUND,
            429 to AppError.TIMEOUT,
            500 to AppError.SERVER_ERROR,
            503 to AppError.SERVER_ERROR,
        )

        for ((status, expected) in cases) {
            server.enqueue(MockResponse().setResponseCode(status))
            val result = source.stream(RemoteRequest(server.url("/p.m3u").toString())) { it.readText() }
            val failure = result as RemoteResult.Failure
            assertEquals("status $status", expected, failure.error)
            assertEquals(status, failure.statusCode)
        }
    }

    @Test
    fun `a timeout is reported as a timeout, not as a generic failure`() {
        server.enqueue(
            MockResponse()
                .setBody("#EXTM3U")
                .throttleBody(1, 5, TimeUnit.SECONDS),
        )

        val result = source.stream(RemoteRequest(server.url("/slow.m3u").toString())) { it.readText() }

        assertEquals(AppError.TIMEOUT, (result as RemoteResult.Failure).error)
    }

    @Test
    fun `an unreachable host is a network failure`() {
        val result = source.stream(RemoteRequest("http://localhost:1/never")) { it.readText() }

        assertEquals(AppError.NETWORK_UNAVAILABLE, (result as RemoteResult.Failure).error)
    }

    @Test
    fun `a url that is not a url fails without throwing`() {
        val result = source.stream(RemoteRequest("this is not a url")) { it.readText() }

        assertTrue(result is RemoteResult.Failure)
    }

    @Test
    fun `the user agent is sent when a provider needs one`() {
        server.enqueue(MockResponse().setBody("#EXTM3U"))

        source.stream(
            RemoteRequest(server.url("/p.m3u").toString(), userAgent = "Castivio/1.0"),
        ) { it.readText() }

        assertEquals("Castivio/1.0", server.takeRequest().getHeader("User-Agent"))
    }

    @Test
    fun `a change probe asks the server and reads almost nothing`() {
        server.enqueue(MockResponse().setBody("#EXTM3U\n" + "x".repeat(100_000)))

        val result = source.hasChanged(RemoteRequest(server.url("/p.m3u").toString(), noCache = true))

        assertTrue(result is RemoteResult.Success)
        val request = server.takeRequest()
        assertEquals("no-cache", request.getHeader("Cache-Control"))
        assertEquals("bytes=0-4095", request.getHeader("Range"))
    }

    @Test
    fun `a probe against unchanged content reports not modified`() {
        server.enqueue(MockResponse().setResponseCode(304))

        val result = source.hasChanged(
            RemoteRequest(server.url("/p.m3u").toString(), etag = "\"v1\"", noCache = true),
        )

        assertEquals(RemoteResult.NotModified, result)
    }

    @Test
    fun `the stream is closed even when the consumer throws`() {
        server.enqueue(MockResponse().setBody("#EXTM3U\nA\n"))

        val thrown = runCatching {
            source.stream(RemoteRequest(server.url("/p.m3u").toString())) {
                error("consumer failed")
            }
        }

        assertTrue(thrown.isFailure)
        // A leaked body holds a socket; a second request proves the pool recovered.
        server.enqueue(MockResponse().setBody("#EXTM3U\nB\n"))
        val next = source.stream(RemoteRequest(server.url("/p.m3u").toString())) { it.readText() }
        assertTrue(next is RemoteResult.Success)
    }

    private fun gzip(content: String): Buffer {
        val result = Buffer()
        GzipSink(result).buffer().use { it.writeUtf8(content) }
        return result
    }
}
