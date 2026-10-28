package com.castivio.data.playlist

import com.castivio.data.networking.XtreamHttpApi
import com.castivio.data.parsing.XtreamImportEngine
import com.castivio.data.parsing.XtreamSeriesInfo
import com.castivio.domain.CatalogPager
import com.castivio.domain.CatalogWriter
import com.castivio.domain.EpisodesLoad
import com.castivio.domain.PlaylistSource
import com.castivio.domain.RequestTimes
import com.castivio.domain.SeriesDetails
import com.castivio.domain.SeriesDetailsStore
import com.castivio.domain.SeriesEpisodes
import com.castivio.domain.SourceRepository
import com.castivio.domain.asAccountSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * A show's episodes from its provider, written to the catalogue when the show is opened.
 *
 * The show's own row says which provider it is from and by what id that provider knows it; the account is the
 * source itself for Xtream, and the account a playlist's link names for M3U. The episodes are written with an
 * append, which prunes nothing -- a replacement here would delete the rest of the library -- and a show already
 * holding episodes is written over with what the provider says now.
 *
 * The same reply carries what the provider says of the show itself, and of each episode: it is kept in [details]
 * once the episodes are written, so the page that asked for the episodes has the rating, the plot and the cast as
 * well without a second request.
 *
 * A provider that cannot be reached, or answers with something that is not a list of episodes, ends as
 * [EpisodesLoad.Failed], never as a throw: the page then says it could not be read, and the episodes it already
 * had stay as they were.
 */
class DefaultSeriesEpisodes(
    private val pager: CatalogPager,
    private val sources: SourceRepository,
    private val client: OkHttpClient,
    private val writers: () -> CatalogWriter,
    private val details: SeriesDetailsStore,
    private val dispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) : SeriesEpisodes {

    override suspend fun load(seriesId: String): EpisodesLoad {
        val shell = pager.seriesShell(seriesId) ?: return EpisodesLoad.NoProvider
        val account = when (val source = sources.get(shell.sourceId)?.asAccountSource()) {
            is PlaylistSource.Xtream -> source
            is PlaylistSource.M3u -> source.xtream
            else -> null
        } ?: return EpisodesLoad.NoProvider

        return withContext(dispatcher) {
            try {
                var said: XtreamSeriesInfo? = null
                var plots: Map<String, String> = emptyMap()
                var times: RequestTimes? = null
                val written = XtreamImportEngine(writers(), clock = clock).importEpisodes(
                    sourceId = shell.sourceId,
                    api = XtreamHttpApi(client, account.host, account.username, account.password),
                    providerSeriesId = shell.providerRef,
                    seriesTitle = shell.title,
                    groupId = shell.groupId,
                    onDetails = { info, episodePlots ->
                        said = info
                        plots = episodePlots
                    },
                    onTimes = { times = it },
                )
                // The episodes are in; what the provider said of them is a courtesy, and failing to keep it
                // must not turn a page that has its episodes into one that says it could not be read.
                runCatching { details.put(seriesId, said?.toDomain(), plots, clock()) }
                    .onFailure { if (it is CancellationException) throw it }
                EpisodesLoad.Loaded(written, times)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                EpisodesLoad.Failed(e)
            } catch (e: RuntimeException) {
                // A reply the parser does not accept, or a write that did not take: the page says it failed.
                EpisodesLoad.Failed(e)
            }
        }
    }
}

private fun XtreamSeriesInfo.toDomain() = SeriesDetails(
    rating = rating,
    plot = plot,
    cast = cast,
    genres = genres,
    releaseYear = releaseYear,
    backdropUrl = backdropUrl,
)
