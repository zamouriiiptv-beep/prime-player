package com.castivio.benchmark

import com.castivio.data.parsing.CatalogImportEngine
import com.castivio.domain.CatalogItem
import com.castivio.domain.CatalogWriter
import com.castivio.domain.ImportMode
import com.castivio.domain.ImportSummary
import com.castivio.domain.MediaGroup
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gates on the import pipeline: parse → classify → row → batch.
 *
 * The database is stubbed, which is the point. SQLite insert speed is the
 * device's problem and varies by an order of magnitude between a Fire Stick and
 * a Shield; *our* cost per entry is fixed code, so that is what a per-commit
 * gate can honestly hold to a budget.
 */
class ImportBudgetTest {

    @Test
    fun `import throughput stays within budget`() {
        val entries = 200_000
        val lines = Fixtures.m3uLines(entries).toList()   // exclude generation from timing
        val writer = CountingWriter()

        val measurement = Harness.measure("catalogue-import") {
            val summary = CatalogImportEngine(writer).importM3u("bench", lines.asSequence())
            Sink.consumed += writer.titleChars
            summary.items.toLong()
        }
        println("[budget] $measurement")

        assertTrue(
            """
            Catalogue import regressed.
              measured : ${"%,d".format(measurement.perSecond)} entries/sec
              budget   : ${"%,d".format(PerformanceBudgets.IMPORT_ENTRIES_PER_SECOND_MIN)} entries/sec minimum
            This covers parse, classification and row construction. Usual causes:
            a Regex in the classifier, a per-row allocation in id generation, or
            work moved into the per-entry path that belongs per batch.
            """.trimIndent(),
            measurement.perSecond >= PerformanceBudgets.IMPORT_ENTRIES_PER_SECOND_MIN,
        )
    }

    /**
     * The important one: importing 300,000 entries must cost a batch, not a
     * catalogue.
     *
     * The writer here drops everything it is given, so anything still on the heap
     * afterwards is the engine holding on — which on a Fire Stick is the
     * difference between an import and an OOM.
     */
    @Test
    fun `importing does not retain the catalogue`() {
        val count = PerformanceBudgets.MEMORY_PROBE_ENTRIES
        val writer = CountingWriter()
        var imported = 0

        val retainedKb = Harness.retainedKb {
            // Generated lazily, so the fixture is not what occupies the heap.
            val summary = CatalogImportEngine(writer).importM3u("bench", Fixtures.m3uLines(count))
            imported = summary.items
            Sink.consumed += writer.titleChars
        }
        val retainedMb = retainedKb / 1024
        println("[budget] import-retained-heap: $retainedKb KB after $imported items")

        assertTrue("expected to import $count entries, got $imported", imported > count * 0.9)
        assertTrue(
            """
            The import engine is retaining the catalogue.
              measured : $retainedMb MB retained after importing ${"%,d".format(imported)} items
              budget   : ${PerformanceBudgets.IMPORT_RETAINED_HEAP_MB_MAX} MB maximum
            Import memory must be the batch plus the group index, nothing more.
            Something is accumulating rows — most likely a list built to insert
            everything in one transaction at the end.
            """.trimIndent(),
            retainedMb <= PerformanceBudgets.IMPORT_RETAINED_HEAP_MB_MAX,
        )
    }

    /**
     * Stands in for SQLite: touches every row so the work cannot be optimised
     * away, then drops it so retention measures the engine and not the stub.
     */
    private class CountingWriter : CatalogWriter {
        var titleChars = 0L
            private set

        override fun begin(sourceId: String, mode: ImportMode) = Unit

        override fun writeGroups(groups: List<MediaGroup>) {
            for (group in groups) titleChars += group.name.length
        }

        override fun writeItems(items: List<CatalogItem>) {
            for (item in items) titleChars += item.title.length + item.id.length + item.streamUrl.length
        }

        override fun commit() = Unit
        override fun finish(summary: ImportSummary) = Unit
        override fun abort(cause: Throwable?) = Unit
    }
}
