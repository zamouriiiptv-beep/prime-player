package com.castivio.core.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The stopwatch, checked before anything is timed with it.
 *
 * The numbers this produces are going to become Castivio's official baseline — the thing
 * every later optimisation is measured against and declared a success or a failure by. A
 * clock that reports a zero where it should report nothing, or that lets one run's counts
 * leak into the next, would not merely be wrong: it would be wrong in a way that looks
 * like evidence, and somebody would write it down.
 *
 * Robolectric because `SystemClock` is Android's. The durations themselves are not
 * asserted — a unit test cannot make a real load take a real amount of time — so what is
 * asserted is everything else: which fields are populated, which stay null, and that a
 * new run is genuinely new.
 */
@RunWith(RobolectricTestRunner::class)
class PerformanceLogTest {

    @Before
    fun setUp() {
        PerformanceLog.begin(PerfSection.CHANNELS)
    }

    /** A run starts with a clock and nothing else. Absent is not zero. */
    @Test
    fun `a new run reports nothing it has not measured`() {
        val report = PerformanceLog.current.value
        assertNotNull("a run was started", report)
        requireNotNull(report)
        assertEquals(PerfSection.CHANNELS, report.section)
        assertEquals(PerfMode.RUNNING, report.mode)
        assertTrue("a fresh run is running", report.running)
        assertNull("first content was not reached", report.firstContentMs)
        assertNull("nothing was loaded", report.fullLoadMs)
        assertNull("nothing was asked of a server", report.serverMs)
        assertNull("nothing was counted", report.requests)
    }

    /**
     * **A warm open has no full load, and says so by omission.**
     *
     * The rule that stops this reporting a measurement it did not take. Nothing was
     * imported, so the only honest number is the first frame; publishing the same instant
     * again under the name "Full Load" would be inventing a second measurement out of one
     * timestamp, and a baseline built on that is a baseline that cannot be improved on.
     */
    @Test
    fun `a warm open reports first content and no full load`() {
        PerformanceLog.firstContent()
        PerformanceLog.settled(PerfMode.WARM, fullLoad = false)

        val report = PerformanceLog.current.value!!
        assertEquals(PerfMode.WARM, report.mode)
        assertNotNull("first content was reached", report.firstContentMs)
        assertNull("a warm open imports nothing, so nothing finished", report.fullLoadMs)
        assertNull("a warm open asks the server nothing", report.serverMs)
        assertEquals("no requests", 0, report.requests)
    }

    /** A cold open reports all four, because all four happened. */
    @Test
    fun `a cold open reports the server, the content, the load and the count`() {
        PerformanceLog.network(requests = 42, serverMs = 7_900, cacheHits = 0)
        PerformanceLog.firstContent()
        PerformanceLog.settled(PerfMode.COLD, fullLoad = true)

        val report = PerformanceLog.current.value!!
        assertEquals(PerfMode.COLD, report.mode)
        assertEquals(42, report.requests)
        assertEquals(7_900L, report.serverMs)
        assertNotNull(report.firstContentMs)
        assertNotNull(report.fullLoadMs)
    }

    /**
     * **A cache hit is claimed only when every call was one.**
     *
     * The distinction the brief asked for explicitly. A partly cached import is a
     * download, and calling it a cache hit would hide the very thing the baseline exists
     * to expose.
     */
    @Test
    fun `a partly cached load is a cold load`() {
        PerformanceLog.network(requests = 42, serverMs = 7_900, cacheHits = 41)
        PerformanceLog.settled(PerfMode.COLD, fullLoad = true)
        assertEquals(PerfMode.COLD, PerformanceLog.current.value!!.mode)
    }

    @Test
    fun `a fully cached load is reported as a cache hit`() {
        PerformanceLog.network(requests = 12, serverMs = 40, cacheHits = 12)
        PerformanceLog.settled(PerfMode.COLD, fullLoad = true)
        assertEquals(PerfMode.CACHE_HIT, PerformanceLog.current.value!!.mode)
    }

