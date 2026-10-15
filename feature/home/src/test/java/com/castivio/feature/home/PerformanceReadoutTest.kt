package com.castivio.feature.home

import com.castivio.core.platform.CallTimes
import com.castivio.core.platform.DatabaseStep
import com.castivio.core.platform.IndexCheck
import com.castivio.core.platform.PassTimes
import com.castivio.core.platform.PerfMode
import com.castivio.core.platform.PerfPhase
import com.castivio.core.platform.PerfSection
import com.castivio.core.platform.RecordCounts
import com.castivio.core.platform.RunPhase
import com.castivio.core.platform.SectionReport
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lines the performance panel draws for a run.
 *
 * They are photographed off a phone and compared reading against reading, so what is
 * tested is the shape as much as the arithmetic: the same lines in the same order, spans
 * that add up to the full load they cut, parts that add up to the time they divide, and
 * nothing printed for a moment never reached.
 */
class PerformanceReadoutTest {

    // ------------------------------------------------------------------ the spans

    @Test
    fun `the spans cut the full load in the order the run reached its moments`() {
        val readout = performanceReadout(measured())

        val spans = readout.lines.filterIsInstance<ReadoutLine.Legend>().single().spans
        assertEquals(
            listOf(
                "import", "checks before", "categories", "groups", "first commit",
                "streams", "finished", "checks after", "recorded", "done",
            ),
            spans.map { it.label },
        )
        assertEquals(
            listOf(20L, 310L, 1_980L, 150L, 1_650L, 28_120L, 4_850L, 350L, 10L, 80L),
            spans.map { it.ms },
        )
        assertEquals("the spans are the full load, cut", 37_520L, spans.sumOf { it.ms })
        assertEquals("28.12", spans.single { it.label == "streams" }.figure)
        assertEquals(spans, readout.lines.filterIsInstance<ReadoutLine.Cuts>().single().spans)
    }

    /**
     * **The stages add up to the full load as they are printed, not only as they were measured.**
     *
     * Four spans of 5 ms are 0.01 each when rounded one by one, and 0.04 beside a full load
     * of 0.02: a reader adding the legend up would find a total nothing took. Rounded
     * together they come to the full load's own figure.
     */
    @Test
    fun `the stage figures add up to the full load's figure to the hundredth`() {
        val readout = performanceReadout(
            report(PerfMode.COLD).copy(
                phases = listOf(phase(PerfPhase.IMPORT, 5), phase(PerfPhase.CATEGORIES, 10), phase(PerfPhase.STREAMS, 15)),
                fullLoadMs = 20,
            ),
        )

        val full = readout.lines.filterIsInstance<ReadoutLine.Figure>().single { it.label == "Full Load" }.value
        val spans = readout.lines.filterIsInstance<ReadoutLine.Legend>().single().spans
        assertEquals("0.02 s", full)
        assertEquals("0.02", hundredthsSum(spans.map { it.figure }))

        // And on a real reading, whatever the milliseconds.
        val measured = performanceReadout(measured().copy(fullLoadMs = 37_527))
        assertEquals(
            measured.lines.filterIsInstance<ReadoutLine.Figure>().single { it.label == "Full Load" }.value.removeSuffix(" s"),
            hundredthsSum(measured.lines.filterIsInstance<ReadoutLine.Legend>().single().spans.map { it.figure }),
        )
    }

    /**
     * Still running: the spans so far are named, but nothing is drawn to scale, because a
     * bar divided by the time so far would be a second progress bar under the gate's.
     */
    @Test
    fun `a running load names its spans but draws no bar`() {
        val readout = performanceReadout(
            report(PerfMode.RUNNING).copy(
                phases = listOf(phase(PerfPhase.IMPORT, 40), phase(PerfPhase.CATEGORIES, 620)),
            ),
        )

        assertEquals(ReadoutLine.Elapsed, readout.lines.first())
        assertTrue(readout.lines.none { it is ReadoutLine.Cuts })
        val spans = readout.lines.filterIsInstance<ReadoutLine.Legend>().single().spans
        assertEquals("a span was closed by a full load that has not happened", listOf("import", "categories"), spans.map { it.label })
    }

