package com.castivio.data.networking

import com.castivio.core.common.AppDispatchers
import com.castivio.core.common.DefaultDispatchers
import com.castivio.core.common.Outcome
import com.castivio.domain.PlaylistSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The provider check does not run on the thread that asked for it.
 *
 * ## The defect this exists to prevent coming back
 *
 * Everything in this validator is synchronous OkHttp. `suspend` does not move work
 * anywhere — it only means a function may be suspended — so a `suspend` wrapper around
 * a blocking call runs that call on whatever thread called it. The caller here is
 * `ActivationViewModel.submit`, which collects on `viewModelScope`
 * (`Dispatchers.Main.immediate`) through a cold `flow { }` with no `flowOn`; a cold
 * flow's body runs in its collector's context. So pressing Connect did a DNS lookup, a
 * TCP handshake and an HTTP round trip on the main thread, and with a twelve-second
 * connect timeout Android declared the app not responding after five.
 *
 * It is not a defect you can see by reading the call site, and it is not one a screen
 * shows you until a real server is slow. What it *is* is a fact about which thread ran
 * the request — so that is what these tests assert, by recording the thread inside an
 * interceptor and comparing it with the caller's.
 */
class ProviderValidatorThreadTest {

    private lateinit var server: MockWebServer

    /** The thread OkHttp actually executed the call on, captured on the wire. */
    private val servedOn = AtomicReference<Thread?>()

    /**
     * A stand-in for Android's main thread.
     *
     * A single thread with a name, so a failure says which thread served the request
     * rather than only that two of them were equal. There is no Looper on the JVM, and
     * none is needed: what broke was a blocking call on the caller's thread, and any
     * single caller thread reproduces that exactly.
     */
    private val fakeMain: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, MAIN) }
            .asCoroutineDispatcher()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ------------------------------------------------------------------- the claim

    /**
     * Called from the caller's thread, the request is served on another one.
     *
     * The production wiring: `DefaultDispatchers`, whose `io` is `Dispatchers.IO`. This
     * is the assertion that would have failed before the fix and is the whole point of
     * the file.
     */
    @Test
    fun `checking a panel does not run on the thread that asked`() {
        server.enqueue(MockResponse().setBody(ACCOUNT_OK))
        val validator = validator(DefaultDispatchers)

        val callerThread = runBlocking(fakeMain) {
            val here = Thread.currentThread()
            validator.validate(xtream())
            here
        }

        val served = servedOn.get()
        assertNotNull("the request never reached the server", served)
        assertEquals(MAIN, callerThread.name)
        assertFalse(
            "the HTTP call ran on ${served?.name}, the caller's own thread — this is the ANR",
            served === callerThread,
        )
    }

    /** A playlist URL is checked over the wire too, so it needs the same guarantee. */
    @Test
    fun `checking a playlist url does not run on the thread that asked`() {
        server.enqueue(MockResponse().setBody("#EXTM3U"))
        val validator = validator(DefaultDispatchers)

        val callerThread = runBlocking(fakeMain) {
            val here = Thread.currentThread()
            validator.validate(PlaylistSource.M3u(server.url("/playlist.m3u").toString()))
            here
        }

        assertNotNull("the request never reached the server", servedOn.get())
        assertFalse(servedOn.get() === callerThread)
    }

    /**
     * And a validator built without being handed a dispatcher is safe as well.
     *
     * An unsafe default is exactly how this comes back: one construction site that
     * forgets the argument, and the main thread is blocking again on a path nobody
     * thought to re-check.
     */
    @Test
    fun `a validator built with no dispatcher still moves off the caller`() {
        server.enqueue(MockResponse().setBody(ACCOUNT_OK))
        val validator = HttpProviderValidator(client(), HttpStreamSource(client()))

        val callerThread = runBlocking(fakeMain) {
            val here = Thread.currentThread()
            validator.validate(xtream())
            here
        }

        assertFalse(servedOn.get() === callerThread)
    }

    /**
     * The switch does not change the answer, only where it is computed.
     *
     * Worth asserting alongside: a fix that moved the work and lost the result would
     * pass every thread check above and break the screen.
     */
    @Test
    fun `moving the work off the caller does not change what it answers`() = runBlocking {
        server.enqueue(MockResponse().setBody(ACCOUNT_OK))

        val result = validator(DefaultDispatchers).validate(xtream())

        assertTrue("$result", result is Outcome.Success)
        assertTrue((result as Outcome.Success).value.usable)
    }

    /** A refused subscription is still a refusal after the move, not a transport error. */
    @Test
    fun `a rejected panel is still reported as rejected`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = validator(DefaultDispatchers).validate(xtream())

        assertTrue("$result", result is Outcome.Failure)
    }

    // ----------------------------------------------------------------- the harness

    private fun validator(dispatchers: AppDispatchers) = HttpProviderValidator(
        client = client(),
        streams = HttpStreamSource(client()),
        userAgent = "Castivio/test",
        dispatchers = dispatchers,
    )

    /**
     * A client that records which thread executed the call.
     *
     * An interceptor rather than a `MockWebServer` dispatcher: an interceptor runs on
     * the *calling* side, which is the side this test is about. The server's own
     * threads would tell us nothing about the app's.
     */
    private fun client() = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .addInterceptor(
            Interceptor { chain: Interceptor.Chain ->
                servedOn.set(Thread.currentThread())
                chain.proceed(chain.request())
            },
        )
        .build()

    private fun xtream() = PlaylistSource.Xtream(
        host = server.url("/").toString(),
        username = "bob",
        password = "hunter2",
    )

    private companion object {
        const val MAIN = "castivio-fake-main"

        /** The smallest `player_api.php` reply that reads as an authenticated line. */
        const val ACCOUNT_OK =
            """{"user_info":{"auth":1,"status":"Active","max_connections":"1","active_cons":"0"}}"""
    }
}
