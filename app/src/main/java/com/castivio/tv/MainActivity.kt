package com.castivio.tv

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.platform.AndroidDeviceCapabilities
import com.castivio.tv.gate.SplashGate
import com.castivio.tv.locale.AppLocale
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
 * The shell still runs on mock data. Activation now commits a real catalogue, but
 * reading it back onto Home is the next slice, so what a user sees after activating is
 * the same demo content as before — deliberately unchanged rather than half-rewired.
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

            CastivioTheme(performance = performance, motionLevel = motionLevel) {
                SplashGate(
                    onExit = { finish() },
                    home = {
                        ShellScreen(
                            motionLevel = motionLevel,
                            onMotionLevel = { motionLevel = it },
                        )
                    },
                )
            }
        }
    }
}
