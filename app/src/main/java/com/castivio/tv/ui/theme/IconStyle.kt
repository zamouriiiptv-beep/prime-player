package com.castivio.tv.ui.theme

import androidx.compose.ui.unit.Dp

/**
 * Icon style.
 *
 * **Family.** Castivio uses the *Rounded* Material Symbols set exclusively
 * (`Icons.Rounded.*`). Rounded terminals echo the corner radii and the brand
 * mark; mixing in Filled or Sharp icons is the fastest way to make the
 * interface look assembled from parts.
 *
 * **Weight.** Outlined by default. A filled icon means "active" or "playing" —
 * that is the only place fill is allowed to appear.
 *
 * **Colour.** Icons inherit [CastivioColors.onBackground] at rest and
 * [CastivioColors.primary] when they carry the action of their row. Never
 * colour an icon purely for decoration; colour is meaning.
 *
 * **Sizing.** Only the four sizes in [IconSize]. Icons sit optically centred
 * against text, not mathematically — pair [IconSize.sm] with label text,
 * [IconSize.md] with titles.
 *
 * **Pairing.** An icon is always separated from its label by [Spacing.sm].
 * Icon-only controls must supply a content description.
 */
object IconStyle {
    /** Prefix used across the app: `Icons.Rounded.*`. Do not mix families. */
    const val family = "Material Symbols Rounded"
}

object IconSize {
    /** Inline with body/label text. */
    val sm: Dp = Sizing.iconSm
    /** Default: buttons, toolbars, list rows. */
    val md: Dp = Sizing.iconMd
    /** Card leading icons, navigation rail. */
    val lg: Dp = Sizing.iconLg
    /** Feature/empty-state glyphs. */
    val xl: Dp = Sizing.iconXl
}
