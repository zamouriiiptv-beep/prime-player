package com.castivio.data.playlist

import com.castivio.core.common.metadata.IdCoverage
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
import org.junit.Assert.assertFalse
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
        IdCoverage.reset()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        IdCoverage.reset()
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
        val account = account()
        val port = server.port
        server.shutdown()
        val repository = DefaultMovieDetails(cache, OneSource(account), OkHttpClient(), Dispatchers.Unconfined) { now }
        assertNull(repository.details(dune))

        // The same repository, and the same provider, back up: it is asked, not excused by the failure.
        val back = MockWebServer().also { it.start(port) }
        try {
            back.enqueue(full())
            assertEquals("A storm closes the port.", repository.details(dune)?.plot)
            assertEquals(1, back.requestCount)
        } finally {
            back.shutdown()
        }
    }

    /** The page has the details when the provider has answered; the cache keeps them behind it. */
    @Test
    fun `the details are handed back without waiting for the cache to keep them`() = runTest {
        server.enqueue(full())
        val slow = object : MovieDetailsCache {
            override suspend fun get(sourceId: String, providerRef: String): MovieDetails? = null
            override suspend fun put(sourceId: String, providerRef: String, details: MovieDetails, now: Long) {
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val repository = DefaultMovieDetails(slow, OneSource(account()), OkHttpClient(), Dispatchers.Unconfined) { now }

        val details = repository.details(dune)

        assertEquals("A storm closes the port.", details?.plot)
    }

    @Test
    fun `the account is read once for the subscription, with the cache, and not again for the next film`() = runTest {
        server.enqueue(full())
        server.enqueue(full())
        val source = OneSource(account())
        val repository = DefaultMovieDetails(cache, source, OkHttpClient(), Dispatchers.Unconfined) { now }

        repository.details(dune)
        repository.details(dune.copy(id = "m2", providerRef = "78", streamUrl = "http://panel/movie/u/p/78.mkv"))

        assertEquals(1, source.gets)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `what the lookups cost is told with the asking`() = runTest {
        server.enqueue(full())
        val told = mutableListOf<RequestTimes>()

        repository().details(dune) { told += it }

        assertTrue(told.single().lookupMs >= 0)
    }

    @Test
    fun `a film with no provider id has nobody to ask`() = runTest {
        val m3u = dune.copy(providerRef = null, streamUrl = "http://panel/live/x.ts")

        assertNull(repository().details(m3u))
        assertEquals(0, server.requestCount)
    }

    // ------------------------------------------------------------------ the film's numbers elsewhere

    @Test
    fun `the ids the provider sends are kept with the film's details`() = runTest {
        server.enqueue(MockResponse().setBody(WITH_IDS))

        val details = repository().details(dune)

        assertEquals(603L, details?.tmdbId)
        assertEquals("tt0133093", details?.imdbId)
        assertEquals("what the cache keeps holds them too", 603L, cache.get("src", "77")?.tmdbId)
        assertEquals("tt0133093", cache.get("src", "77")?.imdbId)
    }

    @Test
    fun `a film the provider gave no ids has none, and was read for them`() = runTest {
        server.enqueue(full())

        val details = repository().details(dune)

        assertNull(details?.tmdbId)
        assertNull(details?.imdbId)
        assertTrue(details!!.idsRead)
    }

    /**
     * The cache has no expiry, so a film opened before the ids were read would be without them for good. It is drawn from what is
     * kept -- the page does not wait -- and its provider is asked again, once, behind it.
     */
    @Test
    fun `a film kept before its ids were read is drawn from what is kept, and asked of its provider once more behind the page`() = runTest {
        cache.held["src/77"] = MovieDetails(plot = "Kept before.", idsRead = false)
        server.enqueue(MockResponse().setBody(WITH_IDS))
        val repository = repository()

        val first = repository.details(dune)

        assertEquals("the page is drawn from what was kept", "Kept before.", first?.plot)
        assertFalse(first!!.idsRead)
        assertEquals("and the provider is asked once, behind it", 1, server.requestCount)
        val replaced = cache.get("src", "77")!!
        assertEquals("the reply read for ids takes the old row's place", 603L, replaced.tmdbId)
        assertTrue(replaced.idsRead)
        assertEquals("A storm closes the port.", replaced.plot)

        val next = repository.details(dune)

        assertEquals("the next page draws what is kept now", 603L, next?.tmdbId)
        assertEquals("and asks nothing", 1, server.requestCount)
    }

    @Test
    fun `a film kept before its ids were read is asked once in a run, whatever the provider says`() = runTest {
        cache.held["src/77"] = MovieDetails(plot = "Kept before.", idsRead = false)
        server.enqueue(empty())
        val repository = repository()

        repository.details(dune)
        repository.details(dune)

        assertEquals(1, server.requestCount)
        assertEquals("a provider that says nothing now leaves the row as it was", "Kept before.", cache.get("src", "77")?.plot)
        assertFalse(cache.get("src", "77")!!.idsRead)
        assertEquals(0, cache.puts)
    }

    @Test
    fun `a film kept with its ids read is not asked again`() = runTest {
        cache.held["src/77"] = MovieDetails(plot = "Kept.", tmdbId = 603L)

        val details = repository().details(dune)

        assertEquals("Kept.", details?.plot)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a film kept before its ids were read, whose provider cannot be reached, keeps what it had and fails nothing`() = runTest {
        cache.held["src/77"] = MovieDetails(plot = "Kept before.", idsRead = false)
        val account = account()
        server.shutdown()
        val repository = DefaultMovieDetails(cache, OneSource(account), OkHttpClient(), Dispatchers.Unconfined) { now }

        val details = repository.details(dune)

        assertEquals("Kept before.", details?.plot)
        assertEquals(0, cache.puts)
        assertEquals("a failed asking says nothing of the provider's ids", 0L, IdCoverage.figures().films)
    }

    @Test
    fun `a film a portal's list wrote is not asked for, since no panel can be asked for it`() = runTest {
        cache.held["src/77"] = MovieDetails(plot = "From the portal.", idsRead = false)
        val repository = DefaultMovieDetails(cache, OneSource(null), OkHttpClient(), Dispatchers.Unconfined) { now }

        val details = repository.details(dune)

        assertEquals("From the portal.", details?.plot)
        assertEquals(0, server.requestCount)
    }

    // ------------------------------------------------------------------ how often the provider says them

    @Test
    fun `a reply with ids is counted as covered, and one without as not`() = runTest {
        server.enqueue(MockResponse().setBody(WITH_IDS))
        server.enqueue(full())
        val repository = repository()

        repository.details(dune)
        repository.details(dune.copy(id = "m2", providerRef = "78", streamUrl = "http://panel/movie/u/p/78.mkv"))

        val figures = IdCoverage.figures()
        assertEquals(2L, figures.films)
        assertEquals(1L, figures.filmsWithTmdb)
        assertEquals(1L, figures.filmsWithImdb)
    }

    @Test
    fun `a film whose provider answered nothing every time is counted once, not once for each asking`() = runTest {
        repeat(4) { server.enqueue(empty()) }

        repository().details(dune)

        assertEquals(4, server.requestCount)
        assertEquals(1L, IdCoverage.figures().films)
        assertEquals(0L, IdCoverage.figures().filmsWithTmdb)
    }

    @Test
    fun `a refusal says nothing of the provider's ids, and is not counted`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        repository().details(dune)

        assertEquals(0L, IdCoverage.figures().films)
    }

    @Test
    fun `a film is counted when it is read, and not again each time its page is opened`() = runTest {
        server.enqueue(MockResponse().setBody(WITH_IDS))
        val repository = repository()

        repository.details(dune)
        repository.details(dune)
        repository.details(dune)

        assertEquals(1L, IdCoverage.figures().films)
    }

    private class MemoryCache : MovieDetailsCache {
        val held = mutableMapOf<String, MovieDetails>()
        var puts = 0
        override suspend fun get(sourceId: String, providerRef: String): MovieDetails? = held["$sourceId/$providerRef"]
        override suspend fun put(sourceId: String, providerRef: String, details: MovieDetails, now: Long) {
            puts++
            held["$sourceId/$providerRef"] = details
        }
    }

    private class OneSource(var source: ProviderSource?) : SourceRepository {
        var gets = 0
        override suspend fun register(source: PlaylistSource, label: String?): ProviderSource = error("not asked")
        override fun sources(): Flow<List<ProviderSource>> = emptyFlow()
        override fun active(): Flow<ProviderSource?> = emptyFlow()
        override suspend fun activeNow(): ProviderSource? = source
        override suspend fun get(id: String): ProviderSource? {
            gets++
            return source
        }
        override suspend fun save(source: ProviderSource) = Unit
        override suspend fun setActive(id: String) = Unit
        override suspend fun recordCatalogueImport(id: String, sync: SyncState) = Unit
        override suspend fun recordEpgImport(id: String, atMs: Long) = Unit
        override suspend fun delete(id: String) = Unit
    }

    private companion object {
        const val FULL = """{"info":{"plot":"A storm closes the port.","rating":"7.5","releasedate":"2019-05-01","genre":"Drama","cast":"A, B","duration_secs":"7200"},"movie_data":{"stream_id":77}}"""
        const val EMPTY = """{"info":{},"movie_data":{}}"""
        const val WITH_IDS = """{"info":{"plot":"A storm closes the port.","tmdb_id":"603","imdb_id":"tt0133093"},"movie_data":{}}"""
    }
}
