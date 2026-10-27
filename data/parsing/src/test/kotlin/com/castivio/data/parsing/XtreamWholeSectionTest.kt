package com.castivio.data.parsing

import com.castivio.data.parsing.XtreamImportEngine.PassReport
import com.castivio.data.parsing.XtreamImportEngine.Phase
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
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A live section in one reply, and every way that reply can fail to be the answer.
 *
 * The claim worth gating is equivalence: the one reply and the category-by-category walk
 * must leave the same rows behind — the same ids, under the same groups, in the same
 * order, with the stations still filed as radio — because the viewer cannot tell which
 * of the two ran and must never be able to. Everything else here is a way the reply can
 * fall short (refused, not a list, empty, broken halfway, missing categories), and each
 * must end with the walk's result rather than with less.
 *
 * Most of it reads the whole live endpoint, channels and stations together, as a whole
 * catalogue does ([LIVE_ENDPOINT]). What a section loaded on its own takes from the reply
 * -- the channels, with the stations passed over, and the radio by its own categories --
 * is tested on its own below.
 */
class XtreamWholeSectionTest {

    // ------------------------------------------------------------- equivalence

    @Test
    fun `one reply leaves exactly the rows the walk leaves`() {
        val walked = RecordingWriter().also { writer ->
            XtreamImportEngine(writer).importCatalogue("src", panel(), kinds = LIVE_ENDPOINT, mode = ImportMode.APPEND)
        }
        val reports = mutableListOf<StreamsReport>()
        val replied = RecordingWriter().also { writer ->
            XtreamImportEngine(writer).importCatalogue(
                "src",
                panel(),
                kinds = LIVE_ENDPOINT,
                mode = ImportMode.APPEND,
                wholeSection = setOf(MediaKind.LIVE),
                onStreams = { reports += it },
            )
        }

        assertEquals(walked.rows(), replied.rows())
        assertEquals(walked.groups, replied.groups)
        assertEquals(StreamStrategy.WHOLE_SECTION, reports.single().strategy)
        assertEquals(5, reports.single().streams)
        assertEquals(0, reports.single().gapFilled)
    }

    /** The point of it: one request for the streams, not one per category. */
    @Test
    fun `one reply asks for no category on its own`() {
        val api = panel()

        XtreamImportEngine(RecordingWriter()).importCatalogue(
            "src",
            api,
            kinds = LIVE_ENDPOINT,
            wholeSection = setOf(MediaKind.LIVE),
        )

        assertEquals(1, api.wholeSectionRequests)
        assertEquals(emptyList<String>(), api.categoryStreamRequests)
    }

    @Test
    fun `stations in the one reply are filed as radio`() {
        val writer = RecordingWriter()

        XtreamImportEngine(writer).importCatalogue(
            "src",
            panel(),
            kinds = LIVE_ENDPOINT,
            wholeSection = setOf(MediaKind.LIVE),
        )

        val radio = writer.items.single { it.title == "Atlas FM" }
        assertEquals(MediaKind.RADIO, radio.kind)
        assertEquals(StableIds.group("src", MediaKind.RADIO, "Radio FM"), radio.groupId)
    }

    /**
     * The radio on its own walks its own categories: the one reply is the whole live list,
     * and taking it for the stations would download every channel to keep a handful.
     */
    @Test
    fun `radio asked for on its own walks its own categories, never the whole list`() {
        val api = panel()
        val writer = RecordingWriter()

        XtreamImportEngine(writer).importCatalogue(
            "src",
            api,
            kinds = setOf(MediaKind.RADIO),
            wholeSection = setOf(MediaKind.LIVE),
        )

        assertEquals(0, api.wholeSectionRequests)
        assertEquals(listOf("3"), api.categoryStreamRequests)
        assertEquals(listOf("Atlas FM"), writer.items.map { it.title })
        assertEquals(listOf(MediaKind.RADIO), writer.groups.map { it.kind })
    }

