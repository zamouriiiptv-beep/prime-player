package com.castivio.core.design.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Castivio colour palette.
 *
 * Identity: a deep navy "void" base lit by an aurora of azure and violet.
 * Nothing is pure black and nothing is pure white — every surface carries a
 * trace of the brand hue, which is what separates Castivio from the flat
 * grey/black of typical IPTV players.
 *
 * Raw values live here. Screens should read semantic tokens from
 * [CastivioColors] via `CastivioTheme.colors` rather than these constants.
 */
object Palette {

    // -- Base: deep navy, darkest to lightest -----------------------------
    val Void = Color(0xFF08071A)
    val Abyss = Color(0xFF0D0B22)
    val Deep = Color(0xFF141031)
    val Slate = Color(0xFF1C1840)
    val Haze = Color(0xFF272253)

    // -- Brand: azure ------------------------------------------------------
    val Azure10 = Color(0xFF0A1E3D)
    val Azure40 = Color(0xFF2E6BFF)
    val Azure50 = Color(0xFF4C9BFF)
    val Azure60 = Color(0xFF6FB2FF)
    val Azure80 = Color(0xFFB4D6FF)

    // -- Brand: violet -----------------------------------------------------
    val Violet10 = Color(0xFF1B1038)
    val Violet40 = Color(0xFF6E4BD8)
    val Violet50 = Color(0xFF9B6BFF)
    val Violet60 = Color(0xFFB694FF)
    val Violet80 = Color(0xFFDCCBFF)

    // -- Accent: used sparingly, for the play/live identity ----------------
    val Ember = Color(0xFFFF3B5C)
    val Aqua = Color(0xFF2FBF9F)
    val Amber = Color(0xFFFFB020)

    // -- Neutrals: tinted toward the brand, never pure -----------------------
    val White = Color(0xFFFFFFFF)
    val Mist = Color(0xFFF2F2F8)
    val Silver = Color(0xFFC9C9DA)
    val Muted = Color(0xFFA6A6BF)
    val Faint = Color(0xFF6E6E8A)

    // -- Glass: translucent white layers used on top of the base -----------
    val GlassHigh = Color(0x1FFFFFFF)
    val GlassMid = Color(0x14FFFFFF)
    val GlassLow = Color(0x0AFFFFFF)
    val GlassEdge = Color(0x3DFFFFFF)
    val GlassEdgeSoft = Color(0x14FFFFFF)

    // -- Status -------------------------------------------------------------
    val Success = Color(0xFF3DD68C)
    val Warning = Amber
    val Danger = Color(0xFFFF5A5A)
}

/**
 * Semantic colour tokens. Screens use these names, never raw palette values,
 * so the whole app re-skins from one place.
 */