    /** With no calls at all there is nothing to have hit a cache, so it stays cold. */
    @Test
    fun `no calls is never a cache hit`() {
        PerformanceLog.network(requests = 0, serverMs = 0, cacheHits = 0)
        PerformanceLog.settled(PerfMode.COLD, fullLoad = true)
        assertEquals(PerfMode.COLD, PerformanceLog.current.value!!.mode)
    }

    /**
     * **Opening a section twice is two measurements, not one.**
     *
     * The requirement that makes a before-and-after comparison possible at all. The
     * second open of Channels reads from the database and the first did not; a panel
     * showing the first one's numbers over the second is the single most misleading
     * thing this could do.
     */
    @Test
    fun `a second open starts a new run and inherits nothing`() {
        PerformanceLog.network(requests = 42, serverMs = 7_900, cacheHits = 0)
        PerformanceLog.firstContent()
        PerformanceLog.settled(PerfMode.COLD, fullLoad = true)
        val first = PerformanceLog.current.value!!

        PerformanceLog.begin(PerfSection.CHANNELS)
        val second = PerformanceLog.current.value!!

        assertTrue("the run number did not advance", second.run > first.run)
        assertEquals(PerfMode.RUNNING, second.mode)
        assertNull("the previous run's first content leaked", second.firstContentMs)
        assertNull("the previous run's full load leaked", second.fullLoadMs)

        // And the network window from the previous run must not be inherited either:
        // a warm second open made no calls, and 42 would be the first open's.
        PerformanceLog.settled(PerfMode.WARM, fullLoad = false)
        assertEquals("the previous run's requests leaked", 0, PerformanceLog.current.value!!.requests)
        assertNull("the previous run's server time leaked", PerformanceLog.current.value!!.serverMs)
    }

    /** The first row is the one that counts; later ones do not move the number. */
    @Test
    fun `first content is recorded once`() {
        PerformanceLog.firstContent()
        val first = PerformanceLog.current.value!!.firstContentMs
        Thread.sleep(SETTLE_MS)
        PerformanceLog.firstContent()
        assertEquals("a later row overwrote the first", first, PerformanceLog.current.value!!.firstContentMs)
    }

    /** A settled run is settled: a second verdict does not overwrite the first. */
    @Test
    fun `a run is settled once`() {
        PerformanceLog.settled(PerfMode.WARM, fullLoad = false)
        PerformanceLog.settled(PerfMode.COLD, fullLoad = true)
        val report = PerformanceLog.current.value!!
        assertEquals(PerfMode.WARM, report.mode)
        assertNull(report.fullLoadMs)
    }

    /**
     * **TTID and TTFC are two measurements, and the gap between them is the point.**
     *
     * A board whose furniture appears in 200ms and whose first row appears in nine
     * seconds is the exact shape Fast Content Loading exists to fix, and neither number
     * alone shows it. Recorded once, like first content: composition runs again for
     * reasons that are not a first frame, and a later one would erase the wait.
     */
    @Test
    fun `the first frame and the first row are recorded separately and once`() {
        PerformanceLog.firstFrame()
        val ttid = PerformanceLog.current.value!!.ttidMs
        assertNotNull("the first frame was not recorded", ttid)

        Thread.sleep(SETTLE_MS)
        PerformanceLog.firstFrame()
        assertEquals("a later frame overwrote TTID", ttid, PerformanceLog.current.value!!.ttidMs)

        PerformanceLog.firstContent()
        val report = PerformanceLog.current.value!!
        assertNotNull(report.firstContentMs)
        assertTrue(
            "the first row cannot precede the first frame",
            report.firstContentMs!! >= report.ttidMs!!,
        )
    }

