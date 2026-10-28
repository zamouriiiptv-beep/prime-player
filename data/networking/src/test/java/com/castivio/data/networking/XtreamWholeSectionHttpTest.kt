package com.castivio.data.networking

import com.castivio.data.parsing.WholeSectionUnsupported
import com.castivio.domain.MediaKind
import java.io.IOException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The one-reply question, as the panel actually receives it.
 *
 * What is pinned here is the HTTP half of a promise the engine makes: the question is the
 * streams action with no category, it is asked once, and a "no" comes back as a refusal
 * the engine can recognise rather than as three attempts and a timeout. Against a real
 * server, because what matters is the request that leaves and the attempts that are made,
 * which a fake would assume.
 */
class XtreamWholeSectionHttpTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun api(client: okhttp3.OkHttpClient = HttpClientProvider.create()) = XtreamHttpApi(
        client = client,
        base = server.url("/").toString().trimEnd('/'),
        username = "sam",
        password = "hunter2",
    )

    @Test
    fun `the one reply is the streams action with no category`() {
        server.enqueue(MockResponse().setBody("""[{"stream_id":"1","name":"Nova","category_id":"3"}]"""))

        val body = api().allStreams(MediaKind.LIVE)!!.use { it.readText() }

        val path = server.takeRequest().path.orEmpty()
        assertTrue(path, path.contains("action=get_live_streams"))
        assertFalse("a category was asked for: $path", path.contains("category_id"))
        assertTrue(body.contains("Nova"))
    }

    /** Radio is read from the live categories, so it asks the live question. */
    @Test
    fun `radio asks the live question`() {
        server.enqueue(MockResponse().setBody("[]"))

        api().allStreams(MediaKind.RADIO)!!.close()

        assertTrue(server.takeRequest().path.orEmpty().contains("action=get_live_streams"))
    }

    /**
     * **A refusal is one request.** Retrying it would make a panel that will never answer
     * cost three round trips before the walk that answers anyway.
     */
    @Test
    fun `a refusal comes back as one, after one attempt`() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("[]"))

        try {
            api().allStreams(MediaKind.LIVE)
            fail("a 404 was read as an answer")
        } catch (e: WholeSectionUnsupported) {
            assertEquals("HTTP 404", e.message)
        }
        assertEquals("the refusal was retried", 1, server.requestCount)
    }

    /** A panel that falls over on the big reply is not asked it again either. */
    @Test
    fun `a server error is not retried`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("[]"))

        try {
            api().allStreams(MediaKind.LIVE)
            fail("a 503 was read as an answer")
        } catch (e: WholeSectionUnsupported) {
            assertEquals("HTTP 503", e.message)
        }
        assertEquals(1, server.requestCount)
    }

    /**
     * No answer is not a refusal: it is thrown as what it is, once, and the walk runs. A
     * read timeout, which OkHttp itself never retries once the request has been sent, so
     * the one attempt counted is this layer's.
     */
    @Test
    fun `no answer is thrown as it is, without a retry`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setBody("[]"))

        try {
            api(HttpClientProvider.create(readTimeoutSeconds = 1)).allStreams(MediaKind.LIVE)
            fail("a dropped connection was read as an answer")
        } catch (e: WholeSectionUnsupported) {
            fail("a dropped connection was reported as a refusal")
        } catch (e: IOException) {
            // The engine's fallback, not this layer's retry.
        }
        assertEquals(1, server.requestCount)
    }

    /** The shows' list is `get_series` with no category: the same question, the same single attempt. */
    @Test
    fun `series ask for the whole list of shows with no category`() {
        server.enqueue(MockResponse().setBody("""[{"series_id":"7","name":"Dark Harbor","category_id":"3"}]"""))

        val body = api().allStreams(MediaKind.SERIES)!!.use { it.readText() }

        val path = server.takeRequest().path.orEmpty()
        assertTrue(path, path.contains("action=get_series"))
        assertFalse("get_series_categories is not the list: $path", path.contains("get_series_categories"))
        assertFalse("a category was asked for: $path", path.contains("category_id"))
        assertTrue(body.contains("Dark Harbor"))
    }

    @Test
    fun `a refused list of shows is one request, then the walk`() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("[]"))

        try {
            api().allStreams(MediaKind.SERIES)
            fail("a 404 was read as an answer")
        } catch (e: WholeSectionUnsupported) {
            assertEquals("HTTP 404", e.message)
        }
        assertEquals(1, server.requestCount)
    }
}
