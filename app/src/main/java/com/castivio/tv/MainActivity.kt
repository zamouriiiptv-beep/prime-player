package com.castivio.tv

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.castivio.tv.data.DeviceIdentity
import com.castivio.tv.data.SourceStore
import com.castivio.tv.ui.CastivioTheme
import com.castivio.tv.ui.M3uScreen
import com.castivio.tv.ui.WelcomeScreen
import com.castivio.tv.ui.XtreamScreen

private enum class Screen { Welcome, Xtream, M3u }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val identity = DeviceIdentity.get(this)

        setContent {
            CastivioTheme {
                var screen by remember { mutableStateOf(Screen.Welcome) }

                when (screen) {
                    Screen.Welcome -> WelcomeScreen(
                        identity = identity,
                        onContinue = {
                            val source = SourceStore.load(this)
                            if (source == null) {
                                Toast.makeText(
                                    this,
                                    getString(R.string.no_playlist_yet),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                // TODO: next phase — load the playlist and open the channel list.
                                Toast.makeText(this, getString(R.string.saved), Toast.LENGTH_SHORT).show()
                            }
                        },
                        onXtream = { screen = Screen.Xtream },
                        onM3u = { screen = Screen.M3u },
                    )
                    Screen.Xtream -> XtreamScreen(
                        onSaved = { screen = Screen.Welcome },
                        onCancel = { screen = Screen.Welcome },
                    )
                    Screen.M3u -> M3uScreen(
                        onSaved = { screen = Screen.Welcome },
                        onCancel = { screen = Screen.Welcome },
                    )
                }
            }
        }
    }
}
