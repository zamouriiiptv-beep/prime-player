package com.castivio.data.parsing

import com.castivio.data.parsing.XtreamImportEngine.StreamStrategy
import com.castivio.data.parsing.XtreamImportEngine.StreamsReport
import com.castivio.domain.CatalogItem
import com.castivio.domain.CatalogWriter
import com.castivio.domain.ImportMode
import com.castivio.domain.ImportProgress
import com.castivio.domain.ImportSummary
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaKind
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A library that is the whole of what the subscription carries.
 *
 * Two ways a film went missing without a word, each pinned here: a film whose category the panel
 * does not list was never asked for (the walk asks only the categories it was given), and a
 * category that failed once was left out for good. Neither showed anywhere -- the section simply
 * held fewer films than the same subscription does in another player.
 */
class XtreamCompletenessTest {

    // ------------------------------------------------------------------ the films in one reply

    /** The one reply holds every film once, and a film of no listed category is one of them. */
    @Test
    fun `films in one reply include the films of no listed category`() {
        val panel = Panel(
            categories = listOf("5" to "Action", "6" to "Drama"),
            streams = mapOf(
                "5" to listOf(film("1", "Heat", "5"), film("2", "Ronin", "5")),
                "6" to listOf(film("3", "Rocky", "6")),
            ),
            whole = listOf(
                film("1", "Heat", "5"),
                film("3", "Rocky", "6"),
                film("2", "Ronin", "5"),
                film("4", "Loose Film", "999"),
                film("5", "No Category At All", null),
            ),
        )
        val writer = RecordingWriter()

        XtreamImportEngine(writer, retryDelayMs = 0).importCatalogue(
            "src",
            panel,
            kinds = setOf(MediaKind.MOVIE),
            wholeSection = setOf(MediaKind.MOVIE),
        )

        assertEquals("one request for the whole section, none per category", 1, panel.wholeRequests.get())
        assertEquals(emptyList<String>(), panel.categoryRequests.keys.toList())
        assertEquals(listOf("Heat", "Rocky", "Ronin", "Loose Film", "No Category At All").sorted(), writer.items.map { it.title }.sorted())
        assertEquals(
            "the two it could not file are in no group",
            setOf("Loose Film", "No Category At All"),
            writer.items.filter { it.groupId == null }.map { it.title }.toSet(),
        )
        assertTrue("every film is a film", writer.items.all { it.kind == MediaKind.MOVIE })
    }

    /**
     * A reply keyed by categories the panel never listed is still a reply: its streams are written
     * under no group, and the walk of the categories it filled nothing in puts each one under its own.
     */
    @Test
    fun `a reply naming only unlisted categories is read and then filed by the walk`() {
        val panel = Panel(
            categories = listOf("5" to "Action"),
            streams = mapOf("5" to listOf(film("1", "Heat", "5"))),
            whole = listOf(film("1", "Heat", "999")),
        )
        val writer = RecordingWriter()
        val reports = mutableListOf<StreamsReport>()

        XtreamImportEngine(writer, retryDelayMs = 0).importCatalogue(
            "src",
            panel,
            kinds = setOf(MediaKind.MOVIE),
            wholeSection = setOf(MediaKind.MOVIE),
            onStreams = { reports += it },
        )

        assertEquals(StreamStrategy.WHOLE_SECTION, reports.single().strategy)
        assertEquals(1, reports.single().gapFilled)
        val stored = writer.items.associateBy { it.id }.values.single()
        assertEquals("the writer replaces by id, and the walk wrote last", StableIds.group("src", MediaKind.MOVIE, "Action"), stored.groupId)
    }

    /** The same panel asked per category still holds exactly what its categories list. */
    @Test
    fun `a panel that refuses the whole list is walked and loses nothing it lists`() {
        val panel = Panel(
            categories = listOf("5" to "Action"),
            streams = mapOf("5" to listOf(film("1", "Heat", "5"), film("2", "Ronin", "5"))),
            whole = emptyList(),
            wholeRefused = true,
        )
        val writer = RecordingWriter()

        XtreamImportEngine(writer, retryDelayMs = 0).importCatalogue(
            "src",
            panel,
            kinds = setOf(MediaKind.MOVIE),
            wholeSection = setOf(MediaKind.MOVIE),
        )

        assertEquals(listOf("Heat", "Ronin"), writer.items.map { it.title }.sorted())
    }

