package com.castivio.core.platform

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
    @Synchronized
    fun begin(section: PerfSection) {
        runs += 1
        lastNetwork = null
        databaseNanos.set(0)
        databaseSteps.clear()
        actions.clear()
        failureCounts.clear()
        _current.value = SectionReport(
            section = section,
            run = runs,
            startedAtNanos = SystemClock.elapsedRealtimeNanos(),
        )
    }

    /**
     * An import of [section] is starting.
     *
     * A run that is still going is this import's own: the section was just opened, and
     * [begin] already started the clock at the press. Anything else means the import
     * started on its own -- a section opened again after Refresh forgot its marks -- and
     * that is a load of its own, so it starts a run of its own, from zero, rather than
     * writing its numbers into the last one's finished report.
     */
    @Synchronized
    fun importStarted(section: PerfSection) {
        val report = _current.value
        if (report == null || !report.running) begin(section)
    }

    /** How the writer is saving this run: `REPLACE` or `APPEND`, each named once. */
    fun saveMode(mode: String) = updateRunning { report ->
        if (mode in report.saveModes) report else report.copy(saveModes = report.saveModes + mode)
    }

    /**
     * One pass over a section's streams, as the engine measured it -- with the database's
     * steps as they stand, so the writer's rows and search rows can be read beside it while
     * the import is still finishing.
     */
    fun pass(pass: PassTimes) = updateRunning { it.copy(passes = it.passes + pass, databaseSteps = stepsSoFar()) }

    /** How many records each stage of the run saw. */
    fun recordCounts(counts: RecordCounts) = updateRunning { it.copy(recordCounts = counts) }

    /** The catalogue and its search index, counted before the import ([before]) or after it. */
    fun check(before: Boolean, check: IndexCheck) = updateRunning {
        if (before) it.copy(checkBefore = check) else it.copy(checkAfter = check)
    }

    /**
     * [change] applied to the run in progress, and to nothing else.
     *
     * A finished report is a reading somebody may be photographing; nothing that arrives
     * after it settled -- a late progress event, the next load's first numbers -- may
     * change it. The next load starts a run of its own.
     */
    private inline fun updateRunning(crossinline change: (SectionReport) -> SectionReport) {
        _current.update { report -> if (report == null || !report.running) report else change(report) }
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
    fun network(
        requests: Int,
        serverMs: Long,
        cacheHits: Int,
        waitMs: Long? = null,
        /** The same window by action: each request's time, its wait, its size. */
        calls: List<CallTimes> = emptyList(),
    ) {
        lastNetwork = NetworkWindow(requests, serverMs, cacheHits, waitMs, calls)
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

    /*
     * Counted per action -- the provider's own name for the request, `get_live_streams`,
     * never its address -- because a total said 1,673 attempts and 365 failures beside an
     * import of 836 calls, and a total cannot say which requests those were. The run's
     * window, from the section opening: wider than the importer's, on purpose, so a
     * request that is not the import's still shows up as one.
     */
    private val actions = ConcurrentHashMap<String, ActionCounter>()
    private val failureCounts = ConcurrentHashMap<String, AtomicInteger>()

    private class ActionCounter {
        val started = AtomicInteger()
        val failed = AtomicInteger()
        val retried = AtomicInteger()
    }

    private fun counter(action: String) = actions.getOrPut(action) { ActionCounter() }

    /** One HTTP attempt is about to be made, retries included. */
    fun requestStarted(action: String) {
        counter(action).started.incrementAndGet()
    }

    /**
     * An attempt gave up -- after its retries, or at once for a failure no retry could
     * change -- for [reason]: an HTTP status, `timeout`, `unknown host`, or an exception's
     * class name. Never a URL. A category may survive this; see the engine.
     */
    fun requestFailed(action: String, reason: String) {
        counter(action).failed.incrementAndGet()
        failureCounts.getOrPut("$action · $reason") { AtomicInteger() }.incrementAndGet()
    }

    /** An attempt failed transiently and is being made again. */
    fun requestRetried(action: String) {
        counter(action).retried.incrementAndGet()
    }

    /**
     * What the import reached, published by the importer as its progress arrives.
     *
     * `categories` and `records` are the importer's own running totals rather than a
     * second count kept here, so the panel and the progress line cannot disagree.
     */
    fun importProgress(categories: Int, records: Int) = updateRunning { report ->
        report.withCounts().copy(
            categories = categories,
            records = records,
            concurrency = concurrencyLimit,
        )
    }

    /** The request counters as they stand, folded into a report. */
    private fun SectionReport.withCounts(): SectionReport {
        val byAction = actions.entries.associate { (action, count) ->
            action to ActionCount(count.started.get(), count.failed.get(), count.retried.get())
        }
        return copy(
            requestsStarted = byAction.values.sumOf { it.started }.takeIf { byAction.isNotEmpty() },
            requestsFailed = byAction.values.sumOf { it.failed }.takeIf { byAction.isNotEmpty() },
            retries = byAction.values.sumOf { it.retried }.takeIf { byAction.isNotEmpty() },
            byAction = byAction,
            failureReasons = failureCounts.entries.associate { it.key to it.value.get() },
        )
    }

    /* ---------------------------------------------------------- where the time went
     *
     * The 48.75 seconds of a full load, cut at the moments the load passes through, so
     * a later change can say which part of them it removed. Reported by whoever reaches
     * each moment -- the importer, the engine through it, the writer -- and timed here,
     * on the run's own monotonic clock, so the cuts and the full load share a zero.
     */

    /**
     * The run reached [phase]. The first report wins; a later one is the same moment again.
     *
     * The database's steps are folded in at the same time, so the end of an import -- the
     * prune, the counts, the rebuild -- is on the panel from the moment it is done rather than
     * from the moment the run settles, which is the moment the panel leaves the screen.
     */
    fun phase(phase: PerfPhase) {
        _current.update { report ->
            when {
                report == null || report.mode != PerfMode.RUNNING -> report
                report.phases.any { it.phase == phase } -> report
                else -> report.copy(
                    phases = report.phases + RunPhase(phase, report.sinceStartMs()),
                    databaseSteps = stepsSoFar(),
                )
            }
        }
    }

    /** A fact about how the run went that is not a time: which way the streams came. */
    fun note(key: String, value: String) {
        _current.update { report ->
            if (report == null || report.mode != PerfMode.RUNNING) report else report.copy(notes = report.notes + (key to value))
        }
    }

    /**
     * SQLite's share by step, accumulated.
     *
     * Beside [addDatabaseNanos], which is the commits alone and always was: the rows are
     * written between commits and the end of an import does three more things, and a
     * figure that leaves those out reads as the whole of the database when it is a part.
     */
    fun addDatabaseStep(step: DatabaseStep, nanos: Long) {
        databaseSteps.getOrPut(step) { AtomicLong() }.addAndGet(nanos)
    }

    private val databaseSteps = ConcurrentHashMap<DatabaseStep, AtomicLong>()

    /** The steps as they stand, in milliseconds. */
    private fun stepsSoFar(): Map<DatabaseStep, Long> =
        databaseSteps.entries.associate { it.key to it.value.get() / 1_000_000 }

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
        val settledReport = report.withCounts().copy(
            mode = if (mode == PerfMode.COLD && cached) PerfMode.CACHE_HIT else mode,
            requests = requests,
            // Null rather than zero when nothing was asked of the provider: a warm open
            // did not take the server no time, it did not ask it anything.
            serverMs = window?.serverMs?.takeIf { requests > 0 },
            serverWaitMs = window?.waitMs?.takeIf { requests > 0 },
            fullLoadMs = if (fullLoad) report.sinceStartMs() else null,
            // Same rule: a warm open writes nothing, so it has no database time. Zero
            // here would read as "SQLite was instant" rather than "SQLite was not asked".
            databaseMs = (databaseNanos.get() / 1_000_000).takeIf { it > 0 || fullLoad },
            databaseSteps = stepsSoFar(),
            calls = window?.calls.orEmpty(),
        )
        _current.value = settledReport
        settledReport.lines().forEach { Log.i(TAG, it) }
        onSettled?.invoke(settledReport)
    }

    /**
     * Told of every run as it settles, after it is printed.
     *
     * Null by default, which is every release build. A debuggable one hands the reading on
     * through it -- see `MeasurementClipboard` in the app -- because the loading screen that
     * drew the readout gives way to the section in the same frame the run settles, so the
     * finished readout is never on screen long enough to be read, and the log needs a cable.
     */
    @Volatile
    var onSettled: ((SectionReport) -> Unit)? = null

    private data class NetworkWindow(
        val requests: Int,
        val serverMs: Long,
        val cacheHits: Int,
        val waitMs: Long?,
        val calls: List<CallTimes>,
    )

    /**
     * Where a settled run is printed, beside the panel: `adb logcat -s CastivioPerf`.
     *
     * The panel is on the loading screen, and the loading screen is gone the moment the
     * load ends; the log is what keeps the run's whole breakdown once it has.
     */
    const val TAG = "CastivioPerf"

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

    /* ------------------------------------------------------ where the time went */

    /** The moments the run passed through, in the order it reached them. */
    val phases: List<RunPhase> = emptyList(),

    /** Facts about how it ran that are not times: which way the streams came. */
    val notes: Map<String, String> = emptyMap(),

    /** Attempts by action, retries included, from the section opening. */
    val byAction: Map<String, ActionCount> = emptyMap(),

    /** Failed attempts by `action · reason`. */
    val failureReasons: Map<String, Int> = emptyMap(),

    /**
     * How long the calls waited for the server's first answer, summed: the part of
     * [serverMs] before a response began. The rest of a call is the body arriving and
     * being parsed -- the call does not end until it has been read -- so the two are
     * different costs, and only this one shrinks when there are fewer requests.
     */
    val serverWaitMs: Long? = null,

    /** SQLite by step, in milliseconds, beside [databaseMs], which is the commits alone. */
    val databaseSteps: Map<DatabaseStep, Long> = emptyMap(),

    /* ------------------------------------------------- the measurement build's detail */

    /** How the writer saved: `REPLACE`, `APPEND`, or both, in the order they began. */
    val saveModes: List<String> = emptyList(),

    /** Each pass over a section's streams: what the readers and the writer did at once. */
    val passes: List<PassTimes> = emptyList(),

    /** How many records each stage saw, from the reply to the rows written. */
    val recordCounts: RecordCounts? = null,

    /** Every request of the import window by action: time, wait for an answer, size. */
    val calls: List<CallTimes> = emptyList(),

    /** The catalogue and its search index before the import, and after it. */
    val checkBefore: IndexCheck? = null,
    val checkAfter: IndexCheck? = null,
) {
    /** Milliseconds since this run began, on the monotonic clock. */
    fun sinceStartMs(): Long = (SystemClock.elapsedRealtimeNanos() - startedAtNanos) / 1_000_000

    /** True while the panel should keep showing a running clock rather than a result. */
    val running: Boolean get() = mode == PerfMode.RUNNING

    /**
     * The run as lines for a log: times, cuts, requests by action and failures by reason.
     *
     * Names and numbers only -- an action, a status, a class name -- because this is
     * printed, and an Xtream URL carries the user's password.
     */
    fun lines(): List<String> = buildList {
        add("── ${section.name} · run $run · ${mode.name}${saveModes.joinToString("") { " · $it" }} ──")
        add("full load ${fullLoadMs.seconds()} · ttid ${ttidMs.seconds()} · ttfc ${firstContentMs.seconds()}")
        // The stages one after another, and their sum: the full load when there is one, to the
        // hundredth, because the figures are rounded so that they add up -- see `hundredthsAddingUp`.
        val cut = spans()
        if (cut.isNotEmpty()) {
            val whole = cut.sumOf { it.ms }
            add(
                "stages " + cut.labelled(hundredthsAddingUp(cut.map { it.ms }, whole)) { it.label } +
                    " = " + hundredthsText(hundredths(whole)) + " s" + if (fullLoadMs == null) " so far" else "",
            )
            add("phases " + phases.joinToString(" · ") { "${it.phase.label} ${it.atMs.seconds()}" })
        }
        notes.forEach { (key, value) -> add("$key: $value") }
        passes.forEach { pass ->
            add(
                "at the same time, ${if (pass.oneReply) "one reply" else "per category"} ${pass.kind}" +
                    " · ${pass.requests} request${if (pass.requests == 1) "" else "s"}" +
                    " · ${pass.parallelReaders} at once · window ${pass.windowMs.seconds()}",
            )
            val reader = pass.readerParts()
            add(
                "  reader${if (pass.parallelReaders > 1) "s, summed," else ""} " +
                    reader.labelled(hundredthsAddingUp(reader.map { it.ms }, pass.readerMs)) { it.label } +
                    " = ${pass.readerMs.seconds()}",
            )
            val writer = writerParts(pass)
            add(
                "  writer " + writer.labelled(hundredthsAddingUp(writer.map { it.ms }, pass.windowMs)) { it.label } +
                    " = ${pass.windowMs.seconds()}",
            )
        }
        if (finishParts().any { it.second != null }) {
            add("then " + finishParts().joinToString(" · ") { (label, ms) -> "$label ${ms.seconds()}" })
        }
        recordCounts?.let { r ->
            add(
                "records reply ${r.parsed.grouped()} → filed ${r.filed.grouped()} · orphans ${r.orphans.grouped()}" +
                    " · written ${r.written.byName()} · categories ${r.categories.byName()}",
            )
            if (r.orphanCategories.isNotEmpty()) {
                add("orphan categories " + r.orphanCategories.joinToString(" · ") { "${it.first} ×${it.second.grouped()}" })
            }
        }
        calls.forEach { call ->
            add(
                "call ${call.action} ×${call.count} (fail ${call.failed}) · ${call.totalMs.seconds()}" +
                    " · wait ${call.waitMs.seconds()} · ${call.bytes.grouped()} bytes",
            )
        }
        checkBefore?.let { add("index before: ${it.describe()}") }
        checkAfter?.let { add("index after: ${it.describe()}") }
        add(
            "network ${serverMs.seconds()} in calls · ${serverWaitMs.seconds()} waiting · ${requests ?: 0} calls" +
                " · categories ${categories ?: 0} · records ${records ?: 0}",
        )
        add(
            "database commit ${databaseMs.seconds()}" +
                databaseSteps.entries.sortedBy { it.key.ordinal }.joinToString("") { " · ${it.key.label} ${it.value.seconds()}" },
        )
        if (byAction.isNotEmpty()) {
            add(
                "attempts " + byAction.entries.sortedByDescending { it.value.started }.joinToString(" · ") {
                    "${it.key} ${it.value.started} (fail ${it.value.failed}, retry ${it.value.retried})"
                },
            )
        }
        if (failureReasons.isNotEmpty()) {
            add("failures " + failureReasons.entries.sortedByDescending { it.value }.joinToString(" · ") { "${it.key} ×${it.value}" })
        }
    }

    private fun Long?.seconds(): String = this?.let { hundredthsText(hundredths(it)) + " s" } ?: "–"

    /** `ask 0.92 + network 2.85`: the parts with the figures they were given, as a sum. */
    private fun <T> List<T>.labelled(figures: List<Long>, label: (T) -> String): String =
        indices.joinToString(" + ") { "${label(this[it])} ${hundredthsText(figures[it])}" }

    private fun Int.grouped(): String = String.format(Locale.ROOT, "%,d", this)

    private fun Long.grouped(): String = String.format(Locale.ROOT, "%,d", this)

    private fun Map<String, Int>.byName(): String = entries.joinToString(", ") { "${it.key} ${it.value.grouped()}" }

    private fun IndexCheck.describe(): String =
        "rows ${rows.byName()} · all rows ${allRows.grouped()}" +
            " · search entries ${searchRows.grouped()} · ids indexed more than once ${duplicated.grouped()}" +
            " · sample search ${sampleResults?.grouped() ?: "–"} results for ${sampleRows?.grouped() ?: "–"} rows" +
            " · took ${tookMs.seconds()}"
}