    /**
     * The database's share is summed across the import's batches, and belongs to one run.
     *
     * A streaming import commits once per batch, so the number worth reporting is the
     * total -- and it must not survive into the next section, or Movies would be blamed
     * for the time Channels spent in SQLite.
     */
    @Test
    fun `database time accumulates across batches and does not outlive its run`() {
        PerformanceLog.addDatabaseNanos(40_000_000)
        PerformanceLog.addDatabaseNanos(60_000_000)
        PerformanceLog.settled(PerfMode.COLD, fullLoad = true)
        assertEquals(100L, PerformanceLog.current.value!!.databaseMs)

        PerformanceLog.begin(PerfSection.MOVIES)
        PerformanceLog.settled(PerfMode.COLD, fullLoad = true)
        assertEquals(
            "the previous section's commits leaked",
            0L,
            PerformanceLog.current.value!!.databaseMs,
        )
    }

    /** A warm open writes nothing, so it reports no database time rather than zero. */
    @Test
    fun `a warm open has no database time`() {
        PerformanceLog.settled(PerfMode.WARM, fullLoad = false)
        assertNull("a warm open wrote nothing", PerformanceLog.current.value!!.databaseMs)
    }

    // ------------------------------------------------------------ step 0: the breakdown

    /**
     * A total said 1,673 attempts and 365 failures beside an import of 836 calls, and a
     * total cannot say which requests those were. By action, and by reason, it can.
     */
    @Test
    fun `attempts are counted by action and failures by reason`() {
        repeat(3) { PerformanceLog.requestStarted("get_live_streams") }
        PerformanceLog.requestRetried("get_live_streams")
        PerformanceLog.requestFailed("get_live_streams", "HTTP 404")
        PerformanceLog.requestStarted("get_live_categories")
        PerformanceLog.settled(PerfMode.COLD, fullLoad = true)

        val report = PerformanceLog.current.value!!
        assertEquals(ActionCount(started = 3, failed = 1, retried = 1), report.byAction["get_live_streams"])
        assertEquals(ActionCount(started = 1, failed = 0, retried = 0), report.byAction["get_live_categories"])
        assertEquals(mapOf("get_live_streams · HTTP 404" to 1), report.failureReasons)
        assertEquals("the totals are the actions' sum", 4, report.requestsStarted)
        assertEquals(1, report.requestsFailed)
        assertEquals(1, report.retries)
    }

    @Test
    fun `a new run forgets the previous run's attempts`() {
        PerformanceLog.requestStarted("get_live_streams")
        PerformanceLog.requestFailed("get_live_streams", "timeout")
        PerformanceLog.begin(PerfSection.CHANNELS)
        PerformanceLog.settled(PerfMode.WARM, fullLoad = false)

        val report = PerformanceLog.current.value!!
        assertTrue(report.byAction.isEmpty())
        assertTrue(report.failureReasons.isEmpty())
        assertNull("no attempt is not zero attempts", report.requestsStarted)
    }

    /** The cuts are kept in the order they were reached, each once, and only while running. */
    @Test
    fun `phases are kept in order, once each, until the run settles`() {
        PerformanceLog.phase(PerfPhase.IMPORT)
        PerformanceLog.phase(PerfPhase.CATEGORIES)
        PerformanceLog.phase(PerfPhase.IMPORT)
        PerformanceLog.settled(PerfMode.COLD, fullLoad = true)
        PerformanceLog.phase(PerfPhase.STREAMS)

        val phases = PerformanceLog.current.value!!.phases
        assertEquals(listOf(PerfPhase.IMPORT, PerfPhase.CATEGORIES), phases.map { it.phase })
        assertTrue("a later cut cannot precede an earlier one", phases[1].atMs >= phases[0].atMs)
    }

    @Test
    fun `a note says how the run went, and is kept with it`() {
        PerformanceLog.note("streams", "LIVE: one reply")
        PerformanceLog.settled(PerfMode.COLD, fullLoad = true)
        assertEquals(mapOf("streams" to "LIVE: one reply"), PerformanceLog.current.value!!.notes)
    }

