package com.castivio.data.playlist

import com.castivio.domain.Movie
import com.castivio.domain.MovieDetails
import com.castivio.domain.MovieDetailsCache
import com.castivio.domain.PlaylistSource
import com.castivio.domain.ProviderSource
import com.castivio.domain.RequestTimes
import com.castivio.domain.SourceKind
import com.castivio.domain.SourceRepository
import com.castivio.domain.SyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A film's details asked of its provider, against a server that answers like a panel.
 *
 * What is pinned is the asking rather than the parsing: an empty answer is a provider that has not built the film
 * yet and is asked again before it is believed, a failed asking is never taken for "nothing here", and only a provider
 * that answered nothing every time is left alone -- and then only for a while.
 */
class DefaultMovieDetailsTest {

    private lateinit var server: MockWebServer
    private val cache = MemoryCache()
    private var now = 1_000_000L

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private val dune = Movie(
        id = "m1",
        title = "Dune",
        artworkUrl = null,
        streamUrl = "http://panel/movie/u/p/77.mkv",
        sourceId = "src",
        providerRef = "77",
    )

    private fun repository() = DefaultMovieDetails(
        cache = cache,
        sources = OneSource(account()),
        client = OkHttpClient(),
        dispatcher = Dispatchers.Unconfined,
        clock = { now },
    )

    private fun account() = ProviderSource(
        id = "src",
        kind = SourceKind.XTREAM,
        label = "panel",
        url = server.url("/").toString().trimEnd('/'),
        username = "u",
        password = "p",
    )

    private fun full() = MockResponse().setBody(FULL)
    private fun empty() = MockResponse().setBody(EMPTY)

    @Test
    fun `the details the provider sends are kept, and a film already kept costs no request`() = runTest {
        server.enqueue(full())
        val repository = repository()

        val first = repository.details(dune)
        val again = repository.details(dune)

        assertEquals("A storm closes the port.", first?.plot)
        assertEquals(first, again)
        assertEquals(1, server.requestCount)
        assertEquals(1, cache.puts)
    }

    /** A provider that has not built the film answers with nothing the first time, and with all of it a moment later. */
    @Test
    fun `an empty answer is asked for again, and the details come with the second`() = runTest {
        server.enqueue(empty())
        server.enqueue(full())
        val told = mutableListOf<RequestTimes>()

        val details = repository().details(dune) { told += it }

        assertEquals("A storm closes the port.", details?.plot)
        assertEquals(2, server.requestCount)
        // Each asking is told, and says which one it is.
        assertEquals(listOf(1, 2), told.map { it.tries })
        assertTrue(told.all { it.askMs >= 0 && it.readMs >= 0 })
    }

    @Test
    fun `a provider that answers nothing every time is asked four times, and then left alone for a while`() = runTest {
        repeat(4) { server.enqueue(empty()) }
        val repository = repository()

        assertNull(repository.details(dune))
        assertEquals(4, server.requestCount)

        // A reopened page asks nothing until the memory is up.
        assertNull(repository.details(dune))
        assertEquals(4, server.requestCount)

        now += 11 * 60 * 1000L
        server.enqueue(full())
        assertEquals("A storm closes the port.", repository.details(dune)?.plot)
        assertEquals(5, server.requestCount)
    }

    /** The provider has said so in so many words: asking again would only hear it again. */
    @Test
    fun `a provider that refuses the film is asked once`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(full())

        assertNull(repository().details(dune))

        assertEquals(1, server.requestCount)
    }

    /**
     * A film whose asking failed on the way -- a weak connection, a timeout -- is not a film with nothing to say:
     * the next page reaches the provider again, and does not find the failure remembered.
     */
    @Test
    fun `a failed asking is not remembered as having nothing`() = runTest {
        server.shutdown()
        val sources = OneSource(account())
        val repository = DefaultMovieDetails(cache, sources, OkHttpClient(), Dispatchers.Unconfined) { now }
        assertNull(repository.details(dune))

        // The same repository, and a provider that can now be reached: it is asked, not excused by the failure.
        val back = MockWebServer().also { it.start() }
        try {
            back.enqueue(full())
            sources.source = account().copy(url = back.url("/").toString().trimEnd('/'))
            assertEquals("A storm closes the port.", repository.details(dune)?.plot)
            assertEquals(1, back.requestCount)
        } finally {
            back.shutdown()
        }
    }

    @Test
    fun `a film with no provider id has nobody to ask`() = runTest {
        val m3u = dune.copy(providerRef = null, streamUrl = "http://panel/live/x.ts")

        assertNull(repository().details(m3u))
        assertEquals(0, server.requestCount)
    }

    private class MemoryCache : MovieDetailsCache {
        private val held = mutableMapOf<String, MovieDetails>()
        var puts = 0
        override suspend fun get(sourceId: String, providerRef: String): MovieDetails? = held["$sourceId/$providerRef"]
        override suspend fun put(sourceId: String, providerRef: String, details: MovieDetails, now: Long) {
            puts++
            held["$sourceId/$providerRef"] = details
        }
    }

    private class OneSource(var source: ProviderSource?) : SourceRepository {
        override suspend fun register(source: PlaylistSource, label: String?): ProviderSource = error("not asked")
        override fun sources(): Flow<List<ProviderSource>> = emptyFlow()
        override fun active(): Flow<ProviderSource?> = emptyFlow()
        override suspend fun activeNow(): ProviderSource? = source
        override suspend fun get(id: String): ProviderSource? = source
        override suspend fun save(source: ProviderSource) = Unit
        override suspend fun setActive(id: String) = Unit
        override suspend fun recordCatalogueImport(id: String, sync: SyncState) = Unit
        override suspend fun recordEpgImport(id: String, atMs: Long) = Unit
        override suspend fun delete(id: String) = Unit
    }

    private companion object {
        const val FULL = """{"info":{"plot":"A storm closes the port.","rating":"7.5","releasedate":"2019-05-01","genre":"Drama","cast":"A, B","duration_secs":"7200"},"movie_data":{"stream_id":77}}"""
        const val EMPTY = """{"info":{},"movie_data":{}}"""
    }
}