/**
 * One pass over a section's streams, in milliseconds: what the readers and the writer did
 * at the same time. See the engine's `PassReport`, which this is a copy of.
 *
 * Each side adds up to its own time and never to the other's. The reader's figures are
 * summed over [parallelReaders] requests in flight at once, so with more than one they
 * exceed [windowMs]; the writer's are one thread inside it.
 */
data class PassTimes(
    val kind: String,
    val oneReply: Boolean,
    val parallelReaders: Int,
    val requests: Int,
    /** The pass on the wall clock, from its first request to its last row written. */
    val windowMs: Long,
    /** Every request's time, from asking to its last batch handed over, summed. */
    val readerMs: Long,
    /** Connecting, sending, waiting for the answer to begin. */
    val askMs: Long,
    /** Inside the answer's reads: the bytes arriving. */
    val readMs: Long,
    /** Blocked handing a batch to the writer while it was busy with the last. */
    val handOverMs: Long,
    /** Handing rows to the database: rows and search entries together. */
    val writeMs: Long,
    val commitMs: Long,
    /** Waiting for the next batch: the readers behind the writer. */
    val idleMs: Long,
) {
    /**
     * The reader's remaining time: parsing. The remainder of the figures as they are shown,
     * so the reader's four parts add up to [readerMs] to the millisecond.
     */
    val parseMs: Long get() = (readerMs - askMs - readMs - handOverMs).coerceAtLeast(0)

    /** The writer's bookkeeping between the three, by the same rule: the four make [windowMs]. */
    val otherMs: Long get() = (windowMs - writeMs - commitMs - idleMs).coerceAtLeast(0)
}

