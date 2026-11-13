package com.castivio.data.networking

import com.castivio.core.common.artwork.ArtworkHost
import com.castivio.core.common.artwork.ArtworkTally
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What the image loader's calls did, as the listener tells it, against a real server.
 *
 * A counter nobody has tested is a number nobody should quote, and this one exists to say why a wall of posters is empty: it
 * has to tell a refusal from a timeout from a road that failed from a card that was scrolled away. Each is made to happen
 * here, on a tally of the test's own.
 */
class ArtworkListenerTest {

    private lateinit var server: MockWebServer
    private val tally = ArtworkTally()

    @Before
    fun setUp() {
        CallMetrics.forgetHosts()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        CallMetrics.forgetHosts()
    }

    private fun client(callTimeoutMs: Long = 0L): OkHttpClient = OkHttpClient.Builder()
        .eventListenerFactory { ArtworkListener(tally) }
        .apply { if (callTimeoutMs > 0) callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS) }
        .build()

    private fun get(client: OkHttpClient, path: String = "/p.jpg"): ByteArray? =
        client.newCall(Request.Builder().url(server.url(path)).build()).execute().use { it.body?.bytes() }

    // ------------------------------------------------------------------ how a call ended

    @Test
    fun `a picture that came is counted with its bytes`() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(5_000))))

        get(client())

        val figures = tally.figures()
        assertEquals(1, figures.started)
        assertEquals(1, figures.ok)
        assertEquals(0, figures.inFlight)
        assertEquals(5_000L, figures.bytes)
        assertEquals(1, figures.bodies)
        assertTrue(figures.httpFailures.isEmpty())
    }

    @Test
    fun `a refusal is counted by its status, and is not a picture`() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(503))

        repeat(3) { get(client()) }

        val figures = tally.figures()
        assertEquals(mapOf(404 to 2L, 503 to 1L), figures.httpFailures)
        assertEquals(0, figures.ok)
        assertEquals(0, figures.inFlight)
    }

    @Test
    fun `a call whose time ran out is a timeout`() {
        server.enqueue(MockResponse().setBody("late").setHeadersDelay(1, TimeUnit.SECONDS))

        val failure = runCatching { get(client(callTimeoutMs = 150L)) }.exceptionOrNull()

        assertTrue("$failure", failure is InterruptedIOException)
        val figures = tally.figures()
        assertEquals(1, figures.timeouts)
        assertEquals(0, figures.network)
        assertEquals(0, figures.cancelled)
        assertEquals(0, figures.inFlight)
    }

    @Test
    fun `a host that stops sending is a timeout too, whatever the limit that ran out`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val patientForTheWholeCall = OkHttpClient.Builder()
            .eventListenerFactory { ArtworkListener(tally) }
            .readTimeout(150, TimeUnit.MILLISECONDS)
            .build()

        val failure = runCatching { get(patientForTheWholeCall) }.exceptionOrNull()

        assertTrue("$failure", failure is InterruptedIOException)
        assertEquals(1, tally.figures().timeouts)
        assertEquals("not a card that left the screen", 0, tally.figures().cancelled)
    }

    @Test
    fun `a call timeout is not mistaken for a card that left the screen, though OkHttp ends it by cancelling it`() {
        server.enqueue(MockResponse().setBody("late").setHeadersDelay(1, TimeUnit.SECONDS))
        val call = client(callTimeoutMs = 150L).newCall(Request.Builder().url(server.url("/p.jpg")).build())

        runCatching { call.execute().close() }

        assertTrue("OkHttp's timer cancelled the call, which is why a cancelled call cannot be told from a timeout by that", call.isCanceled())
        assertEquals(1, tally.figures().timeouts)
        assertEquals(0, tally.figures().cancelled)
    }

    @Test
    fun `a road that failed is a network failure`() {
        val url = server.url("/p.jpg")
        server.shutdown()

        val failure = runCatching {
            client().newCall(Request.Builder().url(url).build()).execute().use { it.body?.bytes() }
        }.exceptionOrNull()

        assertTrue("$failure", failure is IOException)
        val figures = tally.figures()
        assertEquals(1, figures.network)
        assertEquals(0, figures.timeouts)
        assertEquals(0, figures.inFlight)
    }

    @Test
    fun `a call that nobody wants any more is cancelled, which is not a failure of the road or of the host`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val ended = CountDownLatch(1)
        val call = client().newCall(Request.Builder().url(server.url("/p.jpg")).build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = ended.countDown()
            override fun onResponse(call: Call, response: Response) {
                response.close()
                ended.countDown()
            }
        })
        // The host has the request and says nothing: the card is scrolled away.
        assertTrue("the request reached the host", server.takeRequest(5, TimeUnit.SECONDS) != null)

        call.cancel()

        assertTrue("the call ended", ended.await(5, TimeUnit.SECONDS))
        val figures = tally.figures()
        assertEquals(1, figures.cancelled)
        assertEquals("a cancelled call is not a network failure", 0, figures.network)
        assertEquals("nor a timeout", 0, figures.timeouts)
        assertEquals(0, figures.inFlight)
    }

    @Test
    fun `a call that has not ended is in flight`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val call = client().newCall(Request.Builder().url(server.url("/p.jpg")).build())
        val ended = CountDownLatch(1)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = ended.countDown()
            override fun onResponse(call: Call, response: Response) {
                response.close()
                ended.countDown()
            }
        })
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)

        assertEquals(1, tally.figures().inFlight)

        call.cancel()
        assertTrue(ended.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `how long the call took is told, and how long it waited for a connection is not more`() {
        server.enqueue(MockResponse().setBody("poster").setHeadersDelay(120, TimeUnit.MILLISECONDS))

        get(client())

        val figures = tally.figures()
        assertTrue("the call took at least what the host sat on it: ${figures.meanMs}", figures.meanMs >= 100)
        assertTrue(figures.slowestMs >= figures.meanMs)
        assertTrue("it waited for a connection for less than the whole call", figures.meanBeforeConnectionMs <= figures.meanMs)
    }

    // ------------------------------------------------------------------ whose host

    @Test
    fun `the public picture host is told by its name, and a name that only ends the same way is not it`() {
        assertEquals(ArtworkHost.TMDB, ArtworkListener.hostKind("https://image.tmdb.org/t/p/w342/x.jpg".toHttpUrl()))
        assertEquals(ArtworkHost.TMDB, ArtworkListener.hostKind("https://tmdb.org/x.jpg".toHttpUrl()))
        assertEquals(ArtworkHost.OTHER, ArtworkListener.hostKind("https://notmdb.org/x.jpg".toHttpUrl()))
    }

    @Test
    fun `a host that has answered an API call is the provider's`() {
        val panel = "http://panel.example:8080/player_api.php?username=u&password=p&action=get_vod_streams".toHttpUrl()
        assertEquals("not known before it has answered", ArtworkHost.OTHER, ArtworkListener.hostKind("http://panel.example/images/p.jpg".toHttpUrl()))

        CallMetrics.noteHost(panel, "get_vod_streams")

        assertEquals(ArtworkHost.PROVIDER, ArtworkListener.hostKind("http://panel.example:8080/images/p.jpg".toHttpUrl()))
        assertEquals("a host is its name, and not its port", ArtworkHost.PROVIDER, ArtworkListener.hostKind("http://panel.example/images/p.jpg".toHttpUrl()))
        assertEquals(ArtworkHost.OTHER, ArtworkListener.hostKind("http://cdn.example/images/p.jpg".toHttpUrl()))
    }

    @Test
    fun `a call that is not an API call does not make its host the provider's`() {
        CallMetrics.noteHost("http://cdn.example/images/p.jpg".toHttpUrl(), "other")

        assertEquals(ArtworkHost.OTHER, ArtworkListener.hostKind("http://cdn.example/images/q.jpg".toHttpUrl()))
    }

    @Test
    fun `the tally says which kind of host each call was made to`() {
        CallMetrics.noteHost(server.url("/player_api.php?action=get_vod_streams"), "get_vod_streams")
        server.enqueue(MockResponse().setBody("poster"))

        get(client())

        val figures = tally.figures()
        assertEquals(1, figures.fromProvider)
        assertEquals(0, figures.fromOther)
        assertEquals(0, figures.fromTmdb)
    }

    @Test
    fun `no address reaches a counter or a line`() {
        server.enqueue(MockResponse().setResponseCode(404))
        get(client(), path = "/secret-user/secret-password/poster.jpg")

        val said = tally.figures().describe().joinToString("\n")

        assertFalse(said, "secret" in said)
        assertFalse(said, server.hostName in said)
        assertFalse(said, server.port.toString() in said)
    }

    @Test
    fun `a listener makes one call's figures its own, so two calls together are two`() {
        server.enqueue(MockResponse().setBody("one"))
        server.enqueue(MockResponse().setBody("two"))
        val shared = client()

        get(shared, "/a.jpg")
        get(shared, "/b.jpg")

        assertEquals(2, tally.figures().started)
        assertEquals(2, tally.figures().ok)
    }
}