    /**
     * The channels on their own take the one reply and pass over the stations in it: not
     * written, not a group, and not orphans either -- they are the radio's, and counted so.
     */
    @Test
    fun `channels asked for on their own pass over the stations in the reply`() {
        val reports = mutableListOf<StreamsReport>()
        val passes = mutableListOf<PassReport>()
        val writer = RecordingWriter()

        XtreamImportEngine(writer).importCatalogue(
            "src",
            panel(),
            kinds = setOf(MediaKind.LIVE),
            wholeSection = setOf(MediaKind.LIVE),
            onStreams = { reports += it },
            onPass = { passes += it },
        )

        assertTrue(writer.items.none { it.kind == MediaKind.RADIO })
        assertTrue(writer.groups.none { it.kind == MediaKind.RADIO })
        assertEquals(4, writer.items.size)
        assertEquals(0, reports.single().orphans)
        assertEquals(1, reports.single().elsewhere)
        val pass = passes.single()
        assertEquals("the reply is what was filed and what was left to another section", pass.parsed, pass.filed + pass.elsewhere)
        assertEquals(1, pass.elsewhere)
    }

    /**
     * Where the panel numbers its channels the number is the order, whichever way the
     * rows arrived; where it does not, the order is the category's and then the reply's.
     */
    @Test
    fun `the order is the panel's numbering, then the categories' own`() {
        val writer = RecordingWriter()

        XtreamImportEngine(writer).importCatalogue(
            "src",
            panel(),
            kinds = LIVE_ENDPOINT,
            wholeSection = setOf(MediaKind.LIVE),
        )

        assertEquals(
            listOf("Nova Sports 1", "Atlas Sport", "Atlas News", "Nova News", "Atlas FM"),
            writer.items.sortedBy { it.providerOrder }.map { it.title },
        )
    }

    // ------------------------------------------------ when the reply is not the answer

    @Test
    fun `a refused reply falls back to the walk and loses nothing`() {
        val reports = mutableListOf<StreamsReport>()
        val api = panel(wholeSection = { throw WholeSectionUnsupported("HTTP 404") })
        val writer = RecordingWriter()

        XtreamImportEngine(writer).importCatalogue(
            "src",
            api,
            kinds = LIVE_ENDPOINT,
            wholeSection = setOf(MediaKind.LIVE),
            onStreams = { reports += it },
        )

        assertEquals(walkedRows(), writer.rows())
        assertEquals(listOf("1", "2", "3"), api.categoryStreamRequests.sorted())
        assertEquals(StreamStrategy.REFUSED, reports.single().strategy)
        // What the panel answered, which is what the panel reading needs; not a class name.
        assertEquals("HTTP 404", reports.single().cause)
        assertEquals("LIVE: one reply refused (HTTP 404), asked per category", reports.single().toString())
    }

    /** An object, or a page of HTML, where a list was asked for. */
    @Test
    fun `a reply that is not a list is a refusal`() {
        val reports = mutableListOf<StreamsReport>()
        val writer = RecordingWriter()

        XtreamImportEngine(writer).importCatalogue(
            "src",
            panel(wholeSection = { StringReader("""{"user_info":{"auth":1}}""") }),
            kinds = LIVE_ENDPOINT,
            wholeSection = setOf(MediaKind.LIVE),
            onStreams = { reports += it },
        )

        assertEquals(walkedRows(), writer.rows())
        assertEquals(StreamStrategy.REFUSED, reports.single().strategy)
    }

    /** Some panels answer the question without a category with nothing at all. */
    @Test
    fun `an empty reply is a refusal`() {
        val reports = mutableListOf<StreamsReport>()
        val writer = RecordingWriter()

        XtreamImportEngine(writer).importCatalogue(
            "src",
            panel(wholeSection = { StringReader("[]") }),
            kinds = LIVE_ENDPOINT,
            wholeSection = setOf(MediaKind.LIVE),
            onStreams = { reports += it },
        )

        assertEquals(walkedRows(), writer.rows())
        assertEquals(StreamStrategy.REFUSED, reports.single().strategy)
        assertNull("an empty answer has no exception to name", reports.single().cause)
    }