/** How many records each stage of a run saw. Kinds by name. */
data class RecordCounts(
    /** Streams parsed out of the replies. */
    val parsed: Int,
    /** Filed under a category the panel listed, and handed to the writer. */
    val filed: Int,
    /** Parsed, naming a category the panel did not list, and not written. */
    val orphans: Int,
    /** Rows written, by kind. */
    val written: Map<String, Int>,
    /** The categories the import covered, by the kind each was filed as. */
    val categories: Map<String, Int>,
    /** The orphans by the category id they named, most first. */
    val orphanCategories: List<Pair<String, Int>>,
)

/** One action's requests in an import window. */
data class CallTimes(
    val action: String,
    val count: Int,
    val failed: Int,
    /** Every call's time, summed: from asking until its answer was read to the end. */
    val totalMs: Long,
    /** The part of [totalMs] before each answer began. */
    val waitMs: Long,
    val bytes: Long,
)

/** The catalogue and its search index, counted at one moment. Kinds by name. */
data class IndexCheck(
    /** This source's rows, by kind. */
    val rows: Map<String, Int>,
    /** Every source's rows: what the search index is built from. */
    val allRows: Int,
    /** Entries in the search index. One per row when it is in step. */
    val searchRows: Int,
    /** Rows the index holds more than one entry for. */
    val duplicated: Int,
    /** A sample search run the app's way: results, and the different rows among them. */
    val sampleResults: Int?,
    val sampleRows: Int?,
    /** How long counting took: part of the load's time, shown so it can be set aside. */
    val tookMs: Long,
)

