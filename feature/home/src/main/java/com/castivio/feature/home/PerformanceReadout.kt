package com.castivio.feature.home

import com.castivio.core.platform.DatabaseStep
import com.castivio.core.platform.IndexCheck
import com.castivio.core.platform.PassTimes
import com.castivio.core.platform.PerfPhase
import com.castivio.core.platform.SectionReport
import com.castivio.core.platform.TimePart
import com.castivio.core.platform.finishParts
import com.castivio.core.platform.hundredths
import com.castivio.core.platform.hundredthsAddingUp
import com.castivio.core.platform.hundredthsText
import com.castivio.core.platform.readerParts
import com.castivio.core.platform.spans
import com.castivio.core.platform.writerParts
import java.util.Locale

/**
 * A run's report as the lines [PerformancePanel] draws, in the order it draws them.
 *
 * ## Why the panel does not read the report itself
 *
 * Because what it shows is decided, not merely printed: which requests are worth a row,
 * how the load's spans are cut, how wide each is drawn and how each figure is rounded.
 * Decided in composition, that is sorting and arithmetic on every recomposition of a screen
 * whose whole job is to measure; decided here, it runs once per report and is a unit test
 * rather than a photograph. The panel maps it to colours and draws it.
 *
 * ## What adds up, and what does not
 *
 * The bar's spans follow one another, and their figures add up to the full load to the
 * hundredth: they are rounded together for exactly that -- see `hundredthsAddingUp`. A
 * pass's two sides are drawn under a heading marked `∥` because they ran *at the same time*,
 * inside the bar's spans: the reader's parts add up to the reader's time and the writer's to
 * the pass's window, and neither is ever added to the bar or to the other. What the writer
 * did after the last row -- prune, counts, rebuild -- is the `then` line, inside the
 * `finished` span.
 *
 * ## What is shown, and what is left to the log
 *
 * Everything that has a moment behind it, and nothing that does not -- a line whose moment
 * was never reached is absent, never zero, for the reason [SectionReport] gives. The lists
 * that can outgrow a phone held sideways are cut short and say so: the slowest [MAX_CALLS]
 * actions, the [MAX_ORPHAN_IDS] category ids that orphaned the most streams, the commonest
 * [MAX_FAILURES] failures. The run's log lines (`adb logcat -s CastivioPerf`, and the
 * clipboard of a debuggable build) carry all of them.
 */
internal data class PerformanceReadout(
    /** `CHANNELS · run 1 · COLD · REPLACE · TTID 0.17` -- so two photographs are never confused. */
    val title: String,
    val lines: List<ReadoutLine>,
)

/** One line of the readout. */
internal sealed interface ReadoutLine {

    /** The running clock. Its figure is the panel's own, redrawn while the load runs. */
    data object Elapsed : ReadoutLine

    /** A label and its figure. */
    data class Figure(
        val label: String,
        val value: String,
        val ink: ReadoutInk,
        /** A part of the figure above it: drawn smaller, and set in from the start edge. */
        val part: Boolean = false,
        /** The first line of a group. */
        val opensGroup: Boolean = false,
    ) : ReadoutLine

    /** A fact that is a sentence rather than a figure: why the streams came the way they did. */
    data class Note(val text: String) : ReadoutLine

    /** The load's spans to scale, end to end: drawn only once there is an end. */
    data class Cuts(val spans: List<PhaseSpan>) : ReadoutLine

    /** The same spans named, each with its duration. */
    data class Legend(val spans: List<PhaseSpan>) : ReadoutLine

    /**
     * The heading over a pass's two sides: how long the pass took, and that what follows
     * happened at the same time, inside the bar's spans rather than after them.
     */
    data class Parallel(val pieces: List<Piece>) : ReadoutLine

    /** A line under a short tag in the start column: `reader`, `then`, `calls`, `records`... */
    data class Tagged(
        /** Empty for a line that continues the group above it. */
        val tag: String,
        val pieces: List<Piece>,
        val ink: ReadoutInk = ReadoutInk.QUIET,
        /** One side of a pass: tagged in the parallel heading's colour. */
        val parallel: Boolean = false,
        /** The first line of a group. */
        val opensGroup: Boolean = false,
    ) : ReadoutLine
}

