package com.castivio.core.platform

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A stopwatch the user can read, on the device, without a cable.
 *
 * ## Why this exists next to a macrobenchmark that measures the same things
 *
 * Because the macrobenchmark cannot be run by the person who needs the number. It
 * wants a host machine, a USB cable, an SDK and a Gradle invocation; the number it
 * would produce is about a section of somebody's real subscription, which only exists
 * on their device. So the measurement has to happen where the subscription is.
 *
 * This is therefore not a second measurement system competing with
 * [CastivioTrace] — it is the same boundaries, reported to a screen instead of to
 * `perfetto`. The trace sections stay because a benchmark on fixed hardware is still
 * the right instrument for regressions; this is the right instrument for *this* user,
 * *this* provider, once.
 *
 * ## Monotonic, and why that is not a detail
 *
 * [SystemClock.elapsedRealtimeNanos] counts from boot and never moves backwards.
 * `System.currentTimeMillis` is the wall clock, and the wall clock is corrected by NTP
 * — usually within the first minute after a television reconnects to the network,
 * which is exactly the minute a first section import happens in. A correction of a few
 * seconds mid-import would produce a negative duration or a plausible-looking wrong
 * one, and the second is far worse than the first.
 *
 * ## What is recorded, and what each thing means
 *
 * Nothing here interprets. Each field is a timestamp difference or a count read from an
 * instrument that already existed, and a field that was never reached stays null rather
 * than becoming a zero — a zero in a performance report reads as "instant".
 */
object PerformanceLog {

    private val _current = MutableStateFlow<SectionReport?>(null)

    /** The run in progress, or the last one that finished. Null before anything opened. */
    val current: StateFlow<SectionReport?> = _current.asStateFlow()

    private var runs = 0

    /**
     * A section was opened. Starts a new run, discarding whatever the last one said.
     *
     * Every open is its own measurement: leaving Channels and coming back must not show
     * the first visit's numbers, because the second visit reads from the database and
     * the first did not. [SectionReport.run] increments so two runs of the same section
     * are visibly different rather than merely equal.
     */
    fun begin(section: PerfSection) {
        runs += 1
        lastNetwork = null
        databaseNanos.set(0)
        requestsStarted.set(0)
        requestsFailed.set(0)
        retries.set(0)
        _current.value = SectionReport(
            section = section,
            run = runs,
            startedAtNanos = SystemClock.elapsedRealtimeNanos(),
        )
    }

    /**
     * The screen's own first frame: its furniture is on screen, with no rows in it yet.
     *
     * **TTID** — time to initial display. The pair to [firstContent], and the reason
     * both exist: TTID is when the viewer stops looking at the previous screen, TTFC is
     * when they can act. A section that reaches TTID in 200ms and TTFC in nine seconds
     * feels broken in a way neither number alone describes, and that gap is precisely
     * what Fast Content Loading has to close.
     *
     * Recorded once per run, like [firstContent]: composition runs again for reasons
     * that are not a first frame.
     */
    fun firstFrame() {
        val report = _current.value ?: return
        if (report.ttidMs != null) return
        _current.value = report.copy(ttidMs = report.sinceStartMs())
    }

    /**
     * How long SQLite has held the import so far, accumulated.
     *
     * Added to rather than set, because a streaming import commits once per batch and
     * the number worth knowing is the total the database cost across all of them. The
     * caller is `RoomCatalogWriter.commit`, which already owns that boundary for the
     * trace section — so this is the same measurement reported to a second place, not a
     * second definition of it.
     */
    fun addDatabaseNanos(nanos: Long) {
        databaseNanos.addAndGet(nanos)
    }

    /** Cleared with every [begin], so one section's commits never land on another's. */
    private val databaseNanos = java.util.concurrent.atomic.AtomicLong()

    /**
     * The first row a viewer can act on is on screen.
     *
     * Recorded once per run. A section that scrolls, re-sorts or loads a second page
     * reaches this composable again, and the first of those is the only one that
     * answers the question "how long did I stare at nothing".
     */
    fun firstContent() {
        val report = _current.value ?: return
        if (report.firstContentMs != null) return
        _current.value = report.copy(firstContentMs = report.sinceStartMs())
    }

