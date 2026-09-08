package com.castivio.core.design.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two grounds: that dark is still what shipped, and that light is a ground rather
 * than a rearrangement of the dark one.
 *
 * ## Why the first half is a list of literals
 *
 * Because the requirement was literally "dark must not change", and the only honest way
 * to gate that is to state what it was. Adding a light theme meant moving eleven colours
 * out of `Backdrop.kt` and out of the features into roles, and every one of those moves
 * is a chance to substitute a value that looks the same in a diff. These assertions are
 * the reference: if one fails, dark has moved, whatever the intention was.
 *
 * It deliberately does *not* assert every field. A test that restated the whole palette
 * would fail on every legitimate change and be deleted within a month. What is here is
 * the set that this pass actually touched.
 */
class ThemeColorsTest {

    private val dark = castivioDarkColors()
    private val light = castivioLightColors()

    /* ------------------------------------------------------ dark, unchanged */

    @Test
    fun `dark is the palette that shipped`() {
        assertEquals(Palette.Void, dark.background)
        assertEquals(Palette.Deep, dark.backgroundElevated)
        assertEquals(Palette.White, dark.onBackground)
        assertEquals(Palette.Quartz, dark.description)
        assertEquals(Palette.Azure50, dark.primary)
        assertEquals(Palette.Violet50, dark.secondary)
        assertEquals(Palette.GlassLow, dark.glassFill)
        assertEquals(Palette.Azure60, dark.focusRing)
    }

    /**
     * The eleven that moved out of a file into a role, at the values they had there.
     *
     * `Backdrop.kt` named `Deep`, `Violet10` and `Azure10` for its gradient, `Violet40`
     * and `Azure40` for its two glows and `Azure80` for the motes; eleven call sites
     * across the features named `EdgeQuiet`, `EdgeCard`, `EdgeAccent`, `White` and
     * `Violet10`. None of them is allowed to have changed on the way.
     */
    @Test
    fun `the colours that moved out of the features kept their values`() {
        assertEquals(listOf(Palette.Deep, Palette.Violet10, Palette.Azure10), dark.backdropStops)
        assertEquals(Palette.Violet40, dark.backdropWarmGlow)
        assertEquals(Palette.Azure40, dark.backdropCoolGlow)
        assertEquals(Palette.Azure80, dark.backdropMote)

        assertEquals(Palette.EdgeQuiet, dark.edgeQuiet)
        assertEquals(Palette.EdgeCard, dark.edgeCard)
        assertEquals(Palette.EdgeAccent, dark.edgeAccent)
        assertEquals(Palette.White, dark.onBackgroundStrong)
        assertEquals(Palette.Violet10, dark.featuredFill)
        assertEquals(Palette.Void, dark.rowEdgeFade)
    }

    /* ---------------------------------------------------------------- light */

    /**
     * Light is light and dark is dark, measured rather than asserted by name.
     *
     * The backdrop's own stops are included: a light theme whose page gradient is still
     * the dark one is a light theme in name only, and that is exactly what a partial
     * port produces — the roles flip, the canvas does not, and the result is dark type
     * on a dark ground.
     */
    @Test
    fun `each ground is on the side of the ramp it claims`() {
        assertTrue("dark's ground is not dark", dark.background.luminance() < 0.1f)
        assertTrue("light's ground is not light", light.background.luminance() > 0.8f)
        for (stop in dark.backdropStops) {
            assertTrue("a dark backdrop stop is light: $stop", stop.luminance() < 0.1f)
        }
        for (stop in light.backdropStops) {
            assertTrue("a light backdrop stop is dark: $stop", stop.luminance() > 0.7f)
        }
    }

    /**
     * Text is readable on both, at the threshold the product already claims elsewhere.
     *
     * 4.5:1 is WCAG AA for body text, and it is the floor rather than the target — the
     * dark theme clears it several times over. The point of measuring is the *light*
     * one: a pale ink chosen to look right beside a dark screenshot is the classic way
     * a light mode ships unreadable, and an eye is a poor instrument for catching it.
     */
    @Test
    fun `the three inks clear AA on both grounds`() {
        for ((name, colors) in listOf("dark" to dark, "light" to light)) {
            for ((role, ink) in listOf(
                "onBackground" to colors.onBackground,
                "onBackgroundStrong" to colors.onBackgroundStrong,
                "description" to colors.description,
                "onBackgroundVariant" to colors.onBackgroundVariant,
            )) {
                val ratio = contrast(ink, colors.background)
                assertTrue(
                    "$name/$role is ${"%.2f".format(ratio)}:1 against the ground, under 4.5",
                    ratio >= 4.5f,
                )
            }
        }
    }

    /**
     * The brand does not change with the lights.
     *
     * Azure, violet, amber, aqua and ember are what Castivio *is*; re-picking them for
     * a light ground would be a second brand, and the request was one product with two
     * grounds. What the light theme moves is which *step* of the blue and the violet
     * carries a role — `primary` is Azure40 rather than Azure50 because the lighter step
     * disappears on a pale page — not the hues themselves.
     */
    @Test
    fun `the accents are the same in both`() {
        assertEquals(dark.accent, light.accent)
        assertEquals(dark.live, light.live)
        assertEquals(dark.success, light.success)
        assertEquals(dark.warning, light.warning)
        assertEquals(dark.danger, light.danger)
        assertEquals(dark.logoTints, light.logoTints)
    }

    /** The flag both palettes carry, since the derived brushes read it. */
    @Test
    fun `each palette knows which one it is`() {
        assertTrue(light.isLight)
        assertTrue(!dark.isLight)
    }

    /** WCAG's ratio, on opaque colours. */
    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05f) / (lo + 0.05f)
    }
}
