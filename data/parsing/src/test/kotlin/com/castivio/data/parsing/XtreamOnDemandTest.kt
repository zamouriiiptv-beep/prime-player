package com.castivio.data.parsing

import com.castivio.domain.CatalogItem
import com.castivio.domain.CatalogWriter
import com.castivio.domain.ImportMode
import com.castivio.domain.ImportSummary
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Reader
import java.io.StringReader

/**
 * The contract the whole flow rests on: opening one thing fetches one thing.
 *
 * These are the assertions that would catch the defect coming back. It is easy to
 * write code that looks lazy and quietly is not — a loop that "just" lists the other
 * kinds to get their counts, a category screen that prefetches the first category to
 * feel fast — and none of that is visible from the screen. What is visible is the
 * request log, so the request log is what is asserted.
 */
class XtreamOnDemandTest {

    // ------------------------------------------------------------------ categories

    /**
     * Listing a section's categories asks for that section, and writes no content.
     *
     * The single most important claim here. Pressing Channels must not cost films, and
     * must not cost channels either — a category list is names, and the channels behind
     * them are a separate request the user has not asked for yet.
     */
    @Test
    fun `listing categories fetches one endpoint and no streams at all`() {
        val writer = RecordingWriter()
        val api = FakeApi(
            liveCategories = listOf("1" to "Sports", "2" to "News"),
            liveStreams = mapOf("1" to listOf(stream("11", "Nova"))),
            vodCategories = listOf("5" to "Movies"),
            seriesCategories = listOf("9" to "Drama"),
        )

        val groups = XtreamImportEngine(writer).importCategories("src", api, MediaKind.LIVE)

        assertEquals(listOf("Sports", "News"), groups.map { it.name })
        assertEquals("only the live endpoint", listOf(MediaKind.LIVE), api.categoryRequests)
        assertTrue("no category's contents were fetched", api.streamRequests.isEmpty())
        assertTrue("no rows were written", writer.items.isEmpty())
    }

    /** The provider's own id is kept, because nothing else can ask for the contents. */
    @Test
    fun `a listed category keeps the id its contents are addressed by`() {
        val writer = RecordingWriter()
        val api = FakeApi(liveCategories = listOf("42" to "UK General"))

        val groups = XtreamImportEngine(writer).importCategories("src", api, MediaKind.LIVE)

        assertEquals("42", groups.single().providerRef)
        assertEquals("42", writer.groups.single().providerRef)
    }

    /**
     * `APPEND`, and it matters more here than anywhere.
     *
     * A `REPLACE` write of one kind's categories would prune every row that is not one
     * of them — which is every film, every show, and every channel already fetched.
     */
    @Test
    fun `listing categories never prunes what other sections already loaded`() {
        val writer = RecordingWriter()
        XtreamImportEngine(writer).importCategories(
            "src",
            FakeApi(liveCategories = listOf("1" to "Sports")),
            MediaKind.LIVE,
        )

        assertEquals(ImportMode.APPEND, writer.mode)
    }

    /**
     * Live and radio come out of one request, each landing under its own kind.
     *
     * Xtream has no radio endpoint — stations sit in live categories and the name is
     * the only signal — so asking for channels lists both and files them apart. The
     * caller gets back only the kind it asked for.
     */
    @Test
    fun `a radio category is filed as radio and left out of the channel list`() {
        val writer = RecordingWriter()
        val api = FakeApi(liveCategories = listOf("1" to "Sports", "2" to "RADIO ARABIC"))

        val channels = XtreamImportEngine(writer).importCategories("src", api, MediaKind.LIVE)

        assertEquals(listOf("Sports"), channels.map { it.name })
        assertEquals(
            listOf(MediaKind.LIVE, MediaKind.RADIO),
            writer.groups.map { it.kind },
        )
    }

    // -------------------------------------------------------------- one category

