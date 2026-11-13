package com.castivio.data.networking

import okhttp3.Callback
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The image loader's way of making a call, against a real server.
 *
 * What is proved is what the application relies on and a screenshot would only show late: the word that says "this is a
 * picture" is read and **taken off** before the call leaves, a picture is given its longer limit and a logo its shorter, and
 * the pictures' client queues posters among posters and nowhere else.
 */
class ArtworkCallsTest {

    private lateinit var server: MockWebServer

    private val marker = "X-Test-Kind"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun calls(markedLimitMs: Long = 5_000L, limitMs: Long = 5_000L) =
        ArtworkCalls(OkHttpClient(), marker, markedLimitMs, limitMs)

    private fun request(marked: Boolean, path: String = "/p.jpg"): Request = Request.Builder()
        .url(server.url(path))
        .header("Accept", "image/*")
        .apply { if (marked) header(marker, "picture") }
        .build()

    private fun fetch(factory: Call.Factory, request: Request): String? =
        factory.newCall(request).execute().use { it.body?.string() }

    // ------------------------------------------------------------------ the word is the loader's, not the host's

    @Test
    fun `the word that says picture never reaches the host`() {
        server.enqueue(MockResponse().setBody("poster"))

        assertEquals("poster", fetch(calls(), request(marked = true)))

        val seen = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertNull("an instruction to the loader is not for a host: ${seen.headers}", seen.getHeader(marker))
    }

    @Test
    fun `everything else about the request is as it was made`() {
        server.enqueue(MockResponse().setBody("poster"))

        fetch(calls(), request(marked = true, path = "/images/p.jpg?size=w342"))

        val seen = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/images/p.jpg?size=w342", seen.path)
        assertEquals("GET", seen.method)
        assertEquals("image/*", seen.getHeader("Accept"))
    }

    @Test
    fun `a request with no word is sent as it was made`() {
        server.enqueue(MockResponse().setBody("logo"))

        assertEquals("logo", fetch(calls(), request(marked = false)))

        val seen = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertNull(seen.getHeader(marker))
        assertEquals("image/*", seen.getHeader("Accept"))
    }

    // ------------------------------------------------------------------ the two limits

    @Test
    fun `a picture is given its limit and a logo its own`() {
        val factory = calls(markedLimitMs = 20_000L, limitMs = 6_000L)

        assertEquals(TimeUnit.MILLISECONDS.toNanos(20_000L), factory.newCall(request(marked = true)).timeout().timeoutNanos())
        assertEquals(TimeUnit.MILLISECONDS.toNanos(6_000L), factory.newCall(request(marked = false)).timeout().timeoutNanos())
    }

    @Test
    fun `a host that is slow is waited for by a picture and given up on by a logo`() {
        repeat(2) { server.enqueue(MockResponse().setBody("late").setHeadersDelay(600, TimeUnit.MILLISECONDS)) }
        val factory = calls(markedLimitMs = 10_000L, limitMs = 150L)

        val logo = runCatching { fetch(factory, request(marked = false)) }
        assertTrue("a logo is given up on: $logo", logo.exceptionOrNull() is InterruptedIOException)

        assertEquals("a poster is waited for", "late", fetch(factory, request(marked = true)))
    }

    @Test
    fun `the client's own limit is the one a call has when it is not told another`() {
        val patient = OkHttpClient.Builder().callTimeout(7, TimeUnit.SECONDS).build()
        val factory = ArtworkCalls(patient, marker, markedLimitMs = 20_000L, limitMs = 6_000L)

        // A logo's limit is the factory's, not the client's: the factory is the one place the two are put side by side.
        assertEquals(TimeUnit.SECONDS.toNanos(6), factory.newCall(request(marked = false)).timeout().timeoutNanos())
    }

    // ------------------------------------------------------------------ the pictures' own client

    private fun noListener() = EventListener.Factory { EventListener.NONE }

