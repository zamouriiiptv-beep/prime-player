package com.castivio.core.platform

import android.os.SystemClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the section timer says between the press on Home and the section being there.
 *
 * No test asserts how long anything took -- a unit test cannot make a load take a real amount of time -- so what is
 * pinned is what each session is waiting for, which moments it keeps apart, and that one section's marks never land on
 * another's. The one place a duration matters is the press that came before the run: it is placed in the past by
 * construction, so the wait is there whatever the clock does.
 */
@RunWith(RobolectricTestRunner::class)
class LoadTraceTest {

    @Before
    fun setUp() {
        DevDiagnostics.enable(true)
        LoadTrace.reset()
    }

    @After
    fun tearDown() {
        DevDiagnostics.enable(false)
        LoadTrace.reset()
    }

    private fun session(section: PerfSection) = LoadTrace.sessions.value[section]!!

    /** A press five milliseconds ago, or no test where the clock has not got that far. */
    private fun earlier(): Long {
        val now = SystemClock.elapsedRealtimeNanos()
        assumeTrue("the clock has not run for five milliseconds", now > FIVE_MS)
        return now - FIVE_MS
    }

    // ----------------------------------------------------------- where the clock starts

    @Test
    fun `the run is counted from the press, and the wait is its first phase`() {
        LoadTrace.pressed(PerfSection.MOVIES, earlier(), LoadPress.FIRST_OPENS)
        LoadTrace.runStarted(PerfSection.MOVIES)

        val report = PerformanceLog.current.value!!
        assertEquals(PerfSection.MOVIES, report.section)
        assertEquals("the wait is the run's first phase", PerfPhase.QUEUED, report.phases.first().phase)
        assertTrue("and it is the wait that was there", report.phases.first().atMs >= 5)
        assertEquals("the session holds the run that is its load", report.run, session(PerfSection.MOVIES).run)
        assertTrue(session(PerfSection.MOVIES).runStartedMs!! >= 5)
    }

    @Test
    fun `a load nobody pressed Home for is a session of its own, counted from the load`() {
        LoadTrace.runStarted(PerfSection.SERIES)

        val refresh = session(PerfSection.SERIES)
        assertEquals(LoadOrigin.REFRESH, refresh.origin)
        assertTrue(refresh.expectsLoad)
        assertFalse("no screen is opened by it", refresh.expectsScreen)
        assertTrue("no press, so no wait to count", PerformanceLog.current.value!!.phases.isEmpty())
    }

    @Test
    fun `with the instruments off nothing is recorded and the run begins as it always did`() {
        DevDiagnostics.enable(false)

        assertEquals("no reading is taken", 0L, LoadTrace.stamp())
        LoadTrace.pressed(PerfSection.MOVIES, 1_000L, LoadPress.FIRST_OPENS)
        LoadTrace.runStarted(PerfSection.MOVIES)
        LoadTrace.contentShown(PerfSection.MOVIES)

        assertTrue(LoadTrace.sessions.value.isEmpty())
        assertNotNull("the run still began", PerformanceLog.current.value)
        assertTrue("and counted from itself", PerformanceLog.current.value!!.phases.isEmpty())
    }

    // ------------------------------------------------------- what completes a session

    @Test
    fun `rows already on the device are not a finished refresh`() {
        LoadTrace.pressed(PerfSection.MOVIES, earlier(), LoadPress.OPEN_AND_REFRESH)
        LoadTrace.runStarted(PerfSection.MOVIES)
        LoadTrace.screenShown(PerfSection.MOVIES)
        LoadTrace.contentShown(PerfSection.MOVIES)

        val shown = session(PerfSection.MOVIES)
        assertTrue("the local rows are there", shown.contentMs != null)
        assertTrue("and the refresh behind them is still going", shown.running)
        assertNull(shown.loadDoneMs)

        LoadTrace.answered(PerfSection.MOVIES, LoadAnswer.Done(saved = 100))

        val done = session(PerfSection.MOVIES)
        assertEquals(LoadState.DONE, done.state)
        assertTrue("the load's own moment is kept apart from the rows'", done.loadDoneMs != null)
        assertEquals(100, done.saved)
    }

    @Test
    fun `a press that only opens a section is over with its rows`() {
        LoadTrace.pressed(PerfSection.RADIO, earlier(), LoadPress.OPEN)
        assertFalse("nothing is loaded for it", session(PerfSection.RADIO).expectsLoad)
        assertEquals(LoadOrigin.WARM, session(PerfSection.RADIO).origin)

        LoadTrace.screenShown(PerfSection.RADIO)
        assertTrue("a screen without rows is not the section", session(PerfSection.RADIO).running)
        LoadTrace.contentShown(PerfSection.RADIO)

        assertEquals(LoadState.DONE, session(PerfSection.RADIO).state)
    }

