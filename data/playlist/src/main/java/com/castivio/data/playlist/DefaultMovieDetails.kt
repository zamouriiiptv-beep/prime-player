package com.castivio.data.playlist

import com.castivio.data.networking.XtreamHttpApi
import com.castivio.data.parsing.XtreamParser
import com.castivio.data.parsing.XtreamVodInfo
import com.castivio.domain.Movie
import com.castivio.domain.MovieDetails
import com.castivio.domain.MovieDetailsCache
import com.castivio.domain.MovieDetailsRepository
import com.castivio.domain.PlaylistSource
import com.castivio.domain.SourceRepository
import com.castivio.domain.asAccountSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * A film's details from its provider, asked for once and kept.
 *
 * The cache is read first, so a film whose page was opened before costs no request. A provider that cannot
 * be reached, or gives a reply the parser does not accept, yields no details rather than an error: the page
 * then has less to show and the film still plays. Nothing is asked of a film with no provider id.
 */
class DefaultMovieDetails(
    private val cache: MovieDetailsCache,
    private val sources: SourceRepository,
    private val client: OkHttpClient,
    private val dispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
) : MovieDetailsRepository {

    /** Films asked for whose provider gave nothing, and when: not asked again for [MISS_MEMORY_MS]. */
    private val misses = ConcurrentHashMap<String, Long>()

    override suspend fun details(movie: Movie): MovieDetails? {
        val sourceId = movie.sourceId ?: return null
        // The provider's own id: kept on the row by the API imports, and inside the stream's address for a
        // film read from a panel's playlist file, which keeps no id of its own.
        val providerRef = movie.providerRef ?: xtreamVodId(movie.streamUrl) ?: return null
        // A portal's details are written with its list, so the cache is the whole answer for one.
        cache.get(sourceId, providerRef)?.let { return it }

        val key = "$sourceId/$providerRef"
        misses[key]?.let { if (clock() - it < MISS_MEMORY_MS) return null }

        // The panel account the film is read through: the source itself for Xtream, and the account a
        // playlist's link names for M3U -- whether its films were read through the panel or from the file.
        val xtream = when (val source = sources.get(sourceId)?.asAccountSource()) {
            is PlaylistSource.Xtream -> source
            is PlaylistSource.M3u -> source.xtream
            else -> null
        } ?: return null
        val info = withContext(dispatcher) {
            try {
                XtreamHttpApi(client, xtream.host, xtream.username, xtream.password)
                    .vodInfo(providerRef)
                    ?.use { XtreamParser.parseVodInfo(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                null
            }
        }?.toDomain()?.takeIf { it != MovieDetails() }

        if (info == null) {
            misses[key] = clock()
            return null
        }
        cache.put(sourceId, providerRef, info, clock())
        return info
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
    )
}

/**
 * The film id inside an Xtream film's address, `http://host/movie/user/pass/12345.mkv`, or null for an
 * address of another shape. What `get_vod_info` is asked by when the row kept no id of its own.
 */
internal fun xtreamVodId(streamUrl: String): String? =
    VOD_ADDRESS.find(streamUrl.substringBefore('?'))?.groupValues?.get(1)

private val VOD_ADDRESS = Regex("""/movie/[^/]+/[^/]+/(\d+)(?:\.[A-Za-z0-9]+)?$""")

/** How long a film whose provider gave no details is left alone: a reopened page asks nothing until then. */
private const val MISS_MEMORY_MS = 10 * 60 * 1000L
