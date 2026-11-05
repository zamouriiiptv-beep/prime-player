package com.castivio.data.networking

import com.castivio.data.parsing.RadioBrowserServerPool
import com.castivio.domain.CachedBody
import com.castivio.domain.DirectoryCache
import com.castivio.domain.DirectoryFailure
import com.castivio.domain.DirectoryResult
import com.castivio.domain.WorldListing
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
import kotlin.random.Random

/**
 * The world radio directory against two real servers: which one is asked, what is kept, and what an outage says.
 *
 * The directory's names are the pool's -- `a1.api.radio-browser.info` and `b2.api.radio-browser.info` -- and the test's own
 * `urlOf` turns each into a local server, so everything between the name and the wire is the real code.
 */
class RadioBrowserDirectoryTest {

    private val hostA = "a1.api.radio-browser.info"
    private val hostB = "b2.api.radio-browser.info"

    private class Recorder(val body: (RecordedRequest) -> MockResponse) : Dispatcher() {
        val paths = ArrayList<String>()

        @Synchronized
        override fun dispatch(request: RecordedRequest): MockResponse {
            paths += request.path.orEmpty()
            return body(request)
        }
    }

    private class MemoryCache : DirectoryCache {
        val rows = HashMap<String, CachedBody>()
        override suspend fun read(key: String): CachedBody? = rows[key]
        override suspend fun write(key: String, body: String, fetchedAtMs: Long) {
            rows[key] = CachedBody(body, fetchedAtMs)
        }
    }

    private lateinit var a: MockWebServer
    private lateinit var b: MockWebServer
    private var now = 1_000_000_000L
    private val cache = MemoryCache()

    private val countriesJson = """[{"name":"Morocco","iso_3166_1":"MA","stationcount":105},{"name":"France","iso_3166_1":"FR","stationcount":900}]"""

    private fun stationsJson(vararg uuids: String) =
        uuids.joinToString(prefix = "[", postfix = "]") { """{"stationuuid":"$it","name":"S $it","url":"http://x.invalid/$it","lastcheckok":1}""" }

    @Before
    fun setUp() {
        a = MockWebServer().apply { start() }
        b = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        runCatching { a.shutdown() }
        runCatching { b.shutdown() }
    }

    private fun directory(keep: String = hostA): RadioBrowserDirectory {
        val http = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        val servers = mapOf(hostA to a, hostB to b)
        // A name that is not one of the two is a port nothing listens on: discovery against it fails at once.
        val urlOf: (String, String) -> String = { host, path -> servers[host]?.url(path)?.toString() ?: "http://127.0.0.1:1$path" }
        val pool = RadioBrowserServerPool(clock = { now }, seed = listOf(hostA, hostB), random = Random(3))
        pool.succeeded(keep)
        val api = RadioBrowserApi(
            http = http,
            pool = pool,
            discovery = RadioBrowserDiscovery(http, Dispatchers.Unconfined, resolve = { emptyList() }, urlOf = urlOf),
            io = Dispatchers.Unconfined,
            clock = { now },
            urlOf = urlOf,
        )
        return RadioBrowserDirectory(api, cache, clock = { now })
    }

    private fun serve(server: MockWebServer, body: (RecordedRequest) -> MockResponse): Recorder =
        Recorder(body).also { server.dispatcher = it }

    private fun ok(json: String) = MockResponse().setResponseCode(200).setBody(json)

    // ------------------------------------------------------------------------------- countries

    @Test
    fun `the countries are read, kept on the device, and not asked for again while fresh`() = runBlocking {
        val seen = serve(a) { ok(countriesJson) }
        val directory = directory()

        val first = directory.countries()
        now += 60_000
        val second = directory.countries()

        assertEquals(listOf("MA", "FR"), (first as DirectoryResult.Fresh).value.map { it.code })
        assertEquals(listOf("MA", "FR"), (second as DirectoryResult.Fresh).value.map { it.code })
        assertEquals("the second visit made no request", 1, seen.paths.size)
        assertTrue(seen.paths.single().startsWith("/json/countries?hidebroken=true"))
        assertEquals(countriesJson, cache.rows.getValue("countries").body)
    }

