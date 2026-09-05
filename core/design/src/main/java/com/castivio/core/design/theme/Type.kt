package com.castivio.core.design.theme

import androidx.compose.material3.Typography
import com.castivio.core.design.R
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Castivio typography.
 *
 * ## The line heights are measured, not chosen
 *
 * They were set against Latin and were a millimetre from clipping other scripts.
 * The shipping set has eleven writing systems in it, and the yardstick is glyph
 * ink from Canvas metrics -- not the font's recommended spacing, which reports
 * 46dp for a 22sp Arabic string and would fail every line that renders
 * perfectly. Worst cases, with the raised values: bodySmall 17.0dp of Thai in
 * 20, bodyMedium 19.4dp of Arabic in 22, headlineMedium 28.7 of Thai in 32,
 * headlineLarge 36.3 of Thai in 40, overline 15.0 of Thai in 18. Three
 * millimetres of headroom each; see `design/activation-spec.md` §9.
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

    /**
     * The Latin face: IBM Plex Sans, at the four weights this product uses.
     *
     * ## It was Inter, and the reason it is not is one word: companion
     *
     * Inter is a fine face and it was never the problem on its own. The problem was
     * that Arabic was set in IBM Plex Sans Arabic — because Inter has no Arabic
     * coverage — so the interface was reading in two families drawn by different
     * hands, and the difference showed exactly where a reader can least afford it:
     * a heading that reads bold in English and a shade lighter in Arabic, a card
     * whose title and description look like one family in one language and two in
     * the other.
     *
     * Plex Sans and Plex Sans Arabic are one family. Their weights mean the same
     * thing, their x-heights relate, their 400 has the same colour on the page. The
     * hierarchy is then what it was always supposed to be — size, weight and ink —
     * rather than partly an accident of which script a string happens to be in.
     *
     * Four static faces rather than the variable font: variable weight axes are
     * honoured from API 26 and Castivio's minSdk is 21. A file per weight is
     * bigger on disk and correct on every device.
     */
    val PlexSans: FontFamily = FontFamily(
        Font(R.font.plex_sans_regular, FontWeight.Normal),
        Font(R.font.plex_sans_medium, FontWeight.Medium),
        Font(R.font.plex_sans_semibold, FontWeight.SemiBold),
        Font(R.font.plex_sans_bold, FontWeight.Bold),
    )

    /**
     * Inter, kept for one string: the wordmark.
     *
     * CASTIVIO is a logotype rather than a heading. It is the mark the product signs
     * its name with, it is drawn at a tracking and a gradient nothing else uses, and
     * changing the shapes of its letters changes the logo — which is a decision about
     * the brand, not about the interface's typography. So the interface moved to Plex
     * and the signature did not.
     *
     * Only [com.castivio.core.design.components.CastivioMark] and the header's
     * wordmark name this. If the brand is ever redrawn in Plex, these four files and
     * this property go with it.
     */
    val Inter: FontFamily = FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal),
        Font(R.font.inter_medium, FontWeight.Medium),
        Font(R.font.inter_semibold, FontWeight.SemiBold),
        Font(R.font.inter_bold, FontWeight.Bold),
    )

    /**
     * The Arabic face: IBM Plex Sans Arabic, at the same four weights.
     *
     * The companion of [PlexSans], by the same designers and drawn against it, which
     * is the whole reason the Latin side moved. Leaving Arabic to the platform
     * fallback is how an interface ends up with two typefaces that were never drawn
     * together — different weight, different colour on the page, a heading that reads
     * bold in English and light in Arabic.
     */
    val PlexArabic: FontFamily = FontFamily(
        Font(R.font.plex_arabic_regular, FontWeight.Normal),
        Font(R.font.plex_arabic_medium, FontWeight.Medium),
        Font(R.font.plex_arabic_semibold, FontWeight.SemiBold),
        Font(R.font.plex_arabic_bold, FontWeight.Bold),
    )

    /**
     * The face for a language, which is the only place this choice is made.
     *
     * Android matches a `FontFamily` by weight and style, never by script, so one
     * family cannot serve both -- the selection has to happen somewhere, and it
     * happens here, once, driven by the language the user picked.
     *
     * Every style below leaves `fontFamily` unset on purpose. `Text` merges the
     * style it is handed onto `LocalTextStyle`, so a null family inherits the one
     * `CastivioTheme` provides and no screen has to know which script it is
     * rendering. That is what "applied globally through CastivioType" means here:
     * eight files, two families, one decision.
     */
    fun brandFor(language: String): FontFamily =
        if (language in ARABIC_SCRIPT) PlexArabic else PlexSans

    /**
     * The languages Castivio ships that are written in Arabic script.
     *
     * Language codes rather than `Locale.getScript()`: the script subtag is absent
     * from a plain `ar` or `fa`, and a lookup that depends on it returns Latin for
     * exactly the locales this exists to catch.
     */
    private val ARABIC_SCRIPT = setOf("ar", "fa", "ur", "ps", "sd", "ug")

    /**
     * Codes stay monospace in every language.
     *
     * A MAC address and a six-digit key are Latin digits whatever the interface
     * language, and what makes them readable is that the columns line up. Neither
     * brand face is monospaced, so neither is used here.
     */
    val Mono: FontFamily = FontFamily.Monospace

    // -- Display: hero moments only (one per screen, at most) --------------
    val displayLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 44.sp, lineHeight = 52.sp, letterSpacing = (-0.5).sp,
    )
    val displayMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = (-0.4).sp,
    )

    // -- Headline: screen and section titles -------------------------------
    val headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp, lineHeight = 40.sp, letterSpacing = (-0.2).sp,
    )
    val headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp, lineHeight = 32.sp,
    )
    val headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp, lineHeight = 26.sp,
    )

    // -- Title: card and list-row titles -----------------------------------
    val titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp, lineHeight = 24.sp,
    )
    val titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp, lineHeight = 22.sp,
    )

    // -- Body ---------------------------------------------------------------
    val bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 24.sp,
    )
    val bodyMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 22.sp,
    )
    val bodySmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.5.sp, lineHeight = 20.sp,
    )

    // -- Label: buttons, chips, captions, overlines -------------------------
    val labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp,
    )
    val labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp,
    )
    val labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 18.sp, letterSpacing = 0.4.sp,
    )
    /** ALL-CAPS section marker. Use with `text.uppercase()`. */
    val overline = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp, lineHeight = 18.sp, letterSpacing = 1.2.sp,
    )

    // -- Subtitles ----------------------------------------------------------
    //
    // Four sizes rather than a slider, because a viewer choosing subtitle size is
    // answering "can I read that from here", and a number they have to nudge is a worse
    // way to answer it than four steps they can try in a second. The line heights are
    // generous: a caption is read at a glance, in a hurry, over a moving picture.
    //
    // Semi-bold at every size and not by taste. A caption sits on whatever the film
    // happens to be showing, so the letter that lands on a bright frame has to hold its
    // shape without the backdrop's help — the backdrop is the viewer's choice and may be
    // switched off.
    val subtitleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp,
    )
    val subtitleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp, lineHeight = 26.sp,
    )
    val subtitleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 23.sp, lineHeight = 32.sp,
    )
    val subtitleHuge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 29.sp, lineHeight = 40.sp,
    )

    // -- Code: MAC addresses, activation keys, IDs --------------------------
    val codeHero = TextStyle(
        fontFamily = Mono, fontWeight = FontWeight.Bold,
        fontSize = 42.sp, lineHeight = 52.sp, letterSpacing = 1.sp,
    )
    /**
     * The address on a phone. Seventeen monospace characters at 42sp need 445dp,
     * which no phone in landscape can spare beside a code; at 28sp they need 303
     * in a mono whose advance is 0.6em, which is Roboto Mono's.
     */
    val codeCompact = TextStyle(
        fontFamily = Mono, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = 1.sp,
    )

    /**
     * The device key: six digits, one group. Four steps below the address so the
     * address stays the anchor, and tracked wider than it, because these six get
     * read off a television, typed into a phone, and sometimes said out loud.
     */
    val codeKey = TextStyle(
        fontFamily = Mono, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 2.5.sp,
    )
    val codeKeyTv = TextStyle(
        fontFamily = Mono, fontWeight = FontWeight.Bold,
        fontSize = 32.sp, lineHeight = 42.sp, letterSpacing = 3.5.sp,
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
