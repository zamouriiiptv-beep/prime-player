package com.castivio.data.parsing

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
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The import's concurrency, its failure isolation and its ordering.
 *
 * These are the Phase C acceptance criteria expressed as code. Each one replaces a
 * behaviour that was previously true and cost the user the catalogue:
 *
 *  - the loop was strictly sequential, so a 497-category provider was 497 round trips
 *    before anything could be read;
 *  - any single failing request threw out of the loop into `writer.abort`, discarding
 *    every category that had already succeeded;
 *  - ordering came from one counter incremented across the whole import, which
 *    concurrency would otherwise have scrambled.
 */
class XtreamImportConcurrencyTest {

    /**
     * **Bounded: neither one at a time nor all at once.**
     *
     * The two ways to get this wrong are opposite and both are failures. Sequential is
     * what was measured at 497 round trips; unbounded is 497 sockets aimed at one PHP
     * host, which gets the user's subscription throttled rather than their app made
     * fast. The assertion is on the *peak* observed, so it fails in both directions.
     */
    @Test
    fun `requests run concurrently but never exceed the limit`() {
        val api = CountingApi(categories = 24, itemsPerCategory = 3)
        val writer = RecordingWriter()

        XtreamImportEngine(writer, concurrency = 4)
            .importCatalogue("s", api, kinds = setOf(MediaKind.LIVE), mode = ImportMode.APPEND)

        assertTrue(
            "peak concurrency was ${api.peak}, over the limit of 4",
            api.peak <= 4,
        )
        assertTrue(
            "peak concurrency was ${api.peak}: the import is still effectively sequential",
            api.peak > 1,
        )
    }

    /**
     * **One category that will not load no longer takes the catalogue with it.**
     *
     * This is the defect that produced an activation screen after four minutes of
     * successful downloading. Every other category must still be written, and the
     * import must still report a summary rather than throwing.
     */
    @Test
    fun `a failing category does not cancel the others`() {
        val api = CountingApi(categories = 10, itemsPerCategory = 5, failing = setOf("3", "7"))
        val writer = RecordingWriter()

        val summary = XtreamImportEngine(writer, concurrency = 4)
            .importCatalogue("s", api, kinds = setOf(MediaKind.LIVE), mode = ImportMode.APPEND)

        // Eight of the ten categories carried five items each.
        assertEquals(40, summary.items)
        assertEquals(40, writer.items.size)
        assertTrue("the writer was aborted", !writer.aborted)
        assertTrue("the import did not finish", writer.finished)
    }

    /**
     * **Provider order survives out-of-order completion.**
     *
     * For live television the provider's order *is* the channel numbering, which is
     * what a remote navigates by. Categories now finish in whatever order the network
     * returns them, so a shared counter would number the same catalogue differently on
     * every import. Position is derived from the category's index instead, and this is
     * what says so.
     */
    @Test
    fun `ordering follows the provider, not the order replies arrive in`() {
        val api = CountingApi(categories = 6, itemsPerCategory = 4, reverseDelays = true)
        val writer = RecordingWriter()

        XtreamImportEngine(writer, concurrency = 4)
            .importCatalogue("s", api, kinds = setOf(MediaKind.LIVE), mode = ImportMode.APPEND)

        // Category 0's rows must all sort before category 1's, and so on, whatever
        // order the replies actually came back in.
        val byCategory = writer.items.groupBy { it.groupId }
        val spans = byCategory.values.map { rows -> rows.minOf { it.providerOrder } to rows.maxOf { it.providerOrder } }
            .sortedBy { it.first }
        for (i in 1 until spans.size) {
            assertTrue(
                "category spans overlap: ${spans[i - 1]} then ${spans[i]}",
                spans[i - 1].second < spans[i].first,
            )
        }
    }

