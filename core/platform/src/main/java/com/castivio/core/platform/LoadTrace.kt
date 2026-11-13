package com.castivio.core.platform

import android.os.SystemClock
import android.util.Log
import com.castivio.core.common.artwork.ArtworkFigures
import com.castivio.core.common.artwork.ArtworkMetrics
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

    /** How often a load that is over is read again, for the pictures that go on arriving after it. */
    private const val AFTER_TICK_MS = 1_000L
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
            // The pictures are counted from the press, as the viewer's wait is: the strip says what the posters did for
            // this opening and not for the one before it.
            ArtworkMetrics.reset()
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
                    ArtworkMetrics.reset()
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
                            emit(render(session, SystemClock.elapsedRealtimeNanos(), report, pulse, picturesOf(session)))
                            delay(TICK_MS)
                        }
                    }

                    // A load that is over is a reading that no longer moves -- except the pictures, which go on arriving, or
                    // failing and being asked again, after it. So it is read again, slowly, and drawn only when it changed.
                    else -> flow<List<StripLine>> {
                        while (true) {
                            emit(render(session, session.pressedAtNanos, session.settledReport, pulse = null, artwork = picturesOf(session)))
                            delay(AFTER_TICK_MS)
                        }
                    }.distinctUntilChanged()
                }
            }

    /**
     * What the pictures have done, for [session]'s strip -- or nothing, for a session that is not the last one.
     *
     * The tally is the application's one, counted from the last press, so it is the last session's and no other's: an
     * older section's strip, read now, would tell of pictures it did not ask for.
     */
    private fun picturesOf(session: LoadSession): ArtworkFigures? =
        if (session.id == _sessions.value.values.maxOfOrNull { it.id }) ArtworkMetrics.figures() else null

    /**
     * One session as the lines the strip draws and the log keeps.
     *
     * Pure: the clock, the import's report and its live figures are given, so what a line says can be checked
     * without a device. [report] is the run's -- the settled one once it is over -- and null for a session with
     * no load; [pulse] is the live figures of a load that is still running; [artwork] is what the pictures have done, for
     * the session that has been counting them.
     */
    internal fun render(
        session: LoadSession,
        nowNanos: Long,
        report: SectionReport?,
        pulse: LivePulse?,
        artwork: ArtworkFigures? = null,
    ): List<StripLine> {
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
        val categoryFigures = if (session.categories > 0) "${session.categoriesDone ?: "?"}/${session.categories}" else null
        val categories = categoryFigures?.let { " · categories $it" } ?: ""
        val summary = StripSummary(
            sessionId = session.id,
            name = "${session.section.name} · ${session.origin.name}",
            seconds = seconds(total),
            status = stage,
            categories = categoryFigures,
            tone = tone,
            running = session.running,
            endedAtNanos = if (session.running) null else session.endedMs?.let { session.pressedAtNanos + it * NANOS_PER_MS },
        )
        // The line is what it always was; the summary beside it is the same facts for the card that shows them in a glance.
        lines += StripLine("${session.section.name} · ${session.origin.name} · ${seconds(total)} · $stage$categories", tone, summary)

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

        reply(report)?.let { lines += StripLine(it, StripTone.MUTED) }

        wire(report).forEach { lines += StripLine(it, StripTone.MUTED) }

        artwork?.describe()?.forEach { lines += StripLine(it, StripTone.MUTED) }

        work(report)?.let { lines += StripLine(it, StripTone.MUTED) }

        pipeline(report)?.let { lines += StripLine(it, StripTone.MUTED) }
        probes(report).forEach { lines += StripLine(it, StripTone.MUTED) }
        database(report)?.let { lines += StripLine(it, StripTone.MUTED) }

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
     * completed calls', so it is exact only when the load has ended: until then it says what the reply that is arriving
     * has come to so far, and says it is being measured while no byte of one has.
     */
    private fun figures(session: LoadSession, report: SectionReport?, pulse: LivePulse?): String {
        val read = pulse?.recordsRead ?: report?.recordsRead
        val started = pulse?.requestsStarted ?: report?.requestsStarted
        val failed = pulse?.requestsFailed ?: report?.requestsFailed
        val retried = pulse?.retries ?: report?.retries
        val received = report?.calls?.sumOf { it.bytes }?.takeIf { it > 0 }
        val size = when {
            received != null -> megabytes(received)
            // Not yet complete, so not yet a size: but the reply that is arriving is being counted, and is said as far as
            // it has come -- how much, how it travels, how fast -- until the calls are done and their own figures follow.
            session.running && session.loadDoneMs == null -> pulse?.reply?.describe() ?: MEASURING
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

    /**
     * Who waited on whom in the pass over the section's streams, from the readers' and the writer's own accounts of
     * the same stretch.
     *
     * The reader's four parts -- the bytes arriving and being decoded, parsing, and being held back by a writer that
     * was still busy -- and the writer's three: handing rows to the database, committing, and waiting for the next
     * batch. A reader blocked on the writer and a writer that never waits is a load the writer sets the pace of; the
     * reverse is one the reader does. Nothing here is added to anything: the two sides overlap.
     */
    private fun pipeline(report: SectionReport?): String? {
        val passes = report?.passes?.takeIf { it.isNotEmpty() } ?: return null
        fun total(of: (PassTimes) -> Long) = plain(passes.sumOf(of))
        return "pipeline (s): reader — socket+decode ${total { it.readMs }} · parse ${total { it.parseMs }}" +
            " · blocked on the writer ${total { it.handOverMs }}; writer — write() ${total { it.writeMs }}" +
            " · commit ${total { it.commitMs }} · waiting for the reader ${total { it.idleMs }}"
    }

    /**
     * How the section's streams were fetched, in the importer's own words: the one reply or the walk, taken to a file
     * first or read as it arrived, and -- when it was not the reply that was used -- why, how many rows it had delivered
     * and how long it had run when it failed. What was missing from a load that took ten minutes: the strip said that a
     * walk of every category had happened, and not that the reply had failed first or what had cut it.
     */
    private fun reply(report: SectionReport?): String? = report?.notes?.get(NOTE_STREAMS)?.let { "reply: $it" }

    /**
     * The database's side of the load, on one line: how often the screens were told, where the writer waited for its
     * transaction and how its commits ended, and what the file and its log came to. The figures the writer has always
     * left in its notes for the log, and that nobody holding a phone could read.
     */
    private fun database(report: SectionReport?): String? {
        val parts = listOfNotNull(
            report?.notes?.get(NOTE_TOLD)?.let { "screens told $it" },
            report?.notes?.get(NOTE_TX_BEGIN)?.let { "tx begin $it" },
            report?.notes?.get(NOTE_TX_END)?.let { "tx end $it" },
            report?.notes?.get(NOTE_DB_CONTEXT)?.let { "db $it" },
        )
        return if (parts.isEmpty()) null else "database: " + parts.joinToString(" · ")
    }

    /**
     * What the processor did against what the clock says, and when the first rows moved.
     *
     * A thread's CPU time set beside its wall time says how much of the wall time it was running: a reader or a writer
     * whose CPU time is near its wall time is slow because its work is, and one whose CPU time is far below it was
     * waiting or kept off the processor. The process's CPU over the same import, and the collector's work, say whether
     * the whole device was busy. The first-batch line splits the wait for the first rows into the provider's and the
     * application's. Each part appears only when it was measured, which is a measuring build.
     */
    private fun probes(report: SectionReport?): List<String> = buildList {
        if (report == null) return@buildList
        val passes = report.passes
        val cpu = buildList {
            val reader = passes.sumOf { it.readerCpuMs }
            if (reader > 0) add("reader ${plain(reader)} of ${plain(passes.sumOf { it.readerMs })}")
            report.notes[NOTE_WRITER_CPU]?.let { add("writer $it") }
            report.notes[NOTE_PROCESS_CPU]?.let { add(it) }
        }
        if (cpu.isNotEmpty()) add("cpu vs wall (s): " + cpu.joinToString(" · "))
        // Who spent the process's CPU: a name that holds the reader and the writer holds their time above as well.
        report.notes[NOTE_THREAD_CPU]?.let { add("cpu by thread name (s): $it") }
        // What the writer's wall time was made of, how the cost of a row went as the load grew, what the storage was asked
        // for, and what the pool of Room's threads -- the largest spender of the processor -- was doing.
        report.notes[NOTE_WRITER_SCHED]?.let { add("writer rows (s): $it") }
        report.notes[NOTE_CURVE]?.let { add("rows curve: $it") }
        report.notes[NOTE_PROCESS_IO]?.let { add("process io: $it") }
        report.notes[NOTE_ROOM_THREADS]?.let { add("room threads: $it") }

        val first = passes.firstOrNull { it.oneReply }
        if (first != null) {
            val groupsAt = report.phases.firstOrNull { it.phase == PerfPhase.GROUPS }?.atMs
            val commitAt = report.phases.firstOrNull { it.phase == PerfPhase.FIRST_COMMIT }?.atMs
            val marks = listOfNotNull(
                first.replyReadyMs.takeIf { it >= 0 }?.let { "reply in hand +${plain(it)}" },
                first.firstHandedMs.takeIf { it >= 0 }?.let { "first rows handed over +${plain(it)}" },
                first.firstWrittenMs.takeIf { it >= 0 }?.let { "written +${plain(it)}" },
                if (groupsAt != null && commitAt != null) "first commit +${plain((commitAt - groupsAt).coerceAtLeast(0))}" else null,
            )
            if (marks.isNotEmpty()) add("first batch (s after the groups): " + marks.joinToString(" · "))
        }
    }

    private fun Long?.orDash(): String = if (this == null) DASH else plain(this)

    private fun plain(ms: Long): String = hundredthsText(hundredths(ms))

    private fun seconds(ms: Long): String = plain(ms) + " s"

    private fun group(count: Int): String = String.format(Locale.ROOT, "%,d", count)

    private fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / BYTES_PER_MB)

    private const val DASH = "-"

    /**
     * The notes the writer and the importer leave for the strip, under the names they write them by: this module
     * cannot see theirs, so the two names are said here as well.
     */
    private const val NOTE_WRITER_CPU = "writer cpu"
    private const val NOTE_PROCESS_CPU = "process cpu"
    private const val NOTE_THREAD_CPU = "thread cpu"
    private const val NOTE_WRITER_SCHED = "writer sched"
    private const val NOTE_CURVE = "rows curve"
    private const val NOTE_PROCESS_IO = "process io"
    private const val NOTE_ROOM_THREADS = "room threads"
    private const val NOTE_STREAMS = "streams"
    private const val NOTE_TOLD = "told"
    private const val NOTE_TX_BEGIN = "tx begin"
    private const val NOTE_TX_END = "tx end"
    private const val NOTE_DB_CONTEXT = "db context"

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

/**
 * One line of the strip, and what it is about, which the screen turns into a colour. The first line of a session also
 * carries its [summary]; the others carry none.
 */
data class StripLine(val text: String, val tone: StripTone, val summary: StripSummary? = null)