    @Test
    fun `a forced refresh asks again`() = runBlocking {
        val seen = serve(a) { ok(countriesJson) }
        val directory = directory()
        directory.countries()

        directory.countries(forceRefresh = true)

        assertEquals(2, seen.paths.size)
    }

    @Test
    fun `a day later the list is asked for again, and the old copy is not shown while the new one arrives`() = runBlocking {
        val seen = serve(a) { ok(countriesJson) }
        val directory = directory()
        directory.countries()

        now += 25 * 60 * 60_000L
        val later = directory.countries()

        assertTrue(later is DirectoryResult.Fresh)
        assertEquals(2, seen.paths.size)
    }

    @Test
    fun `when no server answers, a copy kept the week before is shown as stale`() = runBlocking {
        cache.rows["countries"] = CachedBody(countriesJson, now - 7 * 24 * 60 * 60_000L)
        serve(a) { MockResponse().setResponseCode(503) }
        serve(b) { MockResponse().setResponseCode(503) }

        val result = directory().countries()

        assertTrue(result is DirectoryResult.Stale)
        assertEquals(2, (result as DirectoryResult.Stale).value.size)
        assertEquals(7 * 24 * 60 * 60_000L, result.ageMs)
    }

    @Test
    fun `a copy older than two weeks is not shown`() = runBlocking {
        cache.rows["countries"] = CachedBody(countriesJson, now - 15 * 24 * 60 * 60_000L)
        serve(a) { MockResponse().setResponseCode(503) }
        serve(b) { MockResponse().setResponseCode(503) }

        assertEquals(DirectoryResult.Unavailable(DirectoryFailure.SERVICE_DOWN), directory().countries())
    }

    // ------------------------------------------------------------------------- failing over

    @Test
    fun `a server that answers with an error is left and the next one is asked`() = runBlocking {
        val first = serve(a) { MockResponse().setResponseCode(500) }
        val second = serve(b) { ok(countriesJson) }

        val result = directory(keep = hostA).countries()

        assertTrue(result is DirectoryResult.Fresh)
        assertEquals(1, first.paths.size)
        assertEquals(1, second.paths.size)
    }

    @Test
    fun `every server erroring is a service that is down, and none reachable is offline`() = runBlocking {
        serve(a) { MockResponse().setResponseCode(502) }
        serve(b) { MockResponse().setResponseCode(502) }
        assertEquals(DirectoryResult.Unavailable(DirectoryFailure.SERVICE_DOWN), directory().countries())
    }

    @Test
    fun `no server reachable at all is offline`() = runBlocking {
        a.shutdown()
        b.shutdown()

        assertEquals(DirectoryResult.Unavailable(DirectoryFailure.OFFLINE), directory().countries())
    }

    @Test
    fun `an answer that is not the directory's is a bad answer`() = runBlocking {
        serve(a) { ok("<html>maintenance</html>") }
        serve(b) { ok("<html>maintenance</html>") }

        assertEquals(DirectoryResult.Unavailable(DirectoryFailure.BAD_ANSWER), directory().countries())
    }

    // ----------------------------------------------------------------------------------- pages

    @Test
    fun `a page is asked for as a page, and read with what was sent`() = runBlocking {
        val seen = serve(a) { ok(stationsJson("s1", "s2", "s3")) }
        val directory = directory()

        val result = directory.page(WorldListing.OfCountry("ma"), offset = 60, limit = 60)

        val page = (result as DirectoryResult.Fresh).value
        assertEquals(listOf("s1", "s2", "s3"), page.stations.map { it.uuid })
        assertEquals(3, page.rawCount)
        assertTrue("fewer than asked for: the list ends", page.endReached)
        assertEquals(63, page.nextOffset)
        val path = seen.paths.single()
        assertTrue(path, path.contains("countrycode=MA") && path.contains("hidebroken=true") && path.endsWith("offset=60&limit=60"))
    }

