package com.castivio.feature.home

import com.castivio.core.platform.ActionCount
import com.castivio.core.platform.DatabaseStep
import com.castivio.core.platform.PerfPhase
import com.castivio.core.platform.SectionReport
import java.util.Locale

/**
 * A run's report as the lines [PerformancePanel] draws, in the order it draws them.
 *
 * ## Why the panel does not read the report itself
 *
 * Because what it shows is decided, not merely printed: which requests are worth a row,
 * which failures, how the load's spans are cut and how wide each is drawn. Decided in
 * composition, that is sorting and arithmetic on every recomposition of a screen whose
 * whole job is to measure; decided here, it runs once per report and is a unit test
 * rather than a photograph. The panel maps it to colours and draws it.
 *
 * ## What is shown, and what is left to the log
 *
 * Everything that has a moment behind it, and nothing that does not — a line whose moment
 * was never reached is absent, never zero, for the reason [SectionReport] gives. Requests
 * are the one list that can outgrow a phone held sideways, so the panel shows the busiest
 * [MAX_ACTIONS] actions and [MAX_FAILURES] failure reasons and says how many it left out;
 * the log line of the same run (`adb logcat -s CastivioPerf`) carries all of them.
 */
internal data class PerformanceReadout(
    /** `CHANNELS · run 1 · COLD` — the run's identity, so two photographs are never confused. */
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
        /** The first line of a group — network, database, the import's counts. */
        val opensGroup: Boolean = false,
    ) : ReadoutLine

    /** A fact that is a sentence rather than a figure: which way the streams came. */
    data class Note(val text: String) : ReadoutLine

    /** The load's spans to scale, end to end: drawn only once there is an end. */
    data class Cuts(val spans: List<PhaseSpan>) : ReadoutLine

    /** The same spans named, each with its duration. */
    data class Legend(val spans: List<PhaseSpan>) : ReadoutLine
}

/** How loudly a line is drawn. Mapped to the theme by the panel. */
internal enum class ReadoutInk { STRONG, VARIANT, QUIET, WARNING }

/**
 * The time between two moments the run passed, named by the moment that ended it.
 *
 * `streams 8.66` is the time from the moment before it to the last stream arriving. The
 * last span, `done`, runs from the last moment to the full load. Taken together they are
 * the full load, cut — which is what makes them addable where the network and database
 * figures are not.
 */