    /**
     * A reply that breaks halfway has written rows the walk then writes again. They are
     * the same rows under the same ids, so the catalogue is right; the count must be too.
     */
    @Test
    fun `a reply that breaks halfway falls back and counts each row once`() {
        val reports = mutableListOf<StreamsReport>()
        val writer = RecordingWriter()
        val api = panel(wholeSection = { BreakingReader(WHOLE_REPLY, breakAfter = WHOLE_REPLY.length / 2) })

        val summary = XtreamImportEngine(writer, batchSize = 1).importCatalogue(
            "src",
            api,
            kinds = LIVE_ENDPOINT,
            wholeSection = setOf(MediaKind.LIVE),
            onStreams = { reports += it },
        )

        assertEquals(StreamStrategy.FAILED, reports.single().strategy)
        assertEquals("IOException", reports.single().cause)
        assertEquals(walkedRows(), writer.rows())
        assertEquals("each row counted once", 5, summary.items)
        assertEquals(4, summary.count(MediaKind.LIVE))
        assertEquals(1, summary.count(MediaKind.RADIO))
    }

    @Test
    fun `no answer at all falls back to the walk`() {
        val reports = mutableListOf<StreamsReport>()
        val writer = RecordingWriter()

        XtreamImportEngine(writer).importCatalogue(
            "src",
            panel(wholeSection = { throw IOException("timeout") }),
            kinds = LIVE_ENDPOINT,
            wholeSection = setOf(MediaKind.LIVE),
            onStreams = { reports += it },
        )

        assertEquals(walkedRows(), writer.rows())
        assertEquals(StreamStrategy.FAILED, reports.single().strategy)
    }

    /** A panel that leaves a category out of the big reply must not lose its channels. */
    @Test
    fun `a category the reply left empty is asked for on its own`() {
        val reports = mutableListOf<StreamsReport>()
        val partial = WHOLE_ROWS.filterNot { it.contains("\"category_id\":\"2\"") }
        val api = panel(wholeSection = { StringReader(partial.joinToString(",", "[", "]")) })
        val writer = RecordingWriter()

        XtreamImportEngine(writer).importCatalogue(
            "src",
            api,
            kinds = LIVE_ENDPOINT,
            wholeSection = setOf(MediaKind.LIVE),
            onStreams = { reports += it },
        )

        assertEquals(listOf("2"), api.categoryStreamRequests)
        assertEquals(walkedRows(), writer.rows())
        assertEquals(1, reports.single().gapFilled)
    }

    /**
     * The walk never asks for them, so the one reply is the only way they arrive: written under no
     * group, they are in the section and in search, which is what the panel carries.
     */
    @Test
    fun `streams naming a category the panel did not list are written under no group`() {
        val reports = mutableListOf<StreamsReport>()
        val withOrphan = WHOLE_ROWS + row("99", "Hidden Channel", "77", number = 9)
        val writer = RecordingWriter()

        XtreamImportEngine(writer).importCatalogue(
            "src",
            panel(wholeSection = { StringReader(withOrphan.joinToString(",", "[", "]")) }),
            kinds = LIVE_ENDPOINT,
            wholeSection = setOf(MediaKind.LIVE),
            onStreams = { reports += it },
        )

        val hidden = writer.items.single { it.title == "Hidden Channel" }
        assertNull("it was filed under a category the panel never listed", hidden.groupId)
        assertEquals(MediaKind.LIVE, hidden.kind)
        assertEquals("every other row is exactly the walk's", walkedRows(), writer.rows().filterNot { it.title == "Hidden Channel" })
        assertEquals(1, reports.single().orphans)
    }

    // ------------------------------------------------------------- what is asked

    @Test
    fun `a caller that does not ask gets the walk, and is told so`() {
        val reports = mutableListOf<StreamsReport>()
        val api = panel()

        XtreamImportEngine(RecordingWriter()).importCatalogue(
            "src",
            api,
            kinds = LIVE_ENDPOINT,
            onStreams = { reports += it },
        )

        assertEquals(0, api.wholeSectionRequests)
        assertEquals(StreamStrategy.PER_CATEGORY, reports.single().strategy)
    }

    /** Films are not part of this change: asked per category even beside a one-reply live. */
    @Test
    fun `films are walked even when live takes the one reply`() {
        val api = panel()

        XtreamImportEngine(RecordingWriter()).importCatalogue(
            "src",
            api,
            kinds = setOf(MediaKind.LIVE, MediaKind.MOVIE),
            wholeSection = setOf(MediaKind.LIVE),
        )

        assertEquals(1, api.wholeSectionRequests)
        assertEquals(listOf(MediaKind.MOVIE to "5"), api.streamRequests.filter { it.first == MediaKind.MOVIE })
    }

