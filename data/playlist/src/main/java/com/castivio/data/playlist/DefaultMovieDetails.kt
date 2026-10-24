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
import com.castivio.domain.asPlaylistSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException

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

    override suspend fun details(movie: Movie): MovieDetails? {
        val sourceId = movie.sourceId ?: return null
        val providerRef = movie.providerRef ?: return null
        cache.get(sourceId, providerRef)?.let { return it }

        val xtream = sources.get(sourceId)?.asPlaylistSource() as? PlaylistSource.Xtream ?: return null
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
        }?.toDomain() ?: return null

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
