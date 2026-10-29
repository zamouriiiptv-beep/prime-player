package com.castivio.core.platform

import android.os.SystemClock
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * **Temporary, for development.** Where the time goes between pressing a section on Home and having it: one
 * session for each press, counted from the press, read by a strip on Home and at the top of the section.
 *
 * ## Why a session and not the run
 *
 * [PerformanceLog] times a *load*, which begins when the queue starts it -- after the press, and after whatever
 * the queue was doing for another section. A viewer waits from the press, and a section already on the device is
 * opened with no load at all. So the clock here is the viewer's: [stamp] is read on the first line of the press
 * handler, the session keeps it, and the load that follows takes it over ([runStarted] hands it to
 * [PerformanceLog.begin], which counts the wait as the run's first phase).
 *
 * ## What it separates
 *
 * Four things that were one:
 *  - the **screen** appearing -- its first composition;
 *  - the **rows** appearing -- what the viewer can act on, from the device when the section was already there;
 *  - the **load** completing -- the provider's catalogue arrived, was written and marked;
 *  - the **first category** being listed -- what opens a section by itself.
 *
 * Rows that were already on the device are not a completed refresh: a session pressed over rows with a load
 * behind them is not over until the load is.
 *
 * ## What it adds up, and what it does not
 *
 * The *stages* (`queue`, `categories`, `streams`, ...) follow one another and add up to the load, as they do in
 * [PerformanceLog]. The *instants* (`screen`, `rows`, `load done`) are counted from the press and are not parts of
 * anything: the screen can open in the middle of a load. The *work* line -- calls, parsing, the database -- is
 * the importer's time spent at the same moment on several threads, and is printed as overlapping.
 *
 * ## One session per section, never mixed
 *
 * A section's marks land on that section's session and nowhere else; a press replaces the section's last session
 * (a replaced one that was still running is abandoned, not completed); a mark that arrives for a session that is
 * over changes nothing. The import's own figures are read from [PerformanceLog] by run number, so a run that is
 * not this session's is never read as its.
 *
 * Off unless [DevDiagnostics] is on: every entry point returns before taking a reading. Removing it is deleting
 * this file, `LoadStrip`, and the one-line calls that feed it.
 */
object LoadTrace {

    private const val TICK_MS = 200L
    private const val NANOS_PER_MS = 1_000_000L
    private const val BYTES_PER_MB = 1_048_576.0

    private val lock = Any()
    private var counter = 0
    private val _sessions = MutableStateFlow<Map<PerfSection, LoadSession>>(emptyMap())

    /** The latest session of each section pressed since the app began. */
    val sessions: StateFlow<Map<PerfSection, LoadSession>> = _sessions.asStateFlow()

    /** Forgets every session: for a test, which begins from none. */
    internal fun reset() {
        synchronized(lock) {
            _sessions.value = emptyMap()
            counter = 0
        }
    }

    /**
     * The moment, to be given to [pressed] a few lines later: read first, before the handler decides or does
     * anything, so the decision is part of what is timed. Zero when the instruments are off.
     */
    fun stamp(): Long = if (DevDiagnostics.enabled) SystemClock.elapsedRealtimeNanos() else 0L

    /**
     * Home's press on [section] decided what it decided: a session begins, counted from [pressedAtNanos].
     *
     * A press that starts nothing -- one on a section whose first load is already arriving -- is not told to
     * this: it is the same wait, and its clock is the first press's.
     */
    fun pressed(section: PerfSection, pressedAtNanos: Long, press: LoadPress) {
        if (!DevDiagnostics.enabled || pressedAtNanos <= 0L) return
        synchronized(lock) {
            val session = LoadSession(
                id = ++counter,
                section = section,
                origin = if (press == LoadPress.OPEN || press == LoadPress.OPEN_AND_REFRESH) LoadOrigin.WARM else LoadOrigin.COLD,
                pressedAtNanos = pressedAtNanos,
                expectsLoad = press != LoadPress.OPEN,
                expectsScreen = press != LoadPress.FIRST_STAYS,
            )
            _sessions.value = _sessions.value + (section to session)
        }
    }

    /**
     * The queue began running a load of [section]: it left the wait. Begins the [PerformanceLog] run, counted from
     * the press that asked for it.
     *
     * A load nobody pressed Home for -- Retry or Refresh pressed on the section's own screen, a section loaded
     * again behind the board -- is a session of its own, counted from here: its press was not on Home and is not
     * known.
     */
    fun runStarted(section: PerfSection) {
        var pressedAt: Long? = null
        if (DevDiagnostics.enabled) {
            synchronized(lock) {
                val session = _sessions.value[section]
                if (session != null && session.running && session.run == null) {
                    pressedAt = session.pressedAtNanos
                } else {
                    _sessions.value = _sessions.value + (
                        section to LoadSession(
                            id = ++counter,
                            section = section,
                            origin = LoadOrigin.REFRESH,
                            pressedAtNanos = SystemClock.elapsedRealtimeNanos(),
                            expectsLoad = true,
                            expectsScreen = false,
                        )
                        )
                }
            }
        }
        PerformanceLog.begin(section, pressedAt)
        if (!DevDiagnostics.enabled) return
        val run = PerformanceLog.current.value?.run
        change(section) { session, at ->
            if (session.run != null) session else session.copy(run = run, runStartedMs = at, expectsLoad = true)
        }
    }

    /**
     * The queue's answer for [section]'s load, after [PerformanceLog] has been told it: a run that has just ended
     * is settled by now, and its report is kept on the session, because the next run replaces it.
     */
    fun answered(section: PerfSection, answer: LoadAnswer) {
        if (!DevDiagnostics.enabled || answer is LoadAnswer.Skipped) return
        val report = PerformanceLog.current.value
        change(section) { session, at ->
            val own = report?.takeIf { it.section == session.section && it.run == session.run }
            when (answer) {
                is LoadAnswer.Loading -> session.copy(
                    saved = answer.saved,
                    categories = answer.categories,
                    categoriesDone = answer.categoriesDone,
                    bytes = answer.bytes,
                    listedMs = session.listedMs ?: at.takeIf { answer.listed > 0 },
                )

                is LoadAnswer.Done -> session.copy(saved = answer.saved, loadDoneMs = at, settledReport = own)
                LoadAnswer.Ready -> session.copy(loadDoneMs = at, settledReport = own)
                is LoadAnswer.Failed -> session.copy(
                    state = LoadState.FAILED,
                    endedMs = at,
                    failure = answer.failure,
                    settledReport = own,
                )

                LoadAnswer.Skipped -> session
            }
        }
    }

    /** The section's screen was composed for the first time since the press. */
    fun screenShown(section: PerfSection) {
        change(section) { session, at -> if (session.screenMs == null) session.copy(screenMs = at) else session }
    }

    /** The section's screen has a row a viewer can act on: from the device when it was there, or as it arrived. */
    fun contentShown(section: PerfSection) {
        change(section) { session, at ->
            session.copy(screenMs = session.screenMs ?: at, contentMs = session.contentMs ?: at)
        }
    }

    /**
     * Applies [transform] to [section]'s session if it is still running, ending it when everything it was waiting
     * for has come. A session that is over, or none, is left exactly as it was: a late event is nobody's.
     */
    private fun change(section: PerfSection, transform: (LoadSession, Long) -> LoadSession) {
        if (!DevDiagnostics.enabled) return
        val now = SystemClock.elapsedRealtimeNanos()
        synchronized(lock) {
            val session = _sessions.value[section] ?: return
            if (!session.running) return
            val next = finished(transform(session, (now - session.pressedAtNanos) / NANOS_PER_MS), now)
            if (next == session) return
            _sessions.value = _sessions.value + (section to next)
            if (!next.running) {
                val report = next.settledReport
                render(next, now, report, pulse = null).forEach { Log.i(PerformanceLog.TAG, "session ${it.text}") }
            }
        }
    }

    /**
     * Over once the load it was waiting for has ended and the screen it opens has rows. A press that is no load, or
     * that leaves the viewer on Home, does not wait for what will not come.
     */
    private fun finished(session: LoadSession, nowNanos: Long): LoadSession {
        if (!session.running) return session
        val loaded = !session.expectsLoad || session.loadDoneMs != null
        val shown = !session.expectsScreen || session.contentMs != null
        return if (loaded && shown) {
            session.copy(state = LoadState.DONE, endedMs = (nowNanos - session.pressedAtNanos) / NANOS_PER_MS)
        } else {
            session
        }
    }

    /**
     * The strip's lines for [section] -- or, for Home, null: the session pressed last -- as they stand, and again
     * every [TICK_MS] while the session runs, so the clock moves between events.
     *
     * [runningOnly] is for the top of a section: nothing there once its session is over, or none.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun lines(section: PerfSection?, runningOnly: Boolean): Flow<List<StripLine>> =
        sessions
            .map { all -> if (section != null) all[section] else all.values.maxByOrNull { it.id } }
            .distinctUntilChanged()
            .flatMapLatest { session ->
                when {
                    session == null || (runningOnly && !session.running) -> flowOf(emptyList<StripLine>())
                    session.running -> flow {
                        while (true) {
                            val report = session.settledReport ?: PerformanceLog.current.value
                                ?.takeIf { it.section == session.section && it.run == session.run }
                            val pulse = if (session.run != null && session.settledReport == null) PerformanceLog.pulse() else null
                            emit(render(session, SystemClock.elapsedRealtimeNanos(), report, pulse))
                            delay(TICK_MS)
                        }
                    }

                    else -> flowOf(render(session, session.pressedAtNanos, session.settledReport, pulse = null))
                }
            }

    /**
     * One session as the lines the strip draws and the log keeps.
     *
     * Pure: the clock, the import's report and its live figures are given, so what a line says can be checked
     * without a device. [report] is the run's -- the settled one once it is over -- and null for a session with
     * no load; [pulse] is the live figures of a load that is still running.
     */
    internal fun render(session: LoadSession, nowNanos: Long, report: SectionReport?, pulse: LivePulse?): List<StripLine> {
        val total = if (session.running) (nowNanos - session.pressedAtNanos) / NANOS_PER_MS else session.endedMs ?: 0L
        val tone = when (session.state) {
            LoadState.RUNNING -> StripTone.RUNNING
            LoadState.DONE -> StripTone.DONE
            LoadState.FAILED -> StripTone.FAILED
            LoadState.CANCELLED -> StripTone.MUTED
        }
        val lines = ArrayList<StripLine>(6)

        val stage = when (session.state) {
            LoadState.RUNNING -> "now ${stageNow(session, report)}"
            LoadState.DONE -> "done"
            LoadState.FAILED -> "FAILED at ${session.failure?.stage ?: DASH}"
            LoadState.CANCELLED -> "replaced"
        }
        val categories = if (session.categories > 0) " · categories ${session.categoriesDone ?: "?"}/${session.categories}" else ""
        lines += StripLine("${session.section.name} · ${session.origin.name} · ${seconds(total)} · $stage$categories", tone)

        // Stages: one after another, so they add up. The report's own, which closes with the full load.
        val spans = report?.spans().orEmpty()
        if (spans.isNotEmpty()) {
            lines += StripLine("stages: " + spans.joinToString(" · ") { "${it.label} ${plain(it.ms)}" }, StripTone.MUTED)
        }

        // Instants: from the press, not parts of anything, and not to be added.
        val instants = listOfNotNull(
            session.listedMs?.let { "first category" to it },
            session.loadDoneMs?.let { "load done" to it },
            session.screenMs?.let { "screen" to it },
            session.contentMs?.let { "rows" to it },
        ).sortedBy { it.second }
        if (instants.isNotEmpty()) {
            lines += StripLine("at: " + instants.joinToString(" · ") { "${it.first} ${plain(it.second)}" }, StripTone.MUTED)
        }

        if (session.expectsLoad) lines += StripLine(figures(session, report, pulse), StripTone.MUTED)

        wire(report).forEach { lines += StripLine(it, StripTone.MUTED) }

        work(report)?.let { lines += StripLine(it, StripTone.MUTED) }

        session.failure?.let { f ->
            lines += StripLine(
                "FAILED at ${f.stage} · ${f.exception ?: f.error} · HTTP ${f.http ?: DASH}" +
                    " · received ${f.received?.let { megabytes(it) } ?: DASH} · read ${group(f.read)} / saved ${group(f.saved)}" +
                    " · ${f.route ?: DASH}",
                StripTone.FAILED,
            )
        }
        return lines
    }

    /** What the session is doing now, in the order a load goes through its stages. */
    private fun stageNow(session: LoadSession, report: SectionReport?): String = when {
        session.expectsLoad && session.run == null -> "queue"
        session.expectsLoad && session.loadDoneMs == null ->
            report?.phases?.lastOrNull()?.phase?.let { stageAfter(it) } ?: "starting"

        session.screenMs == null -> "opening the screen"
        session.contentMs == null -> "waiting for rows"
        else -> "finishing"
    }

    /** The stage that follows the moment the run last reached: spans are named by the moment that ends them. */
    private fun stageAfter(reached: PerfPhase): String = when (reached) {
        PerfPhase.QUEUED -> "starting"
        PerfPhase.IMPORT -> "categories"
        PerfPhase.CATEGORIES -> "groups"
        PerfPhase.GROUPS, PerfPhase.FIRST_COMMIT -> "rows"
        PerfPhase.STREAMS -> "finishing"
        PerfPhase.FINISHED -> "recording"
        PerfPhase.RECORDED -> "recorded"
    }

    /**
     * Records read and saved -- two figures, never one -- the provider's requests, and the size. The size is the
     * completed calls', so it is exact only when the load has ended: until then it says it is being measured.
     */
    private fun figures(session: LoadSession, report: SectionReport?, pulse: LivePulse?): String {
        val read = pulse?.recordsRead ?: report?.recordsRead
        val started = pulse?.requestsStarted ?: report?.requestsStarted
        val failed = pulse?.requestsFailed ?: report?.requestsFailed
        val retried = pulse?.retries ?: report?.retries
        val received = report?.calls?.sumOf { it.bytes }?.takeIf { it > 0 }
        val size = when {
            received != null -> megabytes(received)
            session.running && session.loadDoneMs == null -> MEASURING
            else -> UNAVAILABLE
        }
        return "read ${read?.let { group(it) } ?: DASH} · saved ${group(session.saved)}" +
            " · requests ${started ?: DASH} (failed ${failed ?: DASH}, retried ${retried ?: DASH}) · size $size"
    }

    /**
     * How the provider's answers travelled -- protocol, compression, wire against decoded, where the time went --
     * for every call of the run that was answered by the network. Nothing until a call has been, and for a run whose
     * calls were all served from the cache. Sums over the provider's own calls; the figures are described in
     * [WireFigures.describe].
     */
    private fun wire(report: SectionReport?): List<String> {
        val onTheNetwork = report?.calls?.mapNotNull { call -> call.wire?.let { call to it } }.orEmpty()
        // The provider's own replies are the ones whose body was metered. The rest of the window is whatever else
        // went through the same client while the section loaded -- short calls, logos above all -- and counted in,
        // they dilute every ratio on the line: the wire's size grows and the decoded one does not. Only when no
        // body was metered (a playlist, a portal) is the whole window what there is to say.
        val answered = onTheNetwork.filter { it.second.metered > 0 }.ifEmpty { onTheNetwork }
        if (answered.isEmpty()) return emptyList()
        val figures = answered.map { it.second }.reduce { all, next -> all + next }
        return figures.describe(wireBytes = answered.sumOf { it.first.bytes }, calls = answered.sumOf { it.first.count })
    }

    /**
     * The load's work by kind: the provider's calls, the parsing, SQLite. Overlapping -- the readers run beside the
     * writer, and four requests at once -- so the line says it, and a reader is not to add it up.
     */
    private fun work(report: SectionReport?): String? {
        if (report == null) return null
        val parse = report.passes.sumOf { it.parseMs }.takeIf { report.passes.isNotEmpty() }
        val steps = report.databaseSteps
        val finish = listOf(DatabaseStep.PRUNE, DatabaseStep.COUNTS, DatabaseStep.SEARCH).sumOf { steps[it] ?: 0L }
        if (report.serverMs == null && parse == null && report.databaseMs == null) return null
        return "work, overlapping (s): calls ${report.serverMs.orDash()} (waiting ${report.serverWaitMs.orDash()})" +
            " · parse ${parse.orDash()} · room rows ${steps[DatabaseStep.ROWS].orDash()}" +
            " · commit ${report.databaseMs.orDash()} · finish ${finish.takeIf { it > 0 }.orDash()}"
    }

    private fun Long?.orDash(): String = if (this == null) DASH else plain(this)

    private fun plain(ms: Long): String = hundredthsText(hundredths(ms))

    private fun seconds(ms: Long): String = plain(ms) + " s"

    private fun group(count: Int): String = String.format(Locale.ROOT, "%,d", count)

    private fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / BYTES_PER_MB)

    private const val DASH = "-"

    /** Said in place of a size that is not known yet. */
    private const val MEASURING = "جارٍ القياس"

    /** Said in place of a size that cannot be known. */
    private const val UNAVAILABLE = "غير متاح"
}

