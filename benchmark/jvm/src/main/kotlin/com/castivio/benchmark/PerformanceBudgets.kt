package com.castivio.benchmark

/**
 * Performance budgets — the single source of truth.
 *
 * A budget that isn't enforced is a comment, so every value here is asserted by
 * a test that fails the build when breached.
 *
 * ## Why these numbers look loose
 *
 * Shared CI runners vary by 2-3x between runs: noisy neighbours, different CPU
 * generations, cold JIT. A budget set at "5% above yesterday's measurement"
 * would flake every day and get disabled within a week — the classic way perf
 * gates die.
 *
 * So these are set to catch **algorithmic** regressions, not drift: someone
 * adding a `Regex` per playlist line, or collecting entries into a list, moves
 * these numbers by 10x and trips the gate immediately. A genuine 15% slowdown
 * will not be caught here — it is caught by the on-device tier, where the
 * hardware is fixed.
 *
 * Being explicit about that is the point. Two tiers, honestly labelled, beats
 * one tier that pretends to measure everything.
 */
object PerformanceBudgets {

    // ---------------------------------------------------------------- parsing

    /**
     * M3U entries parsed per second, single-threaded.
     *
     * Baseline on a CI runner is comfortably into six figures; the floor is set
     * far below that so only a structural regression trips it.
     */
    const val M3U_ENTRIES_PER_SECOND_MIN = 40_000

    /**
     * Peak retained heap while parsing [MEMORY_PROBE_ENTRIES] entries.
     *
     * This is the most important budget in the file. It is the executable form
     * of "the catalogue is never in memory": if anyone changes the parser to
     * accumulate a list, retained heap grows with the playlist and this fails.
     */
    const val M3U_RETAINED_HEAP_MB_MAX = 24
    const val MEMORY_PROBE_ENTRIES = 300_000

    /** XMLTV programmes parsed per second. */
    const val XMLTV_PROGRAMMES_PER_SECOND_MIN = 25_000

    /** Retained heap while streaming a large guide. Same reasoning as above. */
    const val XMLTV_RETAINED_HEAP_MB_MAX = 24
    const val XMLTV_PROBE_PROGRAMMES = 200_000

    /**
     * XMLTV timestamp conversions per second. Called once per programme, so a
     * `SimpleDateFormat` creeping in here would be felt across a whole guide.
     */
    const val XMLTV_TIMESTAMPS_PER_SECOND_MIN = 1_000_000

    // ---------------------------------------------------------------- importing

    /**
     * Entries per second through the whole import pipeline — parse, classify,
     * build the row, batch it — with the database stubbed out.
     *
     * It is the number that answers "how long until the catalogue is on disk",
     * minus the part SQLite owns.
     *
     * Baseline when written: ~500,000 entries/sec, so a 400,000 item provider
     * costs under a second of *our* work. The floor sits an order of magnitude
     * below that, which is where a structural regression lands and normal runner
     * noise does not.
     */
    const val IMPORT_ENTRIES_PER_SECOND_MIN = 50_000

    /**
     * Retained heap while importing [MEMORY_PROBE_ENTRIES] entries.
     *
     * The same guard as [M3U_RETAINED_HEAP_MB_MAX], one layer up. The engine may
     * hold a batch and a group index; it may never hold the catalogue. Anyone who
     * "helpfully" collects rows to insert them once at the end trips this.
     */
    const val IMPORT_RETAINED_HEAP_MB_MAX = 24

    // ------------------------------------------------------- on-device budgets
    // Declared here so the numbers live in one place, asserted by the
    // macrobenchmark tier on real hardware rather than by CI.

    /** Cold start to first frame, low-end box. */
    const val COLD_START_MS_MAX = 1_200

    /** Warm start to first frame. */
    const val WARM_START_MS_MAX = 500

    /** Cached playlist to a usable Home. */
    const val HOME_READY_MS_MAX = 400

    /** Our own overhead on a channel change, excluding provider time. */
    const val ZAP_OVERHEAD_MS_MAX = 200

    /** Search keystroke to rendered results. */
    const val SEARCH_LATENCY_MS_MAX = 50

    /** Frames slower than this are janky at 60 fps. */
    const val FRAME_BUDGET_MS = 16.6

    /** Share of frames allowed to miss the budget while scrolling. */
    const val JANK_PERCENT_MAX = 1.0

    /** Resident memory on a low-end box with a 400k library loaded. */
    const val RESIDENT_MEMORY_MB_MAX = 96

    /** ANRs are never acceptable; tracked from production vitals. */
    const val ANR_COUNT_MAX = 0
}
