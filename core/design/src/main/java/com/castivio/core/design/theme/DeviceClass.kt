package com.castivio.core.design.theme

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
 * layouts read a single [DeviceClass].
 *
 * There used to be a `scale` multiplier here, and it is gone. A television is
 * not a phone with everything 1.15 times bigger: the address grows from 28sp to
 * 42sp, a fifty per cent rise, while the standing notice barely moves and the
 * targets step 48 to 56. Those are three different decisions about three
 * different things, and one multiplier can only be wrong about all of them. What
 * changes between devices is decided per element, against measured space.
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
