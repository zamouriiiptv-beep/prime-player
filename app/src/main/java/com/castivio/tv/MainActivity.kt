package com.castivio.tv

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.castivio.core.design.components.CastivioIntro
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.platform.AndroidDeviceCapabilities
import com.castivio.tv.gate.SplashGate
import com.castivio.tv.debug.CrashReportSheet
import com.castivio.tv.locale.AppLocale
import com.castivio.tv.locale.LocalLocaleController
import com.castivio.tv.locale.LocaleController
import com.castivio.tv.root.ExitGuard
import com.castivio.tv.shell.ShellScreen
import dagger.hilt.android.AndroidEntryPoint

/**
 * Hosts the gate, which decides everything else.
 *
 * The activity itself makes one decision — how much this box can afford to animate —
 * and hands the rest to [SplashGate]: licence, then catalogue, then either activation
 * or the shell. Which of those the user sees is a domain question, answered in nine
 * lines of pure code and merely rendered here.
 *
 * The shell now runs on the provider's own catalogue. Activation commits it and the
 * sections read it back — categories, channels, films and episodes — so what a user
 * sees after activating is what they subscribed to, and a press opens the provider's
 * stream in the real engine.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * The language is applied to the `Context` the activity is built on, before it
     * is built.
     *
     * This is the whole of mechanism C at the point of use, and it is here rather
     * than around the composition on purpose: a `CompositionLocalProvider` would
     * translate the screen and leave every notification, `Toast` and
     * accessibility announcement in the device's language instead of the user's.
     *
     * `ComponentActivity`, and no AppCompat. See `design/activation-spec.md`
     * §10.6.1.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    /**
     * The application's language, as a value the composition can watch.
     *
     * A field rather than a `remember`, because [onConfigurationChanged] arrives
     * outside composition and has to be able to write to it.
     */
    private val locale by lazy { LocaleController(this) }

    /**
     * The device's language changed, or the platform noticed ours did.
     *
     * Reached rather than recreated, because the manifest declares
     * `locale|layoutDirection`. Re-resolving is the whole response: for a user
     * with a stored choice it produces the same answer, and for one without it
     * produces the device's new language, in place and without a flash.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        locale.refreshFromSystem()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Dark on both bars, explicitly, with no scrim.
        //
        // The bare `enableEdgeToEdge()` this replaced uses `SystemBarStyle.auto`,
        // which follows the *system* theme: on a phone in light mode it paints a
        // light scrim behind the navigation bar, and a real-device review found
        // exactly that -- a white strip down the side of a landscape screen that
        // is otherwise Castivio's dark gradient. Castivio has one theme, so the
        // bars are told which one rather than left to guess.
        //
        // `Color.TRANSPARENT` for both scrims: on API 29 and up the bars are
        // genuinely transparent and the gradient runs under them, which is the
        // point of going edge to edge. Below 29 the platform substitutes its own
        // translucent scrim, and there is no API that prevents it.
        //
        // Drawing under the bars is only half of it. Content must not sit under
        // them, and that is `safeDrawing` on the screens themselves -- see
        // `ActivationSurface`. Hiding system UI and letting content be obscured
        // by it are different things, and only one of them is wanted here.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        // Measure once, then let the design system do less on a weak box.
        val performance = AndroidDeviceCapabilities(this).toPerformanceProfile()

        setContent {
            // The level the device can afford is the starting point; the user changes
            // it live in Settings, which is exactly what this build is here to validate.
            var motionLevel by remember { mutableStateOf(performance.suggestedMotion) }

            // The language, applied to the composition rather than by recreating
            // the activity.
            //
            // `attachBaseContext` has already applied it to the context this
            // activity was built on, which is what serves every launch and every
            // surface that is not a composition. This is the same decision handed
            // to the tree so that *changing* it does not need a new window: the
            // context, the configuration and the layout direction are re-provided
            // and `stringResource` re-reads. `LocalConfiguration` is what makes
            // that happen -- Compose's resource readers observe it, so providing a
            // new context without it would leave the old strings cached.
            // `localise`, not `wrap`. The difference is which end of the chain the
            // activity is on, and it is the difference between an app that starts
            // and one that does not -- see `AppLocale.localise`.
            val resolved = locale.current
            val localised = remember(resolved.tag) { AppLocale.localise(this, resolved) }
            val configuration = remember(resolved.tag) { localised.resources.configuration }

            CompositionLocalProvider(
                LocalLocaleController provides locale,
                LocalContext provides localised,
                LocalConfiguration provides configuration,
                // Taken from the configuration the platform resolved, not from a
                // list of right-to-left languages kept here. Two sources for one
                // fact is how they come to disagree.
                LocalLayoutDirection provides
                    if (configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) {
                        LayoutDirection.Rtl
                    } else {
                        LayoutDirection.Ltr
                    },
            ) {
                CastivioTheme(performance = performance, motionLevel = motionLevel) {
                    // Whether the mark has already played. `rememberSaveable`,
                    // so a rotation or a night-mode change does not replay it:
                    // the intro belongs to *starting Castivio*, and turning a
                    // phone sideways is not that.
                    var introDone by rememberSaveable { mutableStateOf(false) }

                    Box(Modifier.fillMaxSize()) {
                        // One exit question, above the gate and the shell alike.
                        // Both used to call `finish()` directly and only the
                        // shell asked first -- which the gate makes unreachable
                        // on a fresh install. See `ExitGuard`.
                        ExitGuard(onExit = { finish() }) { askToExit ->
                            SplashGate(
                                onExit = askToExit,
                                home = {
                                    ShellScreen(
                                        motionLevel = motionLevel,
                                        onMotionLevel = { motionLevel = it },
                                        onExit = askToExit,
                                    )
                                },
                            )
                        }

                        // Last, so it is over everything, and gone the moment it
                        // is done -- a transparent full-screen box that stayed
                        // would eat every touch that landed on it.
                        if (!introDone) {
                            CastivioIntro(onFinished = { introDone = true })
                        }

                        // Above even the intro, because a build that crashed on launch
                        // would otherwise hide its own report behind the thing that
                        // crashed. Debug only, and it draws nothing when there is no
                        // report to show.
                        CrashReportSheet()
                    }
                }
            }
        }
    }
}
