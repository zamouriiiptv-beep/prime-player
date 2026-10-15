package com.castivio.core.platform

/**
 * One stretch of a run between two moments it passed, named by the moment that ended it.
 *
 * `streams 28.12` is the time from the moment before it to the last stream arriving, and
 * the last span, [DONE], runs from the last moment to the full load. They follow one
 * another and never overlap, so together they are the full load, cut -- which is what
 * makes them addable where the times of things that ran at once are not.
 */
data class RunSpan(
    val label: String,
    /** The moment that ended it; null for [DONE], which the full load ends. */
    val phase: PerfPhase?,
    val ms: Long,
) {
    companion object {
        /** The span from the last moment the run reported to the full load. */
        const val DONE = "done"
    }
}

/**
 * The spans between the moments this run reached, in the order it reached them, closed by
 * the full load when there is one. They add up to [SectionReport.fullLoadMs] exactly.
 *
 * A moment reported earlier than the one before it -- which no path does today -- is a span
 * of zero rather than a negative one, and does not move the clock back.
 */
fun SectionReport.spans(): List<RunSpan> {
    if (phases.isEmpty()) return emptyList()
    val cut = ArrayList<RunSpan>(phases.size + 1)
    var previous = 0L
    for (reached in phases) {
        cut += RunSpan(reached.phase.label, reached.phase, (reached.atMs - previous).coerceAtLeast(0L))
        previous = maxOf(previous, reached.atMs)
    }
    fullLoadMs?.let { cut += RunSpan(RunSpan.DONE, null, (it - previous).coerceAtLeast(0L)) }
    return cut
}

/** A named part of a time, in milliseconds. */
data class TimePart(val label: String, val ms: Long)

/**
 * The reader's side of a pass, part by part: asking, the bytes arriving, parsing, and
 * waiting for the writer to take a batch. They add up to [PassTimes.readerMs] exactly,
 * parsing being what is left of it.
 */
fun PassTimes.readerParts(): List<TimePart> = listOf(
    TimePart("ask", askMs),
    TimePart("network", readMs),
    TimePart("parse", parseMs),
    TimePart("waiting for save", handOverMs),
)

/**
 * The writer's side of [pass], part by part, adding up to the pass's window.
 *
 * The rows are split into the table's and the search index's where the split belongs to
 * this pass alone: the writer times the two per row but reports them for the whole run, so
 * they are this pass's only when the run had no other. With two passes the writer's time
 * is one figure, `write`, and the split is left to the log's database line. Whatever the
 * named parts do not cover is `other`: the writer's bookkeeping between them.
 */
fun SectionReport.writerParts(pass: PassTimes): List<TimePart> {
    val rows = databaseSteps[DatabaseStep.ROWS]
    val writing = if (passes.size == 1 && rows != null) {
        listOfNotNull(
            TimePart(DatabaseStep.ROWS.label, rows),
            databaseSteps[DatabaseStep.SEARCH_ROWS]?.let { TimePart(DatabaseStep.SEARCH_ROWS.label, it) },
        )
    } else {
        listOf(TimePart(WRITE, pass.writeMs))
    }
    val named = writing + TimePart(COMMIT, pass.commitMs) + TimePart(IDLE, pass.idleMs)
    return named + TimePart(OTHER, (pass.windowMs - named.sumOf { it.ms }).coerceAtLeast(0L))
}

/**
 * What the writer does after the last row, in order: the old generation pruned, every
 * category counted, the search index rebuilt. Null for a step this run did not take -- an
 * appending import prunes nothing and rebuilds nothing.
 */
fun SectionReport.finishParts(): List<Pair<String, Long?>> =
    FINISH_STEPS.map { step -> step.label to databaseSteps[step] }

/** Milliseconds as hundredths of a second, halves up: the precision every figure is given in. */
fun hundredths(ms: Long): Long = (ms + 5).floorDiv(10L)

/** Hundredths of a second as `36.85`, with a point whatever the device's language. */
fun hundredthsText(hundredths: Long): String {
    if (hundredths < 0) return "-" + hundredthsText(-hundredths)
    val fraction = hundredths % 100
    return "${hundredths / 100}.${if (fraction < 10) "0" else ""}$fraction"
}

/**
 * [partsMs] in hundredths of a second that add up to exactly the hundredths of [wholeMs],
 * each within one hundredth of its own rounding.
 *
 * Rounded one by one, parts do not promise that: three spans of 4 ms are 0.00 each and 0.01
 * together, and a reader adding the legend up would find the full load a hundredth out. So
 * every part is rounded down, and the hundredths still missing go to the parts that lost the
 * most -- the largest-remainder rule. When the parts do not add up to the whole in the first
 * place, there is nothing to keep, and each is rounded on its own.
 */
fun hundredthsAddingUp(partsMs: List<Long>, wholeMs: Long): List<Long> {
    if (partsMs.any { it < 0 } || partsMs.sum() != wholeMs) return partsMs.map(::hundredths)
    val shares = LongArray(partsMs.size) { partsMs[it] / 10 }
    val missing = hundredths(wholeMs) - shares.sum()
    if (missing < 0 || missing > partsMs.size) return partsMs.map(::hundredths)
    partsMs.indices
        .sortedWith(compareByDescending<Int> { partsMs[it] % 10 }.thenBy { it })
        .take(missing.toInt())
        .forEach { shares[it]++ }
    return shares.toList()
}

private val FINISH_STEPS = listOf(DatabaseStep.PRUNE, DatabaseStep.COUNTS, DatabaseStep.SEARCH)

private const val WRITE = "write"
private const val COMMIT = "commit"
private const val IDLE = "idle"
private const val OTHER = "other"