/** A run of a line's text. A [figure] is a time, and is drawn in the readout's figure face. */
internal data class Piece(val text: String, val figure: Boolean = false)

/** A line's text, whole, as somebody reading it aloud would say it. */
internal val List<Piece>.text: String get() = joinToString("") { it.text }

/** How loudly a line is drawn. Mapped to the theme by the panel. */
internal enum class ReadoutInk { STRONG, VARIANT, QUIET, WARNING }

/**
 * The time between two moments the run passed, named by the moment that ended it.
 *
 * `streams 8.66` is the time from the moment before it to the last stream arriving. The
 * last span, `done`, runs from the last moment to the full load. Taken together they are
 * the full load, cut -- which is what makes them addable where the parallel figures are not.
 */
internal data class PhaseSpan(
    val label: String,
    val ms: Long,
    /** `8.66`, in seconds. The unit is the readout's, stated once by the full load above it. */
    val figure: String,
    val tone: SpanTone,
    /**
     * Its share of the bar: [ms], raised to a floor so a span of a few milliseconds is still
     * a visible mark rather than nothing. Never zero -- a layout weight cannot be.
     */
    val weight: Float,
)

/** The colour a span is drawn in: an identity per moment, the same in every run. */
internal enum class SpanTone { QUIET, PRIMARY, LIVE, SECONDARY_SOFT, SECONDARY }

/** The report, as the panel's lines. */
internal fun performanceReadout(report: SectionReport): PerformanceReadout {
    val spans = phaseSpans(report)
    val lines = buildList {
        if (report.running) add(ReadoutLine.Elapsed)

        report.fullLoadMs?.let { add(ReadoutLine.Figure("Full Load", seconds(it), ReadoutInk.STRONG)) }
        // To scale only against an end. A run still going has no total to divide by, and a
        // bar drawn against the time so far would be a second progress bar under the gate's,
        // advancing for a different reason.
        if (report.fullLoadMs != null && spans.isNotEmpty()) add(ReadoutLine.Cuts(spans))
        if (spans.isNotEmpty()) add(ReadoutLine.Legend(spans))
        report.firstContentMs?.let { add(ReadoutLine.Figure("TTFC · First Content", seconds(it), ReadoutInk.STRONG)) }

        report.passes.forEach { addAll(passLines(report, it)) }
        // Why the streams came category by category: the reply refused, failed, or left gaps.
        // A clean single reply says nothing the records below do not.
        if (report.passes.any { !it.oneReply }) report.notes.values.forEach { add(ReadoutLine.Note(it)) }
        // A playlist or a portal has no pass, and its rows' share of the database is said here.
        if (report.passes.isEmpty()) savingLine(report)?.let(::add)
        finishLine(report)?.let(::add)
        addAll(callLines(report))
        addAll(recordLines(report))
        addAll(searchLines(report))
        orphanLine(report)?.let(::add)
        failureLine(report)?.let(::add)
    }
    return PerformanceReadout(title = title(report), lines = lines)
}

/** The run's identity, with how it saved and when its screen first drew. */
private fun title(report: SectionReport): String = buildList {
    add(report.section.name)
    add("run ${report.run}")
    add(report.mode.name)
    addAll(report.saveModes)
    report.ttidMs?.let { add("TTID ${hundredthsText(hundredths(it))}") }
}.joinToString(SEPARATOR)

/** A pass: its window, then what the reader and the writer each did inside it. */
private fun passLines(report: SectionReport, pass: PassTimes): List<ReadoutLine> {
    val heading = buildList {
        add(
            Piece(
                if (pass.oneReply) {
                    "while receiving "
                } else {
                    "${count(pass.requests)} categories, ${pass.parallelReaders} at once, "
                },
            ),
        )
        add(Piece(seconds(pass.windowMs), figure = true))
        add(Piece(", at the same time:"))
    }
    // Several readers at once are summed, and their sum is longer than the window: four
    // people's hours exceed the hour they worked together. Said, so nobody compares them.
    val summed = if (pass.parallelReaders > 1) listOf(Piece("$SEPARATOR${pass.parallelReaders} summed")) else emptyList()
    return listOf(
        ReadoutLine.Parallel(heading),
        ReadoutLine.Tagged(READER, partsAddingUp(pass.readerParts(), pass.readerMs) + summed, parallel = true),
        ReadoutLine.Tagged(WRITER, partsAddingUp(report.writerParts(pass), pass.windowMs), parallel = true),
    )
}

