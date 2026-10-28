package com.castivio.data.parsing

import com.castivio.data.parsing.XtreamImportEngine.StreamStrategy
import com.castivio.data.parsing.XtreamImportEngine.StreamsReport
import com.castivio.domain.CatalogItem
import com.castivio.domain.CatalogWriter
import com.castivio.domain.ImportMode
import com.castivio.domain.ImportSummary
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaKind
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shows in one reply, like the channels and the films, and every way that reply can fall short.
 *
 * Only the section differs from [XtreamWholeSectionTest]: a show is read by `get_series` and not by the
 * streams actions, and is written as a shell with no episodes and no address. What is gated is the same
 * claim -- the one reply and the walk of the categories leave the same rows behind, so the viewer cannot
 * tell which ran -- and the same fallbacks: a refusal, a broken reply and a category the reply left out all
 * end in the walk's result, never in less.
 */
class XtreamSeriesWholeSectionTest {

    @Test
    fun `one reply leaves exactly the shells the walk leaves`() {
        val walked = RecordingWriter().also { writer ->
            XtreamImportEngine(writer).importCatalogue("src", panel(), kinds = SERIES)
        }
        val reports = mutableListOf<StreamsReport>()
        val replied = RecordingWriter().also { writer ->
            XtreamImportEngine(writer).importCatalogue(
                "src",
                panel(),
                kinds = SERIES,
                wholeSection = SERIES,
                onStreams = { reports += it },
            )
        }

        assertEquals(walked.rows(), replied.rows())
        assertEquals(walked.groups, replied.groups)
        assertEquals(StreamStrategy.WHOLE_SECTION, reports.single().strategy)
        assertEquals(5, reports.single().streams)
        assertEquals(0, reports.single().gapFilled)
    }

    /** The point of it: one request for the list of shows, not one per category. */
    @Test
    fun `one reply asks for no category on its own`() {
        val api = panel()

        XtreamImportEngine(RecordingWriter()).importCatalogue("src", api, kinds = SERIES, wholeSection = SERIES)

        assertEquals(1, api.wholeSectionRequests)
        assertEquals(emptyList<String>(), api.categoryRequests)
    }

    /** A show is a shell: no address to play, and the id that opens its page. */
    @Test
    fun `a show from the one reply is a shell with its series id`() {
        val writer = RecordingWriter()

        XtreamImportEngine(writer).importCatalogue("src", panel(), kinds = SERIES, wholeSection = SERIES)

        val show = writer.items.single { it.title == "Dark Harbor" }
        assertEquals(MediaKind.SERIES, show.kind)
        assertEquals("", show.streamUrl)
        assertEquals("7", show.providerRef)
        assertEquals(StableIds.seriesByProviderId("src", "7"), show.seriesId)
        assertEquals(StableIds.group("src", MediaKind.SERIES, "Drama"), show.groupId)
        assertNull(show.episodeNumber)
    }

    @Test
    fun `a refused reply falls back to the walk and loses nothing`() {
        val reports = mutableListOf<StreamsReport>()
        val api = panel(wholeSection = { throw WholeSectionUnsupported("HTTP 404") })
        val writer = RecordingWriter()

        XtreamImportEngine(writer).importCatalogue(
            "src",
            api,
            kinds = SERIES,
            wholeSection = SERIES,
            onStreams = { reports += it },
        )

        assertEquals(walkedRows(), writer.rows())
        assertEquals(listOf("1", "2"), api.categoryRequests.sorted())
        assertEquals(StreamStrategy.REFUSED, reports.single().strategy)
        assertEquals("SERIES: one reply refused (HTTP 404), asked per category", reports.single().toString())
    }

    /** Some panels answer the question without a category with nothing at all. */
    @Test
    fun `an empty reply is a refusal`() {
        val reports = mutableListOf<StreamsReport>()
        val writer = RecordingWriter()

        XtreamImportEngine(writer).importCatalogue(
            "src",
            panel(wholeSection = { StringReader("[]") }),
            kinds = SERIES,
            wholeSection = SERIES,
            onStreams = { reports += it },
        )

        assertEquals(walkedRows(), writer.rows())
        assertEquals(StreamStrategy.REFUSED, reports.single().strategy)
    }

