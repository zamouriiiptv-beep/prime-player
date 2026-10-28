package com.castivio.data.parsing

import com.castivio.domain.CatalogItem
import com.castivio.domain.CatalogWriter
import com.castivio.domain.ImportMode
import com.castivio.domain.ImportProgress
import com.castivio.domain.ImportSummary
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaKind
import com.castivio.domain.RequestTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Reader
import java.io.StringReader
import java.util.concurrent.CopyOnWriteArrayList

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
        // Sorted by `providerOrder`, not by the order rows reached the writer.
        //
        // Categories are fetched four at a time, so arrival order is whichever reply
        // landed first -- this assertion was written against the sequential engine and
        // has been failing about one run in twelve ever since, silently, because a flake
        // that rare reads as an unlucky CI run. `XtreamImportConcurrencyTest` was moved
        // onto `providerOrder` when the concurrency landed; this one was missed.
        //
        // The sorted assertion is the *stronger* claim: it says the catalogue reads in
        // the provider's own order -- for live television, the channel numbering -- which
        // is the guarantee a viewer actually has, and it holds however the replies land.
        assertEquals(
            listOf("Nova Sports 1", "Atlas Sport", "Atlas News"),
            writer.items.sortedBy { it.providerOrder }.map { it.title },
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
     * category name is the only signal there is. Asked for together, as a whole
     * catalogue asks, the two are told apart in the one walk.
     */
    @Test
    fun `a radio category becomes radio, not live`() {
        val writer = RecordingWriter()

        val summary = XtreamImportEngine(writer).importCatalogue("src", liveAndRadio(), kinds = setOf(MediaKind.LIVE, MediaKind.RADIO))

        assertEquals(1, summary.count(MediaKind.LIVE))
        assertEquals(1, summary.count(MediaKind.RADIO))
        assertEquals(MediaKind.RADIO, writer.groups.first { it.name.contains("RADIO") }.kind)
        assertEquals(MediaKind.RADIO, writer.items.single { it.title == "Radio Mars" }.kind)
    }

    /** A load of the channels is a load of the channels: no station, no radio group, no radio request. */
    @Test
    fun `channels asked for alone leave the radio categories alone`() {
        val writer = RecordingWriter()
        val api = liveAndRadio()

        val summary = XtreamImportEngine(writer).importCatalogue("src", api, kinds = setOf(MediaKind.LIVE))

        assertEquals(listOf("Nova Sports"), writer.items.map { it.title })
        assertEquals(listOf("Sports"), writer.groups.map { it.name })
        assertEquals(0, summary.count(MediaKind.RADIO))
        assertEquals(listOf("1"), api.streamRequests.map { it.second })
    }

    /** And a load of the radio walks the radio categories alone, touching no channel. */
    @Test
    fun `radio asked for alone walks only the radio categories`() {
        val writer = RecordingWriter()
        val api = liveAndRadio()

        val summary = XtreamImportEngine(writer).importCatalogue("src", api, kinds = setOf(MediaKind.RADIO))

        assertEquals(listOf(MediaKind.RADIO), writer.items.map { it.kind })
        assertEquals(listOf(MediaKind.RADIO), writer.groups.map { it.kind })
        assertEquals(0, summary.count(MediaKind.LIVE))
        assertEquals(listOf("2"), api.streamRequests.map { it.second })
    }

    private fun liveAndRadio() = FakeApi(
        liveCategories = listOf("1" to "Sports", "2" to "| AR | RADIO"),
        liveStreams = mapOf(
            "1" to listOf(stream("11", "Nova Sports")),
            "2" to listOf(stream("21", "Radio Mars")),
        ),
    )

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

    /**
     * Where the wait for a show's page went is told once the episodes are kept: the catalogue made ready, the provider
     * answering, its answer read, and the reply kept. A clock that is stepped says which is which.
     */
    @Test
    fun `the cost of a show's request is told in the order it was paid`() {
        val api = FakeApi(seriesInfo = mapOf("551" to listOf(Episode("9001", "Pilot", 1, 1))))
        var now = 0L
        // Every reading of the clock is a millisecond later than the one before it.
        val engine = XtreamImportEngine(RecordingWriter(), nanoClock = { now += 1_000_000L; now })
        val told = mutableListOf<RequestTimes>()

        engine.importEpisodes("src", api, "551", "Chernobyl", null, onTimes = { told += it })

        val cost = told.single()
        assertTrue("open ${cost.openMs}", cost.openMs >= 1)
        assertTrue("ask ${cost.askMs}", cost.askMs >= 1)
        assertTrue("read ${cost.readMs}", cost.readMs >= 1)
        assertTrue("save ${cost.saveMs}", cost.saveMs >= 1)
        assertEquals(1, cost.tries)
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

    /**
     * `get_short_epg` and catch-up URLs are addressed by the provider's stream id,
     * and the row id is a hash that cannot be reversed — so the id has to be kept.
     */
    @Test
    fun `the provider's own ids are kept on every row`() {
        val writer = RecordingWriter()
        val api = FakeApi(
            liveCategories = listOf("1" to "Sports"),
            liveStreams = mapOf("1" to listOf(stream("11", "Nova"))),
            seriesCategories = listOf("9" to "Drama"),
            series = mapOf("9" to listOf("551" to "Chernobyl")),
            seriesInfo = mapOf("551" to listOf(Episode("9001", "Pilot", 1, 1))),
        )

        XtreamImportEngine(writer).importCatalogue(
            "src", api, kinds = setOf(MediaKind.LIVE, MediaKind.SERIES),
        )
        assertEquals("11", writer.items.first { it.title == "Nova" }.providerRef)
        assertEquals("551", writer.items.first { it.title == "Chernobyl" }.providerRef)

        val episodes = RecordingWriter()
        XtreamImportEngine(episodes).importEpisodes("src", api, "551", "Chernobyl", null)
        assertEquals("9001", episodes.items.single().providerRef)
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

    /**
     * The loading screen's `120 / 834` is a real fraction, and it reaches its end.
     *
     * Three things this pins, each of which was wrong at some point while it was written:
     *
     *  1. **The denominator is the total, from the first report.** Every category is
     *     written before any worker starts, so `groupsReady` is the whole set rather than
     *     a second running count. A bar whose denominator grows is a bar that goes
     *     backwards.
     *  2. **The numerator reaches the denominator.** Finishing a category *reports*
     *     rather than merely counting. It counted silently first, which left the last
     *     category's completion unannounced — the bar resting at two of three until the
     *     import ended.
     *  3. **An empty category still counts.** It sends no rows, so there is no `Items`
     *     event to report against, and a numerator driven only by rows would never move
     *     for it.
     */
    @Test
    fun `progress counts every category that finishes, including an empty one`() {
        val writer = RecordingWriter()
        val api = FakeApi(
            liveCategories = listOf("1" to "News", "2" to "Empty", "3" to "Sport"),
            liveStreams = mapOf(
                "1" to listOf(stream("11", "Nova")),
                "2" to emptyList(),
                "3" to listOf(stream("31", "Arena")),
            ),
        )
        val progress = mutableListOf<ImportProgress>()

        XtreamImportEngine(writer)
            .importCatalogue("src", api, kinds = setOf(MediaKind.LIVE), onProgress = progress::add)

        val reports = progress.filterIsInstance<ImportProgress.Importing>()
        assertTrue("the import should report progress at all", reports.isNotEmpty())
        assertTrue(
            "the denominator is the whole set from the first report",
            reports.all { it.groupsReady == 3 },
        )

        // Non-null throughout: Xtream addresses categories one at a time, so it can
        // always answer the question. Null is reserved for the importers that cannot.
        val counted = reports.map { it.categoriesDone ?: error("Xtream must report a count") }
        assertEquals("every category should be counted done by the end", 3, counted.last())
        // Never backwards, and never past the end: the two ways a fraction stops being
        // believed. Categories finish out of order under four workers, so this is a
        // property of the counter rather than of the arrival order.
        assertEquals(counted, counted.sorted())
        assertTrue(reports.all { (it.categoriesDone ?: 0) <= it.groupsReady })
    }

    /**
     * Cancellation is still observed, and now costs nothing rather than one round trip.
     *
     * **Updated by Phase C, and tightened rather than relaxed.** It used to assert that
     * exactly one category was fetched, because the sequential loop checked the flag
     * *after* fetching. Workers check it before taking their permit, so a run cancelled
     * at the outset now issues no stream request at all. The import must still report
     * itself cancelled and must still abort — that half is unchanged and is what this
     * keeps guarding.
     */
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
        assertEquals("a cancelled run should fetch no category at all", 0, api.streamRequests.size)
        assertTrue(writer.aborted)
        assertNull(writer.finished)
    }

    /**
     * A failure that leaves nothing behind still fails, and still carries its cause.
     *
     * **Kept deliberately, against a change that could have quietly removed it.** Phase C
     * makes a failing category survivable so that one bad request out of five hundred
     * cannot discard the other four hundred and ninety-nine — see
     * `XtreamImportConcurrencyTest`. It would have been easy to let that swallow *every*
     * failure and report an empty success, and an empty success reaches the user as
     * "your provider carries nothing" instead of "it timed out".
     *
     * So the rule is: partial failure survives, total failure propagates. This is the
     * total case — one category, and it fails — and it must still throw and still abort.
     */
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

    /**
     * **A transaction per category was costing more than the content it revealed.**
     *
     * Writing a batch and committing it used to be one call, which was right while a
     * batch and a category were the same size. On the measured provider they are not:
     * 19,003 channels across 495 categories is 38 rows each, so the import opened and
     * closed 495 transactions — 23.98s of commit time against 2.39s to first content.
     * Every one of those commits after the first was paid so that rows nobody was
     * waiting for could become visible 38 at a time.
     *
     * The two assertions are the two halves of the rule, and they pull in opposite
     * directions on purpose: commit far less often, and still commit the first rows
     * immediately. A change that satisfies one by breaking the other fails here.
     *
     * The clock is frozen, so [XtreamImportEngine.COMMIT_INTERVAL_MS] cannot fire and
     * the count is decided by the row rule alone. That makes the assertion a statement
     * about the policy rather than about how fast the machine running it is.
     */
    @Test
    fun `commits are far fewer than categories, and the first one is immediate`() {
        val categories = (1..60).map { it.toString() to "Category $it" }
        val writer = CommitCountingWriter()
        val api = FakeApi(
            liveCategories = categories,
            liveStreams = categories.associate { (id, _) ->
                id to (1..40).map { n -> stream("$id-$n", "Channel $id-$n") }
            },
        )

        XtreamImportEngine(writer, clock = { FROZEN_MS }).importCatalogue(
            sourceId = "src",
            api = api,
            kinds = setOf(MediaKind.LIVE),
        )

        assertEquals(2_400, writer.items)

        // Row-driven: 2,400 rows at a 2,000 ceiling is one forced commit, plus the
        // first and the final one. Nothing like 60.
        assertTrue(
            "committed ${writer.commits} times for 60 categories: still one per category",
            writer.commits <= 6,
        )

        // And the first content is not held back for any of that. The first commit
        // carries the first category and nothing more, which is what TTFC measures.
        assertEquals(
            "the first commit waited for more than the first category",
            40,
            writer.itemsAtFirstCommit,
        )
    }

    /**
     * A category read on its own is complete when its request ends, and is counted then
     * rather than at the end of the load -- what lets a screen opened during the load list
     * it. The first one with rows is committed at once: it is what the section opens with.
     * One that turned out empty is counted, to zero, and never listed.
     *
     * The clock is frozen, as above, so no commit comes from the half-second rule: without
     * the first category's own commit, both categories with rows would be counted together
     * at the end. Categories finish in any order under the workers, so nothing here depends
     * on which comes first.
     */
    @Test
    fun `each category is counted when its request ends, and the first one with rows at once`() {
        val writer = CountingWriter()
        val api = FakeApi(
            liveCategories = listOf("1" to "News", "2" to "Empty", "3" to "Sport"),
            liveStreams = mapOf(
                "1" to listOf(stream("11", "Nova")),
                "2" to emptyList(),
                "3" to listOf(stream("31", "Arena")),
            ),
        )
        val progress = mutableListOf<ImportProgress>()

        XtreamImportEngine(writer, clock = { FROZEN_MS }).importCatalogue(
            sourceId = "src",
            api = api,
            kinds = setOf(MediaKind.LIVE),
            onProgress = progress::add,
        )

        val counts = writer.log.take(writer.log.indexOf("finish"))
            .filter { it.startsWith("count ") }
            .map { it.removePrefix("count ").split(",") }
        val firstListed = counts.first { "News" in it || "Sport" in it }
        assertEquals(
            "the first category with rows waited for the other: $counts",
            1,
            firstListed.count { it == "News" || it == "Sport" },
        )
        assertEquals("every category is counted before the load ends", setOf("News", "Empty", "Sport"), counts.flatten().toSet())

        val reports = progress.filterIsInstance<ImportProgress.Importing>()
        val listed = reports.map { it.categoriesListed }
        assertEquals("listed never goes back", listed, listed.sorted())
        assertTrue("a category was listed before the load ended", listed.any { it >= 1 })
    }

    /** A category with nothing in it is counted -- its count is zero -- and never listed. */
    @Test
    fun `an empty category is counted and never listed`() {
        val writer = CountingWriter()
        val api = FakeApi(liveCategories = listOf("2" to "Empty"), liveStreams = mapOf("2" to emptyList()))
        val progress = mutableListOf<ImportProgress>()

        XtreamImportEngine(writer, clock = { FROZEN_MS }).importCatalogue(
            sourceId = "src",
            api = api,
            kinds = setOf(MediaKind.LIVE),
            onProgress = progress::add,
        )

        assertTrue("counted, so a count from before is not left behind", "count Empty" in writer.log)
        val reports = progress.filterIsInstance<ImportProgress.Importing>()
        assertTrue(reports.isNotEmpty())
        assertTrue("an empty category was listed", reports.all { it.categoriesListed == 0 })
    }

    /** Records rows, counts and commits in order, the categories by name. */
    private class CountingWriter : CatalogWriter {
        val log = mutableListOf<String>()
        private val names = HashMap<String, String>()

        override fun begin(sourceId: String, mode: ImportMode, sections: Set<MediaKind>) = Unit
        override fun writeGroups(groups: List<MediaGroup>) {
            for (group in groups) names[group.id] = group.name
        }

        override fun writeItems(items: List<CatalogItem>) {
            log += "rows ${items.size}"
        }

        override fun countGroups(groupIds: Collection<String>) {
            log += "count " + groupIds.joinToString(",") { names.getValue(it) }
        }

        override fun commit() {
            log += "commit"
        }

        override fun finish(summary: ImportSummary) {
            log += "finish"
        }

        override fun abort(cause: Throwable?) = Unit
    }

    /** Counts transactions, and remembers how much the first one carried. */
    private class CommitCountingWriter : CatalogWriter {
        var items = 0
        var commits = 0
        var itemsAtFirstCommit = -1
        var finished = false

        override fun begin(sourceId: String, mode: ImportMode, sections: Set<MediaKind>) = Unit
        override fun writeGroups(groups: List<MediaGroup>) = Unit
        override fun writeItems(items: List<CatalogItem>) {
            this.items += items.size
        }

        override fun commit() {
            commits++
            if (itemsAtFirstCommit < 0) itemsAtFirstCommit = items
        }

        override fun finish(summary: ImportSummary) {
            finished = true
        }

        override fun abort(cause: Throwable?) = Unit
    }

    /** Any instant; only its constancy matters. */
    private val FROZEN_MS = 1_700_000_000_000L

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

        // Appended to from the engine's readers, which ask for several categories at once. A plain
        // list lost an append, or threw inside `streams` -- which the engine rightly took for a
        // failed category, so a test of 2,400 rows now and then counted 2,360.
        val categoryRequests = CopyOnWriteArrayList<MediaKind>()
        val streamRequests = CopyOnWriteArrayList<Pair<MediaKind, String>>()
        val openReaders = CopyOnWriteArrayList<TrackedReader>()

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

        override fun begin(sourceId: String, mode: ImportMode, sections: Set<MediaKind>) {
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