/** The rows' share of the database, for an import with no pass to draw it under. */
private fun savingLine(report: SectionReport): ReadoutLine? {
    val rows = report.databaseSteps[DatabaseStep.ROWS] ?: return null
    val parts = listOfNotNull(
        TimePart(DatabaseStep.ROWS.label, rows),
        report.databaseSteps[DatabaseStep.SEARCH_ROWS]?.let { TimePart(DatabaseStep.SEARCH_ROWS.label, it) },
        report.databaseMs?.let { TimePart(COMMIT, it) },
    )
    return ReadoutLine.Tagged(SAVING, figures(parts.map { it.label to it.ms }) + Piece(UNIT), opensGroup = true)
}

/** What the writer did after the last row, in order; a step not taken is a dash. */
private fun finishLine(report: SectionReport): ReadoutLine? {
    val steps = report.finishParts()
    if (steps.none { it.second != null }) return null
    return ReadoutLine.Tagged(THEN, figures(steps) + Piece(UNIT), opensGroup = true)
}

/**
 * Each action's requests: their time, their wait for an answer, their size. Before the run
 * settles there is no per-action time yet, so the attempts so far are counted instead.
 */
private fun callLines(report: SectionReport): List<ReadoutLine> {
    if (report.calls.isNotEmpty()) {
        val lines = report.calls.take(MAX_CALLS).mapIndexed { index, call ->
            val pieces = buildList {
                add(Piece("${call.action} ×${count(call.count)} "))
                add(Piece(seconds(call.totalMs), figure = true))
                add(Piece("${SEPARATOR}wait "))
                add(Piece(hundredthsText(hundredths(call.waitMs)), figure = true))
                add(Piece("$SEPARATOR${formatBytes(call.bytes)}"))
                if (call.failed > 0) add(Piece("${SEPARATOR}failed ${count(call.failed)}"))
            }
            ReadoutLine.Tagged(if (index == 0) CALLS else "", pieces, opensGroup = index == 0)
        }
        val left = report.calls.size - MAX_CALLS
        return if (left > 0) lines + ReadoutLine.Note("+$left more in the log") else lines
    }
    // A playlist's or a portal's window, which is counted but not by action.
    report.serverMs?.let { ms ->
        val pieces = buildList {
            add(Piece("${count(report.requests ?: 0)} in "))
            add(Piece(seconds(ms), figure = true))
            report.serverWaitMs?.let {
                add(Piece("${SEPARATOR}wait "))
                add(Piece(hundredthsText(hundredths(it)), figure = true))
            }
        }
        return listOf(ReadoutLine.Tagged(CALLS, pieces, opensGroup = true))
    }
    // While the import runs.
    report.requestsStarted?.let { started ->
        val text = "${count(started)} started$SEPARATOR${count(report.requestsFailed ?: 0)} failed" +
            "$SEPARATOR${count(report.retries ?: 0)} retried"
        return listOf(ReadoutLine.Tagged(CALLS, listOf(Piece(text)), opensGroup = true))
    }
    return emptyList()
}

/**
 * The records at each stage: what the reply held, what was filed under a category, what was
 * left an orphan, what was written by kind -- and what the database holds of those kinds
 * once the import has finished, which is the same when every row written was a new one.
 */
private fun recordLines(report: SectionReport): List<ReadoutLine> {
    val counts = report.recordCounts
    if (counts == null) {
        // A playlist or a portal: the importer's own running totals.
        val saved = report.records ?: return emptyList()
        val text = "${count(saved)} saved$SEPARATOR${count(report.categories ?: 0)} categories"
        return listOf(ReadoutLine.Tagged(RECORDS, listOf(Piece(text)), opensGroup = true))
    }
    val lines = ArrayList<ReadoutLine>(3)
    lines += ReadoutLine.Tagged(
        RECORDS,
        listOf(Piece("reply ${count(counts.parsed)} → filed ${count(counts.filed)}${SEPARATOR}orphans ${count(counts.orphans)}")),
        opensGroup = true,
    )
    val written = "written " + counts.written.byKind()
    val stored = report.checkAfter?.rows?.let { rows -> counts.written.keys.associateWith { rows[it] ?: 0 } }
    when {
        stored == null -> lines += ReadoutLine.Tagged("", listOf(Piece(written)))
        stored == counts.written -> lines += ReadoutLine.Tagged("", listOf(Piece("$written${SEPARATOR}in DB the same")))
        else -> {
            lines += ReadoutLine.Tagged("", listOf(Piece(written)))
            lines += ReadoutLine.Tagged("", listOf(Piece("in DB " + stored.byKind())))
        }
    }
    return lines
}

