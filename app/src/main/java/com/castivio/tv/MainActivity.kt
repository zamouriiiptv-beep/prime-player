package com.castivio.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.platform.AndroidDeviceCapabilities
import com.castivio.tv.shell.ShellScreen
import dagger.hilt.android.AndroidEntryPoint

/**
 * Hosts the shell.
 *
 * For this build the shell runs on mock data so the experience can be judged on a
 * real phone before a provider is wired in. The activation flow and the real Home,
 * both of which need a ViewModel and the repository, land in later slices; the
 * pre-modular activation screens stay in the tree until then, unreferenced.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Measure once, then let the design system do less on a weak box.
        val performance = AndroidDeviceCapabilities(this).toPerformanceProfile()

        setContent {
            // The level the device can afford is the starting point; the user changes
            // it live in Settings, which is exactly what this build is here to validate.
            var motionLevel by remember { mutableStateOf(performance.suggestedMotion) }

            CastivioTheme(performance = performance, motionLevel = motionLevel) {
                ShellScreen(
                    motionLevel = motionLevel,
                    onMotionLevel = { motionLevel = it },
                )
            }
        }
    }
}