    /**
     * The commits were the whole of "Database" and are a part of it. The rest is reported
     * beside them, by step, and belongs to one run like the commits do.
     */
    @Test
    fun `database steps are reported beside the commits and do not outlive the run`() {
        PerformanceLog.addDatabaseNanos(10_000_000)
        PerformanceLog.addDatabaseStep(DatabaseStep.ROWS, 30_000_000)
        PerformanceLog.addDatabaseStep(DatabaseStep.ROWS, 20_000_000)
        PerformanceLog.addDatabaseStep(DatabaseStep.SEARCH_ROWS, 9_000_000)
        PerformanceLog.addDatabaseStep(DatabaseStep.COUNTS, 7_000_000)
        PerformanceLog.settled(PerfMode.COLD, fullLoad = true)

        val report = PerformanceLog.current.value!!
        assertEquals(10L, report.databaseMs)
        assertEquals(
            mapOf(DatabaseStep.ROWS to 50L, DatabaseStep.SEARCH_ROWS to 9L, DatabaseStep.COUNTS to 7L),
            report.databaseSteps,
        )

        PerformanceLog.begin(PerfSection.CHANNELS)
        PerformanceLog.settled(PerfMode.COLD, fullLoad = true)
        assertTrue("the previous run's steps leaked", PerformanceLog.current.value!!.databaseSteps.isEmpty())
    }

    @Test
    fun `the wait for answers is reported beside the time in calls`() {
        PerformanceLog.network(requests = 836, serverMs = 135_580, cacheHits = 0, waitMs = 101_000)
        PerformanceLog.settled(PerfMode.COLD, fullLoad = true)

        val report = PerformanceLog.current.value!!
        assertEquals(135_580L, report.serverMs)
        assertEquals(101_000L, report.serverWaitMs)
    }

    /** The log is printed, so it carries names and numbers and never an address. */
    @Test
    fun `the log names actions and reasons and never an address`() {
        PerformanceLog.phase(PerfPhase.IMPORT)
        PerformanceLog.requestStarted("get_live_streams")
        PerformanceLog.requestFailed("get_live_streams", "HTTP 404")
        PerformanceLog.network(requests = 1, serverMs = 900, cacheHits = 0, waitMs = 300)
        PerformanceLog.settled(PerfMode.COLD, fullLoad = true)

        val lines = PerformanceLog.current.value!!.lines()
        assertTrue(lines.any { it.startsWith("phases import ") })
        assertTrue(lines.any { it.contains("get_live_streams 1 (fail 1, retry 0)") })
        assertTrue(lines.any { it.contains("get_live_streams · HTTP 404 ×1") })
        assertTrue("an address reached the log", lines.none { it.contains("http") || it.contains("://") })
    }

    // ------------------------------------------------------- one load, one run, from zero

    /**
     * **An import that starts after a run has settled is a load of its own.**
     *
     * A section opened again after Refresh forgot its marks starts its import with no press
     * to begin a run. It used to write its counters into the last run's finished report --
     * "Records 4000" under a load that had ended, eight requests where there were two.
     */
    @Test
    fun `an import that starts after its run has settled starts a run of its own`() {
        PerformanceLog.requestStarted("get_live_streams")
        PerformanceLog.settled(PerfMode.COLD, fullLoad = true)
        val finished = PerformanceLog.current.value!!

        PerformanceLog.importStarted(PerfSection.RADIO)
        val next = PerformanceLog.current.value!!

        assertEquals(finished.run + 1, next.run)
        assertEquals(PerfSection.RADIO, next.section)
        assertTrue(next.running)
        PerformanceLog.settled(PerfMode.COLD, fullLoad = true)
        assertNull("the previous run's attempts leaked into the new one", PerformanceLog.current.value!!.requestsStarted)
    }

