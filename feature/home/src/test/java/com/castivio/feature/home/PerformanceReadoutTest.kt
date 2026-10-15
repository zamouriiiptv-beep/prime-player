package com.castivio.feature.home

import com.castivio.core.platform.ActionCount
import com.castivio.core.platform.DatabaseStep
import com.castivio.core.platform.PerfMode
import com.castivio.core.platform.PerfPhase
import com.castivio.core.platform.PerfSection
import com.castivio.core.platform.RunPhase
import com.castivio.core.platform.SectionReport
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lines the performance panel draws for a run.
 *
 * They are photographed off a phone and compared reading against reading, so what is
 * tested is the shape as much as the arithmetic: the same lines in the same order, spans
 * that add up to the full load they cut, and nothing printed for a moment never reached.
 */
class PerformanceReadoutTest {

    // ------------------------------------------------------------------ the spans

    @Test
    fun `the spans cut the full load in the order the run reached its moments`() {
        val readout = performanceReadout(finished())

        val spans = readout.lines.filterIsInstance<ReadoutLine.Legend>().single().spans
        assertEquals(
            listOf("import", "categories", "groups", "first commit", "streams", "finished", "recorded", "done"),
            spans.map { it.label },
        )
        assertEquals(listOf(40L, 580L, 60L, 520L, 8_660L, 1_120L, 420L, 20L), spans.map { it.ms })
        assertEquals("the spans are the full load, cut", 11_420L, spans.sumOf { it.ms })
        assertEquals("8.66", spans.single { it.label == "streams" }.figure)
        assertEquals(spans, readout.lines.filterIsInstance<ReadoutLine.Cuts>().single().spans)
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
                concurrency = 4,
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

    // -------------------------------------------------------------- what is shown

    /** The order the approved drawing gives, for a finished cold load. */
    @Test
    fun `a finished load reads in the drawing's order`() {
        val labels = performanceReadout(finished()).lines.map { line ->
            when (line) {
                ReadoutLine.Elapsed -> "Elapsed"
                is ReadoutLine.Figure -> line.label
                is ReadoutLine.Note -> "note: ${line.text}"
                is ReadoutLine.Cuts -> "cuts"
                is ReadoutLine.Legend -> "legend"
            }
        }

        assertEquals(
            listOf(
                "Full Load",
                "cuts",
                "legend",
                "TTID",
                "Network",
                "note: LIVE: asked per category",
                "Database · commit",
                "note: insert 2.30 · prune 0.03 · counts 0.41 · search 0.38 s",
                "Categories · Records",
                "Requests  (fail · retry)",
                // One each: a tie, broken by name.
                "get_live_categories",
                "get_live_streams",
            ),
            labels,
        )
    }

    /** A warm open imported nothing, and draws nothing that would say it had. */
    @Test
    fun `a warm open draws its first frame and nothing else`() {
        val readout = performanceReadout(report(PerfMode.WARM).copy(ttidMs = 120))

        assertEquals(listOf(ReadoutLine.Figure("TTID", "0.12 s", ReadoutInk.STRONG)), readout.lines)
    }

    @Test
    fun `the network line carries the wait only when it was measured`() {
        fun network(report: SectionReport) =
            performanceReadout(report).lines.filterIsInstance<ReadoutLine.Figure>().single { it.label == "Network" }.value

        assertEquals("9.05 s · wait 0.71 s", network(report(PerfMode.COLD).copy(serverMs = 9_050, serverWaitMs = 710)))
        assertEquals("9.05 s", network(report(PerfMode.COLD).copy(serverMs = 9_050)))
    }

    /**
     * The busiest actions and the commonest failures, and a count of the rest — never a
     * list cut short without saying so.
     */
    @Test
    fun `requests show the busiest actions and count what they leave out`() {
        val readout = performanceReadout(
            report(PerfMode.COLD).copy(
                requestsStarted = 1_673,
                requestsFailed = 365,
                retries = 0,
                byAction = mapOf(
                    "account" to ActionCount(1, 0, 0),
                    "get_live_streams" to ActionCount(1_669, 365, 0),
                    "get_live_categories" to ActionCount(1, 0, 0),
                    "get_vod_categories" to ActionCount(1, 0, 0),
                    "xmltv" to ActionCount(1, 0, 0),
                ),
                failureReasons = mapOf(
                    "get_live_streams · HTTP 404" to 300,
                    "get_live_streams · timeout" to 60,
                    "get_live_streams · HTTP 503" to 5,
                ),
            ),
        )

        val figures = readout.lines.filterIsInstance<ReadoutLine.Figure>()
        assertEquals("1673  (365 · 0)", figures.single { it.label.startsWith("Requests") }.value)
        assertEquals(
            "busiest first, then by name",
            listOf("get_live_streams", "account", "get_live_categories"),
            figures.filter { it.part && it.ink == ReadoutInk.QUIET }.map { it.label },
        )
        assertEquals(
            listOf("get_live_streams · HTTP 404" to "×300", "get_live_streams · timeout" to "×60"),
            figures.filter { it.ink == ReadoutInk.WARNING }.map { it.label to it.value },
        )
        assertEquals(
            "two actions and one reason were left out",
            ReadoutLine.Note("+3 more in the log (CastivioPerf)"),
            readout.lines.last(),
        )
    }

    @Test
    fun `nothing is said to be left out when nothing was`() {
        val readout = performanceReadout(finished())
        assertTrue(readout.lines.none { it is ReadoutLine.Note && it.text.contains("more in the log") })
    }

    /** A limit is only true while something can be in flight. */
    @Test
    fun `concurrency is shown while the load runs and not after`() {
        fun shown(report: SectionReport) =
            performanceReadout(report).lines.any { it is ReadoutLine.Figure && it.label == "Concurrency" }

        assertTrue(shown(report(PerfMode.RUNNING).copy(concurrency = 4)))
        assertFalse("four requests still out, after the load", shown(report(PerfMode.COLD).copy(concurrency = 4)))
    }

    @Test
    fun `the title names the section, the run and how it ended`() {
        assertEquals("CHANNELS · run 3 · COLD", performanceReadout(report(PerfMode.COLD).copy(run = 3)).title)
    }

    /** Photographed and copied into a report: the figures must not change shape with the language. */
    @Test
    fun `figures are written the same way in every language`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar"))
            assertEquals("8.37 s", seconds(8_370))
            val spans = performanceReadout(finished()).lines.filterIsInstance<ReadoutLine.Legend>().single().spans
            assertEquals("0.58", spans.single { it.label == "categories" }.figure)
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

    /** A cold load walked category by category, with every figure the panel can show. */
    private fun finished() = report(PerfMode.COLD).copy(
        ttidMs = 190,
        fullLoadMs = 11_420,
        serverMs = 9_050,
        serverWaitMs = 710,
        databaseMs = 380,
        requests = 2,
        categories = 835,
        records = 55_202,
        requestsStarted = 2,
        requestsFailed = 0,
        retries = 0,
        concurrency = 4,
        phases = listOf(
            phase(PerfPhase.IMPORT, 40),
            phase(PerfPhase.CATEGORIES, 620),
            phase(PerfPhase.GROUPS, 680),
            phase(PerfPhase.FIRST_COMMIT, 1_200),
            phase(PerfPhase.STREAMS, 9_860),
            phase(PerfPhase.FINISHED, 10_980),
            phase(PerfPhase.RECORDED, 11_400),
        ),
        notes = mapOf("streams" to "LIVE: asked per category"),
        byAction = mapOf(
            "get_live_categories" to ActionCount(1, 0, 0),
            "get_live_streams" to ActionCount(1, 0, 0),
        ),
        databaseSteps = mapOf(
            DatabaseStep.SEARCH to 380L,
            DatabaseStep.INSERT to 2_300L,
            DatabaseStep.COUNTS to 410L,
            DatabaseStep.PRUNE to 30L,
        ),
    )
}
