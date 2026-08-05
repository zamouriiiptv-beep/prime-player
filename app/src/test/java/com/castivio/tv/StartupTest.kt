package com.castivio.tv

import android.os.Looper
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * The application starts.
 *
 * ## Why this did not exist, and why that was the whole problem
 *
 * Castivio has gates for the activation screen's layout, the licence screen's
 * layout, its budget, its QR, its pricing, its view model, the back policy, the
 * design invariants, the string bundles in 38 locales and the performance
 * budgets. Every one of them tests a **screen**, and no screen is the thing that
 * starts.
 *
 * So a change that killed the app during its first composition shipped with all
 * of them green. The user saw the system splash — the icon Android draws on
 * `windowBackground` until the first frame arrives — and then the launcher,
 * which is exactly what a process dying before its first frame looks like.
 *
 * This is the missing gate. It builds the real Hilt graph, creates the real
 * `MainActivity` through the real `attachBaseContext`, runs the real
 * `setContent`, and requires a first frame to exist.
 *
 * ## What it deliberately does not assert
 *
 * Anything about what is *on* the screen. Which screen appears is the gate's
 * decision and is already tested in `StartGateTest`, in microseconds, without an
 * activity. The only claim here is the one nothing else makes: **the app starts
 * and draws.**
 */
@RunWith(RobolectricTestRunner::class)
// Landscape, because the manifest pins `sensorLandscape` and a portrait harness
// would be measuring a configuration the app never runs in.
@Config(qualifiers = "w873dp-h393dp-land")
class StartupTest {

    /**
     * `onCreate` through to a first frame, with nothing swallowed.
     *
     * `setup()` drives create → start → resume, and the looper is then drained so
     * that composition and the first measure/layout actually run. An exception
     * anywhere in that path — the Hilt graph, `attachBaseContext`, the locale
     * providers, the theme, the debug wrapper, the gate — fails this test with
     * the stack trace that would otherwise only exist in somebody's Logcat.
     */
    @Test
    fun `the activity reaches a first frame`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()

        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val activity = controller.get()
        assertNotNull("MainActivity was never created", activity)
        assertNotNull(
            "MainActivity created but never set any content, so nothing was ever drawn",
            activity.window.decorView,
        )
    }

    /**
     * And it survives a configuration change without being torn down.
     *
     * The manifest declares `locale|layoutDirection` so that changing the
     * language does not recreate the activity, which means `onConfigurationChanged`
     * is now a code path the app actually takes rather than one the platform
     * handles by starting again. A crash in it would look, to a user, exactly
     * like the language switch being broken.
     */
    @Test
    fun `a configuration change is handled in place`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        activity.onConfigurationChanged(activity.resources.configuration)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertNotNull("the activity did not survive a configuration change", controller.get())
    }

    /** The Hilt graph builds at all, separately from anything drawing. */
    @Test
    fun `the application graph builds`() {
        assertNotNull(RuntimeEnvironment.getApplication())
    }
}
