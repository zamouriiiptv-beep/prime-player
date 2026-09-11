package com.castivio.data.networking

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
}
