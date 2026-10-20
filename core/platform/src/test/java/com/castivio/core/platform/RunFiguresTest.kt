package com.castivio.core.platform

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic every reading is printed with: spans that are the full load cut, parts that
 * are the time they divide, and figures rounded so that what is printed adds up.
 *
 * Plain JUnit: none of it reads a clock.
 */
class RunFiguresTest {

    // ------------------------------------------------------------------ the spans

    @Test
    fun `the spans are the full load cut at the moments the run reached`() {
        val spans = report(
            fullLoadMs = 37_520,
            PerfPhase.IMPORT to 20,
            PerfPhase.CATEGORIES to 2_310,
            PerfPhase.STREAMS to 32_230,
            PerfPhase.FINISHED to 37_080,
            PerfPhase.RECORDED to 37_440,
        ).spans()

        assertEquals(
            listOf("import", "categories", "streams", "finished", "recorded", RunSpan.DONE),
            spans.map { it.label },
        )
        assertEquals(listOf(20L, 2_290L, 29_920L, 4_850L, 360L, 80L), spans.map { it.ms })
        assertEquals(37_520L, spans.sumOf { it.ms })
        assertEquals("the last span is closed by the full load, not a moment", null, spans.last().phase)
    }

    @Test
    fun `a run still going has no closing span, and a run with no moments has no spans`() {
        assertEquals(listOf("import"), report(fullLoadMs = null, PerfPhase.IMPORT to 40).spans().map { it.label })
        assertTrue(report(fullLoadMs = 1_000).spans().isEmpty())
    }

    @Test
    fun `a moment reported early is a span of zero and does not wind the clock back`() {
        val spans = report(
            fullLoadMs = 1_000,
            PerfPhase.CATEGORIES to 500,
            PerfPhase.GROUPS to 400,
            PerfPhase.STREAMS to 900,
        ).spans()

        assertEquals(listOf(500L, 0L, 400L, 100L), spans.map { it.ms })
    }

    // -------------------------------------------------------------------- figures

    @Test
    fun `hundredths are rounded halves up and written with a point`() {
        assertEquals(0L, hundredths(4))
        assertEquals(1L, hundredths(5))
        assertEquals(3_685L, hundredths(36_845))
        assertEquals("36.85", hundredthsText(3_685))
        assertEquals("0.05", hundredthsText(5))
        assertEquals("1.00", hundredthsText(100))
        assertEquals("0.00", hundredthsText(0))
    }

    /** Three spans of 4 ms are 0.00 each on their own and 0.01 together; rounded together, they say so. */
    @Test
    fun `parts rounded together add up to the whole's figure`() {
        assertEquals(listOf(1L, 0L, 0L), hundredthsAddingUp(listOf(4, 4, 4), 12))
        assertEquals("the part that lost the most gets the hundredth", listOf(0L, 1L), hundredthsAddingUp(listOf(3, 8), 11))
    }

    /** Whatever the milliseconds: the sum is the whole's figure, and no part moves by more than a hundredth. */
    @Test
    fun `parts rounded together always add up, and each stays within a hundredth`() {
        val random = Random(20261015)
        repeat(2_000) {
            val parts = List(random.nextInt(1, 11)) { random.nextLong(0, 100_000) }
            val whole = parts.sum()
            val figures = hundredthsAddingUp(parts, whole)

            assertEquals(hundredths(whole), figures.sum())
            parts.indices.forEach { i ->
                assertTrue("a part moved by more than a hundredth", kotlin.math.abs(figures[i] * 10 - parts[i]) < 10)
            }
        }
    }

    @Test
    fun `parts that do not add up to the whole are rounded one by one`() {
        assertEquals(listOf(1L, 1L), hundredthsAddingUp(listOf(5, 5), 30))
    }

    // ------------------------------------------------------------- a pass's two sides

    @Test
    fun `the reader's parts are its time, parsing being what is left`() {
        val parts = pass().readerParts()

        assertEquals(listOf("ask", "network", "parse", "waiting for save"), parts.map { it.label })
        assertEquals(1_950L, parts.single { it.label == "parse" }.ms)
        assertEquals(pass().readerMs, parts.sumOf { it.ms })
    }

    /** With one pass, the rows the writer timed for the whole run are that pass's. */
    @Test
    fun `one pass's writer is its rows, commits and idle, adding up to the window`() {
        val report = report(fullLoadMs = null).copy(
            passes = listOf(pass()),
            databaseSteps = mapOf(DatabaseStep.ROWS to 20_130L, DatabaseStep.COUNTS to 4_830L),
        )
        val parts = report.writerParts(pass())

        assertEquals(listOf("rows", "commit", "idle", "other"), parts.map { it.label })
        assertEquals(20_130L, parts.first().ms)
        assertEquals(pass().windowMs, parts.sumOf { it.ms })
    }

    @Test
    fun `with two passes the writer's time is one figure each`() {
        val report = report(fullLoadMs = null).copy(
            passes = listOf(pass(), pass().copy(oneReply = false)),
            databaseSteps = mapOf(DatabaseStep.ROWS to 20_130L),
        )
        val parts = report.writerParts(pass())

        assertEquals(listOf("write", "commit", "idle", "other"), parts.map { it.label })
        assertEquals(pass().windowMs, parts.sumOf { it.ms })
    }

    @Test
    fun `the steps after the last row are named in order, a step not taken as null`() {
        val appended = report(fullLoadMs = null).copy(
            databaseSteps = mapOf(DatabaseStep.COUNTS to 4_830L, DatabaseStep.SEARCH to 170L),
        )

        assertEquals(
            listOf("prune" to null, "counts" to 4_830L, "search index" to 170L),
            appended.finishParts(),
        )
    }

    // ------------------------------------------------------------------ fixtures

    private fun report(fullLoadMs: Long?, vararg reached: Pair<PerfPhase, Long>) = SectionReport(
        section = PerfSection.CHANNELS,
        run = 1,
        startedAtNanos = 0,
        mode = if (fullLoadMs == null) PerfMode.RUNNING else PerfMode.COLD,
        fullLoadMs = fullLoadMs,
        phases = reached.map { (phase, atMs) -> RunPhase(phase, atMs) },
    )

    private fun pass() = PassTimes(
        kind = "LIVE", oneReply = true, parallelReaders = 1, requests = 1,
        windowMs = 29_770, readerMs = 29_700, askMs = 920, readMs = 2_850, handOverMs = 23_980,
        writeMs = 27_730, commitMs = 570, idleMs = 1_470,
    )
}