/**
 * The search index before the import and after it, from the checks a debuggable build runs:
 * how many entries it holds for how many rows, how many rows it holds more than once, and a
 * sample search the app's own way -- its results, for how many different rows.
 *
 * Every row has one entry when the index is in step. An index that gained an entry per row
 * on a reload, or a search that returns a row twice, is the reading this exists to take; it
 * is drawn in the warning colour, and nothing here changes it.
 */
private fun searchLines(report: SectionReport): List<ReadoutLine> {
    val before = report.checkBefore
    val after = report.checkAfter
    if (before == null && after == null) return emptyList()
    // The check after is still to come while the run goes on; once it has ended -- and for
    // the check before, which comes first -- a check that is missing was not taken.
    fun value(check: IndexCheck?, pending: Boolean, read: (IndexCheck) -> Int?): String =
        check?.let(read)?.let(::count) ?: if (check == null && pending) PENDING else ABSENT
    fun change(read: (IndexCheck) -> Int?) =
        "${value(before, pending = false, read)} → ${value(after, pending = report.running, read)}"
    fun rows(read: (IndexCheck) -> Int?): String {
        val then = value(before, pending = false, read)
        val now = value(after, pending = report.running, read)
        return if (then == now) now else "$then → $now"
    }

    val latest = after ?: before
    val repeating = latest != null &&
        (latest.duplicated > 0 || (latest.sampleResults ?: 0) > (latest.sampleRows ?: 0))
    return listOf(
        ReadoutLine.Tagged(
            SEARCH,
            listOf(Piece("entries ${change { it.searchRows }} for ${rows { it.allRows }} rows")),
            opensGroup = true,
        ),
        ReadoutLine.Tagged(
            "",
            listOf(
                Piece(
                    "repeated ids ${change { it.duplicated }}" +
                        "${SEPARATOR}sample ${change { it.sampleResults }} for ${rows { it.sampleRows }} rows",
                ),
            ),
            ink = if (repeating) ReadoutInk.WARNING else ReadoutInk.QUIET,
        ),
    )
}

/** The category ids the orphans named, the commonest first; `none` for a stream that named none. */
private fun orphanLine(report: SectionReport): ReadoutLine? {
    val ids = report.recordCounts?.orphanCategories?.takeIf { it.isNotEmpty() } ?: return null
    val shown = ids.take(MAX_ORPHAN_IDS).joinToString(SEPARATOR) { (id, streams) -> "$id ×${count(streams)}" }
    val left = ids.size - MAX_ORPHAN_IDS
    val text = if (left > 0) "$shown$SEPARATOR+$left more" else shown
    return ReadoutLine.Tagged(ORPHANS, listOf(Piece(text)), ink = ReadoutInk.WARNING)
}

/** Failed attempts by reason, the commonest first, and how many were made again. */
private fun failureLine(report: SectionReport): ReadoutLine? {
    val failures = report.failureReasons.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
    val retried = report.retries ?: 0
    if (failures.isEmpty() && retried == 0) return null
    val text = buildList {
        failures.take(MAX_FAILURES).forEach { (reason, times) -> add("$reason ×${count(times)}") }
        if (failures.size > MAX_FAILURES) add("+${failures.size - MAX_FAILURES} more")
        if (retried > 0) add("retried ${count(retried)}")
    }.joinToString(SEPARATOR)
    return ReadoutLine.Tagged(FAILURES, listOf(Piece(text)), ink = ReadoutInk.WARNING, opensGroup = true)
}

/** [parts] as `network 2.85 · parse 1.95`, rounded so their figures add up to [wholeMs]'s. */
private fun partsAddingUp(parts: List<TimePart>, wholeMs: Long): List<Piece> {
    val shares = hundredthsAddingUp(parts.map { it.ms }, wholeMs)
    return buildList {
        parts.forEachIndexed { index, part ->
            add(Piece((if (index > 0) SEPARATOR else "") + part.label + " "))
            add(Piece(hundredthsText(shares[index]), figure = true))
        }
    }
}