    /** Cancellation stops the import rather than draining every remaining category. */
    @Test
    fun `cancellation stops further categories`() {
        val api = CountingApi(categories = 40, itemsPerCategory = 2)
        val writer = RecordingWriter()
        var seen = 0

        XtreamImportEngine(writer, concurrency = 4).importCatalogue(
            "s", api,
            kinds = setOf(MediaKind.LIVE),
            onProgress = { if (it is ImportProgress.Importing) seen++ },
            isCancelled = { seen >= 3 },
            mode = ImportMode.APPEND,
        )

        assertTrue(
            "every one of the 40 categories was fetched despite cancellation",
            api.started.get() < 40,
        )
    }

    /** The ceiling exists so nobody can turn this back into 497 parallel requests. */
    @Test
    fun `concurrency is bounded at construction`() {
        val writer = RecordingWriter()
        runCatching { XtreamImportEngine(writer, concurrency = 0) }
            .onSuccess { error("a concurrency of 0 was accepted") }
        runCatching { XtreamImportEngine(writer, concurrency = XtreamImportEngine.MAX_CONCURRENCY + 1) }
            .onSuccess { error("a concurrency above the ceiling was accepted") }
    }

    /* --------------------------------------------------------------------- doubles */

    /**
     * An API that counts how many calls overlap.
     *
     * The peak is the measurement: a sequential engine never exceeds one, an unbounded
     * one reaches the category count, and a correct one sits at the limit.
     */
    private class CountingApi(
        val categories: Int,
        val itemsPerCategory: Int,
        val failing: Set<String> = emptySet(),
        /** Makes early categories slowest, so replies arrive in reverse order. */
        val reverseDelays: Boolean = false,
    ) : XtreamImportEngine.Api {

        val started = AtomicInteger()
        private val inFlight = AtomicInteger()

        @Volatile
        var peak = 0

        override fun categories(kind: MediaKind): Reader {
            val body = (0 until categories).joinToString(",") {
                """{"category_id":"$it","category_name":"Category $it"}"""
            }
            return StringReader("[$body]")
        }

        override fun streams(kind: MediaKind, categoryId: String): Reader {
            started.incrementAndGet()
            val now = inFlight.incrementAndGet()
            synchronized(this) { if (now > peak) peak = now }
            try {
                if (reverseDelays) Thread.sleep((categories - categoryId.toInt()) * 4L) else Thread.sleep(4)
                if (categoryId in failing) throw IOException("category $categoryId is unavailable")
                // The same shape `XtreamImportEngineTest` uses, so this test cannot
                // pass against a fixture the real parser would reject.
                val body = (0 until itemsPerCategory).joinToString(",") { n ->
                    """{"num":0,"name":"Channel $categoryId-$n","stream_id":"$categoryId-$n",""" +
                        """"stream_icon":"http://cdn/$categoryId-$n.png",""" +
                        """"epg_channel_id":"epg.$categoryId-$n","category_id":"$categoryId"}"""
                }
                return StringReader("[$body]")
            } finally {
                inFlight.decrementAndGet()
            }
        }

        override fun series(categoryId: String): Reader = StringReader("[]")
        override fun seriesInfo(seriesId: String): Reader = StringReader("{}")
        override fun streamUrl(kind: MediaKind, streamId: String, extension: String?): String =
            "http://example.invalid/$streamId"
    }

    /** Collects what reached SQLite, from whichever thread the engine writes on. */
    private class RecordingWriter : CatalogWriter {
        val items: MutableList<CatalogItem> = Collections.synchronizedList(ArrayList())
        val groups: MutableList<MediaGroup> = Collections.synchronizedList(ArrayList())
        var finished = false
        var aborted = false

        override fun begin(sourceId: String, mode: ImportMode) = Unit
        override fun writeGroups(groups: List<MediaGroup>) { this.groups += groups }
        override fun writeItems(items: List<CatalogItem>) { this.items += items }
        override fun commit() = Unit
        override fun finish(summary: ImportSummary) { finished = true }
        override fun abort(cause: Throwable?) { aborted = true }
    }
}