    /** The import a press started is that press's run, not a second one. */
    @Test
    fun `an import that starts while its run is going joins it`() {
        val pressed = PerformanceLog.current.value!!
        PerformanceLog.importStarted(PerfSection.RADIO)
        val now = PerformanceLog.current.value!!

        assertEquals(pressed.run, now.run)
        assertEquals("the press named the section", PerfSection.CHANNELS, now.section)
    }

    /**
     * **A finished report never changes.** It is a reading somebody may be photographing; a
     * late progress event, a pass, a check or the next load's first numbers must not move it.
     */
    @Test
    fun `nothing an import reports after its run has settled changes it`() {
        PerformanceLog.phase(PerfPhase.IMPORT)
        PerformanceLog.settled(PerfMode.COLD, fullLoad = true)
        val settled = PerformanceLog.current.value!!

        PerformanceLog.importProgress(categories = 835, records = 4_000)
        PerformanceLog.saveMode("APPEND")
        PerformanceLog.pass(pass())
        PerformanceLog.recordCounts(records())
        PerformanceLog.check(before = false, check = check(searchRows = 9))
        PerformanceLog.phase(PerfPhase.STREAMS)
        PerformanceLog.note("streams", "late")

        assertEquals(settled, PerformanceLog.current.value)
    }

    /** What the measurement build adds to a run is kept with that run, and only that run. */
    @Test
    fun `the save mode, the passes, the records and the checks are kept with their run`() {
        PerformanceLog.saveMode("REPLACE")
        PerformanceLog.saveMode("REPLACE")
        PerformanceLog.check(before = true, check = check(searchRows = 0))
        PerformanceLog.pass(pass())
        PerformanceLog.recordCounts(records())
        PerformanceLog.check(before = false, check = check(searchRows = 50))
        PerformanceLog.settled(PerfMode.COLD, fullLoad = true)

        val report = PerformanceLog.current.value!!
        assertEquals("a mode is named once", listOf("REPLACE"), report.saveModes)
        assertEquals(listOf(pass()), report.passes)
        assertEquals(records(), report.recordCounts)
        assertEquals(0, report.checkBefore!!.searchRows)
        assertEquals(50, report.checkAfter!!.searchRows)

        PerformanceLog.begin(PerfSection.CHANNELS)
        val next = PerformanceLog.current.value!!
        assertTrue(next.saveModes.isEmpty() && next.passes.isEmpty())
        assertNull(next.recordCounts)
        assertNull(next.checkBefore)
    }

    /**
     * The database's steps are on the running report as soon as a pass or a moment says so:
     * the readout leaves the screen in the frame the run settles, so a figure published only
     * then is a figure nobody sees.
     */
    @Test
    fun `the database's steps are on the running report once a pass or a moment is reported`() {
        PerformanceLog.addDatabaseStep(DatabaseStep.ROWS, 12_000_000)
        PerformanceLog.pass(pass())
        assertEquals(mapOf(DatabaseStep.ROWS to 12L), PerformanceLog.current.value!!.databaseSteps)

        PerformanceLog.addDatabaseStep(DatabaseStep.COUNTS, 4_000_000)
        PerformanceLog.phase(PerfPhase.FINISHED)
        assertEquals(4L, PerformanceLog.current.value!!.databaseSteps[DatabaseStep.COUNTS])
        assertTrue(PerformanceLog.current.value!!.running)
    }

    /** The calls by action reach the run with the window they were counted in. */
    @Test
    fun `the calls by action are kept with the run`() {
        val calls = listOf(CallTimes("get_live_streams", 1, 0, 28_100, 920, 19_189_759))
        PerformanceLog.network(requests = 1, serverMs = 28_100, cacheHits = 0, waitMs = 920, calls = calls)
        PerformanceLog.settled(PerfMode.COLD, fullLoad = true)
        assertEquals(calls, PerformanceLog.current.value!!.calls)
    }