    // -------------------------------------------------------- progress and phases

    /**
     * Inside the one reply no category is finished until all are, so the bar's numerator
     * is unknown — the screen then counts rows, as it does for a playlist — and it is
     * whole again once the reply has ended.
     */
    @Test
    fun `during the one reply the bar counts rows, and ends whole`() {
        val seen = mutableListOf<ImportProgress.Importing>()

        XtreamImportEngine(RecordingWriter(), batchSize = 1).importCatalogue(
            "src",
            panel(wholeSection = { StringReader(WHOLE_ROWS.filterNot { it.contains("\"category_id\":\"2\"") }.joinToString(",", "[", "]")) }),
            kinds = LIVE_ENDPOINT,
            wholeSection = setOf(MediaKind.LIVE),
            onProgress = { if (it is ImportProgress.Importing) seen += it },
        )

        assertTrue("the reply reported rows", seen.any { it.categoriesDone == null && it.itemsImported > 0 })
        assertEquals("the walk of the gap counts categories", 3, seen.last().categoriesDone)
        assertEquals(3, seen.last().groupsReady)
    }

    /**
     * The usual case, and the one with no walk after it: the reply filled every category,
     * so nothing else will report. The bar has to be whole the moment the reply ends, not
     * empty through the commit and the counts until the import is done.
     */
    @Test
    fun `a reply that leaves nothing to ask for ends with the bar whole`() {
        val api = panel()
        val seen = mutableListOf<ImportProgress.Importing>()

        XtreamImportEngine(RecordingWriter(), batchSize = 1).importCatalogue(
            "src",
            api,
            kinds = LIVE_ENDPOINT,
            wholeSection = setOf(MediaKind.LIVE),
            onProgress = { if (it is ImportProgress.Importing) seen += it },
        )

        assertEquals("a category was asked for again", emptyList<String>(), api.categoryStreamRequests)
        assertEquals(3, seen.last().categoriesDone)
        assertEquals(3, seen.last().groupsReady)
        assertEquals("the last report lost rows", 5, seen.last().itemsImported)
    }

    /**
     * A category in the one reply is complete when the reply moves on to another, and is
     * counted then -- the first at once -- so a screen open during the load lists it before
     * the reply ends. This reply scatters its categories (News, Sports, the station, News,
     * Sports), the case where one is counted again each time the reply leaves it again:
     * listed sooner, its count growing, and listed once.
     *
     * The clock is frozen, so the only commits are the first rows', the first category's,
     * and the one at the end of the reply.
     */
    @Test
    fun `a category in the reply is counted when the reply leaves it, and again when it comes back`() {
        val writer = CountingWriter()
        val seen = mutableListOf<ImportProgress.Importing>()

        XtreamImportEngine(writer, batchSize = 1, clock = { 1L }).importCatalogue(
            "src",
            panel(),
            kinds = LIVE_ENDPOINT,
            wholeSection = setOf(MediaKind.LIVE),
            onProgress = { if (it is ImportProgress.Importing) seen += it },
        )

        val counts = writer.log.filter { it.startsWith("count ") }.map { it.removePrefix("count ").split(",") }
        assertEquals("the first category left is counted on its own, at once", listOf("News"), counts.first())
        assertEquals("commit", writer.log[writer.log.indexOf("count News") + 1])
        assertTrue(
            "counted before the reply's last rows were written",
            writer.log.indexOf("count News") < writer.log.lastIndexOf("rows 1"),
        )
        assertEquals("left twice, counted twice", 2, counts.flatten().count { it == "News" })
        assertEquals(setOf("News", "Sports", "Radio FM"), counts.flatten().toSet())
        assertTrue("all of it before the import ended", writer.log.indexOf("finish") > writer.log.indexOfLast { it.startsWith("count") })

        val listed = seen.map { it.categoriesListed }
        assertEquals(listed, listed.sorted())
        assertEquals("each listed once, whole as the reply ends", 3, seen.last().categoriesListed)
    }

