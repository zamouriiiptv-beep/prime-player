package com.castivio.data.parsing

import com.castivio.domain.CatalogItem
import com.castivio.domain.CatalogWriter
import com.castivio.domain.ImportMode
import com.castivio.domain.ImportProgress
import com.castivio.domain.ImportSummary
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Reader
import java.io.StringReader

class XtreamImportEngineTest {

    @Test
    fun `a catalogue import walks categories and writes their streams`() {
        val writer = RecordingWriter()
        val api = FakeApi(
            liveCategories = listOf("1" to "Sports", "2" to "News"),
            liveStreams = mapOf(
                "1" to listOf(stream("11", "Nova Sports 1"), stream("12", "Atlas Sport")),
                "2" to listOf(stream("13", "Atlas News")),
            ),
        )

        val summary = XtreamImportEngine(writer).importCatalogue(
            sourceId = "src",
            api = api,
            kinds = setOf(MediaKind.LIVE),
        )

        assertEquals(3, summary.items)
        assertEquals(2, summary.groups)
        assertEquals(3, summary.count(MediaKind.LIVE))
        assertEquals(listOf("Sports", "News"), writer.groups.map { it.name })
        assertEquals(
            listOf("Nova Sports 1", "Atlas Sport", "Atlas News"),
            writer.items.map { it.title },
        )
        assertEquals(summary, writer.finished)
        assertEquals(ImportMode.REPLACE, writer.mode)
    }

    /**
     * The whole point of the Xtream path: a category's streams are only fetched
     * when that category is imported, so nothing downloads the catalogue.
     */
    @Test
    fun `only the categories asked for are fetched`() {
        val writer = RecordingWriter()
        val api = FakeApi(
            liveCategories = listOf("1" to "Sports"),
            liveStreams = mapOf("1" to listOf(stream("11", "Nova"))),
            vodCategories = listOf("5" to "Movies"),
            vodStreams = mapOf("5" to listOf(stream("51", "Dune", extension = "mkv"))),
        )

        XtreamImportEngine(writer).importCatalogue("src", api, kinds = setOf(MediaKind.LIVE))

        assertEquals(listOf(MediaKind.LIVE), api.categoryRequests)
        assertEquals(listOf("1"), api.streamRequests.map { it.second })
        assertTrue("VOD must not be touched", api.streamRequests.none { it.first == MediaKind.MOVIE })
    }

    @Test
    fun `movies keep their container extension in the stream url`() {
        val writer = RecordingWriter()
        val api = FakeApi(
            vodCategories = listOf("5" to "Movies"),
            vodStreams = mapOf("5" to listOf(stream("51", "Dune", extension = "mkv"))),
        )

        XtreamImportEngine(writer).importCatalogue("src", api, kinds = setOf(MediaKind.MOVIE))

        assertEquals("http://host/movie/u/p/51.mkv", writer.items.single().streamUrl)
        assertEquals(MediaKind.MOVIE, writer.items.single().kind)
    }

    /**
     * Xtream has no radio endpoint — stations arrive in live categories, and the
     * category name is the only signal there is.
     */
    @Test
    fun `a radio category becomes radio, not live`() {
        val writer = RecordingWriter()
        val api = FakeApi(
            liveCategories = listOf("1" to "Sports", "2" to "| AR | RADIO"),
            liveStreams = mapOf(
                "1" to listOf(stream("11", "Nova Sports")),
                "2" to listOf(stream("21", "Radio Mars")),
            ),
        )

        val summary = XtreamImportEngine(writer).importCatalogue("src", api, kinds = setOf(MediaKind.LIVE))

        assertEquals(1, summary.count(MediaKind.LIVE))
        assertEquals(1, summary.count(MediaKind.RADIO))
        assertEquals(MediaKind.RADIO, writer.groups.first { it.name.contains("RADIO") }.kind)
        assertEquals(MediaKind.RADIO, writer.items.single { it.title == "Radio Mars" }.kind)
    }

    @Test
    fun `series are imported as shells with no episode numbers`() {
        val writer = RecordingWriter()
        val api = FakeApi(
            seriesCategories = listOf("9" to "Drama"),
            series = mapOf("9" to listOf("551" to "Chernobyl", "552" to "Breaking Bad")),
        )

        val summary = XtreamImportEngine(writer).importCatalogue("src", api, kinds = setOf(MediaKind.SERIES))

        assertEquals(2, summary.items)
        val chernobyl = writer.items.first { it.title == "Chernobyl" }
        assertEquals(MediaKind.SERIES, chernobyl.kind)
        assertEquals("Chernobyl", chernobyl.seriesTitle)
        // No episode number: the Series screen counts these as "not loaded yet"
        // rather than as a one-episode show.
        assertNull(chernobyl.episodeNumber)
        assertNull(chernobyl.seasonNumber)
        assertEquals("", chernobyl.streamUrl)
    }

