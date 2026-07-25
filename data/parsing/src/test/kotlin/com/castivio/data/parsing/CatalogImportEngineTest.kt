package com.castivio.data.parsing

import com.castivio.domain.CatalogItem
import com.castivio.domain.CatalogWriter
import com.castivio.domain.ImportProgress
import com.castivio.domain.ImportSummary
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogImportEngineTest {

    @Test
    fun `rows are written in bounded batches`() {
        val writer = RecordingWriter()
        val engine = CatalogImportEngine(writer, batchSize = 100)

        val summary = engine.importM3u("src", playlist(250).lineSequence())

        assertEquals(250, summary.items)
        assertEquals(listOf(100, 100, 50), writer.batchSizes)
        // One commit per batch: partial content has to become visible while the
        // rest of the import is still running.
        assertEquals(3, writer.commits)
        assertEquals("src", writer.began)
        assertEquals(summary, writer.finished)
        assertFalse(writer.aborted)
    }

    /**
     * The executable form of "peak memory is the batch, not the playlist": the
     * engine hands the same buffer over every time and clears it. A writer that
     * keeps the reference sees it empty — which is exactly what the interface
     * documents.
     */
    @Test
    fun `the batch buffer is reused rather than reallocated`() {
        val writer = RecordingWriter()
        CatalogImportEngine(writer, batchSize = 50).importM3u("src", playlist(500).lineSequence())

        assertEquals(10, writer.batchSizes.size)
        assertEquals(1, writer.batchIdentities.size)
        assertTrue("the engine must clear the buffer it lends out", writer.lastBatchRef!!.isEmpty())
    }

    @Test
    fun `progress is reported per batch and once at the end`() {
        val writer = RecordingWriter()
        val clock = FakeClock()
        val engine = CatalogImportEngine(writer, batchSize = 100, clock = clock)
        val progress = mutableListOf<ImportProgress>()

        engine.importM3u("src", playlist(250).lineSequence(), onProgress = progress::add)

        val importing = progress.filterIsInstance<ImportProgress.Importing>()
        assertEquals(listOf(100, 200), importing.map { it.itemsImported })
        assertTrue("groups must be reported as they are discovered", importing.all { it.groupsReady > 0 })

        val done = progress.last() as ImportProgress.Done
        assertEquals(250, done.totalItems)
        assertEquals(clock.elapsed, done.durationMs)
    }

    @Test
    fun `groups are derived in provider order and kept distinct per kind`() {
        val writer = RecordingWriter()
        val lines = listOf(
            "#EXTM3U",
            """#EXTINF:-1 group-title="Sports",Nova Sports""",
            "http://host/live/u/p/1.ts",
            """#EXTINF:-1 group-title="News",Atlas News""",
            "http://host/live/u/p/2.ts",
            """#EXTINF:7200 group-title="Sports",The Final Match""",
            "http://host/movie/u/p/3.mp4",
            """#EXTINF:-1 group-title="  ",Nameless""",
            "http://host/live/u/p/4.ts",
        )

        val summary = CatalogImportEngine(writer).importM3u("src", lines.asSequence())

        assertEquals(listOf("Sports", "News", "Sports"), writer.groups.map { it.name })
        assertEquals(
            listOf(MediaKind.LIVE, MediaKind.LIVE, MediaKind.MOVIE),
            writer.groups.map { it.kind },
        )
        // Same name, different kind — different group, because browsing live
        // "Sports" must not show films.
        assertNotEquals(writer.groups[0].id, writer.groups[2].id)
        assertEquals(3, summary.groups)
        // A blank group title is no group at all, not a group called " ".
        assertNull(writer.items.single { it.title == "Nameless" }.groupId)
    }

    @Test
    fun `provider order is preserved`() {
        val writer = RecordingWriter()
        CatalogImportEngine(writer, batchSize = 10).importM3u("src", playlist(30).lineSequence())

        assertEquals((0 until 30).toList(), writer.items.map { it.providerOrder })
        assertEquals("Channel 0", writer.items.first().title)
        assertEquals("Channel 29", writer.items.last().title)
    }

    /**
     * Favourites and watch progress point at these ids. If a nightly refresh
     * re-keys the catalogue, the user silently loses both.
     */
    @Test
    fun `ids are stable across imports and scoped to the source`() {
        val first = RecordingWriter()
        val second = RecordingWriter()
        val third = RecordingWriter()

        CatalogImportEngine(first).importM3u("src-a", playlist(20).lineSequence())
        CatalogImportEngine(second).importM3u("src-a", playlist(20).lineSequence())
        CatalogImportEngine(third).importM3u("src-b", playlist(20).lineSequence())

        assertEquals(first.items.map { it.id }, second.items.map { it.id })
        assertEquals(20, first.items.map { it.id }.toSet().size)
        assertTrue(
            "two sources in one database must never share ids",
            first.items.map { it.id }.intersect(third.items.map { it.id }.toSet()).isEmpty(),
        )
    }

    @Test
    fun `episodes of one show share a series id`() {
        val writer = RecordingWriter()
        val lines = listOf(
            "#EXTM3U",
            """#EXTINF:-1 group-title="Series",Breaking  Bad S01E01 - Pilot""",
            "http://host/series/u/p/11.mkv",
            """#EXTINF:-1 group-title="Series",breaking bad S01E02""",
            "http://host/series/u/p/12.mkv",
            """#EXTINF:-1 group-title="Series",Chernobyl S01E01""",
            "http://host/series/u/p/13.mkv",
        )

        CatalogImportEngine(writer).importM3u("src", lines.asSequence())

        val (pilot, second, other) = writer.items
        // Case and doubled spacing must not split one show into three.
        assertEquals(pilot.seriesId, second.seriesId)
        assertNotEquals(pilot.seriesId, other.seriesId)
        assertEquals("Pilot", pilot.title)
        assertEquals("Breaking  Bad", pilot.seriesTitle)
        assertEquals(1, pilot.seasonNumber)
        assertEquals(2, second.episodeNumber)
        // No episode title of its own: keep the provider's name rather than blank.
        assertEquals("breaking bad S01E02", second.title)
    }

    @Test
    fun `every kind is counted including radio`() {
        val writer = RecordingWriter()
        val lines = listOf(
            "#EXTM3U",
            """#EXTINF:-1 group-title="Sports",Nova Sports""",
            "http://host/live/u/p/1.ts",
            """#EXTINF:-1 group-title="Radio MA",Radio Mars""",
            "http://host/live/u/p/2.ts",
            """#EXTINF:7200,The Long Return""",
            "http://host/movie/u/p/3.mp4",
            """#EXTINF:-1,Show S01E01""",
            "http://host/series/u/p/4.mkv",
            // Orphan URL with no #EXTINF — common in real playlists.
            "http://host/live/u/p/5.ts",
        )

        val summary = CatalogImportEngine(writer).importM3u("src", lines.asSequence())

        assertEquals(4, summary.items)
        assertEquals(1, summary.count(MediaKind.LIVE))
        assertEquals(1, summary.count(MediaKind.RADIO))
        assertEquals(1, summary.count(MediaKind.MOVIE))
        assertEquals(1, summary.count(MediaKind.SERIES))
        assertEquals(1, summary.skipped)
        assertEquals(7_200, writer.items.single { it.kind == MediaKind.MOVIE }.durationSeconds)
        // Live rows carry no duration; a "0 min" badge in the guide is a bug.
        assertNull(writer.items.single { it.kind == MediaKind.LIVE }.durationSeconds)
    }

    @Test
    fun `metadata survives the round trip`() {
        val writer = RecordingWriter()
        val lines = listOf(
            "#EXTM3U",
            """#EXTINF:-1 tvg-id="nova.1" tvg-logo="http://cdn/l,1.png" group-title="Sports",Nova Sports 1""",
            "http://host/live/u/p/1.ts",
        )

        CatalogImportEngine(writer).importM3u("src", lines.asSequence())

        val item = writer.items.single()
        assertEquals("Nova Sports 1", item.title)
        assertEquals("http://host/live/u/p/1.ts", item.streamUrl)
        assertEquals("nova.1", item.epgChannelId)
        assertEquals("http://cdn/l,1.png", item.artworkUrl)
        assertEquals("src", item.sourceId)
        assertEquals(writer.groups.single().id, item.groupId)
    }

    @Test
    fun `cancellation stops reading the playlist and keeps what was committed`() {
        val writer = RecordingWriter()
        val counted = CountingSequence(playlist(10_000).lineSequence())
        val engine = CatalogImportEngine(writer, batchSize = 100)
        val progress = mutableListOf<ImportProgress>()

        val summary = engine.importM3u(
            sourceId = "src",
            lines = counted.sequence,
            onProgress = progress::add,
            isCancelled = { true },
        )

        assertTrue(summary.cancelled)
        assertEquals(100, summary.items)
        // Reading the remaining ~20,000 lines of a cancelled import would keep
        // the network busy for a screen the user has already left.
        assertTrue("read ${counted.read} lines after cancelling", counted.read < 250)
        assertEquals(100, writer.items.size)
        assertTrue("committed rows must survive cancellation", writer.commits >= 1)
        assertTrue(writer.aborted)
        assertNull(writer.abortCause)
        assertNull("a cancelled import is not a finished import", writer.finished)
        assertTrue(progress.none { it is ImportProgress.Done })
    }

    @Test
    fun `a writer failure aborts and propagates`() {
        val writer = RecordingWriter(failOnBatch = 1)
        val engine = CatalogImportEngine(writer, batchSize = 10)

        val thrown = runCatching { engine.importM3u("src", playlist(100).lineSequence()) }
            .exceptionOrNull()

        assertTrue("the caller must see the failure", thrown is IllegalStateException)
        assertTrue(writer.aborted)
        assertSame(thrown, writer.abortCause)
        assertNull(writer.finished)
    }

    @Test
    fun `an empty playlist is a valid import, not a failure`() {
        val writer = RecordingWriter()

        val summary = CatalogImportEngine(writer).importM3u("src", sequenceOf("#EXTM3U"))

        assertEquals(0, summary.items)
        assertEquals(0, summary.groups)
        assertEquals(0, writer.commits)
        assertEquals(summary, writer.finished)
        assertFalse(writer.aborted)
    }

    @Test
    fun `the batch size is bounded`() {
        val tooLarge = runCatching {
            CatalogImportEngine(RecordingWriter(), batchSize = CatalogImportEngine.MAX_BATCH + 1)
        }
        assertTrue(tooLarge.exceptionOrNull() is IllegalArgumentException)
        assertTrue(
            runCatching { CatalogImportEngine(RecordingWriter(), batchSize = 0) }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }

    // ------------------------------------------------------------------ fixtures

    private fun playlist(entries: Int): String = buildString {
        appendLine("#EXTM3U")
        for (i in 0 until entries) {
            val group = if (i % 2 == 0) "Sports" else "News"
            appendLine("""#EXTINF:-1 tvg-id="ch$i" group-title="$group",Channel $i""")
            appendLine("http://host/live/u/p/$i.ts")
        }
    }

    private class CountingSequence(source: Sequence<String>) {
        var read = 0
            private set
        val sequence: Sequence<String> = source.onEach { read++ }
    }

    private class FakeClock : () -> Long {
        private var now = 1_000L
        val elapsed get() = 50L
        override fun invoke(): Long {
            val value = now
            now += elapsed
            return value
        }
    }

    /**
     * Stands in for the SQLite writer. Copies each batch on purpose — the engine
     * reuses its buffer, and a test that kept the reference would assert on an
     * empty list.
     */
    private class RecordingWriter(private val failOnBatch: Int = -1) : CatalogWriter {
        val batchSizes = mutableListOf<Int>()
        val batchIdentities = mutableSetOf<Int>()
        val groups = mutableListOf<MediaGroup>()
        val items = mutableListOf<CatalogItem>()
        var lastBatchRef: List<CatalogItem>? = null
        var commits = 0
        var began: String? = null
        var finished: ImportSummary? = null
        var aborted = false
        var abortCause: Throwable? = null

        override fun begin(sourceId: String) {
            began = sourceId
        }

        override fun writeGroups(groups: List<MediaGroup>) {
            this.groups += groups
        }

        override fun writeItems(items: List<CatalogItem>) {
            if (batchSizes.size == failOnBatch) throw IllegalStateException("disk full")
            batchSizes += items.size
            batchIdentities += System.identityHashCode(items)
            lastBatchRef = items
            this.items += items
        }

        override fun commit() {
            commits++
        }

        override fun finish(summary: ImportSummary) {
            finished = summary
        }

        override fun abort(cause: Throwable?) {
            aborted = true
            abortCause = cause
        }
    }
}