    /**
     * **The log's stages add up to the full load it prints.** The figures are rounded
     * together, so the sum a reader works out from the line is the one the line states.
     */
    @Test
    fun `the log's stages add up to the full load it prints`() {
        PerformanceLog.phase(PerfPhase.IMPORT)
        Thread.sleep(SETTLE_MS)
        PerformanceLog.phase(PerfPhase.CATEGORIES)
        Thread.sleep(SETTLE_MS)
        PerformanceLog.phase(PerfPhase.FINISHED)
        Thread.sleep(SETTLE_MS)
        PerformanceLog.settled(PerfMode.COLD, fullLoad = true)

        val lines = PerformanceLog.current.value!!.lines()
        val full = lines.single { it.startsWith("full load ") }.removePrefix("full load ").substringBefore(" s")
        val stages = lines.single { it.startsWith("stages ") }
        val figures = stages.substringBefore(" = ").removePrefix("stages ").split(" + ").map { it.substringAfterLast(' ') }
        assertEquals(listOf("import", "categories", "finished", "done").size, figures.size)
        assertEquals(full, stages.substringAfter(" = ").removeSuffix(" s"))
        assertEquals(full, hundredthsText(figures.sumOf { it.replace(".", "").toLong() }))
    }

    /** A pass's two sides are printed as the sums they are, never added to each other. */
    @Test
    fun `the log prints each side of a pass as its own sum`() {
        PerformanceLog.pass(pass())
        PerformanceLog.settled(PerfMode.COLD, fullLoad = true)

        val lines = PerformanceLog.current.value!!.lines()
        assertTrue(lines.contains("at the same time, one reply LIVE · 1 request · 1 at once · window 29.77 s"))
        assertTrue(lines.contains("  reader ask 0.92 + network 2.85 + parse 1.95 + waiting for save 23.98 = 29.70 s"))
        assertTrue(lines.contains("  writer write 27.73 + commit 0.57 + idle 1.47 + other 0.00 = 29.77 s"))
    }

    /** Told once, with the run as it settled -- what a debuggable build puts on the clipboard. */
    @Test
    fun `the settled run is handed on once, as it settled`() {
        val told = ArrayList<SectionReport>()
        PerformanceLog.onSettled = { told += it }
        try {
            PerformanceLog.settled(PerfMode.COLD, fullLoad = true)
            PerformanceLog.settled(PerfMode.COLD, fullLoad = true)
        } finally {
            PerformanceLog.onSettled = null
        }
        assertEquals(listOf(PerformanceLog.current.value!!), told)
    }

    /** Every section this phase was asked to time has a name of its own. */
    @Test
    fun `the four sections are distinct`() {
        assertEquals(4, PerfSection.entries.size)
        assertEquals(PerfSection.entries.size, PerfSection.entries.toSet().size)
    }

    private fun pass() = PassTimes(
        kind = "LIVE", oneReply = true, parallelReaders = 1, requests = 1,
        windowMs = 29_770, readerMs = 29_700, askMs = 920, readMs = 2_850, handOverMs = 23_980,
        writeMs = 27_730, commitMs = 570, idleMs = 1_470,
    )

    private fun records() = RecordCounts(
        parsed = 55_550, filed = 55_213, orphans = 337,
        written = mapOf("LIVE" to 54_130, "RADIO" to 1_083),
        categories = mapOf("LIVE" to 830, "RADIO" to 5),
        orphanCategories = listOf("1201" to 214, "none" to 37),
    )

    private fun check(searchRows: Int) = IndexCheck(
        rows = mapOf("LIVE" to 50), allRows = 50, searchRows = searchRows, duplicated = 0,
        sampleResults = 5, sampleRows = 5, tookMs = 3,
    )

    private companion object {
        /** Long enough that a second reading would differ if it were taken. */
        const val SETTLE_MS = 8L
    }
}