    /**
     * What the network layer counted for the window it just finished.
     *
     * Published **upwards** rather than read downwards, and that is a layering rule
     * rather than a style: `CallMetrics` lives in `:data:networking`, and a feature that
     * reached into it would be a screen depending on an HTTP implementation. The
     * importer already owns this window -- it resets the counters when a section import
     * begins -- so it is the one thing that can honestly say when the window closed.
     *
     * Written before `SectionLoad.Done` reaches a view model, because `LoadSection`
     * emits that only after the importer's flow has completed.
     */
    fun network(requests: Int, serverMs: Long, cacheHits: Int) {
        lastNetwork = NetworkWindow(requests, serverMs, cacheHits)
    }

    /** Cleared with every [begin], so a warm open cannot inherit a cold one's counts. */
    private var lastNetwork: NetworkWindow? = null

    /* ------------------------------------------------------------ the import window
     *
     * Counted where they happen and read here, so the panel states what the import
     * actually did rather than what the loop was written to do. Every one of these is
     * an event somebody incremented; none is derived, and none is displayed unless it
     * was incremented at least once.
     */

    private val requestsStarted = java.util.concurrent.atomic.AtomicInteger()
    private val requestsFailed = java.util.concurrent.atomic.AtomicInteger()
    private val retries = java.util.concurrent.atomic.AtomicInteger()

    /** One HTTP call is about to be made. Called by the Xtream client, per attempt. */
    fun requestStarted() {
        requestsStarted.incrementAndGet()
    }

    /** A call gave up after its retries. A category may survive this; see the engine. */
    fun requestFailed() {
        requestsFailed.incrementAndGet()
    }

    /** A call failed transiently and is being tried again. */
    fun requestRetried() {
        retries.incrementAndGet()
    }

    /**
     * What the import reached, published by the importer as its progress arrives.
     *
     * `categories` and `records` are the importer's own running totals rather than a
     * second count kept here, so the panel and the progress line cannot disagree.
     */
    fun importProgress(categories: Int, records: Int) {
        val report = _current.value ?: return
        _current.value = report.copy(
            categories = categories,
            records = records,
            requestsStarted = requestsStarted.get(),
            requestsFailed = requestsFailed.get(),
            retries = retries.get(),
            concurrency = concurrencyLimit,
        )
    }

    /**
     * How many requests the import is allowed to have in flight at once.
     *
     * Set by the importer from the engine's own limit rather than restated here, so the
     * panel reports the number actually in force.
     */
    fun importConcurrency(limit: Int) {
        concurrencyLimit = limit
    }

    private var concurrencyLimit = 0

    /**
     * The section stopped loading: either the import committed, or there was nothing
     * to import because the rows were already on the device.
     *
     * @param mode which of those it was. Never inferred — see [PerfMode]. The one part
     *   decided here rather than by the caller is [PerfMode.CACHE_HIT], because only
     *   this object holds both halves of that claim: a download ran, and OkHttp said
     *   every one of its calls came from the cache.
     * @param fullLoad false when there is no meaningful end point to report — see
     *   [SectionReport.fullLoadMs].
     */
    fun settled(mode: PerfMode, fullLoad: Boolean) {
        val report = _current.value ?: return
        if (report.mode != PerfMode.RUNNING) return
        val window = lastNetwork
        val requests = window?.requests ?: 0
        val cached = window != null && requests > 0 && window.cacheHits >= requests
        _current.value = report.copy(
            mode = if (mode == PerfMode.COLD && cached) PerfMode.CACHE_HIT else mode,
            requests = requests,
            // Null rather than zero when nothing was asked of the provider: a warm open
            // did not take the server no time, it did not ask it anything.
            serverMs = window?.serverMs?.takeIf { requests > 0 },
            fullLoadMs = if (fullLoad) report.sinceStartMs() else null,
            // Same rule: a warm open writes nothing, so it has no database time. Zero
            // here would read as "SQLite was instant" rather than "SQLite was not asked".
            databaseMs = (databaseNanos.get() / 1_000_000).takeIf { it > 0 || fullLoad },
        )
    }

    private data class NetworkWindow(val requests: Int, val serverMs: Long, val cacheHits: Int)