    @Test
    fun `a first load that opens by itself is over when it has loaded and has rows`() {
        LoadTrace.pressed(PerfSection.CHANNELS, earlier(), LoadPress.FIRST_OPENS)
        LoadTrace.runStarted(PerfSection.CHANNELS)
        assertEquals(LoadOrigin.COLD, session(PerfSection.CHANNELS).origin)

        LoadTrace.answered(PerfSection.CHANNELS, LoadAnswer.Done(saved = 5))
        assertTrue("loaded, and no row on a screen yet", session(PerfSection.CHANNELS).running)

        LoadTrace.contentShown(PerfSection.CHANNELS)
        assertEquals(LoadState.DONE, session(PerfSection.CHANNELS).state)
    }

    @Test
    fun `a first load that leaves the viewer on Home is over when it has loaded`() {
        LoadTrace.pressed(PerfSection.RADIO, earlier(), LoadPress.FIRST_STAYS)
        LoadTrace.runStarted(PerfSection.RADIO)
        assertFalse(session(PerfSection.RADIO).expectsScreen)

        LoadTrace.answered(PerfSection.RADIO, LoadAnswer.Done(saved = 40))

        assertEquals(LoadState.DONE, session(PerfSection.RADIO).state)
    }

    @Test
    fun `the first category listed is kept as a moment of its own`() {
        LoadTrace.pressed(PerfSection.MOVIES, earlier(), LoadPress.FIRST_OPENS)
        LoadTrace.runStarted(PerfSection.MOVIES)

        LoadTrace.answered(PerfSection.MOVIES, LoadAnswer.Loading(saved = 0, categories = 12, categoriesDone = 0, bytes = 0, listed = 0))
        assertNull("none is listed yet", session(PerfSection.MOVIES).listedMs)
        LoadTrace.answered(PerfSection.MOVIES, LoadAnswer.Loading(saved = 800, categories = 12, categoriesDone = 1, bytes = 0, listed = 1))

        assertTrue(session(PerfSection.MOVIES).listedMs != null)
        assertEquals(1, session(PerfSection.MOVIES).categoriesDone)
        assertEquals(12, session(PerfSection.MOVIES).categories)
    }

    // ---------------------------------------------------------------------- failures

    @Test
    fun `a failure keeps its stage and its cause, and the two counts apart`() {
        LoadTrace.pressed(PerfSection.SERIES, earlier(), LoadPress.FIRST_OPENS)
        LoadTrace.runStarted(PerfSection.SERIES)
        val failure = LoadFailure(
            stage = "CONNECT",
            error = "NETWORK_UNAVAILABLE",
            exception = "UnknownHostException<-GaiException",
            http = null,
            received = 253_791,
            read = 16_000,
            saved = 0,
            route = "XTREAM",
        )

        LoadTrace.answered(PerfSection.SERIES, LoadAnswer.Failed(failure))

        val failed = session(PerfSection.SERIES)
        assertEquals(LoadState.FAILED, failed.state)
        assertFalse("a failure is never a success", failed.running)
        val text = LoadTrace.render(failed, 0L, report = null, pulse = null).joinToString("\n") { it.text }
        assertTrue(text, "FAILED at CONNECT" in text)
        assertTrue(text, "UnknownHostException<-GaiException" in text)
        assertTrue("read and saved are said apart: $text", "read 16,000 / saved 0" in text)
        assertTrue(text, "XTREAM" in text)
    }

    // ------------------------------------------------------------------ independence

    @Test
    fun `one section's marks never land on another's`() {
        LoadTrace.pressed(PerfSection.MOVIES, earlier(), LoadPress.OPEN)
        LoadTrace.pressed(PerfSection.SERIES, earlier(), LoadPress.OPEN)

        LoadTrace.contentShown(PerfSection.SERIES)

        assertEquals(LoadState.DONE, session(PerfSection.SERIES).state)
        assertEquals(LoadState.RUNNING, session(PerfSection.MOVIES).state)
        assertNull(session(PerfSection.MOVIES).contentMs)
    }

    @Test
    fun `a new press replaces the section's session, and starts from nothing`() {
        LoadTrace.pressed(PerfSection.MOVIES, earlier(), LoadPress.OPEN)
        LoadTrace.contentShown(PerfSection.MOVIES)
        val first = session(PerfSection.MOVIES)

        LoadTrace.pressed(PerfSection.MOVIES, earlier(), LoadPress.OPEN)

        val second = session(PerfSection.MOVIES)
        assertTrue(second.id > first.id)
        assertTrue("the old press's rows are not this press's", second.running && second.contentMs == null)
    }