    @Test
    fun `a span too short to see is drawn at a floor, and none is drawn at zero`() {
        val spans = performanceReadout(
            report(PerfMode.COLD).copy(
                phases = listOf(phase(PerfPhase.IMPORT, 1), phase(PerfPhase.STREAMS, 10_000)),
                fullLoadMs = 10_000,
            ),
        ).lines.filterIsInstance<ReadoutLine.Cuts>().single().spans

        val floor = 10_000 * 0.006f
        assertTrue("one millisecond vanished from the bar", spans[0].weight >= floor)
        assertEquals("a zero span is still a weight a layout accepts", 0L, spans[2].ms)
        assertTrue(spans[2].weight > 0f)
        assertEquals("a long span is drawn at its own length", 9_999f, spans[1].weight, 0f)
    }

    @Test
    fun `a moment reported out of order is a span of zero and does not wind the clock back`() {
        val spans = performanceReadout(
            report(PerfMode.COLD).copy(
                phases = listOf(phase(PerfPhase.CATEGORIES, 500), phase(PerfPhase.GROUPS, 400), phase(PerfPhase.STREAMS, 900)),
                fullLoadMs = 1_000,
            ),
        ).lines.filterIsInstance<ReadoutLine.Legend>().single().spans

        assertEquals(listOf(500L, 0L, 400L, 100L), spans.map { it.ms })
    }

    // ---------------------------------------------------------- what is shown, in order

    /** The approved drawing's lines, in its order, for a reload that appended. */
    @Test
    fun `a measured load reads in the drawing's order`() {
        assertEquals(
            listOf(
                "Full Load",
                "cuts",
                "legend",
                "∥ while receiving 29.77 s, at the same time:",
                "reader: ask 0.92 · network 2.85 · parse 1.95 · waiting for save 23.98",
                "writer: rows 20.13 · search rows 7.60 · commit 0.57 · idle 1.47 · other 0.00",
                "then: prune – · counts 4.83 · search rebuild – s",
                "calls: get_live_streams ×1 28.10 s · wait 0.92 · 18.3 MB",
                ": get_live_categories ×1 1.34 s · wait 1.19 · 61 KB",
                "records: reply 55,550 → filed 55,213 · orphans 337",
                ": written LIVE 54,130 · RADIO 1,083 · in DB the same",
                "search: entries 55,213 → 110,426 for 55,213 rows",
                ": repeated ids 0 → 55,213 · sample 12 → 24 for 12 rows",
                "orphans: 1201 ×214 · 1188 ×86 · none ×37",
            ),
            performanceReadout(measured()).lines.map { it.described() },
        )
    }

    @Test
    fun `the title names the section, the run, how it ended, how it saved and its first frame`() {
        assertEquals("CHANNELS · run 4 · COLD · APPEND · TTID 0.17", performanceReadout(measured()).title)
        assertEquals("CHANNELS · run 3 · COLD", performanceReadout(report(PerfMode.COLD).copy(run = 3)).title)
    }

    /** A warm open imported nothing, and draws nothing that would say it had. */
    @Test
    fun `a warm open draws nothing it did not do`() {
        val readout = performanceReadout(report(PerfMode.WARM).copy(ttidMs = 120))

        assertTrue(readout.lines.isEmpty())
        assertEquals("CHANNELS · run 1 · WARM · TTID 0.12", readout.title)
    }

    // ------------------------------------------------------- what ran at the same time

    /**
     * **The two sides of a pass each add up to their own time, and to nothing else.**
     *
     * The reader's four parts are the reader's time; the writer's are the pass's window. The
     * two are drawn under one heading because they ran at once, and neither is a stage of
     * the bar: the window lies inside `first commit` and `streams`.
     */
    @Test
    fun `the reader's parts add up to its time and the writer's to the window`() {
        // Milliseconds that, rounded one by one, would come to 29.69 and 29.76.
        val pass = measured().passes.single().copy(readerMs = 29_706, askMs = 924, readMs = 2_854, handOverMs = 23_984)
        val steps = mapOf(DatabaseStep.ROWS to 20_124L, DatabaseStep.SEARCH_ROWS to 7_603L)
        val lines = performanceReadout(measured().copy(passes = listOf(pass), databaseSteps = steps))
            .lines.filterIsInstance<ReadoutLine.Tagged>()

        val reader = lines.single { it.tag == "reader" }
        val writer = lines.single { it.tag == "writer" }
        assertTrue(reader.parallel && writer.parallel)
        assertEquals("29.71", hundredthsSum(reader.pieces.filter { it.figure }.map { it.text }))
        assertEquals("29.77", hundredthsSum(writer.pieces.filter { it.figure }.map { it.text }))
        assertEquals(
            "writer: rows 20.13 · search rows 7.60 · commit 0.57 · idle 1.47 · other 0.00",
            writer.described(),
        )
    }

