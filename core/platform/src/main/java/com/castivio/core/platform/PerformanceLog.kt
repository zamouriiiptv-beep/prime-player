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
) {
    /** Milliseconds since this run began, on the monotonic clock. */
    fun sinceStartMs(): Long = (SystemClock.elapsedRealtimeNanos() - startedAtNanos) / 1_000_000

    /** True while the panel should keep showing a running clock rather than a result. */
    val running: Boolean get() = mode == PerfMode.RUNNING
}