    /**
     * Opening a show must not be able to delete the library. An APPEND write keeps
     * the current generation and prunes nothing.
     */
    @Test
    fun `lazily loaded episodes append rather than replace`() {
        val writer = RecordingWriter()
        val api = FakeApi(
            seriesInfo = mapOf(
                "551" to listOf(
                    Episode("9001", "1:23:45", 1, 1),
                    Episode("9002", "Please Remain Calm", 1, 2),
                ),
            ),
        )
        val engine = XtreamImportEngine(writer)

        val written = engine.importEpisodes(
            sourceId = "src",
            api = api,
            providerSeriesId = "551",
            seriesTitle = "Chernobyl",
            groupId = "group-9",
        )

        assertEquals(2, written)
        assertEquals(ImportMode.APPEND, writer.mode)
        assertEquals(listOf("1:23:45", "Please Remain Calm"), writer.items.map { it.title })
        assertEquals(listOf(1, 2), writer.items.map { it.episodeNumber })
        assertEquals("http://host/series/u/p/9001.mkv", writer.items.first().streamUrl)
        assertTrue(writer.items.all { it.seriesTitle == "Chernobyl" })
        assertTrue(writer.items.all { it.groupId == "group-9" })
    }

    @Test
    fun `an episode carries the same series id as its shell`() {
        val shellWriter = RecordingWriter()
        val episodeWriter = RecordingWriter()
        val api = FakeApi(
            seriesCategories = listOf("9" to "Drama"),
            series = mapOf("9" to listOf("551" to "Chernobyl")),
            seriesInfo = mapOf("551" to listOf(Episode("9001", "1:23:45", 1, 1))),
        )

        XtreamImportEngine(shellWriter).importCatalogue("src", api, kinds = setOf(MediaKind.SERIES))
        XtreamImportEngine(episodeWriter).importEpisodes("src", api, "551", "Chernobyl", null)

        assertEquals(
            "the season list depends on these matching",
            shellWriter.items.single().seriesId,
            episodeWriter.items.single().seriesId,
        )
    }

    @Test
    fun `ids come from provider stream ids, so credentials can change`() {
        val first = RecordingWriter()
        val second = RecordingWriter()
        val api = FakeApi(
            liveCategories = listOf("1" to "Sports"),
            liveStreams = mapOf("1" to listOf(stream("11", "Nova"))),
        )
        val rotated = FakeApi(
            liveCategories = listOf("1" to "Sports"),
            liveStreams = mapOf("1" to listOf(stream("11", "Nova"))),
            password = "new-password",
        )

        XtreamImportEngine(first).importCatalogue("src", api, kinds = setOf(MediaKind.LIVE))
        XtreamImportEngine(second).importCatalogue("src", rotated, kinds = setOf(MediaKind.LIVE))

        // The URL changed with the password; the id must not, or every favourite
        // and watch position would be orphaned by a password rotation.
        assertNotEquals(first.items.single().streamUrl, second.items.single().streamUrl)
        assertEquals(first.items.single().id, second.items.single().id)
    }

    @Test
    fun `rows are written in bounded batches with progress per category`() {
        val writer = RecordingWriter()
        val api = FakeApi(
            liveCategories = listOf("1" to "Big"),
            liveStreams = mapOf("1" to (0 until 250).map { stream("$it", "Channel $it") }),
        )
        val progress = mutableListOf<ImportProgress>()

        XtreamImportEngine(writer, batchSize = 100)
            .importCatalogue("src", api, kinds = setOf(MediaKind.LIVE), onProgress = progress::add)

        assertEquals(listOf(100, 100, 50), writer.batchSizes)
        assertEquals(1, writer.batchIdentities.size)
        assertEquals(250, (progress.last() as ImportProgress.Done).totalItems)
    }

    @Test
    fun `cancellation stops between categories and keeps what was written`() {
        val writer = RecordingWriter()
        val api = FakeApi(
            liveCategories = (1..10).map { "$it" to "Category $it" },
            liveStreams = (1..10).associate { "$it" to listOf(stream("s$it", "Channel $it")) },
        )

        val summary = XtreamImportEngine(writer)
            .importCatalogue("src", api, kinds = setOf(MediaKind.LIVE), isCancelled = { true })

        assertTrue(summary.cancelled)
        assertEquals(1, summary.items)
        assertEquals("only the first category should be fetched", 1, api.streamRequests.size)
        assertTrue(writer.aborted)
        assertNull(writer.finished)
    }