@Suppress("LongParameterList")
class CastivioColors(
    // Backgrounds
    val background: Color,
    val backgroundElevated: Color,
    val scrim: Color,

    // Content
    val onBackground: Color,
    val onBackgroundVariant: Color,
    val onBackgroundMuted: Color,

    // Brand
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val accent: Color,

    // Glass surfaces
    val glassFill: Color,
    val glassFillStrong: Color,
    val glassBorder: Color,
    val glassBorderSoft: Color,

    // Interaction
    val focusRing: Color,
    val focusGlow: Color,
    val divider: Color,

    /**
     * A choice that is currently in force: the language Castivio is in, the
     * quality that is playing, the sort that is applied.
     *
     * Deliberately not the same channel as focus. On a television both are true
     * of a row at once -- which option is active, and where the remote is -- and
     * a design that carries them on one channel makes a focused row look chosen.
     * Focus is a ring drawn outside the surface; selection is the surface.
     *
     * Never the only cue either. Whatever this fills is also expected to carry a
     * mark and a heavier weight, so the state survives a viewer who cannot
     * separate the fill from the ground.
     */
    val selectedFill: Color,
    val selectedBorder: Color,

    /**
     * "Now". The one meaning aqua has anywhere in the product: a live programme,
     * a playing stream, the moving meter. Never used for navigation or status.
     */
    val live: Color,

    // Status
    val success: Color,
    val warning: Color,
    val danger: Color,

    /**
     * Deterministic tints for a logo or avatar placeholder when a provider ships
     * no artwork. A screen picks one by a stable index (a channel's id), so the
     * same channel is always the same colour — and the literals stay in here
     * rather than leaking a `Color(0x…)` into a feature.
     */
    val logoTints: List<Color>,
) {
    /** The Castivio signature gradient — background washes and hero fills. */
    val auroraBrush: Brush
        get() = Brush.linearGradient(listOf(Palette.Deep, Palette.Violet10, Palette.Azure10))

    /** Primary action fill. Three stops so the ramp stays smooth on large buttons. */
    val primaryBrush: Brush
        get() = Brush.horizontalGradient(
            listOf(Palette.Azure50, Palette.Azure40, Color(0xFF2C67F0)),
        )

    /** Brand mark / badge fill: violet into azure. */
    val brandBrush: Brush
        get() = Brush.linearGradient(listOf(Palette.Violet40, Palette.Azure40))

    /** Vertical sheen that gives a glass panel its lit top edge. */
    val glassFillBrush: Brush
        get() = Brush.verticalGradient(listOf(glassFillStrong, glassFill))

    /** Border that fades from lit (top) to invisible (bottom). */
    val glassBorderBrush: Brush
        get() = Brush.verticalGradient(listOf(glassBorder, glassBorderSoft))
}

/** The dark theme — Castivio's only theme. The brand is a dark product. */
fun castivioDarkColors() = CastivioColors(
    background = Palette.Void,
    backgroundElevated = Palette.Deep,
    scrim = Color(0xB3000000),

    onBackground = Palette.White,
    onBackgroundVariant = Palette.Silver,
    onBackgroundMuted = Palette.Muted,

    primary = Palette.Azure50,
    onPrimary = Palette.White,
    primaryContainer = Palette.Azure10,
    secondary = Palette.Violet50,
    onSecondary = Palette.White,
    secondaryContainer = Palette.Violet10,
    accent = Palette.Ember,

    glassFill = Palette.GlassLow,
    glassFillStrong = Palette.GlassMid,
    glassBorder = Palette.GlassEdge,
    glassBorderSoft = Palette.GlassEdgeSoft,

    focusRing = Palette.Azure60,
    focusGlow = Palette.Azure40.copy(alpha = 0.45f),
    divider = Color(0x1FFFFFFF),
    selectedFill = Palette.Violet50.copy(alpha = 0.16f),
    selectedBorder = Palette.Violet50.copy(alpha = 0.34f),
    live = Palette.Aqua,

    success = Palette.Success,
    warning = Palette.Warning,
    danger = Palette.Danger,

    logoTints = listOf(
        Palette.Azure40,
        Palette.Violet40,
        Palette.Aqua,
        Palette.Amber,
        Palette.Ember,
        Palette.Azure50,
        Palette.Violet50,
    ),
)

/**
 * A placeholder poster fill, chosen deterministically from [index].
 *
 * Real artwork replaces this the moment it loads; until then a card should read
 * as the surface it will become rather than as a grey hole. Kept here so the
 * gradient stops are palette values, not literals in a feature.
 */
fun posterPlaceholderBrush(index: Int): Brush {
    val pairs = listOf(
        Palette.Violet10 to Palette.Violet40,
        Palette.Azure10 to Palette.Azure40,
        Palette.Slate to Palette.Aqua,
        Palette.Deep to Palette.Ember,
        Palette.Haze to Palette.Amber,
        Palette.Abyss to Palette.Violet50,
    )
    val (top, bottom) = pairs[(index % pairs.size + pairs.size) % pairs.size]
    return Brush.linearGradient(listOf(top, bottom))
}

/** The soft edge-fade a scrollable row bleeds into, so it reads as "continues". */
val rowEdgeFadeColor: Color get() = Palette.Void
