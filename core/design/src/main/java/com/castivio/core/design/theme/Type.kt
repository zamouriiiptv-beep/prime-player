package com.castivio.core.design.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Castivio typography.
 *
 * One family, five weights, generous line height. Headings are tight and
 * confident; body copy is airy and never pure white, so the eye lands on
 * headings first. Codes (MAC, activation keys) get their own monospace style
 * with wide tracking — they are data to be read aloud or copied, not prose.
 *
 * The family is the platform default today; dropping a brand font into
 * `res/font` and changing [Brand] alone re-skins every screen.
 */
object CastivioType {

    /** Swap this for a bundled brand face (e.g. Inter / Outfit) when available. */
    val Brand: FontFamily = FontFamily.SansSerif
    val Mono: FontFamily = FontFamily.Monospace

    // -- Display: hero moments only (one per screen, at most) --------------
    val displayLarge = TextStyle(
        fontFamily = Brand, fontWeight = FontWeight.Bold,
        fontSize = 44.sp, lineHeight = 52.sp, letterSpacing = (-0.5).sp,
    )
    val displayMedium = TextStyle(
        fontFamily = Brand, fontWeight = FontWeight.Bold,
        fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = (-0.4).sp,
    )

    // -- Headline: screen and section titles -------------------------------
    val headlineLarge = TextStyle(
        fontFamily = Brand, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.2).sp,
    )
    val headlineMedium = TextStyle(
        fontFamily = Brand, fontWeight = FontWeight.Bold,
        fontSize = 22.sp, lineHeight = 30.sp,
    )
    val headlineSmall = TextStyle(
        fontFamily = Brand, fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp, lineHeight = 26.sp,
    )

    // -- Title: card and list-row titles -----------------------------------
    val titleLarge = TextStyle(
        fontFamily = Brand, fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp, lineHeight = 24.sp,
    )
    val titleMedium = TextStyle(
        fontFamily = Brand, fontWeight = FontWeight.Medium,
        fontSize = 15.sp, lineHeight = 22.sp,
    )

    // -- Body ---------------------------------------------------------------
    val bodyLarge = TextStyle(
        fontFamily = Brand, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 24.sp,
    )
    val bodyMedium = TextStyle(
        fontFamily = Brand, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 22.sp,
    )
    val bodySmall = TextStyle(
        fontFamily = Brand, fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp, lineHeight = 18.sp,
    )

    // -- Label: buttons, chips, captions, overlines -------------------------
    val labelLarge = TextStyle(
        fontFamily = Brand, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp,
    )
    val labelMedium = TextStyle(
        fontFamily = Brand, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp,
    )
    val labelSmall = TextStyle(
        fontFamily = Brand, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.4.sp,
    )
    /** ALL-CAPS section marker. Use with `text.uppercase()`. */
    val overline = TextStyle(
        fontFamily = Brand, fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 1.2.sp,
    )

    // -- Code: MAC addresses, activation keys, IDs --------------------------
    val codeHero = TextStyle(
        fontFamily = Mono, fontWeight = FontWeight.Bold,
        fontSize = 42.sp, lineHeight = 52.sp, letterSpacing = 1.sp,
    )
    val codeLarge = TextStyle(
        fontFamily = Mono, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 0.5.sp,
    )
    val codeSmall = TextStyle(
        fontFamily = Mono, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 18.sp,
    )

    /** Material 3 type set, so stock M3 components inherit Castivio type. */
    val material = Typography(
        displayLarge = displayLarge,
        displayMedium = displayMedium,
        displaySmall = headlineLarge,
        headlineLarge = headlineLarge,
        headlineMedium = headlineMedium,
        headlineSmall = headlineSmall,
        titleLarge = titleLarge,
        titleMedium = titleMedium,
        titleSmall = labelLarge,
        bodyLarge = bodyLarge,
        bodyMedium = bodyMedium,
        bodySmall = bodySmall,
        labelLarge = labelLarge,
        labelMedium = labelMedium,
        labelSmall = labelSmall,
    )
}