    @Test
    fun `a failure aborts and propagates`() {
        val writer = RecordingWriter()
        val api = FakeApi(
            liveCategories = listOf("1" to "Sports"),
            liveStreams = mapOf("1" to listOf(stream("11", "Nova"))),
            failOnStreams = true,
        )

        val thrown = runCatching {
            XtreamImportEngine(writer).importCatalogue("src", api, kinds = setOf(MediaKind.LIVE))
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertTrue(writer.aborted)
        assertNull(writer.finished)
    }

    @Test
    fun `every reader is closed`() {
        val writer = RecordingWriter()
        val api = FakeApi(
            liveCategories = listOf("1" to "Sports"),
            liveStreams = mapOf("1" to listOf(stream("11", "Nova"))),
        )

        XtreamImportEngine(writer).importCatalogue("src", api, kinds = setOf(MediaKind.LIVE))

        // A leaked response body holds a socket, and a TV box has few to spare.
        assertTrue("all readers must be closed", api.openReaders.all { it.closed })
        assertFalse(api.openReaders.isEmpty())
    }

    // ------------------------------------------------------------------ fixtures

    private data class Episode(val id: String, val title: String, val season: Int, val number: Int)

    private fun stream(id: String, name: String, extension: String? = null): String =
        """{"num":0,"name":"$name","stream_id":"$id","stream_icon":"http://cdn/$id.png",""" +
            """"epg_channel_id":"epg.$id","category_id":"1"""" +
            (extension?.let { ""","container_extension":"$it"""" } ?: "") + "}"

    private class TrackedReader(content: String) : Reader() {
        private val delegate = StringReader(content)
        var closed = false
            private set

        override fun read(cbuf: CharArray, off: Int, len: Int): Int = delegate.read(cbuf, off, len)

        override fun close() {
            closed = true
            delegate.close()
        }
    }

    private class FakeApi(
        private val liveCategories: List<Pair<String, String>> = emptyList(),
        private val liveStreams: Map<String, List<String>> = emptyMap(),
        private val vodCategories: List<Pair<String, String>> = emptyList(),
        private val vodStreams: Map<String, List<String>> = emptyMap(),
        private val seriesCategories: List<Pair<String, String>> = emptyList(),
        private val series: Map<String, List<Pair<String, String>>> = emptyMap(),
        private val seriesInfo: Map<String, List<Episode>> = emptyMap(),
        private val password: String = "p",
        private val failOnStreams: Boolean = false,
    ) : XtreamImportEngine.Api {

        val categoryRequests = mutableListOf<MediaKind>()
        val streamRequests = mutableListOf<Pair<MediaKind, String>>()
        val openReaders = mutableListOf<TrackedReader>()

        override fun categories(kind: MediaKind): Reader {
            categoryRequests += kind
            val rows = when (kind) {
                MediaKind.MOVIE -> vodCategories
                MediaKind.SERIES -> seriesCategories
                else -> liveCategories
            }
            return track(
                rows.joinToString(",", "[", "]") { (id, name) ->
                    """{"category_id":"$id","category_name":"$name"}"""
                },
            )
        }

        override fun streams(kind: MediaKind, categoryId: String): Reader {
            if (failOnStreams) throw IllegalStateException("provider returned 500")
            streamRequests += kind to categoryId
            val rows = when (kind) {
                MediaKind.MOVIE -> vodStreams[categoryId]
                else -> liveStreams[categoryId]
            }.orEmpty()
            return track(rows.joinToString(",", "[", "]"))
        }

        override fun series(categoryId: String): Reader {
            streamRequests += MediaKind.SERIES to categoryId
            val rows = series[categoryId].orEmpty()
            return track(
                rows.joinToString(",", "[", "]") { (id, name) ->
                    """{"series_id":"$id","name":"$name","cover":"http://cdn/$id.jpg","category_id":"$categoryId"}"""
                },
            )
        }

        override fun seriesInfo(seriesId: String): Reader {
            val episodes = seriesInfo[seriesId].orEmpty()
            val bySeason = episodes.groupBy { it.season }
            val body = bySeason.entries.joinToString(",") { (season, list) ->
                """"$season":""" + list.joinToString(",", "[", "]") { episode ->
                    """{"id":"${episode.id}","episode_num":${episode.number},"title":"${episode.title}",""" +
                        """"container_extension":"mkv","info":{"duration_secs":3600}}"""
                }
            }
            return track("""{"episodes":{$body}}""")
        }

        override fun streamUrl(kind: MediaKind, streamId: String, extension: String?): String =
            XtreamUrls.stream("http://host", "u", password, kind, streamId, extension)

        private fun track(content: String): Reader = TrackedReader(content).also { openReaders += it }
    }

    private class RecordingWriter : CatalogWriter {
        val batchSizes = mutableListOf<Int>()
        val batchIdentities = mutableSetOf<Int>()
        val items = mutableListOf<CatalogItem>()
        val groups = mutableListOf<MediaGroup>()
        var mode: ImportMode? = null
        var finished: ImportSummary? = null
        var aborted = false

        override fun begin(sourceId: String, mode: ImportMode) {
            this.mode = mode
        }

        override fun writeGroups(groups: List<MediaGroup>) {
            this.groups += groups
        }

        override fun writeItems(items: List<CatalogItem>) {
            batchSizes += items.size
            batchIdentities += System.identityHashCode(items)
            this.items += items
        }

        override fun commit() = Unit

        override fun finish(summary: ImportSummary) {
            finished = summary
        }

        override fun abort(cause: Throwable?) {
            aborted = true
        }
    }
}
