package com.castivio.core.design.theme

import com.castivio.core.common.locale.CastivioLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Every shipping language gets a face that can draw it.
 *
 * ## Why this is not obvious
 *
 * Because the failure is silent and looks like a design choice. Plex Sans has no
 * Arabic coverage; ask it for Arabic and Android does not error, it substitutes
 * the platform's fallback — a different typeface, at a different weight, with a
 * different colour on the page. The screen still renders. It just stops being
 * one design, and only somebody who reads Arabic notices.
 *
 * `CastivioType.brandFor` is the single place that choice is made, so this is the
 * single place it can be checked. It is a pure string lookup, which is the reason
 * it was written as one.
 */
class BrandFaceTest {

    /**
     * The six Arabic-script languages in the shipping set reach Plex, and every
     * other language reaches Plex Sans.
     *
     * Driven off [CastivioLanguage] rather than a list written here: a language
     * added to the product without a face to draw it should fail this test, and a
     * hand-copied list is a list that stops being the product's.
     */
    @Test
    fun `every shipping language resolves to a face that covers its script`() {
        val arabicScript = setOf("ar", "fa", "ur", "ps", "sd", "ug")
        for (language in CastivioLanguage.entries) {
            for (variant in language.variants) {
                val code = variant.tag.substringBefore('-')
                val expected =
                    if (code in arabicScript) CastivioType.PlexArabic else CastivioType.PlexSans
                assertEquals(
                    "${language.name} (${variant.tag}) resolved to the wrong face",
                    expected,
                    CastivioType.brandFor(code),
                )
            }
        }
    }

    /** Arabic itself, called out, because it is the one the product ships to first. */
    @Test
    fun `both scripts are drawn in Plex, each in its own cut`() {
        assertEquals(CastivioType.PlexArabic, CastivioType.brandFor("ar"))
        assertEquals(CastivioType.PlexSans, CastivioType.brandFor("en"))
        assertNotEquals(CastivioType.brandFor("ar"), CastivioType.brandFor("en"))
    }

    /**
     * An unknown language is Latin rather than nothing.
     *
     * The lookup is asked for whatever the platform reports, which on a device in
     * a language Castivio has never heard of is not in the set. Falling back to
     * Plex Sans matches the resource fallback to English.
     */
    @Test
    fun `an unknown language falls back to the Latin face`() {
        assertEquals(CastivioType.PlexSans, CastivioType.brandFor(""))
        assertEquals(CastivioType.PlexSans, CastivioType.brandFor("is"))
    }

    /**
     * The styles carry no family of their own.
     *
     * That is what lets one provider in `CastivioTheme` reach every screen: a
     * style with a family baked in would override the locale's face and be
     * invisible until somebody read the Arabic build. Codes are the deliberate
     * exception — they are monospace in every language.
     */
    @Test
    fun `brand styles leave the family to the theme, and codes do not`() {
        for ((name, style) in listOf(
            "displayLarge" to CastivioType.displayLarge,
            "headlineLarge" to CastivioType.headlineLarge,
            "headlineMedium" to CastivioType.headlineMedium,
            "bodyMedium" to CastivioType.bodyMedium,
            "bodySmall" to CastivioType.bodySmall,
            "overline" to CastivioType.overline,
        )) {
            assertEquals("$name pins a font family; the theme can no longer set it", null, style.fontFamily)
        }
        assertEquals(CastivioType.Mono, CastivioType.codeCompact.fontFamily)
        assertEquals(CastivioType.Mono, CastivioType.codeKey.fontFamily)
    }
}
