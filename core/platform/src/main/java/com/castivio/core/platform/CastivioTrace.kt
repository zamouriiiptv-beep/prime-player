package com.castivio.core.platform

import androidx.tracing.Trace
import androidx.tracing.trace

/**
 * The names the app's trace sections are emitted under, and the only place they exist.
 *
 * ## Why a vocabulary rather than string literals at the call sites
 *
 * A macrobenchmark asks for a section **by name**: `TraceSectionMetric("Castivio.Fetch")`
 * matches a literal typed in `:benchmark:macro` against a literal typed in
 * `:feature:home`, and nothing connects the two. Rename one and the benchmark does not
 * fail — it reports zero occurrences, which reads as "this is fast now". A measurement
 * that silently becomes a measurement of nothing is worse than no measurement, because
 * somebody will act on it.
 *
 * So both sides read the constants below. A rename is one edit and the compiler carries
 * it to the benchmark.
 *
 * ## What it costs in a shipped build
 *
 * `Trace.beginSection` checks a flag the platform sets when a trace is being recorded
 * and returns. Nothing here allocates, nothing formats a string at runtime — the names
 * are compile-time constants — and no section is emitted unless somebody is recording.
 * The app is not instrumented in the sense of carrying a measurement system; it is
 * instrumented in the sense of being *able* to be measured by the platform's own.
 *
 * ## Where these are placed, and why only there
 *
 * Phase A named five boundaries and these are those five, not a sixth. Tracing inside a
 * loop that runs 400,000 times would cost more than the thing it measures and would
 * drown the trace buffer; every section below is crossed once per user action or once
 * per network round trip.
 *
 * `:domain` and `:data:parsing` carry none of this and must not: they are pure Kotlin,
 * the invariant script fails on an `androidx` import there, and the boundaries that
 * matter are all observable from the Android side of the same call.
 */
object CastivioTrace {

    /**
     * One section of the catalogue being fetched, from the first request to the last
     * commit. The outermost boundary Phase A identified: `LoadSection.load` is pure
     * Kotlin, so the section is opened around its collection in the view model, which
     * begins and ends at the same instants.
     */
    const val FETCH = "Castivio.Fetch"

    /**
     * One Xtream HTTP call, named by its action — `Castivio.Api.get_live_categories`,
     * `Castivio.Api.get_live_streams`. The action is the provider's own string, so the
     * trace distinguishes the one categories request from the N stream requests that
     * follow it without any counting on our side.
     */
    const val API = "Castivio.Api"

    /** One batch of rows committed to SQLite. Crossed once per flush, not per row. */
    const val COMMIT = "Castivio.Commit"

    /**
     * The first page a `PagingSource` returns after a query changes.
     *
     * Only the first: subsequent pages are prefetches nobody is waiting for, and a
     * section per page would measure scrolling rather than opening.
     */
    const val FIRST_PAGE = "Castivio.FirstPage"

    /**
     * The instant a section screen has content a viewer can act on.
     *
     * The metric Phase A called *time to first useful content*, and the one number on
     * this list that cannot be derived from the others: it is neither "the query
     * returned" nor "the fetch finished" but "there is something on screen".
     */
    const val FIRST_CONTENT = "Castivio.FirstContent"

    /** Whether the platform is recording. Exposed so a caller can skip building a name. */
    val isRecording: Boolean get() = Trace.isEnabled()

    /**
     * Marks an instant rather than a span.
     *
     * `TraceSectionMetric` measures sections, so an instant is emitted as a section of
     * negligible width: what is being read off it is *when* it happened relative to the
     * frame the benchmark started, not how long it took.
     */
    inline fun instant(name: String) = trace(name) { }
}
