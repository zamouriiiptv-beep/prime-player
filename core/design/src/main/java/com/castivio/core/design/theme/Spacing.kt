package com.castivio.core.design.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing scale, built on a 4dp grid.
 *
 * Rule of thumb: space *between* related items uses [sm]/[md]; space between
 * sections uses [xl]/[xxl]; screen padding uses [screen]. Never hand-pick a
 * value outside this scale — if none fits, the layout is wrong, not the scale.
 */
object Spacing {
    val none: Dp = 0.dp
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
    val xxxl: Dp = 48.dp
    val huge: Dp = 64.dp

    /** Default horizontal padding for a screen on a handset / tablet. */
    val screen: Dp = 24.dp

    /**
     * Extra inset for televisions. Broadcast displays overscan, so no content
     * may sit closer than this to the physical edge of the panel.
     */
    val tvOverscan: Dp = 48.dp

    /** Gap between cards in a grid or row. */
    val gridGutter: Dp = 20.dp
}

/** Minimum hit targets. TV remotes are imprecise; touch is worse. */
object Sizing {
    val minTouchTarget: Dp = 48.dp
    val minTvTarget: Dp = 56.dp

    /**
     * The floor that applies on *this* device, which is the only one worth
     * asserting against.
     *
     * Two constants and no way to ask which one applies is how both of them get
     * ignored. Every control in this package that used to pin
     * [minTouchTarget] was 8dp under the D-pad floor on a television, and the
     * two occasions it was caught were both by eye on a photograph — once for
     * the copy control on the activation screen, once here for every button in
     * the application. A named function is what lets a test iterate the frames
     * and ask.
     */
    fun minTarget(isTv: Boolean): Dp = if (isTv) minTvTarget else minTouchTarget

    /**
     * The floor of a button in a band that has to be short -- the two buttons of the arrange and hide bands -- on a handset or a
     * tablet: 34dp, where a thumb's floor is 48. Asked for by the owner, who found the band 14dp too tall for a list that has room
     * for another row, and chosen from `design/mockups/channels-mode-bar-fix-ar.html`.
     */
    val compactTouchTarget: Dp = 34.dp

    /**
     * [compactTouchTarget] on a handset or a tablet, and **the television's own floor on a television**: a remote is not a thumb,
     * and a control under [minTvTarget] is the defect `scripts/check-invariants.sh` was written to stop. So a compact button is
     * shorter only where a finger is what presses it.
     */
    fun compactTarget(isTv: Boolean): Dp = if (isTv) minTvTarget else compactTouchTarget

    val iconSm: Dp = 16.dp
    val iconMd: Dp = 20.dp
    val iconLg: Dp = 26.dp
    val iconXl: Dp = 32.dp
    /** Width beyond which content stops stretching and starts centring. */
    val maxContentWidth: Dp = 1440.dp
}
