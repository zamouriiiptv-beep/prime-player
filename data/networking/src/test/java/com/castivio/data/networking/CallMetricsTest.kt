package com.castivio.data.networking

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * The instrument, checked before anything is measured with it.
 *
 * A counter nobody has tested is a number nobody should quote, and this one exists
 * specifically to produce a figure — how many requests a section costs — that the whole
 * of Phase A had to leave as an unknown. If it counts wrong, the answer is worse than no
 * answer, because it looks like evidence.
 *
 * Real HTTP against `MockWebServer` rather than calling [CallMetrics.record] directly:
 * what is being verified is that OkHttp's events arrive and are attributed, which a
 * direct call would assume rather than test.
 */
class CallMetricsTest {

    private lateinit var server: MockWebServer

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

    private fun client() = HttpClientProvider.create(
        eventListenerFactory = CallMetricsListener.FACTORY,
    )

    private fun get(path: String) {
        client().newCall(okhttp3.Request.Builder().url(server.url(path)).build())
            .execute().use { it.body?.string() }
    }

    /**
     * **The number Phase A could not produce.**
     *
     * One categories call and three stream calls is the `1 + N` shape of an Xtream
     * section import, and the tally has to separate them by action — a single total
     * would say "4 requests" without saying that three of them were the loop.
     */
    @Test
    fun `counts each action separately`() {
        repeat(4) { server.enqueue(MockResponse().setBody("[]")) }

        get("/player_api.php?username=u&password=p&action=get_live_categories")
        repeat(3) { i ->
            get("/player_api.php?username=u&password=p&action=get_live_streams&category_id=$i")
        }

        val byAction = CallMetrics.snapshot().associateBy { it.action }
        assertEquals("total calls", 4L, CallMetrics.totalCalls())
        assertEquals("categories", 1L, byAction.getValue("get_live_categories").count)
        assertEquals("streams", 3L, byAction.getValue("get_live_streams").count)
    }

    /**
     * **The wait for an answer, apart from the answer arriving.**
     *
     * A call does not end until its body has been read, so its time is the server's wait
     * plus the transfer plus whatever reads the body. The wait is the part a request costs
     * merely by being made, which is what fewer requests saves; it has to be measurable
     * apart, and it can never exceed the call it is part of.
     */
    @Test
    fun `the wait for headers is measured apart from the body`() {
        server.enqueue(
            MockResponse()
                .setHeadersDelay(150, TimeUnit.MILLISECONDS)
                .setBody("[" + "0,".repeat(20_000) + "0]")
                .throttleBody(16_384, 40, TimeUnit.MILLISECONDS),
        )

        get("/player_api.php?username=u&password=p&action=get_live_streams")

        val wait = CallMetrics.waitTimeMs()
        val whole = CallMetrics.callTimeMs()
        assertTrue("the headers' delay was not counted: $wait ms", wait >= 140)
        assertTrue("the body's transfer was counted as waiting: $wait of $whole ms", wait < whole)
    }

    /**
     * **No credential ever reaches a counter, a log line or a report.**
     *
     * This object's entire purpose is to be printed, so the rule is not a precaution.
     * An Xtream URL carries the username and the password in its query string, and a
     * tally keyed by URL would put both in logcat on every import.
     */
    @Test
    fun `never records a credential or an address`() {
        server.enqueue(MockResponse().setBody("[]"))
        get("/player_api.php?username=hunter2&password=s3cret&action=get_vod_streams&category_id=9")

        val text = CallMetrics.report("audit").joinToString("\n") + CallMetrics.snapshot().joinToString("\n")
        for (secret in listOf("hunter2", "s3cret", server.hostName, "player_api.php", "category_id")) {
            assertFalse("`$secret` reached the report:\n$text", text.contains(secret))
        }
        assertEquals("get_vod_streams", CallMetrics.snapshot().single().action)
    }

    /** The labels, including the two that are not an `action` at all. */
    @Test
    fun `labels every kind of request this app makes`() {
        assertEquals(
            "get_series_info",
            CallMetrics.label("http://h/player_api.php?username=u&password=p&action=get_series_info&series_id=4".toHttpUrl()),
        )
        assertEquals(
            "xmltv",
            CallMetrics.label("http://h/xmltv.php?username=u&password=p".toHttpUrl()),
        )
        assertEquals(
            "xtream-account",
            CallMetrics.label("http://h/player_api.php?username=u&password=p".toHttpUrl()),
        )
        // A plain M3U link: no action, no username, nothing to say but "other".
        assertEquals("other", CallMetrics.label("http://h/list.m3u".toHttpUrl()).also { })
    }

