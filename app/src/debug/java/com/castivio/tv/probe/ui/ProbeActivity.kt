package com.castivio.tv.probe.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.castivio.core.design.theme.CastivioTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The probe's own window: a second launcher icon, in debug builds only.
 *
 * It is a separate task from the application and has nothing to do with it but the process it shares and the
 * subscription it reads. It starts no import and opens no screen of the application; the application's `onCreate`
 * does no work, so opening this alone starts nothing else on the network.
 *
 * The screen stays on for the session: a phone that sleeps in the middle of a download measures its own sleep.
 */
@AndroidEntryPoint
class ProbeActivity : ComponentActivity() {

    private val model: ProbeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            // No clock for the backdrop to move by: the screen asks for it by name and it is drawn still.
            CastivioTheme(withBackground = false) {
                ProbeScreen(model)
            }
        }
    }
}
