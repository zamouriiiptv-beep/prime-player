package com.castivio.tv.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp

/**
 * What kind of screen are we on?
 *
 * Castivio must feel native on a 55" television driven by a D-pad *and* on a
 * phone held sideways. Rather than sprinkling `if (isTv)` through the screens,
 * layouts read a single [DeviceClass] and the scale factors below.
 */
enum class DeviceClass {
    /** Phone, typically landscape for playback. */
    Compact,
    /** Large phone or small tablet. */
    Medium,
    /** Tablet, desktop window, Chromebook. */
    Expanded,
    /** Android TV, Google TV, Fire TV — leanback, D-pad driven, 10ft away. */
    Television;

    val isTv: Boolean get() = this == Television

    /**
     * Multiplier for type and control sizes. A television is viewed from ~3m,
     * so everything grows; a compact phone shrinks slightly to fit.
     */
    val scale: Float
        get() = when (this) {
            Compact -> 0.92f
            Medium -> 1.0f
            Expanded -> 1.05f
            Television -> 1.15f
        }

    /** Screen edge inset, accounting for TV overscan. */
    val screenPadding: Dp
        get() = if (isTv) Spacing.tvOverscan else Spacing.screen

    /** Columns for a poster/channel grid at this size. */
    val gridColumns: Int
        get() = when (this) {
            Compact -> 3
            Medium -> 4
            Expanded -> 5
            Television -> 6
        }
}

/** Resolves the current [DeviceClass] from UI mode and window width. */
@Composable
fun rememberDeviceClass(): DeviceClass {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val widthDp = configuration.screenWidthDp
    return remember(widthDp, configuration.uiMode) {
        if (context.isTelevision()) {
            DeviceClass.Television
        } else {
            when {
                widthDp < 600 -> DeviceClass.Compact
                widthDp < 840 -> DeviceClass.Medium
                else -> DeviceClass.Expanded
            }
        }
    }
}

private fun Context.isTelevision(): Boolean {
    val uiMode = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
    if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) return true
    val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}