    /** Opening a category fetches that category, and asks for no other. */
    @Test
    fun `opening a category fetches only that category`() {
        val writer = RecordingWriter()
        val api = FakeApi(
            liveCategories = listOf("1" to "Sports", "2" to "News"),
            liveStreams = mapOf(
                "1" to listOf(stream("11", "Nova"), stream("12", "Atlas")),
                "2" to listOf(stream("13", "News 24")),
            ),
        )
        val engine = XtreamImportEngine(writer)
        val sports = engine.importCategories("src", api, MediaKind.LIVE).first()
        api.streamRequests.clear()

        val written = engine.importCategory("src", api, sports)

        assertEquals(2, written)
        assertEquals(listOf(MediaKind.LIVE to "1"), api.streamRequests)
        assertEquals(listOf("Nova", "Atlas"), writer.items.map { it.title })
    }

    @Test
    fun `opening a category appends rather than replacing`() {
        val writer = RecordingWriter()
        val api = FakeApi(
            liveCategories = listOf("1" to "Sports"),
            liveStreams = mapOf("1" to listOf(stream("11", "Nova"))),
        )
        val engine = XtreamImportEngine(writer)
        val sports = engine.importCategories("src", api, MediaKind.LIVE).first()

        engine.importCategory("src", api, sports)

        assertEquals(ImportMode.APPEND, writer.mode)
    }

    /**
     * A series category lists shows, not episodes.
     *
     * `get_series_info` is a request per show, so filling in six hundred of them to
     * draw a grid of posters would be six hundred requests for data that is one press
     * away and mostly never opened.
     */
    @Test
    fun `opening a series category lists shows without their episodes`() {
        val writer = RecordingWriter()
        val api = FakeApi(
            seriesCategories = listOf("9" to "Drama"),
            series = mapOf("9" to listOf("100" to "Chernobyl", "101" to "The Wire")),
            seriesInfo = mapOf("100" to listOf(Episode("1000", 1, 1, "1:23:45"))),
        )
        val engine = XtreamImportEngine(writer)
        val drama = engine.importCategories("src", api, MediaKind.SERIES).first()

        engine.importCategory("src", api, drama)

        assertEquals(listOf("Chernobyl", "The Wire"), writer.items.map { it.title })
        assertTrue("a show is not a stream", writer.items.all { it.streamUrl.isEmpty() })
        assertTrue("no show has an episode number yet", writer.items.all { it.episodeNumber == null })
        assertTrue("get_series_info was called too early", api.seriesInfoRequests.isEmpty())
    }

    /**
     * A category with no provider id cannot be fetched, and says so as zero.
     *
     * That is an M3U group: its rows arrived with the playlist and there is nothing to
     * ask for. Reporting it as a failure would put an error on a screen that has its
     * content.
     */
    @Test
    fun `a category with no provider id is nothing to fetch rather than a failure`() {
        val writer = RecordingWriter()
        val api = FakeApi()
        val group = MediaGroup(id = "g", name = "From a playlist", kind = MediaKind.LIVE)

        val written = XtreamImportEngine(writer).importCategory("src", api, group)

        assertEquals(0, written)
        assertTrue(api.streamRequests.isEmpty())
    }

    // ------------------------------------------------------------------ episodes

    /** And the episodes arrive only when the show itself is opened. */
    @Test
    fun `opening a show fetches that show's episodes and no others`() {
        val writer = RecordingWriter()
        val api = FakeApi(
            seriesInfo = mapOf(
                "100" to listOf(Episode("1000", 1, 1, "Ep one"), Episode("1001", 1, 2, "Ep two")),
                "101" to listOf(Episode("1010", 1, 1, "Other show")),
            ),
        )

        val written = XtreamImportEngine(writer).importEpisodes(
            sourceId = "src",
            api = api,
            providerSeriesId = "100",
            seriesTitle = "Chernobyl",
            groupId = "g",
        )

        assertEquals(2, written)
        assertEquals(listOf("100"), api.seriesInfoRequests)
        assertEquals(listOf("Ep one", "Ep two"), writer.items.map { it.title })
        assertTrue(writer.items.all { it.seriesTitle == "Chernobyl" })
    }

    // ------------------------------------------------------------------ the harness

