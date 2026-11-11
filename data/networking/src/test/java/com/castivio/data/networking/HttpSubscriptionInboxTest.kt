package com.castivio.data.networking

import com.castivio.core.common.AppDispatchers
import com.castivio.core.common.AppError
import com.castivio.core.common.Outcome
import com.castivio.core.common.config.PortalConfig
import com.castivio.domain.identity.DeviceIdentity
import com.castivio.domain.identity.DeviceIdentityRecord
import com.castivio.domain.identity.DeviceKey
import com.castivio.domain.identity.IdentityProvenance
import com.castivio.domain.identity.MacAddress
import com.castivio.domain.portal.SentSource
import com.castivio.domain.portal.SentSubscription
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The portal's mailbox over a real socket: what goes on the wire, and what each way it can go
 * wrong is called.
 *
 * The two things that matter most are asserted in their own tests and not buried in the
 * happy path. The key travels in the body and never in the address of a request, because
 * addresses are what proxies and logs keep. And a refusal, a gateway's page, a dead
 * connection and a silent server are four different answers, not one "failed".
 */
class HttpSubscriptionInboxTest {

    private val mac = "AA:BB:CC:00:11:22"
    private val key = "482731"

    private lateinit var server: MockWebServer

    private class Identity : DeviceIdentity {
        override fun current() = DeviceIdentityRecord(MacAddress.parse("AA:BB:CC:00:11:22")!!, 2, IdentityProvenance.DEVICE)
        override fun legacy(): List<DeviceIdentityRecord> = emptyList()
        override fun key() = DeviceKey("482731")
    }

    private object Inline : AppDispatchers {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun inbox(
        device: PortalDevice = PortalDevice(),
        readTimeoutMs: Long = 5_000,
    ): HttpSubscriptionInbox {
        val client = OkHttpClient.Builder()
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .connectTimeout(2, TimeUnit.SECONDS)
            .build()
        // Trailing slash on purpose: the configuration must not double it.
        return HttpSubscriptionInbox(client, PortalConfig.of(server.url("/").toString()), Identity(), Inline, device)
    }

    private fun json(body: String, code: Int = 200) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    // ----------------------------------------------------------------- the wire

    @Test
    fun `announcing posts the address and the key in the body to the register path`() = runBlocking {
        server.enqueue(json("""{"registered":true,"created":true}"""))

        val outcome = inbox(PortalDevice("NVIDIA SHIELD", "Android TV")).announce()

        assertTrue(outcome is Outcome.Success)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/device/register", request.path)
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("application/json"))
        assertEquals(
            """{"macAddress":"$mac","deviceKey":"$key","modelName":"NVIDIA SHIELD","platform":"Android TV"}""",
            request.body.readUtf8(),
        )
    }

    @Test
    fun `the key is never in the address of a request`() = runBlocking {
        server.enqueue(json("""{"registered":true}"""))
        server.enqueue(json("""{"playlists":[]}"""))
        server.enqueue(json("""{"acknowledged":0}"""))
        val portal = inbox()

        portal.announce()
        portal.waiting()
        portal.acknowledge(emptyList())

        repeat(3) {
            val request = server.takeRequest()
            assertFalse("the key is in ${request.path}", request.path.orEmpty().contains(key))
            assertFalse("the address is in ${request.path}", request.path.orEmpty().contains("BB:CC"))
            assertFalse(request.requestUrl.toString().contains(key))
        }
    }

    @Test
    fun `waiting reads what the portal sends`() = runBlocking {
        server.enqueue(
            json(
                """{"playlists":[{"id":"pl_1","version":"v1","name":"Home","sourceType":"xtream",
                    "serverUrl":"panel.example:8080","username":"bob","password":"p\"w"}]}""",
            ),
        )

        val outcome = inbox().waiting()

        val sent = (outcome as Outcome.Success).value
        assertEquals(
            listOf(SentSubscription("pl_1", "v1", "Home", SentSource.Xtream("panel.example:8080", "bob", "p\"w"))),
            sent,
        )
        val request = server.takeRequest()
        assertEquals("/api/v1/device/inbox", request.path)
        assertEquals("""{"macAddress":"$mac","deviceKey":"$key"}""", request.body.readUtf8())
    }

    @Test
    fun `an acknowledgement names the exact versions`() = runBlocking {
        server.enqueue(json("""{"acknowledged":1}"""))
        val taken = listOf(SentSubscription("pl_1", "v1", "A", SentSource.Playlist("https://x.example/a")))

        val outcome = inbox().acknowledge(taken)

        assertTrue(outcome is Outcome.Success)
        val request = server.takeRequest()
        assertEquals("/api/v1/device/inbox/ack", request.path)
        assertEquals(
            """{"macAddress":"$mac","deviceKey":"$key","delivered":[{"id":"pl_1","version":"v1"}]}""",
            request.body.readUtf8(),
        )
    }

    @Test
    fun `a device that reports nothing about itself announces without it`() = runBlocking {
        server.enqueue(json("""{"registered":true}"""))

        inbox(PortalDevice()).announce()

        assertEquals("""{"macAddress":"$mac","deviceKey":"$key"}""", server.takeRequest().body.readUtf8())
    }

    // ------------------------------------------------------------ what went wrong

    private fun failure(outcome: Outcome<*>): AppError = (outcome as Outcome.Failure).error

    @Test
    fun `a portal that knows the address under another key is unauthorised`() = runBlocking {
        server.enqueue(json("""{"error":"device_not_verified"}""", 403))

        assertEquals(AppError.UNAUTHORIZED, failure(inbox().waiting()))
    }

    @Test
    fun `too many wrong keys and a server that is down are both worth another press`() = runBlocking {
        server.enqueue(json("""{"error":"too_many_attempts","retryAfterSeconds":900}""", 429))
        server.enqueue(json("""{"error":"server_error"}""", 500))
        server.enqueue(json("", 503))
        val portal = inbox()

        assertEquals(AppError.SERVER_ERROR, failure(portal.waiting()))
        assertEquals(AppError.SERVER_ERROR, failure(portal.waiting()))
        assertEquals(AppError.SERVER_ERROR, failure(portal.announce()))
    }

    @Test
    fun `a gateway's page served with a 200 is a failure and not an empty mailbox`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html")
                .setBody("<!DOCTYPE html><html><body>Bad gateway</body></html>"),
        )

        assertEquals(AppError.SERVER_ERROR, failure(inbox().waiting()))
    }

    @Test
    fun `a request the portal calls malformed is not a network problem`() = runBlocking {
        server.enqueue(json("""{"error":"invalid_request"}""", 400))

        assertEquals(AppError.UNKNOWN, failure(inbox().waiting()))
    }

    @Test
    fun `nobody listening is an unreachable network`() = runBlocking {
        val portal = inbox()
        server.shutdown()

        assertEquals(AppError.NETWORK_UNAVAILABLE, failure(portal.waiting()))
    }

    @Test
    fun `a server that accepts and says nothing is a timeout`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        assertEquals(AppError.TIMEOUT, failure(inbox(readTimeoutMs = 300).waiting()))
    }
}
