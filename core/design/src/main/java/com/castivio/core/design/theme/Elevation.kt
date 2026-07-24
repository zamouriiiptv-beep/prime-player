package com.castivio.core.design.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Elevation.
 *
 * Castivio uses light, wide, low-opacity shadows — depth is suggested, never
 * announced. A heavy drop shadow reads as cheap, so the maximum is modest and
 * focus (not resting state) is what lifts an element off the page.
 */
object Elevation {
    /** Flush with the background: dividers, inline text. */
    val level0: Dp = 0.dp
    /** Resting buttons and chips. */
    val level1: Dp = 2.dp
    /** Glass cards at rest. */
    val level2: Dp = 9.dp
    /** Focused cards and buttons. */
    val level3: Dp = 16.dp
    /** Menus, popovers, the bottom toolbar. */
    val level4: Dp = 24.dp
    /** Dialogs and full-screen sheets. */
    val level5: Dp = 32.dp

    /** Soft ambient shadow tint — a cool navy, never neutral black. */
    val ambient = Color(0x33000000)
    val spot = Color(0x33000000)
}
