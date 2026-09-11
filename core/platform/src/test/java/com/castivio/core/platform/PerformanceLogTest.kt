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

    /** Every section this phase was asked to time has a name of its own. */
    @Test
    fun `the four sections are distinct`() {
        assertEquals(4, PerfSection.entries.size)
        assertEquals(PerfSection.entries.size, PerfSection.entries.toSet().size)
    }

    private companion object {
        /** Long enough that a second reading would differ if it were taken. */
        const val SETTLE_MS = 8L
    }
}