    /** A reply that breaks halfway wrote rows the walk writes again: the same ids, so each show counts once. */
    @Test
    fun `a reply that breaks halfway falls back and counts each show once`() {
        val reports = mutableListOf<StreamsReport>()
        val writer = RecordingWriter()
        val api = panel(wholeSection = { BreakingReader(WHOLE_REPLY, breakAfter = WHOLE_REPLY.length / 2) })

        val summary = XtreamImportEngine(writer, batchSize = 1).importCatalogue(
            "src",
            api,
            kinds = SERIES,
            wholeSection = SERIES,
            onStreams = { reports += it },
        )

        assertEquals(StreamStrategy.FAILED, reports.single().strategy)
        assertEquals(walkedRows(), writer.rows())
        assertEquals("each show counted once", 5, summary.items)
        assertEquals(5, summary.count(MediaKind.SERIES))
    }

    @Test
    fun `no answer at all falls back to the walk`() {
        val reports = mutableListOf<StreamsReport>()
        val writer = RecordingWriter()

        XtreamImportEngine(writer).importCatalogue(
            "src",
            panel(wholeSection = { throw IOException("timeout") }),
            kinds = SERIES,
            wholeSection = SERIES,
            onStreams = { reports += it },
        )

        assertEquals(walkedRows(), writer.rows())
        assertEquals(StreamStrategy.FAILED, reports.single().strategy)
    }

    /** A panel that leaves a category out of the big reply must not lose its shows. */
    @Test
    fun `a category the reply left empty is asked for on its own`() {
        val reports = mutableListOf<StreamsReport>()
        val partial = WHOLE_ROWS.filterNot { it.contains("\"category_id\":\"2\"") }
        val api = panel(wholeSection = { StringReader(partial.joinToString(",", "[", "]")) })
        val writer = RecordingWriter()

        XtreamImportEngine(writer).importCatalogue(
            "src",
            api,
            kinds = SERIES,
            wholeSection = SERIES,
            onStreams = { reports += it },
        )

        assertEquals(listOf("2"), api.categoryRequests)
        assertEquals(walkedRows(), writer.rows())
        assertEquals(1, reports.single().gapFilled)
    }

    /**
     * The walk never asks for them, so the one reply is the only way they arrive: written under no group,
     * they are in the section and in search, which is what the subscription carries.
     */
    @Test
    fun `shows naming a category the panel did not list are written under no group`() {
        val reports = mutableListOf<StreamsReport>()
        val withOrphan = WHOLE_ROWS + row("99", "Hidden Show", "77")
        val writer = RecordingWriter()

        XtreamImportEngine(writer).importCatalogue(
            "src",
            panel(wholeSection = { StringReader(withOrphan.joinToString(",", "[", "]")) }),
            kinds = SERIES,
            wholeSection = SERIES,
            onStreams = { reports += it },
        )

        val hidden = writer.items.single { it.title == "Hidden Show" }
        assertNull("it was filed under a category the panel never listed", hidden.groupId)
        assertEquals(MediaKind.SERIES, hidden.kind)
        assertEquals("every other row is exactly the walk's", walkedRows(), writer.rows().filterNot { it.title == "Hidden Show" })
        assertEquals(1, reports.single().orphans)
    }

    @Test
    fun `the categories are listed as the reply finishes them`() {
        val writer = RecordingWriter()

        XtreamImportEngine(writer).importCatalogue("src", panel(), kinds = SERIES, wholeSection = SERIES)

        assertEquals(setOf("Drama", "Comedy"), writer.groups.map { it.name }.toSet())
        assertTrue(writer.countedGroups.isNotEmpty())
    }

    /** A caller that does not opt in is asked per category, as before. */
    @Test
    fun `a caller that does not ask gets the walk`() {
        val api = panel()

        XtreamImportEngine(RecordingWriter()).importCatalogue("src", api, kinds = SERIES)

        assertEquals(0, api.wholeSectionRequests)
        assertEquals(listOf("1", "2"), api.categoryRequests.sorted())
    }