    /** Four readers' hours exceed the hour they worked together, and the line says it summed them. */
    @Test
    fun `several readers at once are summed, and say so`() {
        val walk = PassTimes(
            kind = "LIVE", oneReply = false, parallelReaders = 4, requests = 835,
            windowMs = 86_580, readerMs = 340_000, askMs = 260_000, readMs = 60_000, handOverMs = 5_000,
            writeMs = 3_000, commitMs = 800, idleMs = 82_500,
        )
        val lines = performanceReadout(report(PerfMode.COLD).copy(passes = listOf(walk))).lines

        assertEquals(
            "∥ 835 categories, 4 at once, 86.58 s, at the same time:",
            lines.filterIsInstance<ReadoutLine.Parallel>().single().described(),
        )
        assertEquals(
            "reader: ask 260.00 · network 60.00 · parse 15.00 · waiting for save 5.00 · 4 summed",
            lines.single { it is ReadoutLine.Tagged && it.tag == "reader" }.described(),
        )
        assertEquals(
            "with no split of the rows, the writer's time is one figure",
            "writer: write 3.00 · commit 0.80 · idle 82.50 · other 0.28",
            lines.single { it is ReadoutLine.Tagged && it.tag == "writer" }.described(),
        )
    }

    /** The rows and the search rows are counted for the whole run, so two passes cannot split them. */
    @Test
    fun `with two passes the writer's rows are not split between them`() {
        val reply = measured().passes.single()
        val gaps = reply.copy(
            oneReply = false, parallelReaders = 3, requests = 3,
            windowMs = 900, readerMs = 2_400, askMs = 1_500, readMs = 600, handOverMs = 100,
            writeMs = 300, commitMs = 100, idleMs = 400,
        )
        val writers = performanceReadout(measured().copy(passes = listOf(reply, gaps))).lines
            .filterIsInstance<ReadoutLine.Tagged>().filter { it.tag == "writer" }

        assertEquals(2, writers.size)
        assertTrue(writers.all { it.pieces.text.startsWith("write ") })
    }

    /** Why the streams came category by category is said; a clean single reply needs no note. */
    @Test
    fun `the way the streams came is noted only when it was not the one reply alone`() {
        val refused = "LIVE: one reply refused (HTTP 404), asked per category"
        val walk = measured().passes.single().copy(oneReply = false, parallelReaders = 4, requests = 835)

        assertTrue(performanceReadout(measured()).lines.none { it is ReadoutLine.Note })
        assertTrue(
            performanceReadout(measured().copy(passes = listOf(walk), notes = mapOf("streams" to refused)))
                .lines.contains(ReadoutLine.Note(refused)),
        )
    }

    // ------------------------------------------------------------- the records, the index

    /** Rows the database holds differently from how they were written are shown, not summarised. */
    @Test
    fun `rows the database holds differently from the import's are shown`() {
        val after = measured().checkAfter!!.copy(rows = mapOf("LIVE" to 54_000, "MOVIE" to 9_000))
        val records = performanceReadout(measured().copy(checkAfter = after)).lines
            .filterIsInstance<ReadoutLine.Tagged>().map { it.described() }

        assertTrue(records.contains(": written LIVE 54,130 · RADIO 1,083"))
        assertTrue("only the kinds written are compared", records.contains(": in DB LIVE 54,000 · RADIO 0"))
    }

    @Test
    fun `the index after is pending while the run goes on, and the check before is not`() {
        val running = report(PerfMode.RUNNING).copy(checkBefore = measured().checkBefore)
        val lines = performanceReadout(running).lines.filterIsInstance<ReadoutLine.Tagged>().map { it.described() }

        assertTrue(lines.contains("search: entries 55,213 → … for 55,213 → … rows"))
        assertTrue(lines.contains(": repeated ids 0 → … · sample 12 → … for 12 → … rows"))

        val failedBefore = performanceReadout(measured().copy(checkBefore = null)).lines.map { it.described() }
        assertTrue("a check before that is missing was not taken", failedBefore.contains("search: entries – → 110,426 for – → 55,213 rows"))
    }