    // --------------------------------------------------------- the categories that failed

    /** A category that failed once is asked for again, and arrives. */
    @Test
    fun `a category that failed once is asked again and its films arrive`() {
        val panel = Panel(
            categories = listOf("1" to "A", "2" to "B", "3" to "C"),
            streams = mapOf(
                "1" to listOf(film("11", "One", "1")),
                "2" to listOf(film("21", "Two", "2"), film("22", "Twenty-two", "2")),
                "3" to listOf(film("31", "Three", "3")),
            ),
            failsFirst = mapOf("2" to 1),
        )
        val writer = RecordingWriter()

        val summary = XtreamImportEngine(writer, retryDelayMs = 0).importCatalogue(
            "src",
            panel,
            kinds = setOf(MediaKind.MOVIE),
        )

        assertEquals(2, panel.categoryRequests.getValue("2").get())
        assertEquals(listOf("One", "Three", "Twenty-two", "Two"), writer.items.map { it.title }.sorted())
        assertEquals(0, summary.failedCategories)
    }

    /** A body that stalls after some rows is the case a connection retry never saw. */
    @Test
    fun `a category whose reply broke halfway is asked again whole`() {
        val rows = (1..6).map { film("$it", "Film $it", "1") }
        val panel = Panel(
            categories = listOf("1" to "A", "2" to "B"),
            streams = mapOf("1" to rows, "2" to listOf(film("90", "Other", "2"))),
            breaksFirst = mapOf("1" to 1),
        )
        val writer = RecordingWriter()

        val summary = XtreamImportEngine(writer, batchSize = 2, retryDelayMs = 0).importCatalogue(
            "src",
            panel,
            kinds = setOf(MediaKind.MOVIE),
        )

        assertEquals("every film once, whatever the broken reply had already delivered", 7, writer.items.map { it.id }.toSet().size)
        assertEquals(0, summary.failedCategories)
    }

    /** What stays failed is counted, and does not take the rest with it. */
    @Test
    fun `a category that fails twice is counted as lost and the rest stands`() {
        val panel = Panel(
            categories = listOf("1" to "A", "2" to "B", "3" to "C"),
            streams = mapOf(
                "1" to listOf(film("11", "One", "1")),
                "2" to listOf(film("21", "Two", "2")),
                "3" to listOf(film("31", "Three", "3")),
            ),
            failsFirst = mapOf("2" to 99),
        )
        val writer = RecordingWriter()

        val summary = XtreamImportEngine(writer, retryDelayMs = 0).importCatalogue(
            "src",
            panel,
            kinds = setOf(MediaKind.MOVIE),
        )

        assertEquals(1, summary.failedCategories)
        assertEquals(listOf("One", "Three"), writer.items.map { it.title }.sorted())
        assertEquals("asked once more, and no more", 2, panel.categoryRequests.getValue("2").get())
    }

    /** Nothing arrived at all: that is a panel that is down, and it is said as it was. */
    @Test
    fun `when nothing arrives the categories are not asked again and the failure is thrown`() {
        val panel = Panel(
            categories = listOf("1" to "A", "2" to "B"),
            streams = mapOf("1" to emptyList<String>(), "2" to emptyList<String>()),
            failsFirst = mapOf("1" to 99, "2" to 99),
        )

        assertThrows(IOException::class.java) {
            XtreamImportEngine(RecordingWriter(), retryDelayMs = 0).importCatalogue(
                "src",
                panel,
                kinds = setOf(MediaKind.MOVIE),
            )
        }

        assertEquals(1, panel.categoryRequests.getValue("1").get())
        assertEquals(1, panel.categoryRequests.getValue("2").get())
    }

    /** Too many failures are a refusing panel, and asking them all again would only prove it. */
    @Test
    fun `more failures than the cap are not asked again`() {
        val failing = (1..XtreamImportEngine.MAX_RETRIED_CATEGORIES + 1).map { "f$it" }
        val panel = Panel(
            categories = listOf("ok" to "Fine") + failing.map { it to "Failing $it" },
            streams = mapOf("ok" to listOf(film("1", "Survivor", "ok"))) + failing.associateWith { emptyList<String>() },
            failsFirst = failing.associateWith { 99 },
        )

        val summary = XtreamImportEngine(RecordingWriter(), retryDelayMs = 0).importCatalogue(
            "src",
            panel,
            kinds = setOf(MediaKind.MOVIE),
        )

        assertEquals(failing.size, summary.failedCategories)
        assertTrue(failing.all { panel.categoryRequests.getValue(it).get() == 1 })
    }