    @Test
    fun `the phases are reported once each, in order`() {
        val phases = mutableListOf<Phase>()

        XtreamImportEngine(RecordingWriter()).importCatalogue(
            "src",
            panel(),
            kinds = LIVE_ENDPOINT,
            wholeSection = setOf(MediaKind.LIVE),
            onPhase = { phases += it },
        )

        assertEquals(
            listOf(Phase.CATEGORIES, Phase.GROUPS, Phase.FIRST_COMMIT, Phase.STREAMS, Phase.FINISHED),
            phases,
        )
    }

    /** A reply of tens of megabytes must not be read to its end for nobody. */
    @Test
    fun `a cancelled import stops reading the reply`() {
        val reader = TrackedReader(WHOLE_REPLY)
        var rows = 0
        val writer = object : RecordingWriter() {
            override fun writeItems(items: List<CatalogItem>) {
                super.writeItems(items)
                rows += items.size
            }
        }

        val summary = XtreamImportEngine(writer, batchSize = 1).importCatalogue(
            "src",
            panel(wholeSection = { reader }),
            kinds = LIVE_ENDPOINT,
            wholeSection = setOf(MediaKind.LIVE),
            isCancelled = { rows >= 1 },
        )

        assertTrue(summary.cancelled)
        assertTrue("the reply was left open", reader.closed)
        assertTrue("the whole reply was written for nobody", rows < 5)
        assertFalse(writer.finishedCalled)
    }

    // ------------------------------------------------------------ what a pass cost

    /**
     * **The records at each stage of the one reply, and the ids its orphans named.**
     *
     * What the reply held is what was written, orphans included: a stream naming a category
     * the panel did not list, or naming none, is written under no group, and the reading says
     * which ids they named so the provider's list can be checked against them.
     */
    @Test
    fun `one reply reports what it parsed, filed and orphaned, and the ids the orphans named`() {
        val passes = mutableListOf<PassReport>()
        val reply = WHOLE_ROWS +
            row("98", "Hidden One", "77", number = 8) +
            row("99", "Hidden Two", "77", number = 9) +
            """{"num":7,"name":"Loose","stream_id":"97","stream_icon":"http://cdn/97.png","epg_channel_id":"epg.97"}"""

        XtreamImportEngine(RecordingWriter()).importCatalogue(
            "src",
            panel(wholeSection = { StringReader(reply.joinToString(",", "[", "]")) }),
            kinds = LIVE_ENDPOINT,
            wholeSection = setOf(MediaKind.LIVE),
            onPass = { passes += it },
        )

        val pass = passes.single()
        assertTrue(pass.oneReply)
        assertEquals(1, pass.requests)
        assertEquals(1, pass.parallelReaders)
        assertEquals(8, pass.parsed)
        assertEquals("the five under a category and the three under none", 8, pass.filed)
        assertEquals(3, pass.orphans)
        assertEquals("every stream of the reply was written", pass.parsed, pass.filed)
        assertEquals(mapOf("77" to 2, XtreamImportEngine.NO_CATEGORY to 1), pass.orphanCategories)
        assertEquals(mapOf(MediaKind.LIVE to 2, MediaKind.RADIO to 1), pass.categories)
    }

    /**
     * **Each side of a pass adds up to its own time, and the two are never one sum.**
     *
     * On a clock that moves a millisecond every time it is read, from whichever thread: the
     * reader's asking, reading and waiting fit inside the reader's time with parsing the rest,
     * and the writer's writing, committing and idling fit inside the window with its
     * bookkeeping the rest.
     */
    @Test
    fun `each side of a pass adds up to its own time, on the engine's clock`() {
        val clock = AtomicLong()
        val passes = mutableListOf<PassReport>()

        XtreamImportEngine(RecordingWriter(), batchSize = 2, nanoClock = { clock.addAndGet(1_000_000) }).importCatalogue(
            "src",
            panel(),
            kinds = LIVE_ENDPOINT,
            wholeSection = setOf(MediaKind.LIVE),
            onPass = { passes += it },
        )

        val pass = passes.single()
        assertTrue("the reader's parts are longer than the reader", pass.askNanos + pass.readNanos + pass.handOverNanos <= pass.readerNanos)
        assertEquals(pass.readerNanos, pass.askNanos + pass.readNanos + pass.parseNanos + pass.handOverNanos)
        assertTrue("the writer's parts are longer than the window", pass.writeNanos + pass.commitNanos + pass.idleNanos <= pass.windowNanos)
        assertEquals(pass.windowNanos, pass.writeNanos + pass.commitNanos + pass.idleNanos + pass.writerOtherNanos)
        assertTrue("the request was not timed", pass.askNanos > 0)
        assertTrue("the answer's reads were not timed", pass.readNanos > 0)
        assertTrue("the writing was not timed", pass.writeNanos > 0)
        assertTrue("the commit was not timed", pass.commitNanos > 0)
    }