/** One moment a run passed through, and when, in milliseconds from its start. */
data class RunPhase(val phase: PerfPhase, val atMs: Long)

/**
 * The moments a load is cut at, in the order a load passes them.
 *
 * Named once, here, so every path — a panel, a playlist, a portal — cuts at the same
 * names, a reading of one can be set beside a reading of another, and the panel can tell
 * them apart without matching on text. A path reports only the moments it has: a
 * playlist has no categories to ask for, so it goes from [IMPORT] to [FIRST_COMMIT].
 */
enum class PerfPhase(val label: String) {
    /** The importer took the section over from the screen. */
    IMPORT("import"),

    /**
     * The catalogue and its search index were counted before anything was written: a
     * measurement in debuggable builds, given its own span so its time is never mistaken
     * for the import's.
     */
    CHECKED_BEFORE("checks before"),

    /** The provider's category list arrived. */
    CATEGORIES("categories"),

    /** Every category is written as a group, in the provider's order. */
    GROUPS("groups"),

    /** The first rows are committed: the earliest a list could show anything. */
    FIRST_COMMIT("first commit"),

    /** Every stream has arrived and been written. */
    STREAMS("streams"),

    /** The writer has finished: old rows pruned, counts and search rebuilt. */
    FINISHED("finished"),

    /** Counted again after the import, like [CHECKED_BEFORE] and for the same reason. */
    CHECKED_AFTER("checks after"),

    /** The section is marked as loaded, so the next open reads from the device. */
    RECORDED("recorded"),
}

/** One action's attempts in a run: begun, given up, and made again. */
data class ActionCount(val started: Int, val failed: Int, val retried: Int)

/**
 * The parts of SQLite's work an import reports besides its commits.
 *
 * Each is reported where the writer does it, once per batch or once per import -- never
 * per row, which would cost more than the write. Rows and their search entries are timed
 * row by row, because they alternate inside a batch, and added up before they are told.
 */
enum class DatabaseStep(val label: String) {
    /** Writing rows into `media`, with its indices, between commits. */
    ROWS("rows"),

    /** Writing each row's search entry, which an appending import does row by row. */
    SEARCH_ROWS("search rows"),

    /** Dropping the previous generation, after a replacing import. */
    PRUNE("prune"),

    /** Recounting every group's rows at the end of an import. */
    COUNTS("counts"),

    /** Rebuilding the whole search index in one statement, after a replacing import. */
    SEARCH("search rebuild"),
}