    // ------------------------------------------------------------------ fixtures

    private fun walkedRows(): List<Row> = RecordingWriter().also { writer ->
        XtreamImportEngine(writer).importCatalogue("src", panel(), kinds = SERIES)
    }.rows()

    private fun panel(wholeSection: (() -> Reader)? = { StringReader(WHOLE_REPLY) }) = Panel(wholeSection)

    /** Two show categories. The request log is behind a lock: the walk asks from several workers at once. */
    private class Panel(private val wholeSection: (() -> Reader)?) : XtreamImportEngine.Api {
        var wholeSectionRequests = 0
        val categoryRequests: List<String>
            get() = synchronized(requests) { requests.toList() }
        private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun categories(kind: MediaKind): Reader = StringReader(
            """[{"category_id":"1","category_name":"Drama"},{"category_id":"2","category_name":"Comedy"}]""",
        )

        override fun streams(kind: MediaKind, categoryId: String): Reader = StringReader("[]")

        override fun series(categoryId: String): Reader {
            requests += categoryId
            return StringReader(
                WHOLE_ROWS.filter { it.contains("\"category_id\":\"$categoryId\"") }.joinToString(",", "[", "]"),
            )
        }

        override fun allStreams(kind: MediaKind): Reader? {
            val open = wholeSection ?: return null
            wholeSectionRequests++
            return open()
        }

        override fun seriesInfo(seriesId: String): Reader = StringReader("{}")
        override fun streamUrl(kind: MediaKind, streamId: String, extension: String?): String =
            XtreamUrls.stream("http://host", "u", "p", kind, streamId, extension)
    }

    /** What a row is, for comparing two imports: everything a viewer could see differ. */
    private data class Row(val id: String, val title: String, val kind: MediaKind, val groupId: String?, val order: Int)

    private class RecordingWriter : CatalogWriter {
        val items = mutableListOf<CatalogItem>()
        val groups = mutableListOf<MediaGroup>()
        val countedGroups = mutableListOf<String>()

        fun rows(): List<Row> = items
            .associateBy { it.id } // the writer replaces by id; so does this
            .values
            .map { Row(it.id, it.title, it.kind, it.groupId, it.providerOrder) }
            .sortedBy { it.id }

        override fun begin(sourceId: String, mode: ImportMode, sections: Set<MediaKind>) = Unit
        override fun writeGroups(groups: List<MediaGroup>) {
            this.groups += groups
        }

        override fun writeItems(items: List<CatalogItem>) {
            this.items += items
        }

        override fun countGroups(groupIds: Collection<String>) {
            countedGroups += groupIds
        }

        override fun commit() = Unit
        override fun finish(summary: ImportSummary) = Unit
        override fun abort(cause: Throwable?) = Unit
    }

    /** A reply whose connection drops after [breakAfter] characters. */
    private class BreakingReader(content: String, private val breakAfter: Int) : Reader() {
        private val delegate = StringReader(content)
        private var served = 0

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            if (served >= breakAfter) throw IOException("connection reset")
            val read = delegate.read(cbuf, off, minOf(len, breakAfter - served))
            if (read > 0) served += read
            return read
        }

        override fun close() = delegate.close()
    }

    private companion object {
        val SERIES = setOf(MediaKind.SERIES)

        fun row(id: String, name: String, category: String): String =
            """{"series_id":"$id","name":"$name","cover":"http://cdn/$id.jpg","category_id":"$category"}"""

        /** The panel's shows as it sends them without a category: not grouped by category. */
        val WHOLE_ROWS = listOf(
            row("8", "Quiet Water", "2"),
            row("7", "Dark Harbor", "1"),
            row("9", "Late Shift", "2"),
            row("5", "The Long Tide", "1"),
            row("6", "Red Lantern", "1"),
        )

        val WHOLE_REPLY = WHOLE_ROWS.joinToString(",", "[", "]")
    }
}