    /** The second asking is not a second count on the bar: it never runs past its total. */
    @Test
    fun `asking again does not run the progress bar past its total`() {
        val panel = Panel(
            categories = listOf("1" to "A", "2" to "B", "3" to "C"),
            streams = mapOf(
                "1" to listOf(film("11", "One", "1")),
                "2" to listOf(film("21", "Two", "2")),
                "3" to listOf(film("31", "Three", "3")),
            ),
            failsFirst = mapOf("2" to 1),
        )
        val bar = mutableListOf<Pair<Int, Int>>()

        XtreamImportEngine(RecordingWriter(), retryDelayMs = 0).importCatalogue(
            "src",
            panel,
            kinds = setOf(MediaKind.MOVIE),
            onProgress = { progress ->
                if (progress is ImportProgress.Importing && progress.categoriesDone != null) {
                    bar += progress.categoriesDone!! to progress.groupsReady
                }
            },
        )

        assertTrue("the bar had something to show", bar.isNotEmpty())
        assertTrue("categories done ran past the categories there are: $bar", bar.all { (done, total) -> done <= total })
    }

    // ------------------------------------------------------------------------ fixtures

    private fun film(id: String, name: String, category: String?): String =
        """{"num":$id,"name":"$name","stream_id":"$id","container_extension":"mp4"""" +
            (if (category != null) ""","category_id":"$category"""" else "") + "}"

    /**
     * A panel for films: its categories, what each lists, the whole list, and how many times a
     * category fails before it answers -- as an error at the request, or as a reply that breaks
     * after its first rows. Requests are counted per category, behind a map built for threads:
     * the walk asks from several workers at once.
     */
    private class Panel(
        private val categories: List<Pair<String, String>>,
        private val streams: Map<String, List<String>>,
        private val whole: List<String> = emptyList(),
        private val wholeRefused: Boolean = false,
        private val failsFirst: Map<String, Int> = emptyMap(),
        private val breaksFirst: Map<String, Int> = emptyMap(),
    ) : XtreamImportEngine.Api {
        val wholeRequests = AtomicInteger()
        val categoryRequests = ConcurrentHashMap<String, AtomicInteger>()

        override fun categories(kind: MediaKind): Reader = StringReader(
            categories.joinToString(",", "[", "]") { (id, name) -> """{"category_id":"$id","category_name":"$name"}""" },
        )

        override fun streams(kind: MediaKind, categoryId: String): Reader {
            val asked = categoryRequests.getOrPut(categoryId) { AtomicInteger() }.incrementAndGet()
            if (asked <= (failsFirst[categoryId] ?: 0)) throw IOException("the panel did not answer")
            val body = streams.getValue(categoryId).joinToString(",", "[", "]")
            if (asked <= (breaksFirst[categoryId] ?: 0)) return BreakingReader(body, breakAfter = body.length / 2)
            return StringReader(body)
        }

        override fun allStreams(kind: MediaKind): Reader? {
            if (whole.isEmpty() && !wholeRefused) return null
            wholeRequests.incrementAndGet()
            if (wholeRefused) throw WholeSectionUnsupported("HTTP 404")
            return StringReader(whole.joinToString(",", "[", "]"))
        }

        override fun series(categoryId: String): Reader = StringReader("[]")
        override fun seriesInfo(seriesId: String): Reader = StringReader("{}")
        override fun streamUrl(kind: MediaKind, streamId: String, extension: String?): String =
            XtreamUrls.stream("http://host", "u", "p", kind, streamId, extension)
    }

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

    private class RecordingWriter : CatalogWriter {
        val items = mutableListOf<CatalogItem>()

        override fun begin(sourceId: String, mode: ImportMode, sections: Set<MediaKind>) = Unit
        override fun writeGroups(groups: List<MediaGroup>) = Unit
        override fun writeItems(items: List<CatalogItem>) {
            this.items += items
        }

        override fun commit() = Unit
        override fun finish(summary: ImportSummary) = Unit
        override fun abort(cause: Throwable?) = Unit
    }
}
