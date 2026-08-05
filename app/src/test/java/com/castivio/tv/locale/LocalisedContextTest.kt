package com.castivio.tv.locale

import android.app.Activity
import com.castivio.core.common.locale.CastivioLanguage
import com.castivio.core.common.locale.LanguagePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The context given to the composition is still the activity.
 *
 * ## What went wrong, so that it cannot go wrong again quietly
 *
 * The language is handed to the composition as a `Context` with different
 * resources, provided as `LocalContext`. The first version of that used
 * `AppLocale.wrap`, which ends in `createConfigurationContext` — and that returns
 * a fresh `ContextImpl` rather than a wrapper, so the activity vanished from the
 * chain.
 *
 * Nothing on screen depends on the activity being reachable. Everything that
 * *builds* a screen does: `hiltViewModel()` walks `LocalContext` for an activity
 * and throws when there is none, so the first composition threw and the process
 * died before its first frame.
 *
 * `StartupTest` catches that too, by starting the whole application. This catches
 * it at the one line that decides it, and says why in the failure message — which
 * is the difference between a five minute fix and an afternoon.
 */
@RunWith(RobolectricTestRunner::class)
class LocalisedContextTest {

    private fun activity(): Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test
    fun `the activity is still reachable through the localised context`() {
        val host = activity()

        val localised = AppLocale.localise(host, LanguagePolicy.fallback())

        assertSame(
            "the localised context lost the activity, so hiltViewModel() will throw " +
                "and the app will die before its first frame",
            host,
            localised.findActivity(),
        )
    }

    @Test
    fun `and through it in every language, not only the default`() {
        val host = activity()

        for (language in CastivioLanguage.entries) {
            val resolved = LanguagePolicy.choose(language, emptyList())
            assertNotNull(
                "the localised context lost the activity for ${resolved.tag}",
                AppLocale.localise(host, resolved).findActivity(),
            )
        }
    }

    @Test
    fun `resources come from the chosen language, not the activity's`() {
        val host = activity()
        val arabic = LanguagePolicy.choose(CastivioLanguage.Arabic, emptyList())

        val localised = AppLocale.localise(host, arabic)

        assertEquals(
            "the localised context is not actually localised, so nothing would translate",
            "ar",
            localised.resources.configuration.locales[0].language,
        )
    }

    @Test
    fun `the layout direction follows the language`() {
        val host = activity()

        val arabic = AppLocale.localise(host, LanguagePolicy.choose(CastivioLanguage.Arabic, emptyList()))
        val french = AppLocale.localise(host, LanguagePolicy.choose(CastivioLanguage.French, emptyList()))

        assertEquals(
            "Arabic did not resolve right to left",
            android.view.View.LAYOUT_DIRECTION_RTL,
            arabic.resources.configuration.layoutDirection,
        )
        assertEquals(
            "French did not resolve left to right",
            android.view.View.LAYOUT_DIRECTION_LTR,
            french.resources.configuration.layoutDirection,
        )
    }
}