    @Test
    fun `the pictures' client has the limits it was given and the same threads as the client it came from`() {
        val shared = OkHttpClient()

        val pictures = ArtworkCalls.deriveClient(shared, noListener(), maxRequests = 32, maxRequestsPerHost = 12, callTimeoutMs = 6_000L)

        assertEquals(32, pictures.dispatcher.maxRequests)
        assertEquals(12, pictures.dispatcher.maxRequestsPerHost)
        assertSame("no second pool of threads is made for the pictures", shared.dispatcher.executorService, pictures.dispatcher.executorService)
        assertEquals(6_000, pictures.callTimeoutMillis)
    }

    @Test
    fun `the client it came from is left as it was`() {
        val shared = OkHttpClient()
        val before = Triple(shared.dispatcher.maxRequests, shared.dispatcher.maxRequestsPerHost, shared.callTimeoutMillis)

        ArtworkCalls.deriveClient(shared, noListener(), maxRequests = 32, maxRequestsPerHost = 12, callTimeoutMs = 6_000L)

        assertEquals(before, Triple(shared.dispatcher.maxRequests, shared.dispatcher.maxRequestsPerHost, shared.callTimeoutMillis))
        assertEquals("five to a host, as OkHttp has it", 5, shared.dispatcher.maxRequestsPerHost)
    }

    @Test
    fun `it keeps what a picture is right to share, the connections and the name cache`() {
        val shared = OkHttpClient()

        val pictures = ArtworkCalls.deriveClient(shared, noListener(), maxRequests = 32, maxRequestsPerHost = 12, callTimeoutMs = 6_000L)

        assertSame(shared.connectionPool, pictures.connectionPool)
        assertSame(shared.dns, pictures.dns)
    }

    @Test
    fun `a picture does not wait behind the application's calls for a host's only slot`() {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == "/slow") {
                    MockResponse().setBody("late").setHeadersDelay(3, TimeUnit.SECONDS)
                } else {
                    MockResponse().setBody("poster")
                }
        }
        // The import's client is allowed one call at a time to a host: the slot a poster used to have to wait for.
        val shared = OkHttpClient.Builder()
            .dispatcher(okhttp3.Dispatcher().also { it.maxRequestsPerHost = 1 })
            .build()
        val pictures = ArtworkCalls.deriveClient(shared, noListener(), maxRequests = 32, maxRequestsPerHost = 12, callTimeoutMs = 10_000L)

        val slowEnded = CountDownLatch(1)
        shared.newCall(Request.Builder().url(server.url("/slow")).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = slowEnded.countDown()
            override fun onResponse(call: Call, response: Response) {
                response.close()
                slowEnded.countDown()
            }
        })
        // The host has it, and is sitting on the slot.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (server.requestCount < 1 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals("the slow call has reached the host", 1, server.requestCount)

        val began = System.nanoTime()
        val got = fetch(pictures, Request.Builder().url(server.url("/p.jpg")).build())
        val tookMs = (System.nanoTime() - began) / 1_000_000

        assertEquals("poster", got)
        assertTrue("the poster waited ${tookMs}ms: it stood in the other client's queue", tookMs < 2_000)
        // The slow call is still the application's, and still holds its slot.
        assertEquals(1, shared.dispatcher.runningCallsCount())
        slowEnded.await(10, TimeUnit.SECONDS)
    }

    @Test
    fun `many posters run together to one host, up to its limit and not the application's five`() {
        val running = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val now = running.incrementAndGet()
                peak.updateAndGet { maxOf(it, now) }
                Thread.sleep(300)
                running.decrementAndGet()
                return MockResponse().setBody("poster")
            }
        }
        val pictures = ArtworkCalls.deriveClient(
            OkHttpClient(), noListener(), maxRequests = 32, maxRequestsPerHost = 12, callTimeoutMs = 10_000L,
        )

        val ended = CountDownLatch(10)
        repeat(10) { n ->
            pictures.newCall(Request.Builder().url(server.url("/p$n.jpg")).build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = ended.countDown()
                override fun onResponse(call: Call, response: Response) {
                    response.close()
                    ended.countDown()
                }
            })
        }

        assertTrue("ten posters came", ended.await(15, TimeUnit.SECONDS))
        assertTrue("more than OkHttp's five at once were asked: peak ${peak.get()}", peak.get() > 5)
        assertTrue("and no more than the limit: peak ${peak.get()}", peak.get() <= 12)
    }
}