/** Steps as `prune – · counts 4.83`, each rounded on its own; a step not taken is a dash. */
private fun figures(steps: List<Pair<String, Long?>>): List<Piece> = buildList {
    steps.forEachIndexed { index, (label, ms) ->
        add(Piece((if (index > 0) SEPARATOR else "") + label + " "))
        add(if (ms == null) Piece(ABSENT) else Piece(hundredthsText(hundredths(ms)), figure = true))
    }
}

/**
 * The spans between the moments the run reached, closed by the full load when there is one,
 * with figures that add up to it -- see [hundredthsAddingUp].
 */
private fun phaseSpans(report: SectionReport): List<PhaseSpan> {
    val cut = report.spans()
    if (cut.isEmpty()) return emptyList()
    val whole = cut.sumOf { it.ms }
    val shares = hundredthsAddingUp(cut.map { it.ms }, whole)
    val floor = (whole * MIN_SHARE).toFloat()
    return cut.mapIndexed { index, span ->
        PhaseSpan(
            label = span.label,
            ms = span.ms,
            figure = hundredthsText(shares[index]),
            tone = span.phase?.tone ?: SpanTone.QUIET,
            weight = maxOf(span.ms.toFloat(), floor, 1f),
        )
    }
}

/**
 * Each moment's colour. An identity, not a claim about what the time was spent on: the
 * span ended by `finished` is the writer's on a panel and mostly the download on a
 * playlist, and it is the same colour on both so the same name always looks the same. The
 * checks are the measurement's own time, and as quiet as the moments around them.
 */
private val PerfPhase.tone: SpanTone
    get() = when (this) {
        PerfPhase.IMPORT -> SpanTone.QUIET
        PerfPhase.CHECKED_BEFORE -> SpanTone.QUIET
        PerfPhase.CATEGORIES -> SpanTone.PRIMARY
        PerfPhase.GROUPS -> SpanTone.LIVE
        PerfPhase.FIRST_COMMIT -> SpanTone.SECONDARY_SOFT
        PerfPhase.STREAMS -> SpanTone.SECONDARY
        PerfPhase.FINISHED -> SpanTone.LIVE
        PerfPhase.CHECKED_AFTER -> SpanTone.QUIET
        PerfPhase.RECORDED -> SpanTone.QUIET
    }

/**
 * `8.37 s`, from milliseconds. Two decimals is the precision the clock actually has.
 *
 * Written with a point whatever the device's language. The device's own locale formats this
 * as `8,37` in Arabic and in most of Europe, and a figure that changes shape with the user's
 * language is a figure that gets transcribed wrongly into a report.
 */
internal fun seconds(ms: Long): String = hundredthsText(hundredths(ms)) + UNIT

/** `55,213`: grouped, with the same separator in every language, for the reason [seconds] gives. */
private fun count(value: Int): String = String.format(Locale.ROOT, "%,d", value)

/** `LIVE 54,130 · RADIO 1,083`. */
private fun Map<String, Int>.byKind(): String = entries.joinToString(SEPARATOR) { "${it.key} ${count(it.value)}" }

private const val SEPARATOR = " · "
private const val UNIT = " s"

/** A figure not reached yet, while the run goes on. */
private const val PENDING = "…"

/** A figure that does not exist: a step not taken, a check not made. */
private const val ABSENT = "–"

private const val READER = "reader"
private const val WRITER = "writer"
private const val THEN = "then"
private const val SAVING = "saving"
private const val CALLS = "calls"
private const val RECORDS = "records"
private const val SEARCH = "search"
private const val ORPHANS = "orphans"
private const val FAILURES = "failures"
private const val COMMIT = "commit"

/** The fewest of the bar a span is drawn as: about two pixels of a phone-width panel. */
private const val MIN_SHARE = 0.006

/** Actions shown under `calls`, the slowest first. The rest are counted in a note. */
internal const val MAX_CALLS = 3

/** Orphaned category ids shown, the commonest first. The log has every one. */
internal const val MAX_ORPHAN_IDS = 3

/** Failure reasons shown, the commonest first. */
internal const val MAX_FAILURES = 2
