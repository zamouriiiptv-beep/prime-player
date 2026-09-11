package com.castivio.benchmark.macro

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import com.castivio.core.platform.CastivioTrace
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What opening a section costs, read off the sections the app emits.
 *
 * ## Why trace sections and not a stopwatch
 *
 * Phase A named five boundaries and could time none of them: the parse rate was
 * measurable on the JVM, and everything that involved a device, a socket or SQLite was
 * not. A `TraceSectionMetric` reports the duration of a named section recorded by
 * `perfetto` during the run, which turns those five into numbers without the app timing
 * itself and without a measurement path that could drift from the real one.
 *
 * The names come from [CastivioTrace] rather than from string literals typed here. That
 * is not tidiness: a benchmark that asks for a section nobody emits any more does not
 * fail, it reports zero occurrences — which reads exactly like "this got faster".
 *
 * ## What each measurement answers
 *
 * | section | the Phase A question |
 * |---|---|
 * | `Castivio.Fetch` | how long a first-open of a section takes, end to end |
 * | `Castivio.Api.*` | the `1 + N` round trips, timed and separated by action |
 * | `Castivio.Commit` | SQLite's share, which the JVM benchmark deliberately stubs |
 * | `Castivio.FirstPage` | when the pager answered |
 * | `Castivio.FirstContent` | when a viewer first had something to act on |
 *
 * ## What this cannot do, and why it is written down here
 *
 * **A first open needs a provider.** These measurements are of a section the device has
 * already fetched unless the app under test is signed in to a real subscription. Nothing
 * in this repository holds credentials and nothing should — see the run instructions in
 * `benchmark/README.md`. On a device with no provider the fetch sections are absent and
 * the run reports the local-only half: the pager, the first content and the frames.
 *
 * Absent is reported as absent. It is never reported as zero.
 */
@RunWith(AndroidJUnit4::class)
class SectionBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    /**
     * Opening Live from a cold start: every boundary, in one trace.
     *
     * Cold because that is the case the user complains about, and because a warm run
     * would measure a page cache rather than the app.
     */
    @Test
    fun openLiveSection() = rule.measureRepeated(
        packageName = TARGET,
        metrics = listOf(
            FrameTimingMetric(),
            section(CastivioTrace.FETCH),
            section(CastivioTrace.FIRST_PAGE),
            section(CastivioTrace.FIRST_CONTENT),
            section(CastivioTrace.COMMIT),
            // Every Xtream action at once: the sum over the whole import, which read
            // against `Castivio.Fetch` says how much of a first open is the network.
            section("${CastivioTrace.API}.get_live_categories"),
            section("${CastivioTrace.API}.get_live_streams"),
        ),
        iterations = ITERATIONS,
        startupMode = StartupMode.COLD,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        openSection(LIVE_LABELS)
    }

    /** The same for Movies, which is the section a large provider makes expensive. */
    @Test
    fun openMoviesSection() = rule.measureRepeated(
        packageName = TARGET,
        metrics = listOf(
            FrameTimingMetric(),
            section(CastivioTrace.FETCH),
            section(CastivioTrace.FIRST_PAGE),
            section(CastivioTrace.FIRST_CONTENT),
            section("${CastivioTrace.API}.get_vod_categories"),
            section("${CastivioTrace.API}.get_vod_streams"),
        ),
        iterations = ITERATIONS,
        startupMode = StartupMode.COLD,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        openSection(MOVIE_LABELS)
    }

    /**
     * Series, where Phase A found `pageSeries()` does a full `GROUP BY` per page.
     *
     * Measured, not fixed: this phase is forbidden to optimise, and a number is what
     * decides whether the aggregated-table rewrite is worth its migration.
     */
    @Test
    fun openSeriesSection() = rule.measureRepeated(
        packageName = TARGET,
        metrics = listOf(
            FrameTimingMetric(),
            section(CastivioTrace.FETCH),
            section(CastivioTrace.FIRST_PAGE),
            section(CastivioTrace.FIRST_CONTENT),
        ),
        iterations = ITERATIONS,
        startupMode = StartupMode.COLD,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        openSection(SERIES_LABELS)
    }

    /**
     * Scrolling a section that is already on the device.
     *
     * The one measurement here that needs no provider, because it reads what SQLite and
     * the pager do rather than what the network does. `FrameTimingMetric` is the
     * platform's own frame durations, which is what `JANK_PERCENT_MAX` was always meant
     * to be asserted against.
     */
    @Test
    fun scrollSection() = rule.measureRepeated(
        packageName = TARGET,
        metrics = listOf(FrameTimingMetric(), section(CastivioTrace.FIRST_PAGE)),
        iterations = ITERATIONS,
        startupMode = StartupMode.WARM,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        openSection(LIVE_LABELS)
        val list = device.findObject(By.scrollable(true)) ?: return@measureRepeated
        list.setGestureMargin(device.displayWidth / GESTURE_MARGIN)
        repeat(SCROLLS) {
            list.scroll(Direction.DOWN, 1f)
            device.waitForIdle()
        }
    }

    /**
     * Presses whichever of [labels] this build actually shows.
     *
     * Several rather than one because the label is a translated string and the device
     * running the benchmark is in whatever locale it is in. Matching on text is fragile
     * for exactly that reason, and it is still the honest option: adding test tags to a
     * shipped screen to make a benchmark easier is a change to the app under test, which
     * is the one thing a measurement phase may not do.
     *
     * Returns without pressing if none is found, so a run against a build whose Home
     * looks different reports frames and nothing else rather than failing at the tap.
     */
    private fun androidx.benchmark.macro.MacrobenchmarkScope.openSection(labels: List<String>) {
        for (label in labels) {
            val target = device.findObject(By.textContains(label)) ?: continue
            target.click()
            device.wait(Until.hasObject(By.scrollable(true)), CONTENT_TIMEOUT_MS)
            return
        }
    }

    /**
     * One section's total duration across the iteration, in milliseconds.
     *
     * `Sum` rather than `First`: a fetch crosses `Castivio.Api` once per category, and
     * the total is the number Phase A needs. Where a section is crossed once — the fetch
     * itself, the first page, the first content — the sum is that one crossing.
     */
    private fun section(name: String) = TraceSectionMetric(
        sectionName = name,
        mode = TraceSectionMetric.Mode.Sum,
        targetPackageOnly = true,
    )

    private companion object {
        const val TARGET = "com.castivio.tv"

        /** Fewer than the startup run: each iteration re-downloads a section. */
        const val ITERATIONS = 5

        const val SCROLLS = 6
        const val GESTURE_MARGIN = 5
        const val CONTENT_TIMEOUT_MS = 60_000L

        val LIVE_LABELS = listOf("Live", "Channels", "القنوات", "مباشر")
        val MOVIE_LABELS = listOf("Movies", "Films", "أفلام")
        val SERIES_LABELS = listOf("Series", "Shows", "مسلسلات")
    }
}
