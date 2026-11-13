package com.castivio.data.playlist

import com.castivio.core.common.metadata.IdCoverage
import com.castivio.data.networking.HttpStatusException
import com.castivio.data.networking.XtreamHttpApi
import com.castivio.data.parsing.XtreamParser
import com.castivio.data.parsing.XtreamVodInfo
import com.castivio.domain.Movie
import com.castivio.domain.MovieDetails
import com.castivio.domain.MovieDetailsCache
import com.castivio.domain.MovieDetailsRepository
import com.castivio.domain.PlaylistSource
import com.castivio.domain.RequestTimes
import com.castivio.domain.SourceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * A film's details from its provider, asked for until it answers, and kept.
 *
 * The cache is read first, so a film whose page was opened before costs no request. A provider that cannot
 * be reached, or gives a reply the parser does not accept, yields no details rather than an error: the page
 * then has less to show and the film still plays. Nothing is asked of a film with no provider id.
 *
 * **An empty answer is not believed at once.** A provider that has not yet built a film's details answers with
 * nothing the first time and with all of them a moment later, so the asking is repeated after two, four and
 * seven seconds before it gives up. Only a provider that *answered* nothing every time is remembered as having
 * nothing, and only for a while: a film whose asking failed on the way -- a weak connection, a timeout -- is
 * remembered as nothing at all, because the next page may well reach the provider.
 *
 * **A film kept before its ids were read is asked once more, behind its page.** The cache has no expiry, so the details of every
 * film opened before the provider's TMDB and IMDb ids were read were kept without them and would be for good. Such a film is
 * drawn from what is kept, at once, and its provider is asked again -- once in a run, in the background -- and the reply, which
 * is read for the ids, takes the place of the old row. Nothing waits on it, and nothing it does can fail a page.
 */