    private fun stream(id: String, name: String, extension: String? = null): String = buildString {
        append("""{"stream_id":"$id","name":"$name"""")
        if (extension != null) append(""","container_extension":"$extension"""")
        append("}")
    }

    private data class Episode(val id: String, val season: Int, val number: Int, val title: String)

    /**
     * Records what was asked for, which is the point of every test above.
     *
     * A fake rather than a mock: the assertions are about the *sequence* of requests,
     * and a list of them reads better in a failure than any verification API.
     */
    private class FakeApi(
        private val liveCategories: List<Pair<String, String>> = emptyList(),
        private val liveStreams: Map<String, List<String>> = emptyMap(),
        private val vodCategories: List<Pair<String, String>> = emptyList(),
        private val vodStreams: Map<String, List<String>> = emptyMap(),
        private val seriesCategories: List<Pair<String, String>> = emptyList(),
        private val series: Map<String, List<Pair<String, String>>> = emptyMap(),
        private val seriesInfo: Map<String, List<Episode>> = emptyMap(),
    ) : XtreamImportEngine.Api {

        val categoryRequests = mutableListOf<MediaKind>()
        val streamRequests = mutableListOf<Pair<MediaKind, String>>()
        val seriesInfoRequests = mutableListOf<String>()

        override fun categories(kind: MediaKind): Reader {
            categoryRequests += kind
            val rows = when (kind) {
                MediaKind.MOVIE -> vodCategories
                MediaKind.SERIES -> seriesCategories
                else -> liveCategories
            }
            return StringReader(
                rows.joinToString(",", "[", "]") { (id, name) ->
                    """{"category_id":"$id","category_name":"$name"}"""
                },
            )
        }

        override fun streams(kind: MediaKind, categoryId: String): Reader {
            streamRequests += kind to categoryId
            val rows = when (kind) {
                MediaKind.MOVIE -> vodStreams[categoryId]
                else -> liveStreams[categoryId]
            }.orEmpty()
            return StringReader(rows.joinToString(",", "[", "]"))
        }

        override fun series(categoryId: String): Reader {
            streamRequests += MediaKind.SERIES to categoryId
            val rows = series[categoryId].orEmpty()
            return StringReader(
                rows.joinToString(",", "[", "]") { (id, name) ->
                    """{"series_id":"$id","name":"$name","cover":"http://cdn/$id.jpg"}"""
                },
            )
        }

        override fun seriesInfo(seriesId: String): Reader {
            seriesInfoRequests += seriesId
            val episodes = seriesInfo[seriesId].orEmpty()
            val body = episodes.groupBy { it.season }.entries.joinToString(",") { (season, list) ->
                """"$season":""" + list.joinToString(",", "[", "]") { episode ->
                    """{"id":"${episode.id}","episode_num":${episode.number},""" +
                        """"title":"${episode.title}","container_extension":"mkv"}"""
                }
            }
            return StringReader("""{"episodes":{$body}}""")
        }

        override fun streamUrl(kind: MediaKind, streamId: String, extension: String?): String =
            "http://host/$kind/$streamId.${extension ?: "ts"}"
    }

    private class RecordingWriter : CatalogWriter {
        val groups = mutableListOf<MediaGroup>()
        val items = mutableListOf<CatalogItem>()
        var mode: ImportMode? = null
        var finished: ImportSummary? = null

        override fun begin(sourceId: String, mode: ImportMode) {
            this.mode = mode
        }

        override fun writeGroups(groups: List<MediaGroup>) {
            this.groups += groups
        }

        override fun writeItems(items: List<CatalogItem>) {
            this.items += items
        }

        override fun commit() = Unit

        override fun finish(summary: ImportSummary) {
            finished = summary
        }

        override fun abort(cause: Throwable?) = Unit
    }

    /** Every test above finishes cleanly, so a forgotten `finish` shows up as a failure. */
    @Test
    fun `a listing reports itself finished`() {
        val writer = RecordingWriter()
        XtreamImportEngine(writer).importCategories(
            "src",
            FakeApi(liveCategories = listOf("1" to "Sports")),
            MediaKind.LIVE,
        )

        assertNotNull(writer.finished)
        assertEquals(1, writer.finished?.groups)
    }
}