internal data class PhaseSpan(
    val label: String,
    val ms: Long,
    /** `8.66`, in seconds. The unit is the readout's, stated once by the full load above it. */
    val figure: String,
    val tone: SpanTone,
    /**
     * Its share of the bar: [ms], raised to a floor so a span of a few milliseconds is still
     * a visible mark rather than nothing. Never zero — a layout weight cannot be.
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

        // TTID and TTFC together, in that order, because the gap between them is the
        // measurement Fast Content Loading is judged by.
        report.ttidMs?.let { add(ReadoutLine.Figure("TTID", seconds(it), ReadoutInk.STRONG)) }
        report.firstContentMs?.let { add(ReadoutLine.Figure("TTFC · First Content", seconds(it), ReadoutInk.STRONG)) }

        report.serverMs?.let { calls ->
            val wait = report.serverWaitMs?.let { " · wait ${seconds(it)}" }.orEmpty()
            add(ReadoutLine.Figure("Network", seconds(calls) + wait, ReadoutInk.VARIANT, opensGroup = true))
        }
        report.notes.values.forEach { add(ReadoutLine.Note(it)) }

        // The commits alone, under the name they have always had, so this reading can be
        // set beside one taken before the other steps were measured. The steps follow.
        report.databaseMs?.let { add(ReadoutLine.Figure("Database · commit", seconds(it), ReadoutInk.VARIANT, opensGroup = true)) }
        if (report.databaseSteps.isNotEmpty()) {
            add(
                ReadoutLine.Note(
                    DatabaseStep.entries
                        .mapNotNull { step -> report.databaseSteps[step]?.let { "${step.label} ${twoDecimals(it)}" } }
                        .joinToString(" · ") + " s",
                ),
            )
        }

        // The import window. Shown only once the import has reported, so a warm open --
        // which imports nothing -- draws none of these rather than a column of zeroes.
        report.categories?.let {
            add(ReadoutLine.Figure("Categories · Records", "$it · ${report.records ?: 0}", ReadoutInk.VARIANT, opensGroup = true))
        }
        report.requestsStarted?.let { started ->
            add(
                ReadoutLine.Figure(
                    "Requests  (fail · retry)",
                    "$started  (${report.requestsFailed ?: 0} · ${report.retries ?: 0})",
                    ReadoutInk.VARIANT,
                ),
            )
            val actions = report.byAction.entries
                .sortedWith(compareByDescending<Map.Entry<String, ActionCount>> { it.value.started }.thenBy { it.key })
            actions.take(MAX_ACTIONS).forEach { (action, count) ->
                add(ReadoutLine.Figure(action, "${count.started}  (${count.failed} · ${count.retried})", ReadoutInk.QUIET, part = true))
            }
            val failures = report.failureReasons.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            failures.take(MAX_FAILURES).forEach { (reason, count) ->
                add(ReadoutLine.Figure(reason, "×$count", ReadoutInk.WARNING, part = true))
            }
            val left = (actions.size - MAX_ACTIONS).coerceAtLeast(0) + (failures.size - MAX_FAILURES).coerceAtLeast(0)
            if (left > 0) add(ReadoutLine.Note("+$left more in the log (CastivioPerf)"))
        }

        // A limit, not a measurement, and only true while something can be in flight. After
        // the load it would read as four requests still out.
        report.concurrency?.takeIf { it > 0 && report.running }?.let {
            add(ReadoutLine.Figure("Concurrency", "$it in flight", ReadoutInk.QUIET))
        }
    }
    return PerformanceReadout(
        title = "${report.section.name} · run ${report.run} · ${report.mode.name}",
        lines = lines,
    )
}

/**
 * The spans between the moments the run reached, in the order it reached them, closed by
 * the full load when there is one.
 *
 * A moment reported earlier than the one before it — which no path does today — is drawn
 * as a span of zero rather than a negative one, and does not move the clock back.
 */
private fun phaseSpans(report: SectionReport): List<PhaseSpan> {
    if (report.phases.isEmpty()) return emptyList()
    val cut = ArrayList<Triple<String, Long, SpanTone>>(report.phases.size + 1)
    var previous = 0L
    for (reached in report.phases) {
        cut += Triple(reached.phase.label, (reached.atMs - previous).coerceAtLeast(0L), reached.phase.tone)
        previous = maxOf(previous, reached.atMs)
    }
    report.fullLoadMs?.let { cut += Triple(DONE, (it - previous).coerceAtLeast(0L), SpanTone.QUIET) }

    val floor = (cut.sumOf { it.second } * MIN_SHARE).toFloat()
    return cut.map { (label, ms, tone) ->
        PhaseSpan(label, ms, twoDecimals(ms), tone, weight = maxOf(ms.toFloat(), floor, 1f))
    }
}

/**
 * Each moment's colour. An identity, not a claim about what the time was spent on: the
 * span ended by `finished` is the writer's on a panel and mostly the download on a
 * playlist, and it is the same colour on both so the same name always looks the same.
 */
private val PerfPhase.tone: SpanTone
    get() = when (this) {
        PerfPhase.IMPORT -> SpanTone.QUIET
        PerfPhase.CATEGORIES -> SpanTone.PRIMARY
        PerfPhase.GROUPS -> SpanTone.LIVE
        PerfPhase.FIRST_COMMIT -> SpanTone.SECONDARY_SOFT
        PerfPhase.STREAMS -> SpanTone.SECONDARY
        PerfPhase.FINISHED -> SpanTone.LIVE
        PerfPhase.RECORDED -> SpanTone.QUIET
    }

/**
 * `8.37 s`, from milliseconds. Two decimals is the precision the clock actually has.
 *
 * [Locale.ROOT] so the separator is the one the rest of the readout is written in. The
 * device's own locale formats this as `8,37` in Arabic and in most of Europe, and a
 * figure that changes shape with the user's language is a figure that gets transcribed
 * wrongly into a report.
 */
internal fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.2f s", ms / 1000.0)

private fun twoDecimals(ms: Long): String = String.format(Locale.ROOT, "%.2f", ms / 1000.0)

/** The span from the last moment the run reported to the full load. */
private const val DONE = "done"

/** The fewest of the bar a span is drawn as: about two pixels of a phone-width panel. */
private const val MIN_SHARE = 0.006

/** Action rows shown under the request total. The rest are counted in a note. */
internal const val MAX_ACTIONS = 3

/** Failure-reason rows shown under the actions. The rest are counted in a note. */
internal const val MAX_FAILURES = 2