class DefaultMovieDetails(
    private val cache: MovieDetailsCache,
    private val sources: SourceRepository,
    private val client: OkHttpClient,
    private val dispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
) : MovieDetailsRepository {

    /** Films whose provider answered nothing every time it was asked, and when: left alone for [MISS_MEMORY_MS]. */
    private val misses = ConcurrentHashMap<String, Long>()

    /**
     * Films kept before their ids were read that have been asked of their provider once in this run: see [askForIds]. A set made
     * of a map, because `ConcurrentHashMap.newKeySet` is API 24 and this module ships to API 23.
     */
    private val idsAsked: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    private val accounts = AccountLookup(sources, clock)

    /** Where what a provider said is kept: behind the page that asked for it, never in front of it. */
    private val keeping = CoroutineScope(SupervisorJob() + dispatcher)

    override suspend fun details(movie: Movie, onTimes: (RequestTimes) -> Unit): MovieDetails? {
        val sourceId = movie.sourceId ?: return null
        // The provider's own id: kept on the row by the API imports, and inside the stream's address for a
        // film read from a panel's playlist file, which keeps no id of its own.
        val providerRef = movie.providerRef ?: xtreamVodId(movie.streamUrl) ?: return null
        val key = "$sourceId/$providerRef"
        val lookupStarted = clock()
        // The cache and the account are two reads of the catalogue, and the page waits on whichever is slower: made
        // together. A portal's details are written with its list, so the cache is the whole answer for one.
        val (kept, xtream) = coroutineScope {
            val kept = async { cache.get(sourceId, providerRef) }
            val account = async { accounts.of(sourceId) }
            kept.await() to account.await()
        }
        kept?.let { held ->
            // Kept before the ids were read: the page is drawn from what is kept, and the provider is asked again behind it.
            // A portal's details are written with its list and nobody can be asked for them (`xtream` is null), so they stay.
            if (!held.idsRead && xtream != null) askForIds(sourceId, providerRef, xtream, key)
            return held
        }
        misses[key]?.let { if (clock() - it < MISS_MEMORY_MS) return null }
        // The panel account the film is read through -- see [AccountLookup].
        xtream ?: return null
        val lookupMs = clock() - lookupStarted

        var tries = 0
        var answeredNothing = 0
        var lastRead: Reply? = null
        while (true) {
            tries++
            val reply = ask(xtream, providerRef)
            onTimes(reply.times.copy(tries = tries, lookupMs = lookupMs))
            if (reply.definitive && reply.refusedAccount) accounts.forget(sourceId)
            if (reply.parsed) lastRead = reply
            if (reply.details != null) {
                // The page has its details now; the cache keeps them behind it.
                val details = reply.details
                count(reply)
                keeping.launch { runCatching { cache.put(sourceId, providerRef, details, clock()) } }
                return details
            }
            if (reply.answered) answeredNothing++
            // A provider that said no in so many words is not asked again, and nor is one that is out of tries.
            if (reply.definitive || tries > RETRY_DELAYS_MS.size) break
            delay(RETRY_DELAYS_MS[tries - 1])
        }
        // Counted once for the film, and only if the provider gave a reply that was read: a refusal or a failed asking says
        // nothing of whether it has ids.
        lastRead?.let { count(it) }
        if (answeredNothing == tries) misses[key] = clock()
        return null
    }

    /**
     * Asks the provider once more for a film whose details were kept before the ids were read, behind the page that is drawn
     * from what is kept, and keeps the reply -- which holds the ids, and is read for them -- in the place of the old row.
     *
     * Once in a run for each film: a provider that cannot be reached, or that answers nothing now, leaves the row as it was and is
     * asked again by the next run, and never by a page opened a second time in this one. A courtesy to a feature that is not built
     * yet, so it takes one asking and no retry, and whatever goes wrong with it is nobody's to hear.
     */
    private fun askForIds(sourceId: String, providerRef: String, xtream: PlaylistSource.Xtream, key: String) {
        if (!idsAsked.add(key)) return
        keeping.launch {
            try {
                val reply = ask(xtream, providerRef)
                if (reply.definitive && reply.refusedAccount) accounts.forget(sourceId)
                if (reply.parsed) count(reply)
                reply.details?.let { cache.put(sourceId, providerRef, it, clock()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The film's page is drawn, and the next run asks again.
            }
        }
    }

    /** What a reply that was read says of the provider's ids, for the strip's count: a reply with no details says it has none. */
    private fun count(reply: Reply) {
        IdCoverage.film(tmdb = reply.details?.tmdbId != null, imdb = reply.details?.imdbId != null)
    }

    /** One asking: what came, whether the provider answered at all, and what it cost. */
    private class Reply(
        val details: MovieDetails?,
        val answered: Boolean,
        val times: RequestTimes,
        /** The provider refused in so many words, and asking again would only hear it again. */
        val definitive: Boolean = false,
        /** What it refused was the account itself, so the account is read again before it is used again. */
        val refusedAccount: Boolean = false,
        /** The provider's reply came and was read -- with details or without -- as against a refusal or a failed asking. */
        val parsed: Boolean = false,
    )

    private suspend fun ask(xtream: PlaylistSource.Xtream, providerRef: String): Reply = withContext(dispatcher) {
        val askStarted = clock()
        try {
            val reader = XtreamHttpApi(client, xtream.host, xtream.username, xtream.password).vodInfo(providerRef)
                ?: return@withContext Reply(null, answered = false, times = RequestTimes(clock() - askStarted, 0), definitive = true)
            val askMs = clock() - askStarted
            val readStarted = clock()
            val info = reader.use { XtreamParser.parseVodInfo(it) }
            Reply(
                details = info?.toDomain()?.takeIf { it != MovieDetails() },
                answered = true,
                times = RequestTimes(askMs, clock() - readStarted),
                parsed = true,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpStatusException) {
            // The provider answered, with a refusal. Only a busy or timed-out one is worth asking again.
            val busy = e.statusCode == 408 || e.statusCode == 429 || e.statusCode in 500..599
            Reply(
                null,
                answered = !busy,
                times = RequestTimes(clock() - askStarted, 0),
                definitive = !busy,
                refusedAccount = e.statusCode == 401 || e.statusCode == 403,
            )
        } catch (e: IOException) {
            Reply(null, answered = false, times = RequestTimes(clock() - askStarted, 0))
        }
    }

    private fun XtreamVodInfo.toDomain(): MovieDetails = MovieDetails(
        rating = rating,
        plot = plot,
        director = director,
        cast = cast,
        genres = genres,
        releaseYear = releaseYear,
        durationSeconds = durationSeconds,
        addedEpochSeconds = addedEpochSeconds,
        tmdbId = tmdbId,
        imdbId = imdbId,
    )
}

/**
 * The film id inside an Xtream film's address, `http://host/movie/user/pass/12345.mkv`, or null for an
 * address of another shape. What `get_vod_info` is asked by when the row kept no id of its own.
 */
internal fun xtreamVodId(streamUrl: String): String? =
    VOD_ADDRESS.find(streamUrl.substringBefore('?'))?.groupValues?.get(1)

private val VOD_ADDRESS = Regex("""/movie/[^/]+/[^/]+/(\d+)(?:\.[A-Za-z0-9]+)?$""")

/** How long a film whose provider answered nothing every time is left alone: a reopened page asks nothing until then. */
private const val MISS_MEMORY_MS = 10 * 60 * 1000L

/** The waits before each asking after the first: two, four and seven seconds, and the fourth answer is believed. */
private val RETRY_DELAYS_MS = longArrayOf(2_000, 4_000, 7_000)