    @Test
    fun `a page the viewer just left is not asked for twice, and is after its minutes`() = runBlocking {
        val seen = serve(a) { ok(stationsJson("s1")) }
        val directory = directory()

        directory.page(WorldListing.OfTag("news"), 0, 60)
        now += 5 * 60_000
        directory.page(WorldListing.OfTag("news"), 0, 60)
        assertEquals(1, seen.paths.size)

        now += 6 * 60_000
        directory.page(WorldListing.OfTag("news"), 0, 60)
        assertEquals(2, seen.paths.size)
    }

    @Test
    fun `when the servers fail, the page that was seen is shown as stale`() = runBlocking {
        val directory = directory()
        serve(a) { ok(stationsJson("s1")) }
        directory.page(WorldListing.Top, 0, 60)
        now += 2 * 60 * 60_000L
        serve(a) { MockResponse().setResponseCode(500) }
        serve(b) { MockResponse().setResponseCode(500) }

        val result = directory.page(WorldListing.Top, 0, 60)

        assertTrue(result is DirectoryResult.Stale)
    }

    @Test
    fun `a full page is not the last`() = runBlocking {
        serve(a) { ok(stationsJson("s1", "s2")) }

        val page = (directory().page(WorldListing.Top, 0, 2) as DirectoryResult.Fresh).value

        assertTrue(!page.endReached)
    }

    // --------------------------------------------------------------------- uuids and listens

    @Test
    fun `stations by uuid come back as the directory has them`() = runBlocking {
        val seen = serve(a) { ok(stationsJson("s1", "s3")) }

        val result = directory().stationsByUuid(listOf("s1", "s2", "s3"))

        assertEquals(listOf("s1", "s3"), (result as DirectoryResult.Fresh).value.map { it.uuid })
        assertTrue(seen.paths.single().startsWith("/json/stations/byuuid?uuids=s1%2Cs2%2Cs3"))
    }

    @Test
    fun `a listen is registered and the address now is returned, and a failure is only null`() = runBlocking {
        serve(a) { ok("""{"ok":true,"message":"retrieved station url","stationuuid":"s1","url":"https://now.invalid/s1.mp3"}""") }
        assertEquals("https://now.invalid/s1.mp3", directory().registerListen("s1"))

        serve(a) { MockResponse().setResponseCode(500) }
        serve(b) { MockResponse().setResponseCode(500) }
        assertNull(directory().registerListen("s1"))
    }

    // ------------------------------------------------------------------------------ discovery

    @Test
    fun `the servers are discovered once from one of them, and the names that are not the directory's are ignored`() = runBlocking {
        // The round-robin name is the first the discovery asks, and it is the test's server A.
        val servers = Recorder { request ->
            if (request.path.orEmpty().startsWith("/json/servers")) {
                ok("""[{"ip":"1.1.1.1","name":"$hostA"},{"ip":"2.2.2.2","name":"evil.example.com"}]""")
            } else {
                ok(countriesJson)
            }
        }
        a.dispatcher = servers
        val http = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build()
        val map = mapOf("all.api.radio-browser.info" to a, hostA to a)
        val urlOf: (String, String) -> String = { host, path -> map[host]?.url(path)?.toString() ?: "http://127.0.0.1:1$path" }
        val pool = RadioBrowserServerPool(clock = { now }, seed = listOf(hostB), random = Random(1))
        val api = RadioBrowserApi(
            http, pool, RadioBrowserDiscovery(http, Dispatchers.Unconfined, resolve = { emptyList() }, urlOf = urlOf),
            Dispatchers.Unconfined, { now }, urlOf,
        )

        val result = RadioBrowserDirectory(api, cache, clock = { now }).countries()

        assertTrue(result is DirectoryResult.Fresh)
        assertEquals(listOf(hostA), pool.candidates(5))
        assertTrue(servers.paths.first().startsWith("/json/servers"))
    }
}
