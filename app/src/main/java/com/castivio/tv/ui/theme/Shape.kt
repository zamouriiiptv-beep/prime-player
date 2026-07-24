package com.castivio.tv.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Corner radii.
 *
 * Castivio is a soft-cornered product: nothing in the interface has a sharp
 * corner. Larger surfaces get larger radii so the *visual* curvature stays
 * consistent as elements scale up.
 */
object Radius {
    val none: Dp = 0.dp
    val xs: Dp = 8.dp
    val sm: Dp = 12.dp
    val md: Dp = 16.dp
    val lg: Dp = 20.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 30.dp
    /** Fully rounded — pills, chips, avatars. */
    val pill: Dp = 999.dp
}

object CastivioShapes {
    val chip = RoundedCornerShape(Radius.pill)
    val button = RoundedCornerShape(Radius.sm)
    val iconButton = RoundedCornerShape(Radius.xs)
    val card = RoundedCornerShape(Radius.lg)
    val cardLarge = RoundedCornerShape(Radius.xl)
    val hero = RoundedCornerShape(Radius.xxl)
    val sheet = RoundedCornerShape(topStart = Radius.xl, topEnd = Radius.xl)
    val poster = RoundedCornerShape(Radius.sm)

    /** Material 3 shape set, so stock M3 components inherit Castivio curvature. */
    val material = Shapes(
        extraSmall = RoundedCornerShape(Radius.xs),
        small = RoundedCornerShape(Radius.sm),
        medium = RoundedCornerShape(Radius.md),
        large = RoundedCornerShape(Radius.lg),
        extraLarge = RoundedCornerShape(Radius.xl),
    )
}