    @Test
    fun `a walk reports its requests, how many ran at once, and no orphans`() {
        val passes = mutableListOf<PassReport>()

        XtreamImportEngine(RecordingWriter()).importCatalogue("src", panel(), kinds = LIVE_ENDPOINT, onPass = { passes += it })

        val walk = passes.single()
        assertFalse(walk.oneReply)
        assertEquals(3, walk.requests)
        assertEquals("as many at once as there were categories, under the limit", 3, walk.parallelReaders)
        assertEquals(5, walk.filed)
        assertEquals("a category's streams are all filed under it", walk.filed, walk.parsed)
        assertEquals(0, walk.orphans)
    }

    /** A refusal costs a request, and the reading shows it before the walk that followed. */
    @Test
    fun `a refused reply is a pass of one request, and the walk after it is another`() {
        val passes = mutableListOf<PassReport>()

        XtreamImportEngine(RecordingWriter()).importCatalogue(
            "src",
            panel(wholeSection = { throw WholeSectionUnsupported("HTTP 404") }),
            kinds = LIVE_ENDPOINT,
            wholeSection = setOf(MediaKind.LIVE),
            onPass = { passes += it },
        )

        assertEquals(listOf(true, false), passes.map { it.oneReply })
        assertEquals(listOf(1, 3), passes.map { it.requests })
        assertEquals(0, passes.first().filed)
    }

    /** An API with no way to ask made no request, and reports no reply. */
    @Test
    fun `an API that cannot ask for the whole section reports only the walk`() {
        val passes = mutableListOf<PassReport>()

        XtreamImportEngine(RecordingWriter()).importCatalogue(
            "src",
            panel(wholeSection = null),
            kinds = LIVE_ENDPOINT,
            wholeSection = setOf(MediaKind.LIVE),
            onPass = { passes += it },
        )

        assertEquals(listOf(false), passes.map { it.oneReply })
    }

    /** Only counts are wanted, so a reply naming a thousand ids keeps a hundred and a sum. */
    @Test
    fun `orphan ids are counted one by one up to a bound, and together past it`() {
        val passes = mutableListOf<PassReport>()
        val orphans = (1..XtreamImportEngine.MAX_ORPHAN_IDS + 5).map { row("9$it", "Hidden $it", "x$it", number = 100 + it) }

        XtreamImportEngine(RecordingWriter()).importCatalogue(
            "src",
            panel(wholeSection = { StringReader((WHOLE_ROWS + orphans).joinToString(",", "[", "]")) }),
            kinds = LIVE_ENDPOINT,
            wholeSection = setOf(MediaKind.LIVE),
            onPass = { passes += it },
        )

        val pass = passes.single()
        assertEquals(XtreamImportEngine.MAX_ORPHAN_IDS + 1, pass.orphanCategories.size)
        assertEquals(5, pass.orphanCategories[XtreamImportEngine.OTHER_IDS])
        assertEquals("no orphan is lost from the count", pass.orphans, pass.orphanCategories.values.sum())
    }

    // ------------------------------------------------------------------ fixtures

    private fun walkedRows(): List<Row> = RecordingWriter().also { writer ->
        XtreamImportEngine(writer).importCatalogue("src", panel(), kinds = LIVE_ENDPOINT)
    }.rows()

    private fun panel(wholeSection: (() -> Reader)? = { StringReader(WHOLE_REPLY) }) = Panel(wholeSection)