    /** The reading this panel exists to take, drawn in the warning colour -- and only then. */
    @Test
    fun `a repeating search index is a warning and an index in step is not`() {
        fun inkOf(report: SectionReport) = performanceReadout(report).lines
            .filterIsInstance<ReadoutLine.Tagged>().single { it.pieces.text.startsWith("repeated ids") }.ink

        assertEquals(ReadoutInk.WARNING, inkOf(measured()))
        val inStep = measured().checkAfter!!.copy(searchRows = 55_213, duplicated = 0, sampleResults = 12)
        assertEquals(ReadoutInk.QUIET, inkOf(measured().copy(checkAfter = inStep)))
    }

    @Test
    fun `an empty source has no sample to search`() {
        val empty = IndexCheck(rows = emptyMap(), allRows = 0, searchRows = 0, duplicated = 0, sampleResults = null, sampleRows = null, tookMs = 3)
        val lines = performanceReadout(measured().copy(checkBefore = empty)).lines.map { it.described() }

        assertTrue(lines.contains(": repeated ids 0 → 55,213 · sample – → 24 for – → 12 rows"))
    }

    @Test
    fun `orphan ids past the commonest three are counted, not dropped`() {
        val counts = measured().recordCounts!!.copy(
            orphanCategories = listOf("1201" to 214, "1188" to 86, "none" to 30, "77" to 5, "9" to 2),
        )
        val orphans = performanceReadout(measured().copy(recordCounts = counts)).lines
            .filterIsInstance<ReadoutLine.Tagged>().single { it.tag == "orphans" }

        assertEquals("1201 ×214 · 1188 ×86 · none ×30 · +2 more", orphans.pieces.text)
        assertEquals(ReadoutInk.WARNING, orphans.ink)
    }

    // ---------------------------------------------------------------- the requests

    @Test
    fun `calls past the slowest three are counted in a note, and a failed call says so`() {
        val calls = listOf(
            CallTimes("get_live_streams", 836, 1, 120_000, 90_000, 19_000_000),
            CallTimes("get_live_categories", 1, 0, 900, 700, 60_000),
            CallTimes("get_vod_categories", 1, 0, 800, 700, 40_000),
            CallTimes("xmltv", 1, 0, 500, 400, 4_000_000),
            CallTimes("other", 1, 0, 100, 90, 1_000),
        )
        val lines = performanceReadout(measured().copy(calls = calls)).lines

        assertEquals(
            "calls: get_live_streams ×836 120.00 s · wait 90.00 · 18.1 MB · failed 1",
            lines.first { it is ReadoutLine.Tagged && it.tag == "calls" }.described(),
        )
        assertTrue(lines.contains(ReadoutLine.Note("+2 more in the log")))
    }

    @Test
    fun `before the run settles the attempts so far stand in for the calls`() {
        val running = report(PerfMode.RUNNING).copy(requestsStarted = 2, requestsFailed = 0, retries = 1)
        assertTrue(
            performanceReadout(running).lines.map { it.described() }
                .contains("calls: 2 started · 0 failed · 1 retried"),
        )
    }

    @Test
    fun `a playlist's window is its total, and its rows' share of the database is said`() {
        val playlist = report(PerfMode.COLD).copy(
            requests = 1, serverMs = 12_300, serverWaitMs = 800, databaseMs = 1_100, records = 54_000, categories = 0,
            databaseSteps = mapOf(DatabaseStep.ROWS to 9_400L, DatabaseStep.PRUNE to 50L, DatabaseStep.COUNTS to 700L, DatabaseStep.SEARCH to 2_100L),
        )
        val lines = performanceReadout(playlist).lines.map { it.described() }

        assertEquals(
            listOf(
                "saving: rows 9.40 · commit 1.10 s",
                "then: prune 0.05 · counts 0.70 · search rebuild 2.10 s",
                "calls: 1 in 12.30 s · wait 0.80",
                "records: 54,000 saved · 0 categories",
            ),
            lines,
        )
    }

    @Test
    fun `failures and retries are warnings, the commonest first`() {
        val failing = report(PerfMode.COLD).copy(
            retries = 3,
            failureReasons = mapOf(
                "get_live_streams · HTTP 404" to 300,
                "get_live_streams · timeout" to 60,
                "get_live_streams · HTTP 503" to 5,
            ),
        )
        val line = performanceReadout(failing).lines.filterIsInstance<ReadoutLine.Tagged>().single { it.tag == "failures" }

        assertEquals("get_live_streams · HTTP 404 ×300 · get_live_streams · timeout ×60 · +1 more · retried 3", line.pieces.text)
        assertEquals(ReadoutInk.WARNING, line.ink)
    }

