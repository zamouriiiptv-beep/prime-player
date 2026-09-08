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

    /**
     * The four card hues, at the values the screens used to name directly.
     *
     * They were `Palette.Azure50`, `Violet50`, `Amber` and `Success` written into two
     * screens. Making them roles is what lets the light ground answer with the step
     * that reads there — and it is also the move that could silently change the dark
     * ground, which is what this pins.
     */
    @Test
    fun `the dark card hues are the ones the screens used to name`() {
        assertEquals(Palette.Azure50, dark.hueAzure)
        assertEquals(Palette.Violet50, dark.hueViolet)
        assertEquals(Palette.Success, dark.hueGreen)
        assertEquals(Palette.Amber, dark.hueAmber)
        assertEquals(0.34f, dark.discTop, 0f)
        assertEquals(0.08f, dark.discFoot, 0f)
        assertEquals(0.46f, dark.discEdge, 0f)
        assertEquals(0.30f, dark.backdropGlowWarm, 0f)
        assertEquals(0.26f, dark.backdropGlowCool, 0f)
    }

    /**
     * A glyph is legible on its own disc, on the light ground.
     *
     * This is the failure the first light attempt actually had: the disc is a pale
     * tint of the hue and the glyph was the hue itself, so four icons disappeared into
     * four discs. 3:1 rather than 4.5 because these are glyphs and not words, which is
     * the threshold WCAG sets for a graphical object.
     *
     * Composited by hand: the disc is the hue at [CastivioColors.discTop] over the
     * page, and a ratio taken against the unblended hue would be measuring a colour
     * that is never drawn.
     */
    @Test
    fun `a glyph reads on its own disc in the light theme`() {
        for ((name, hue) in listOf(
            "azure" to light.hueAzure,
            "violet" to light.hueViolet,
            "green" to light.hueGreen,
            "amber" to light.hueAmber,
        )) {
            val disc = over(hue, light.discTop, light.background)
            val ratio = contrast(light.discGlyph(hue), disc)
            assertTrue(
                "light/$name: the glyph is ${"%.2f".format(ratio)}:1 on its disc, under 3",
                ratio >= 3f,
            )
        }
    }

    /**
     * The aurora is a tint of a light page, not a stain on it.
     *
     * The same two glows in the same two corners at the same radii on both grounds, so
     * strength is the only thing that separates a bloom on black from a blot on white
     * — and it is the number the first attempt left alone.
     */
    @Test
    fun `the light glows are laid on more softly than the dark ones`() {
        assertTrue(
            "light's warm glow ${light.backdropGlowWarm} is not softer than dark's " +
                "${dark.backdropGlowWarm}",
            light.backdropGlowWarm < dark.backdropGlowWarm / 2f,
        )
        assertTrue(
            "light's cool glow ${light.backdropGlowCool} is not softer than dark's " +
                "${dark.backdropGlowCool}",
            light.backdropGlowCool < dark.backdropGlowCool / 2f,
        )
    }

    /** The flag both palettes carry, since the derived brushes read it. */
    @Test
    fun `each palette knows which one it is`() {
        assertTrue(light.isLight)
        assertTrue(!dark.isLight)
    }

    /** [fg] at [alpha] laid over [bg], which is what a disc actually is. */
    private fun over(fg: Color, alpha: Float, bg: Color) = Color(
        red = fg.red * alpha + bg.red * (1 - alpha),
        green = fg.green * alpha + bg.green * (1 - alpha),
        blue = fg.blue * alpha + bg.blue * (1 - alpha),
    )

    /** WCAG's ratio, on opaque colours. */
    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05f) / (lo + 0.05f)
    }
}