    /** Whether the performance panel may be drawn: a debuggable build, and nothing else. */
    fun isVisibleIn(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}

/** The five things Phase B was asked to time, named once. */
enum class PerfSection {
    CHANNELS,
    MOVIES,
    SERIES,
    RADIO,
}

/**
 * What kind of load this was.
 *
 * Determined from facts the app already holds, never guessed. The distinction matters
 * more than any single duration: a four-second Channels is a disaster on a warm open
 * and unremarkable on a cold one, and a report that does not say which is not a
 * measurement of anything.
 */
enum class PerfMode {
    /** Still going. The panel shows the clock rather than a conclusion. */
    RUNNING,

    /**
     * The section was not on this device and was downloaded now.
     *
     * From `SectionLoad.Done`: the importer reached the end of the provider's
     * categories and committed. At least one call was answered by the provider.
     */
    COLD,

    /**
     * The section was already on this device, so nothing was downloaded.
     *
     * From `SectionLoad.Ready`, which `LoadSection` emits when a mark exists for this
     * source and kind. Rows came from SQLite; the only work measured is the query and
     * the frame.
     */
    WARM,

    /**
     * A download ran and **every** call was served from OkHttp's cache.
     *
     * Not a guess and not a heuristic: OkHttp tells its `EventListener` per call
     * whether the response came from the cache, and this is reported only when the
     * count of those equals the count of calls. Anything short of all of them is
     * [COLD], because a partly-cached import is a download.
     */
    CACHE_HIT,

    /** The load ended in a way that is none of the above — a failure, or a cancel. */
    UNKNOWN,
}

/**
 * One measurement of one section opening.
 *
 * Every duration is nullable and stays null when its moment was never reached. A
 * performance panel that prints `0.00 s` for something that did not happen is worse
 * than one that prints nothing, because somebody will write the zero down.
 */
data class SectionReport(
    val section: PerfSection,
    /** Which open this is, so two runs of the same section are never confused. */
    val run: Int,
    val startedAtNanos: Long,
    val mode: PerfMode = PerfMode.RUNNING,
    /**
     * **TTID** — from the section opening to its first frame, rows or no rows.
     *
     * When the viewer stops looking at the previous screen. Read against
     * [firstContentMs]: the gap between the two is the whole of the wait Fast Content
     * Loading exists to close, and neither number shows it alone.
     */
    val ttidMs: Long? = null,
    /**
     * **TTFC** — from the section opening to the first row on screen.
     *
     * The number a viewer experiences as "how long until something happened".
     */
    val firstContentMs: Long? = null,
    /**
     * From the section opening to the import committing.
     *
     * Null when there is no honest end point rather than when the number is
     * inconvenient: a **warm** open never imports, so it has no full load — the rows
     * were already there and the only question is how fast they were drawn. Inventing
     * an end for it would mean reporting the same instant twice under two names.
     */
    val fullLoadMs: Long? = null,
    /** How long the provider took, in total, across every call this section made. */
    val serverMs: Long? = null,
    /**
     * How long SQLite held the import, summed across every batch commit.
     *
     * Measured at `RoomCatalogWriter.commit` — the same boundary the `Castivio.Commit`
     * trace section marks, so a perfetto capture and this panel cannot disagree about
     * what "database" means.
     */
    val databaseMs: Long? = null,
    /** How many HTTP calls this section cost. `1 + N` in Phase A's terms, measured. */
    val requests: Int? = null,

    /* --------------------------------------------------------- the import window
     *
     * Null until the import reports for the first time, so a warm open -- which
     * imports nothing -- shows none of these rather than a row of zeroes.
     */

    /** Categories the provider declared and the import has written a group for. */
    val categories: Int? = null,

    /** Rows committed to SQLite so far. Rises while the import runs. */
    val records: Int? = null,

    /** HTTP attempts begun, retries included. */
    val requestsStarted: Int? = null,

    /** Attempts that gave up after their retries. A category can survive one. */
    val requestsFailed: Int? = null,

    /** Attempts made again after a transient failure. */
    val retries: Int? = null,

    /** How many requests the import may have in flight at once. */
    val concurrency: Int? = null,
) {
    /** Milliseconds since this run began, on the monotonic clock. */
    fun sinceStartMs(): Long = (SystemClock.elapsedRealtimeNanos() - startedAtNanos) / 1_000_000

    /** True while the panel should keep showing a running clock rather than a result. */
    val running: Boolean get() = mode == PerfMode.RUNNING
}