    @Test
    fun `a mark that arrives after a session is over changes nothing`() {
        LoadTrace.pressed(PerfSection.RADIO, earlier(), LoadPress.OPEN)
        LoadTrace.contentShown(PerfSection.RADIO)
        val over = session(PerfSection.RADIO)

        LoadTrace.screenShown(PerfSection.RADIO)
        LoadTrace.answered(PerfSection.RADIO, LoadAnswer.Failed(LoadFailure("-", "UNKNOWN", null, null, null, 0, 0, null)))
        LoadTrace.answered(PerfSection.RADIO, LoadAnswer.Done(saved = 9))

        assertEquals(over, session(PerfSection.RADIO))
    }

    @Test
    fun `an answer that says nothing leaves the session as it was`() {
        LoadTrace.pressed(PerfSection.MOVIES, earlier(), LoadPress.FIRST_OPENS)
        val before = session(PerfSection.MOVIES)

        LoadTrace.answered(PerfSection.MOVIES, LoadAnswer.Skipped)

        assertEquals(before, session(PerfSection.MOVIES))
    }

    // ---------------------------------------------------------------------- the lines

    @Test
    fun `the size is being measured while the load runs and unavailable when it ends without a call`() {
        val running = LoadSession(
            id = 1, section = PerfSection.SERIES, origin = LoadOrigin.COLD, pressedAtNanos = 0,
            expectsLoad = true, expectsScreen = true, run = 1,
        )
        val ended = running.copy(state = LoadState.DONE, endedMs = 900, loadDoneMs = 800)

        val whileRunning = LoadTrace.render(running, 2_000_000_000L, report = null, pulse = null).joinToString("\n") { it.text }
        val atTheEnd = LoadTrace.render(ended, 0L, report = null, pulse = null).joinToString("\n") { it.text }

        assertTrue(whileRunning, "size جارٍ القياس" in whileRunning)
        assertFalse("a size that is not known is not 0 MB: $whileRunning", "MB" in whileRunning)
        assertTrue(atTheEnd, "size غير متاح" in atTheEnd)
    }

    @Test
    fun `records read and records saved are two figures`() {
        val loading = LoadSession(
            id = 1, section = PerfSection.MOVIES, origin = LoadOrigin.COLD, pressedAtNanos = 0,
            expectsLoad = true, expectsScreen = true, run = 1, saved = 9_000, categories = 237,
        )
        val pulse = LivePulse(
            requestsStarted = 14, requestsFailed = 1, retries = 2, recordsRead = 16_000,
            databaseMs = 0, databaseSteps = emptyMap(),
        )

        val text = LoadTrace.render(loading, 3_000_000_000L, report = null, pulse = pulse).joinToString("\n") { it.text }

        assertTrue(text, "read 16,000 · saved 9,000" in text)
        assertTrue(text, "requests 14 (failed 1, retried 2)" in text)
        assertTrue("the total is not known, and is said as unknown: $text", "categories ?/237" in text)
    }

    @Test
    fun `the stages are the report's, one after another, and the moments are marked apart`() {
        val report = SectionReport(
            section = PerfSection.SERIES, run = 1, startedAtNanos = 0, mode = PerfMode.COLD, fullLoadMs = 1_000,
            phases = listOf(
                RunPhase(PerfPhase.QUEUED, 50),
                RunPhase(PerfPhase.IMPORT, 80),
                RunPhase(PerfPhase.CATEGORIES, 400),
                RunPhase(PerfPhase.STREAMS, 900),
            ),
        )
        val done = LoadSession(
            id = 1, section = PerfSection.SERIES, origin = LoadOrigin.COLD, pressedAtNanos = 0,
            expectsLoad = true, expectsScreen = true, run = 1, state = LoadState.DONE, endedMs = 1_100,
            screenMs = 600, contentMs = 650, loadDoneMs = 1_000, settledReport = report,
        )

        val text = LoadTrace.render(done, 0L, report, pulse = null).joinToString("\n") { it.text }

        assertTrue(text, "stages: queue 0.05 · import 0.03 · categories 0.32 · streams 0.50 · done 0.10" in text)
        assertTrue("the moments are from the press and in order: $text", "at: screen 0.60 · rows 0.65 · load done 1.00" in text)
    }

    private companion object {
        const val FIVE_MS = 5_000_000L
    }
}
