package com.castivio.data.playlist

import androidx.paging.PagingData
import com.castivio.domain.CatalogItem
import com.castivio.domain.CatalogPager
import com.castivio.domain.CatalogQuery
import com.castivio.domain.CatalogWriter
import com.castivio.domain.EpisodesLoad
import com.castivio.domain.ImportMode
import com.castivio.domain.ImportSummary
import com.castivio.domain.InProgressItem
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaItem
import com.castivio.domain.MediaKind
import com.castivio.domain.PlaylistSource
import com.castivio.domain.ProviderSource
import com.castivio.domain.Season
import com.castivio.domain.SeriesShell
import com.castivio.domain.SeriesSummary
import com.castivio.domain.SourceKind
import com.castivio.domain.SourceRepository
import com.castivio.domain.SyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A show's episodes asked of its provider, against a server that answers like a panel.
 *
 * What matters is what reaches the catalogue and how the load ends: the episodes are written as an append -- a
 * replacement would delete the library -- under the show's own id, and a provider that cannot be reached is a
 * failure the page can say, never an exception out of the load.
 */
class DefaultSeriesEpisodesTest {

    private lateinit var server: MockWebServer
    private val writer = RecordingWriter()

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun loader(shell: SeriesShell? = SHELL, source: ProviderSource? = account()) = DefaultSeriesEpisodes(
        pager = ShellPager(shell),
        sources = OneSource(source),
        client = OkHttpClient(),
        writers = { writer },
        dispatcher = Dispatchers.Unconfined,
    )

    private fun account() = ProviderSource(
        id = "src",
        kind = SourceKind.XTREAM,
        label = "panel",
        url = server.url("/").toString().trimEnd('/'),
        username = "u",
        password = "p",
    )

    @Test
    fun `the episodes the provider lists are written as an append, under the show's id`() = runBlocking {
        server.enqueue(MockResponse().setBody(SERIES_INFO))

        val load = loader().load("show-1")

        assertEquals(EpisodesLoad.Loaded(2), load)
        assertEquals(listOf("src" to ImportMode.APPEND), writer.began)
        assertEquals(listOf("The Tide Table", "Low Water"), writer.items.map { it.title })
        assertEquals(setOf(1), writer.items.map { it.seasonNumber }.toSet())
        assertEquals(listOf(46 * 60 + 20, null), writer.items.map { it.durationSeconds })
        assertTrue(writer.items.all { it.seriesTitle == "Dark Harbor" && it.groupId == "g1" })
        // Asked by the provider's own id for the show.
        val request = server.takeRequest()
        assertTrue(request.path!!.contains("action=get_series_info"))
        assertTrue(request.path!!.contains("series_id=551"))
    }

    @Test
    fun `a show with no row of its own has nobody to ask`() = runBlocking {
        assertEquals(EpisodesLoad.NoProvider, loader(shell = null).load("show-1"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a source that is not a panel account has nobody to ask`() = runBlocking {
        val file = ProviderSource(id = "src", kind = SourceKind.LOCAL_FILE, label = "file", url = "content://x")

        assertEquals(EpisodesLoad.NoProvider, loader(source = file).load("show-1"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a provider that answers nothing ends as a failure and writes nothing`() = runBlocking {
        server.shutdown()

        val load = loader().load("show-1")

        assertTrue("was $load", load is EpisodesLoad.Failed)
        assertTrue(writer.items.isEmpty())
    }

    private class ShellPager(private val shell: SeriesShell?) : CatalogPager {
        override fun items(query: CatalogQuery): Flow<PagingData<MediaItem>> = emptyFlow()
        override fun series(query: CatalogQuery): Flow<PagingData<SeriesSummary>> = emptyFlow()
        override fun seasons(seriesId: String): Flow<List<Season>> = emptyFlow()
        override fun favorites(): Flow<PagingData<MediaItem>> = emptyFlow()
        override fun continueWatching(): Flow<PagingData<InProgressItem>> = emptyFlow()
        override fun history(): Flow<PagingData<InProgressItem>> = emptyFlow()
        override suspend fun seriesShell(seriesId: String): SeriesShell? = shell
    }

    private class OneSource(private val source: ProviderSource?) : SourceRepository {
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

    private class RecordingWriter : CatalogWriter {
        val items = mutableListOf<CatalogItem>()
        val began = mutableListOf<Pair<String, ImportMode>>()
        override fun begin(sourceId: String, mode: ImportMode, sections: Set<MediaKind>) {
            began += sourceId to mode
        }
        override fun writeGroups(groups: List<MediaGroup>) = Unit
        override fun writeItems(items: List<CatalogItem>) {
            this.items += items
        }
        override fun commit() = Unit
        override fun finish(summary: ImportSummary) = Unit
        override fun abort(cause: Throwable?) = Unit
    }

    private companion object {
        val SHELL = SeriesShell(sourceId = "src", providerRef = "551", title = "Dark Harbor", groupId = "g1")

        const val SERIES_INFO = """{
          "info": {"name": "Dark Harbor"},
          "episodes": {
            "1": [
              {"id": "9001", "title": "The Tide Table", "episode_num": 1, "container_extension": "mkv",
               "info": {"duration_secs": 2780, "movie_image": "http://img/e1.jpg"}},
              {"id": "9002", "title": "Low Water", "episode_num": 2, "container_extension": "mkv", "info": {}}
            ]
          }
        }"""
    }
}