    /**
     * Three live categories, one of them radio, and one film category.
     *
     * The request log is behind a lock: the walk asks from several workers at once, and a
     * plain list loses an entry when two of them add at the same moment.
     */
    private class Panel(private val wholeSection: (() -> Reader)?) : XtreamImportEngine.Api {
        var wholeSectionRequests = 0
        val streamRequests: MutableList<Pair<MediaKind, String>> = Collections.synchronizedList(mutableListOf())
        val categoryStreamRequests: List<String>
            get() = synchronized(streamRequests) { streamRequests.filter { it.first != MediaKind.MOVIE }.map { it.second } }

        override fun categories(kind: MediaKind): Reader = StringReader(
            when (kind) {
                MediaKind.MOVIE -> """[{"category_id":"5","category_name":"Films"}]"""
                else -> """[{"category_id":"1","category_name":"Sports"},""" +
                    """{"category_id":"2","category_name":"News"},""" +
                    """{"category_id":"3","category_name":"Radio FM"}]"""
            },
        )

        override fun streams(kind: MediaKind, categoryId: String): Reader {
            streamRequests += kind to categoryId
            val rows = if (kind == MediaKind.MOVIE) {
                listOf(row("51", "Dune", "5", number = 0))
            } else {
                WHOLE_ROWS.filter { it.contains("\"category_id\":\"$categoryId\"") }
            }
            return StringReader(rows.joinToString(",", "[", "]"))
        }

        override fun allStreams(kind: MediaKind): Reader? {
            val open = wholeSection ?: return null
            wholeSectionRequests++
            return open()
        }

        override fun series(categoryId: String): Reader = StringReader("[]")
        override fun seriesInfo(seriesId: String): Reader = StringReader("{}")
        override fun streamUrl(kind: MediaKind, streamId: String, extension: String?): String =
            XtreamUrls.stream("http://host", "u", "p", kind, streamId, extension)
    }

    /** What a row is, for comparing two imports: everything a viewer could see differ. */
    private data class Row(val id: String, val title: String, val kind: MediaKind, val groupId: String?, val order: Int)

    private open class RecordingWriter : CatalogWriter {
        val items = mutableListOf<CatalogItem>()
        val groups = mutableListOf<MediaGroup>()
        var finishedCalled = false

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

        override fun commit() = Unit
        override fun finish(summary: ImportSummary) {
            finishedCalled = true
        }

        override fun abort(cause: Throwable?) = Unit
    }

    /** Records rows, counts and commits in order, the categories by name. */
    private class CountingWriter : RecordingWriter() {
        val log = mutableListOf<String>()
        private val names = HashMap<String, String>()

        override fun writeGroups(groups: List<MediaGroup>) {
            super.writeGroups(groups)
            for (group in groups) names[group.id] = group.name
        }

        override fun writeItems(items: List<CatalogItem>) {
            super.writeItems(items)
            log += "rows ${items.size}"
        }

        override fun countGroups(groupIds: Collection<String>) {
            log += "count " + groupIds.joinToString(",") { names.getValue(it) }
        }

        override fun commit() {
            log += "commit"
        }

        override fun finish(summary: ImportSummary) {
            super.finish(summary)
            log += "finish"
        }
    }

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
        fun row(id: String, name: String, category: String, number: Int): String =
            """{"num":$number,"name":"$name","stream_id":"$id","stream_icon":"http://cdn/$id.png",""" +
                """"epg_channel_id":"epg.$id","category_id":"$category"}"""

        /**
         * The panel's live streams, in the order a panel sends them without a category:
         * not grouped by category, which is the case the filing has to get right.
         */
        val WHOLE_ROWS = listOf(
            row("13", "Atlas News", "2", number = 3),
            row("11", "Nova Sports 1", "1", number = 1),
            row("31", "Atlas FM", "3", number = 5),
            row("14", "Nova News", "2", number = 4),
            row("12", "Atlas Sport", "1", number = 2),
        )

        val WHOLE_REPLY = WHOLE_ROWS.joinToString(",", "[", "]")

        /** The live endpoint whole: its channels and the stations among them, as a whole catalogue asks. */
        val LIVE_ENDPOINT = setOf(MediaKind.LIVE, MediaKind.RADIO)
    }
}
