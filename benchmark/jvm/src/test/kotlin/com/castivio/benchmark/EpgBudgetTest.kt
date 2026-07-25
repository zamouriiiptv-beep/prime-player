package com.castivio.benchmark

import com.castivio.data.parsing.EpgImportEngine
import com.castivio.data.parsing.XmltvParser
import com.castivio.domain.EpgProgramme
import com.castivio.domain.EpgRetention
import com.castivio.domain.EpgSummary
import com.castivio.domain.EpgWriter
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/**
 * Gates on the guide pipeline: parse → resolve stop times → retention → batch.
 *
 * The EPG is the largest dataset the app ingests, and the one where an
 * accumulating structure is easiest to introduce by accident — "index the
 * programmes by channel first" is a natural-looking change that turns a
 * constant-memory import into a several-hundred-megabyte one.
 */
class EpgBudgetTest {

    /**
     * The fixture's own start instant, so retention is anchored to the data rather
     * than to whenever the suite happens to run. Checked against the parser so a
     * fixture change cannot silently move it.
     */
    private val fixtureNow = Fixtures.XMLTV_START_MS

    @Test
    fun `guide import throughput stays within budget`() {
        val programmes = 60_000
        val document = Fixtures.xmltv(channels = 400, programmes = programmes)
        val writer = CountingEpgWriter()

        val measurement = Harness.measure("epg-import") {
            val summary = engine(writer).importXmltv("bench", StringReader(document))
            Sink.consumed += writer.titleChars
            summary.programmes.toLong()
        }
        println("[budget] $measurement")

        assertTrue(
            """
            Guide import regressed.
              measured : ${"%,d".format(measurement.perSecond)} programmes/sec
              budget   : ${"%,d".format(PerformanceBudgets.EPG_PROGRAMMES_PER_SECOND_MIN)} programmes/sec minimum
            This covers the XMLTV scan, stop-time resolution, retention filtering
            and batching. Usual causes: a DOM or pull parser replacing the
            streaming scan, SimpleDateFormat for timestamps, or per-programme work
            that belongs per batch.
            """.trimIndent(),
            measurement.perSecond >= PerformanceBudgets.EPG_PROGRAMMES_PER_SECOND_MIN,
        )
    }

    /**
     * Memory must be O(channels), not O(programmes).
     *
     * The engine may hold one batch and one pending programme per channel. It may
     * never hold the guide — on a Fire TV Stick that is the difference between a
     * guide refresh and an OOM.
     */
    @Test
    fun `importing a guide does not retain it`() {
        val programmes = PerformanceBudgets.XMLTV_PROBE_PROGRAMMES
        val document = Fixtures.xmltv(channels = 800, programmes = programmes)
        val writer = CountingEpgWriter()
        var imported = 0

        val retainedKb = Harness.retainedKb {
            val summary = engine(writer).importXmltv("bench", StringReader(document))
            imported = summary.programmes
            Sink.consumed += writer.titleChars
        }
        val retainedMb = retainedKb / 1024
        println("[budget] epg-retained-heap: $retainedKb KB after $imported programmes")

        assertTrue("expected $programmes programmes, got $imported", imported > programmes * 0.9)
        assertTrue(
            """
            The guide import is retaining the guide.
              measured : $retainedMb MB retained after importing ${"%,d".format(imported)} programmes
              budget   : ${PerformanceBudgets.EPG_RETAINED_HEAP_MB_MAX} MB maximum
            Import memory must be one batch plus one pending programme per channel.
            Something is accumulating programmes — most likely a map built to group
            them by channel before writing.
            """.trimIndent(),
            retainedMb <= PerformanceBudgets.EPG_RETAINED_HEAP_MB_MAX,
        )
    }

    /**
     * Retention has to be cheap as well as correct: a guide that is mostly out of
     * window must be *rejected* fast, since that is the common case for a
     * provider shipping two weeks of schedule.
     */
    @Test
    fun `out-of-window programmes are rejected without being written`() {
        val document = Fixtures.xmltv(channels = 200, programmes = 20_000)
        val writer = CountingEpgWriter()

        // A six-hour window against a guide spanning days.
        val summary = EpgImportEngine(
            writer = writer,
            retention = EpgRetention(pastMs = 0, futureMs = 6 * 60 * 60 * 1000L),
            clock = { fixtureNow },
        ).importXmltv("bench", StringReader(document))

        assertTrue("most of the guide should be out of window", summary.outsideWindow > summary.programmes)
        assertTrue(
            "written rows must match what the writer received",
            summary.programmes == writer.written,
        )
    }

    /** Real default retention: a generated guide fits inside the seven-day window. */
    private fun engine(writer: EpgWriter) =
        EpgImportEngine(writer, retention = EpgRetention.DEFAULT, clock = { fixtureNow })

    /** Stands in for SQLite: touches every row, keeps none. */
    private class CountingEpgWriter : EpgWriter {
        var titleChars = 0L
            private set
        var written = 0
            private set

        override fun begin(sourceId: String) = Unit

        override fun writeProgrammes(programmes: List<EpgProgramme>) {
            for (programme in programmes) {
                titleChars += programme.title.length + programme.channelId.length
                written++
            }
        }

        override fun commit() = Unit
        override fun finish(summary: EpgSummary) = Unit
        override fun abort(cause: Throwable?) = Unit
    }

    @Test
    fun `the fixture's timestamps are real dates`() {
        // The guide generator used to increment the YYYYMMDDHHMMSS digits
        // arithmetically and drift into month 19; every retention-sensitive number
        // in this file depends on that not happening again.
        assertTrue(
            "fixture start must round-trip through the parser",
            XmltvParser.parseXmltvTime(Fixtures.xmltvTime(Fixtures.XMLTV_START_MS)) == Fixtures.XMLTV_START_MS,
        )
        val late = Fixtures.XMLTV_START_MS + 400L * Fixtures.SLOT_MS
        assertTrue(XmltvParser.parseXmltvTime(Fixtures.xmltvTime(late)) == late)
    }
}
