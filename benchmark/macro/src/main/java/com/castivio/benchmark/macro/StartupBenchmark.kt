package com.castivio.benchmark.macro

import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How long Castivio takes to draw its first frame, measured by the platform.
 *
 * ## Why these numbers cannot come from inside the app
 *
 * Cold start begins before any Castivio code runs — process fork, class loading,
 * `Application.onCreate`, the activity's own creation — so nothing the app can time
 * covers the interval a user actually experiences. `StartupTimingMetric` reads the
 * platform's own `ActivityManager` reporting instead: `timeToInitialDisplay` is the
 * system's own definition of the first frame, and it is the same measurement Play
 * Console vitals shows.
 *
 * That is the whole argument for a macrobenchmark module over an instrumented test with
 * a stopwatch in it: the numbers come from the operating system, not from us.
 *
 * ## What the three modes mean
 *
 * - [StartupMode.COLD] — the process is killed and the page cache dropped first. The
 *   worst case, and the one a user gets after a reboot or after the launcher evicts the
 *   app, which on a 2 GB stick is most of the time.
 * - [StartupMode.WARM] — the process is killed but the file cache is warm.
 * - [StartupMode.HOT] — the process survives; only the activity is recreated.
 *
 * `PerformanceBudgets` declares 1,200 ms and 500 ms for the first two. Those figures
 * have been in the repository since the beginning with a comment saying the
 * macrobenchmark tier asserts them; until this file existed, nothing did.
 *
 * ## Why the budgets are not asserted here
 *
 * Deliberately, and only for Phase B. This phase exists to establish a **before**
 * measurement, and a gate that fails the first time it runs tells nobody anything about
 * the code — it tells them the hardware is not what the number was written for. The
 * numbers are reported; whether they become a gate, and at what value on what device, is
 * a decision for after they are known.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldStart() = measure(StartupMode.COLD)

    @Test
    fun warmStart() = measure(StartupMode.WARM)

    @Test
    fun hotStart() = measure(StartupMode.HOT)

    /**
     * @param mode what is thrown away before each of the [ITERATIONS] runs.
     *
     * Ten iterations because startup is noisy on television hardware — a background
     * service, a network callback, the system finishing its own boot — and the metric
     * reports median and both ends, which only means something across a run of them.
     */
    private fun measure(mode: StartupMode) = rule.measureRepeated(
        packageName = TARGET,
        metrics = listOf(StartupTimingMetric()),
        iterations = ITERATIONS,
        startupMode = mode,
        setupBlock = {
            // From the launcher every time, so the measurement includes the work a real
            // launch does and not the shortcut an already-warm task would take.
            pressHome()
        },
    ) {
        startActivityAndWait()
        // The first frame is what `StartupTimingMetric` times, and it is drawn before
        // any content is read. Waiting for the window to settle keeps the *next*
        // iteration's teardown from racing composition, which otherwise shows up as a
        // wide spread rather than as a wrong median.
        device.wait(Until.hasObject(By.pkg(TARGET).depth(0)), WINDOW_TIMEOUT_MS)
    }

    private companion object {
        const val TARGET = "com.castivio.tv"
        const val ITERATIONS = 10
        const val WINDOW_TIMEOUT_MS = 5_000L
    }
}
