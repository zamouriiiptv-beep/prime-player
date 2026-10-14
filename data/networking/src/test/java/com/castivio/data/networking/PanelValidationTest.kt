package com.castivio.data.networking

import com.castivio.core.common.AppDispatchers
import com.castivio.core.common.AppError
import com.castivio.core.common.Outcome
import com.castivio.domain.PlaylistSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Home's refresh, for a playlist read through its panel.
 *
 * It is asked what an Xtream subscription is asked — the account, which is where the
 * expiry Home shows comes from — and, when the panel's API says no for a reason that is
 * not the network, the playlist beside it once instead: the rule the import follows.
 */
class PanelValidationTest {

    private lateinit var server: MockWebServer

    /** The paths the server was asked for, in order, without their queries. */
    private val asked = mutableListOf<String>()

    /** What `player_api.php` answers with. */
    private var account: MockResponse = MockResponse().setBody(ACCOUNT)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                asked += path
                return when (path) {
                    "/player_api.php" -> account
                    "/get.php" -> MockResponse().setBody("#EXTM3U\n")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a playlist read through its panel is asked for its account, and gets its expiry`() = runBlocking {
        val result = validator().validate(routed())

        val status = (result as Outcome.Success).value
        assertTrue(status.usable)
        assertEquals(EXPIRES_S * 1_000, status.expiresAtMs)
        assertEquals("the playlist itself was not fetched", listOf("/player_api.php"), asked)
    }

    @Test
    fun `when the panel refuses its api, the playlist is asked once instead`() = runBlocking {
        account = MockResponse().setResponseCode(403)

        val result = validator().validate(routed())

        val status = (result as Outcome.Success).value
        assertTrue(status.usable)
        assertNull("a playlist states no expiry", status.expiresAtMs)
        assertEquals(listOf("/player_api.php", "/get.php"), asked)
    }

    /** The playlist is on the same server: an unreachable panel is its answer too. */
    @Test
    fun `when the panel cannot be reached, the playlist is not asked`() = runBlocking {
        val unreachable = PlaylistSource.M3u(
            url = server.url(PANEL_LINK).toString(),
            xtream = PlaylistSource.Xtream("http://127.0.0.1:1", "sam", "hunter2"),
        )

        val result = validator().validate(unreachable)

        assertEquals(AppError.NETWORK_UNAVAILABLE, (result as Outcome.Failure).error)
        assertTrue("nothing reached the playlist's server", asked.isEmpty())
    }

    /** A playlist that names no panel is checked as before: by its own link. */
    @Test
    fun `a playlist read as a playlist is checked by its link alone`() = runBlocking {
        val result = validator().validate(PlaylistSource.M3u(server.url(PANEL_LINK).toString()))

        assertTrue(result is Outcome.Success)
        assertEquals(listOf("/get.php"), asked)
    }

    // ------------------------------------------------------------------ fixtures

    private fun routed() = PlaylistSource.M3u(
        url = server.url(PANEL_LINK).toString(),
        xtream = PlaylistSource.Xtream(server.url("/").toString().trimEnd('/'), "sam", "hunter2"),
    )

    private fun validator() = HttpProviderValidator(
        client = client(),
        streams = HttpStreamSource(client()),
        userAgent = "Castivio/test",
        dispatchers = TestDispatchers,
    )

    private fun client() = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    private object TestDispatchers : AppDispatchers {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private companion object {
        const val PANEL_LINK = "/get.php?username=sam&password=hunter2&type=m3u_plus&output=ts"

        /** 2100-01-01, so the line reads as active whatever day the test runs. */
        const val EXPIRES_S = 4_102_444_800L

        const val ACCOUNT =
            """{"user_info":{"auth":1,"status":"Active","exp_date":"$EXPIRES_S","max_connections":"1","active_cons":"0"}}"""
    }
}
