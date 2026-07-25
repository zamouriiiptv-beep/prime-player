package com.castivio.data.parsing

import com.castivio.domain.EpgProgramme
import com.castivio.domain.EpgProgress
import com.castivio.domain.EpgRetention
import com.castivio.domain.EpgSummary
import com.castivio.domain.EpgWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class EpgImportEngineTest {

    private val day = 24 * 60 * 60 * 1000L
    private val hour = 60 * 60 * 1000L
    /** Fifteen minutes: keeps generated fixtures inside the default retention window. */
    private val slot = 15 * 60 * 1000L

    /** 2026-07-25 12:00:00 UTC, the instant every fixture is written around. */
    private val now = 1_784_980_800_000L

    @Test
    fun `programmes are written in bounded batches`() {
        val writer = RecordingEpgWriter()
        val guide = guide(
            (0 until 250).map { programme(channel = "ch${it % 5}", startMs = now + it * slot) },
        )

        val summary = engine(writer, batchSize = 100).importXmltv("src", StringReader(guide))

        assertEquals(250, summary.programmes)
        assertEquals(listOf(100, 100, 50), writer.batchSizes)
        assertEquals(3, writer.commits)
        assertEquals(5, summary.channels)
        assertEquals(summary, writer.finished)
        assertFalse(writer.aborted)
    }

    @Test
    fun `the batch buffer is reused rather than reallocated`() {
        val writer = RecordingEpgWriter()
        val guide = guide((0 until 300).map { programme(startMs = now + it * slot) })

        engine(writer, batchSize = 50).importXmltv("src", StringReader(guide))

        assertEquals(6, writer.batchSizes.size)
        assertEquals(1, writer.batchIdentities.size)
        assertTrue("the engine must clear the buffer it lends out", writer.lastBatch!!.isEmpty())
    }

    /**
     * The rule that keeps the guide from becoming the biggest thing on disk.
     *
     * Providers ship weeks in both directions. Out-of-window programmes are never
     * written at all, so the saving is in writes as well as rows.
     */
    @Test
    fun `programmes outside the retention window are never written`() {
        val writer = RecordingEpgWriter()
        val guide = guide(
            listOf(
                programme(title = "last week", startMs = now - 7 * day),
                programme(title = "yesterday", startMs = now - 20 * hour),
                programme(title = "on now", startMs = now - hour),
                programme(title = "tomorrow", startMs = now + day),
                programme(title = "next month", startMs = now + 30 * day),
            ),
        )

        val summary = engine(writer).importXmltv("src", StringReader(guide))

        assertEquals(listOf("yesterday", "on now", "tomorrow"), writer.programmes.map { it.title })
        assertEquals(3, summary.programmes)
        assertEquals(2, summary.outsideWindow)
    }

    @Test
    fun `the retention window is configurable`() {
        val writer = RecordingEpgWriter()
        val guide = guide(
            listOf(
                programme(title = "yesterday", startMs = now - 20 * hour),
                programme(title = "on now", startMs = now - 30 * 60 * 1000L),
                programme(title = "in three days", startMs = now + 3 * day),
            ),
        )

        // No past window, and only two days ahead: both edges must drop out.
        val summary = engine(
            writer,
            retention = EpgRetention(pastMs = 0, futureMs = 2 * day),
        ).importXmltv("src", StringReader(guide))

        assertEquals(listOf("on now"), writer.programmes.map { it.title })
        assertEquals(1, summary.programmes)
        assertEquals(2, summary.outsideWindow)
    }

    /**
     * `stop` is optional in XMLTV and plenty of providers omit it. Guessing a
     * fixed length would put a two-hour film's progress bar in the wrong place.
     */
    @Test
    fun `a missing stop time is taken from the next programme on that channel`() {
        val writer = RecordingEpgWriter()
        val guide = guide(
            listOf(
                programme(channel = "a", title = "film", startMs = now, stopMs = null),
                programme(channel = "b", title = "other channel", startMs = now, stopMs = now + hour),
                programme(channel = "a", title = "after", startMs = now + 2 * hour, stopMs = now + 3 * hour),
            ),
        )

        engine(writer).importXmltv("src", StringReader(guide))

        val film = writer.programmes.single { it.title == "film" }
        assertEquals("stop must come from the channel's next entry", now + 2 * hour, film.stopMs)
        // Ordering within a channel is preserved even though one row was deferred.
        assertEquals(listOf("other channel", "film", "after"), writer.programmes.map { it.title })
    }

    @Test
    fun `the last programme with no stop falls back to an assumed length`() {
        val writer = RecordingEpgWriter()
        val guide = guide(listOf(programme(title = "final", startMs = now, stopMs = null)))

        engine(writer).importXmltv("src", StringReader(guide))

        val final = writer.programmes.single()
        assertEquals(now + EpgImportEngine.ASSUMED_DURATION_MS, final.stopMs)
    }

    @Test
    fun `entries with no channel or start time are skipped, not written`() {
        val writer = RecordingEpgWriter()
        val guide = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <programme start="20260725120000 +0000" stop="20260725130000 +0000" channel="ch1">
                <title>good</title>
              </programme>
              <programme start="not-a-time" stop="20260725130000 +0000" channel="ch1">
                <title>broken start</title>
              </programme>
              <programme stop="20260725130000 +0000" channel="ch1">
                <title>no start at all</title>
              </programme>
            </tv>
        """.trimIndent()

        val summary = engine(writer).importXmltv("src", StringReader(guide))

        assertEquals(listOf("good"), writer.programmes.map { it.title })
        // The third entry has no start attribute, so the parser never emits it.
        assertEquals(1, summary.skipped)
    }

    @Test
    fun `progress is reported per batch and once at the end`() {
        val writer = RecordingEpgWriter()
        val guide = guide((0 until 120).map { programme(startMs = now + it * slot) })
        val progress = mutableListOf<EpgProgress>()

        engine(writer, batchSize = 50).importXmltv("src", StringReader(guide), onProgress = progress::add)

        assertEquals(listOf(50, 100), progress.filterIsInstance<EpgProgress.Importing>().map { it.programmes })
        assertEquals(120, (progress.last() as EpgProgress.Done).programmes)
    }

    @Test
    fun `cancellation stops reading the guide and keeps what was committed`() {
        val writer = RecordingEpgWriter()
        val guide = guide((0 until 5_000).map { programme(startMs = now + it * 60_000L) })
        val counted = CountingReader(StringReader(guide))

        // Cancelled once the first batches are safely committed, which is the real
        // shape of the case: the user leaves the screen mid-refresh.
        val summary = engine(writer, batchSize = 20)
            .importXmltv("src", counted, isCancelled = { writer.batchSizes.isNotEmpty() })

        assertTrue(summary.cancelled)
        assertTrue("committed programmes must survive", writer.programmes.isNotEmpty())
        // One chunk read, then the stream reports end-of-stream.
        assertTrue("read ${counted.chars} chars of ${guide.length}", counted.chars < guide.length)
        assertTrue(writer.aborted)
        assertNull(writer.finished)
    }

    @Test
    fun `a writer failure aborts and propagates`() {
        val writer = RecordingEpgWriter(failOnBatch = 1)
        val guide = guide((0 until 100).map { programme(startMs = now + it * slot) })

        val thrown = runCatching {
            engine(writer, batchSize = 10).importXmltv("src", StringReader(guide))
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertTrue(writer.aborted)
        assertSame(thrown, writer.abortCause)
        assertNull(writer.finished)
    }

    @Test
    fun `an empty guide is a valid import`() {
        val writer = RecordingEpgWriter()

        val summary = engine(writer).importXmltv("src", StringReader("<tv></tv>"))

        assertEquals(0, summary.programmes)
        assertEquals(0, writer.commits)
        assertEquals(summary, writer.finished)
    }

    // ------------------------------------------------------------------ fixtures

    private fun engine(
        writer: EpgWriter,
        batchSize: Int = EpgImportEngine.DEFAULT_BATCH,
        retention: EpgRetention = EpgRetention.DEFAULT,
    ) = EpgImportEngine(writer, batchSize = batchSize, retention = retention, clock = { now })

    private fun programme(
        channel: String = "ch1",
        title: String = "Programme",
        startMs: Long,
        stopMs: Long? = startMs + 3_600_000L,
    ): String {
        val stopAttribute = stopMs?.let { """ stop="${xmltvTime(it)}"""" } ?: ""
        return """  <programme start="${xmltvTime(startMs)}"$stopAttribute channel="$channel">""" +
            "<title>$title</title><desc>Description of $title</desc></programme>"
    }

    private fun guide(programmes: List<String>): String =
        """<?xml version="1.0" encoding="UTF-8"?>""" + "\n<tv>\n" + programmes.joinToString("\n") + "\n</tv>\n"

    /** Epoch millis → `20260725120000 +0000`, without pulling in a date library. */
    private fun xmltvTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val days = totalSeconds / 86_400
        val secondOfDay = (totalSeconds % 86_400).toInt()
        var y = 1970
        var remaining = days
        while (true) {
            val length = if (isLeap(y)) 366 else 365
            if (remaining < length) break
            remaining -= length
            y++
        }
        val monthLengths = intArrayOf(31, if (isLeap(y)) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        var month = 0
        while (remaining >= monthLengths[month]) {
            remaining -= monthLengths[month]
            month++
        }
        val day = remaining.toInt() + 1
        return "%04d%02d%02d%02d%02d%02d +0000".format(
            y, month + 1, day, secondOfDay / 3600, (secondOfDay % 3600) / 60, secondOfDay % 60,
        )
    }

    private fun isLeap(year: Int) = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

    private class CountingReader(private val delegate: StringReader) : java.io.Reader() {
        var chars = 0
            private set

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            val read = delegate.read(cbuf, off, len)
            if (read > 0) chars += read
            return read
        }

        override fun close() = delegate.close()
    }

    private class RecordingEpgWriter(private val failOnBatch: Int = -1) : EpgWriter {
        val batchSizes = mutableListOf<Int>()
        val batchIdentities = mutableSetOf<Int>()
        val programmes = mutableListOf<EpgProgramme>()
        var lastBatch: List<EpgProgramme>? = null
        var commits = 0
        var began: String? = null
        var finished: EpgSummary? = null
        var aborted = false
        var abortCause: Throwable? = null

        override fun begin(sourceId: String) {
            began = sourceId
        }

        override fun writeProgrammes(programmes: List<EpgProgramme>) {
            if (batchSizes.size == failOnBatch) throw IllegalStateException("disk full")
            batchSizes += programmes.size
            batchIdentities += System.identityHashCode(programmes)
            lastBatch = programmes
            this.programmes += programmes
        }

        override fun commit() {
            commits++
        }

        override fun finish(summary: EpgSummary) {
            finished = summary
        }

        override fun abort(cause: Throwable?) {
            aborted = true
            abortCause = cause
        }
    }
}