    /**
     * **Sequential and concurrent request runs are told apart.**
     *
     * The specific claim Phase A made about the Xtream import — that its category loop
     * is serial — and the specific thing it could not prove. The overlap factor is the
     * sum of the call durations over the wall-clock span they occupy: at or near 1.0 the
     * requests were serial, and well above it they overlapped.
     *
     * Asserted in both directions, because a metric that only ever reports "sequential"
     * would also report it for genuinely parallel work, and would then be used as
     * evidence that a parallelising change had not worked.
     */
    @Test
    fun `distinguishes serial requests from overlapped ones`() {
        val delayMs = 120L

        repeat(3) { server.enqueue(MockResponse().setBody("[]").setBodyDelay(delayMs, TimeUnit.MILLISECONDS)) }
        repeat(3) { get("/player_api.php?action=get_live_streams&category_id=$it") }
        val serial = overlapFactor()

        CallMetrics.reset()
        server.shutdown()
        server = MockWebServer()
        server.start()

        repeat(3) { server.enqueue(MockResponse().setBody("[]").setBodyDelay(delayMs, TimeUnit.MILLISECONDS)) }
        val shared = client()
        val done = CountDownLatch(3)
        repeat(3) { i ->
            Thread {
                shared.newCall(
                    okhttp3.Request.Builder()
                        .url(server.url("/player_api.php?action=get_live_streams&category_id=$i"))
                        .build(),
                ).execute().use { it.body?.string() }
                done.countDown()
            }.start()
        }
        assertTrue("the parallel calls did not finish", done.await(30, TimeUnit.SECONDS))
        val parallel = overlapFactor()

        assertTrue("serial run reported an overlap of $serial", serial < 1.6)
        assertTrue("parallel run reported an overlap of $parallel", parallel > serial)
    }

    private fun overlapFactor(): Double {
        val sum = CallMetrics.snapshot().sumOf { it.totalMs }
        val span = CallMetrics.spanMs()
        return if (span <= 0) 0.0 else sum / span
    }

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        return out.toByteArray()
    }

    private val json = "[" + "{\"stream_id\":\"1\",\"name\":\"Channel\"},".repeat(2_000) + "{}]"

    /**
     * **Is the provider compressing?** The listener sees the headers before the bridge unpacks the body and drops
     * them, so the encoding, the declared length and the bytes that crossed the wire are the compressed ones --
     * and the client is shown to have asked.
     */
    @Test
    fun `a gzip answer is seen as one, with the size that crossed the wire`() {
        val packed = gzip(json)
        server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody(Buffer().write(packed)))

        get("/player_api.php?username=u&password=p&action=get_live_streams")

        val tally = CallMetrics.snapshot().single()
        val wire = tally.wireFigures()!!
        assertEquals(listOf("gzip"), wire.encodings)
        assertEquals(1, wire.encodedCalls)
        assertEquals(listOf("gzip"), wire.asked)
        assertEquals(listOf("http/1.1"), wire.protocols)
        assertEquals(1, wire.declaredCalls)
        assertEquals(packed.size.toLong(), wire.declaredBytes)
        assertEquals("the byte count is the wire's, not the unpacked", packed.size.toLong(), tally.byteCount)
        assertTrue("it is smaller than what it unpacks to", packed.size < json.length)
    }

    @Test
    fun `an answer with no encoding is seen as plain`() {
        server.enqueue(MockResponse().setBody(json))

        get("/player_api.php?username=u&password=p&action=get_live_streams")

        val wire = CallMetrics.snapshot().single().wireFigures()!!
        assertTrue(wire.encodings.isEmpty())
        assertEquals(0, wire.encodedCalls)
        assertEquals(listOf("gzip"), wire.asked)
        assertEquals(json.length.toLong(), wire.declaredBytes)
    }

    @Test
    fun `a chunked answer declares no length`() {
        server.enqueue(MockResponse().setChunkedBody(json, 4_096))

        get("/player_api.php?username=u&password=p&action=get_live_streams")

        val wire = CallMetrics.snapshot().single().wireFigures()!!
        assertEquals(0, wire.declaredCalls)
        assertEquals(0L, wire.declaredBytes)
    }

    /** Where the time goes: the provider thinking is the gap before the first byte, apart from the body. */
    @Test
    fun `the provider's thinking time is the first byte, apart from the transfer`() {
        server.enqueue(
            MockResponse()
                .setHeadersDelay(200, TimeUnit.MILLISECONDS)
                .setBody("[" + "0,".repeat(20_000) + "0]")
                .throttleBody(8_192, 30, TimeUnit.MILLISECONDS),
        )

        get("/player_api.php?username=u&password=p&action=get_live_streams")

        val wire = CallMetrics.snapshot().single().wireFigures()!!
        assertTrue("first byte ${wire.firstByteMs} ms", wire.firstByteMs >= 190)
        assertTrue("body ${wire.transferMs} ms", wire.transferMs >= 100)
        assertTrue("the whole call is not less than its parts", CallMetrics.callTimeMs() >= wire.firstByteMs)
    }

    /** Only the answers are transport; a call that failed before any header has none to report. */
    @Test
    fun `a call that never got an answer has no wire figures`() {
        // A port nothing listens on: refused at once, before there is a header to read.
        runCatching {
            client().newCall(
                okhttp3.Request.Builder()
                    .url("http://127.0.0.1:1/player_api.php?username=u&password=p&action=get_live_streams")
                    .build(),
            ).execute().close()
        }

        assertEquals(null, CallMetrics.snapshot().single().wireFigures())
    }

    /** The report is printed, so it holds what the wire lines hold and none of what they must not. */
    @Test
    fun `the printed report carries the wire lines and still no credential`() {
        server.enqueue(MockResponse().setBody(json))
        get("/player_api.php?username=hunter2&password=s3cret&action=get_live_streams")

        val text = CallMetrics.report("audit").joinToString("\n")

        assertTrue(text, "wire: http/1.1 · asked gzip" in text)
        assertTrue(text, "wire time (s): " in text)
        for (secret in listOf("hunter2", "s3cret", server.hostName, "player_api.php")) {
            assertFalse("`$secret` reached the report:\n$text", text.contains(secret))
        }
    }
}
