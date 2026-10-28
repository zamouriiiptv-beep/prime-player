package com.castivio.data.networking

import com.castivio.domain.MediaKind
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The meter on an Xtream reply's body, against a real server.
 *
 * Two promises: what the parser is handed is the same with the meter as without it, and what the meter says about
 * the body is true -- how big it was once unpacked, whether it was the provider or the app that unpacked it, and
 * that the count is the wire's own and not the unpacked one.
 */
class XtreamMeteringTest {

    private lateinit var server: MockWebServer

    private val json = "[" + """{"stream_id":"1","name":"Channel","category_id":"3"},""".repeat(3_000) + "{}]"

    @Before
    fun setUp() {
        CallMetrics.reset()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        CallMetrics.reset()
    }

    private fun api(metered: Boolean) = XtreamHttpApi(
        client = HttpClientProvider.create(eventListenerFactory = CallMetricsListener.FACTORY),
        base = server.url("/").toString().trimEnd('/'),
        username = "sam",
        password = "hunter2",
        metered = { metered },
    )

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        return out.toByteArray()
    }

    private fun tally() = CallMetrics.snapshot().single { it.action == "get_live_streams" }

    @Test
    fun `the parser is handed the same text with the meter as without it`() {
        server.enqueue(MockResponse().setBody(json))
        server.enqueue(MockResponse().setBody(json))

        val plain = api(metered = false).allStreams(MediaKind.LIVE)!!.use { it.readText() }
        val seen = api(metered = true).allStreams(MediaKind.LIVE)!!.use { it.readText() }

        assertEquals(json, plain)
        assertEquals(json, seen)
    }

    @Test
    fun `an unmetered reply claims no decoded size`() {
        server.enqueue(MockResponse().setBody(json))

        api(metered = false).allStreams(MediaKind.LIVE)!!.use { it.readText() }

        val wire = tally().wireFigures()!!
        assertEquals(0, wire.metered)
        assertEquals(0L, wire.decodedBytes)
        assertEquals(0L, wire.blockedMs)
    }

    @Test
    fun `a provider that compresses is seen as the one that unpacked it`() {
        val packed = gzip(json)
        server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody(Buffer().write(packed)))

        val text = api(metered = true).allStreams(MediaKind.LIVE)!!.use { it.readText() }

        assertEquals(json, text)
        val tally = tally()
        val wire = tally.wireFigures()!!
        assertEquals(1, wire.metered)
        assertEquals(json.toByteArray().size.toLong(), wire.decodedBytes)
        assertEquals(1, wire.encodedCalls)
        assertEquals(0, wire.sniffedGzipCalls)
        assertEquals("the wire's count is the packed size", packed.size.toLong(), tally.byteCount)
        assertTrue("and the packed size is smaller", tally.byteCount < wire.decodedBytes)
    }

    @Test
    fun `gzip with no header is unpacked by the app and said to be`() {
        val packed = gzip(json)
        server.enqueue(MockResponse().setBody(Buffer().write(packed)))

        val text = api(metered = true).allStreams(MediaKind.LIVE)!!.use { it.readText() }

        assertEquals(json, text)
        val wire = tally().wireFigures()!!
        assertEquals(1, wire.sniffedGzipCalls)
        assertEquals(0, wire.encodedCalls)
        assertEquals(json.toByteArray().size.toLong(), wire.decodedBytes)
    }

    @Test
    fun `a provider that does not compress has the same bytes on the wire and decoded`() {
        server.enqueue(MockResponse().setBody(json))

        api(metered = true).allStreams(MediaKind.LIVE)!!.use { it.readText() }

        val tally = tally()
        assertEquals(tally.byteCount, tally.wireFigures()!!.decodedBytes)
        assertEquals(0, tally.wireFigures()!!.encodedCalls)
    }

    @Test
    fun `a reader that closes early still reports what it was given`() {
        server.enqueue(MockResponse().setBody(json))

        api(metered = true).allStreams(MediaKind.LIVE)!!.use { it.read(CharArray(100)) }

        val wire = tally().wireFigures()!!
        assertEquals(1, wire.metered)
        assertTrue("some, and not all: ${wire.decodedBytes}", wire.decodedBytes in 1 until json.length)
    }
}