    /** Photographed and copied into a report: the figures must not change shape with the language. */
    @Test
    fun `figures are written the same way in every language`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar"))
            assertEquals("8.37 s", seconds(8_370))
            val readout = performanceReadout(measured())
            assertEquals("1.98", readout.lines.filterIsInstance<ReadoutLine.Legend>().single().spans.single { it.label == "categories" }.figure)
            assertTrue(readout.lines.map { it.described() }.contains("records: reply 55,550 → filed 55,213 · orphans 337"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    // ------------------------------------------------------------------ fixtures

    private fun report(mode: PerfMode) = SectionReport(
        section = PerfSection.CHANNELS,
        run = 1,
        startedAtNanos = 0,
        mode = mode,
    )

    private fun phase(phase: PerfPhase, atMs: Long) = RunPhase(phase, atMs)

    /** A line as the drawing reads it: its tag or label, then its text. */
    private fun ReadoutLine.described(): String = when (this) {
        ReadoutLine.Elapsed -> "Elapsed"
        is ReadoutLine.Figure -> label
        is ReadoutLine.Note -> "note: $text"
        is ReadoutLine.Cuts -> "cuts"
        is ReadoutLine.Legend -> "legend"
        is ReadoutLine.Parallel -> "∥ " + pieces.text
        is ReadoutLine.Tagged -> "$tag: " + pieces.text
    }

    /** Figures like `28.12` added up, in hundredths, and written back the same way. */
    private fun hundredthsSum(figures: List<String>): String {
        val total = figures.sumOf { it.replace(".", "").toLong() }
        return "${total / 100}.${(total % 100).toString().padStart(2, '0')}"
    }

    /** A reload after Refresh that appended, with every figure the measurement build reports. */
    private fun measured() = report(PerfMode.COLD).copy(
        run = 4,
        ttidMs = 170,
        fullLoadMs = 37_520,
        serverMs = 29_440,
        serverWaitMs = 2_110,
        databaseMs = 600,
        requests = 2,
        categories = 835,
        records = 55_213,
        requestsStarted = 2,
        requestsFailed = 0,
        retries = 0,
        phases = listOf(
            phase(PerfPhase.IMPORT, 20),
            phase(PerfPhase.CHECKED_BEFORE, 330),
            phase(PerfPhase.CATEGORIES, 2_310),
            phase(PerfPhase.GROUPS, 2_460),
            phase(PerfPhase.FIRST_COMMIT, 4_110),
            phase(PerfPhase.STREAMS, 32_230),
            phase(PerfPhase.FINISHED, 37_080),
            phase(PerfPhase.CHECKED_AFTER, 37_430),
            phase(PerfPhase.RECORDED, 37_440),
        ),
        notes = mapOf("streams" to "LIVE: one reply, 55213 rows, 337 orphaned, 0 categories asked again"),
        databaseSteps = mapOf(
            DatabaseStep.ROWS to 20_130L,
            DatabaseStep.SEARCH_ROWS to 7_600L,
            DatabaseStep.COUNTS to 4_830L,
        ),
        saveModes = listOf("APPEND"),
        passes = listOf(
            PassTimes(
                kind = "LIVE", oneReply = true, parallelReaders = 1, requests = 1,
                windowMs = 29_770, readerMs = 29_700, askMs = 920, readMs = 2_850, handOverMs = 23_980,
                writeMs = 27_730, commitMs = 570, idleMs = 1_470,
            ),
        ),
        recordCounts = RecordCounts(
            parsed = 55_550,
            filed = 55_213,
            orphans = 337,
            written = mapOf("LIVE" to 54_130, "RADIO" to 1_083),
            categories = mapOf("LIVE" to 830, "RADIO" to 5),
            orphanCategories = listOf("1201" to 214, "1188" to 86, "none" to 37),
        ),
        calls = listOf(
            CallTimes("get_live_streams", 1, 0, 28_100, 920, 19_189_759),
            CallTimes("get_live_categories", 1, 0, 1_340, 1_190, 62_464),
        ),
        checkBefore = IndexCheck(
            rows = mapOf("LIVE" to 54_130, "RADIO" to 1_083),
            allRows = 55_213, searchRows = 55_213, duplicated = 0, sampleResults = 12, sampleRows = 12, tookMs = 310,
        ),
        checkAfter = IndexCheck(
            rows = mapOf("LIVE" to 54_130, "RADIO" to 1_083),
            allRows = 55_213, searchRows = 110_426, duplicated = 55_213, sampleResults = 24, sampleRows = 12, tookMs = 350,
        ),
    )
}