/** What a press on a section's card on Home decided, as far as the measurement is concerned. */
enum class LoadPress {
    /** Nothing of the section is on the device: it loads, and opens by itself with its first category. */
    FIRST_OPENS,

    /** Nothing of the section is on the device: it loads, and the viewer stays on Home until it ends. */
    FIRST_STAYS,

    /** The section is on the device and opens at once: no load follows. */
    OPEN,

    /** The section is on the device and opens at once, and a load of it runs behind. */
    OPEN_AND_REFRESH,
}

/** How a session began. */
enum class LoadOrigin {
    /** Pressed with nothing of the section on the device: a first load, which the viewer waits on. */
    COLD,

    /** Pressed with rows on the device, which open at once. */
    WARM,

    /** A load nobody pressed Home for: counted from the moment it began. */
    REFRESH,
}

enum class LoadState { RUNNING, DONE, FAILED, CANCELLED }

/**
 * One press on a section, and what has come of it. Every figure is milliseconds from the press, on the monotonic
 * clock, and null for a moment that has not come -- never zero, which reads as "instant".
 */
data class LoadSession(
    val id: Int,
    val section: PerfSection,
    val origin: LoadOrigin,
    val pressedAtNanos: Long,
    /** A load follows the press: the section's first, or a refresh behind rows that are already here. */
    val expectsLoad: Boolean,
    /** The press opens the section's screen, at once or by itself: the session is not over until it has rows. */
    val expectsScreen: Boolean,
    val state: LoadState = LoadState.RUNNING,
    /** The [PerformanceLog] run that is this session's load, once the queue has begun it. */
    val run: Int? = null,
    /** When the load left the queue; the wait before it is the report's `queue` stage. */
    val runStartedMs: Long? = null,
    /** The first category was listed: what opens a section by itself. */
    val listedMs: Long? = null,
    /** The screen's first composition. */
    val screenMs: Long? = null,
    /** The first row a viewer can act on. */
    val contentMs: Long? = null,
    /** The load completed: the catalogue was written and marked. Not the rows appearing. */
    val loadDoneMs: Long? = null,
    /** The session ended: everything it waited for came, or the load failed. */
    val endedMs: Long? = null,
    val categories: Int = 0,
    val categoriesDone: Int? = null,
    /** Rows committed so far, as the queue said. The rows *read* are the import's own figure. */
    val saved: Int = 0,
    val bytes: Long = 0,
    val failure: LoadFailure? = null,
    /** The load's report as it settled, kept because the next run replaces it. */
    val settledReport: SectionReport? = null,
) {
    val running: Boolean get() = state == LoadState.RUNNING
}

/** A load's failure as the strip prints it: names and numbers, never an address. */
data class LoadFailure(
    val stage: String,
    val error: String,
    val exception: String?,
    val http: Int?,
    val received: Long?,
    val read: Int,
    val saved: Int,
    val route: String?,
)

/** What the queue said of a load, as primitives: this module knows no catalogue. */
sealed interface LoadAnswer {
    data class Loading(
        val saved: Int,
        val categories: Int,
        val categoriesDone: Int?,
        val bytes: Long,
        val listed: Int,
    ) : LoadAnswer

    data class Done(val saved: Int) : LoadAnswer

    /** Already on the device: nothing was imported. */
    data object Ready : LoadAnswer

    data class Failed(val failure: LoadFailure) : LoadAnswer

    /** Waiting its turn, or no provider: nothing to record. */
    data object Skipped : LoadAnswer
}

/** One line of the strip, and what it is about, which the screen turns into a colour. */
data class StripLine(val text: String, val tone: StripTone)

enum class StripTone { RUNNING, DONE, FAILED, MUTED }
