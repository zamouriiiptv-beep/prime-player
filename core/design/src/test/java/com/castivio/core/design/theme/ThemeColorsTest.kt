package com.castivio.core.design.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two grounds: that the void is still what shipped, and that the slate beside it is
 * a ground of its own rather than a rearrangement of it.
 *
 * ## Why the first half is a list of literals
 *
 * Because the requirement was literally "dark must not change", and the only honest way
 * to gate that is to state what it was. Adding a second ground meant moving eleven
 * colours out of `Backdrop.kt` and out of the features into roles, and every one of
 * those moves is a chance to substitute a value that looks the same in a diff. These
 * assertions are the reference: if one fails, the void has moved, whatever the
 * intention was.
 *
 * It deliberately does *not* assert every field. A test that restated the whole palette
 * would fail on every legitimate change and be deleted within a month. What is here is
 * the set that this pass actually touched.
 *
 * ## What the second half checks now, and why it is not what it checked before
 *
 * It used to assert that the second ground was *light* — luminance above 0.8, dark ink,
 * its own four pastel hues. That ground shipped, and on a device it cost the product
 * the thing it is for. So the assertion is not deleted because it became inconvenient;
 * it is replaced because the design decision behind it was reversed deliberately, and
 * what replaces it is the property the new ground was actually built to have: not "is
 * it light" but **is a card an object on it**, measured in L*, against the reference
 * players this ground was derived from.
 */
class ThemeColorsTest {

    private val dark = castivioDarkColors()
    private val slate = castivioSlateColors()

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

    /* ---------------------------------------------------------------- slate */

    /**
     * Both grounds are dark, and the slate is the lifted one.
     *
     * The pair of bounds is the whole shape of the decision. Below 0.05 relative
     * luminance says the slate is still a dark ground — the assertion the old light
     * theme could not have passed, and the one that stops this drifting back toward a
     * page brighter than its own content. Above the void says it is a *second* ground
     * and not a repaint of the first.
     *
     * The backdrop's own stops are included: a second ground whose page gradient is
     * still the first one's is a second ground in name only, and that is exactly what
     * a partial port produces.
     */
    @Test
    fun `both grounds are dark, and the slate is the lifted one`() {
        assertTrue("the void is not dark", dark.background.luminance() < 0.01f)
        assertTrue("the slate is not dark", slate.background.luminance() < 0.05f)
        assertTrue(
            "the slate is not lifted off the void",
            slate.background.luminance() > dark.background.luminance(),
        )
        for (stop in dark.backdropStops) {
            assertTrue("a void backdrop stop is not dark: $stop", stop.luminance() < 0.02f)
        }
        for (stop in slate.backdropStops) {
            assertTrue("a slate backdrop stop is not dark: $stop", stop.luminance() < 0.05f)
            assertTrue(
                "a slate backdrop stop is not lifted off the void: $stop",
                stop.luminance() > dark.background.luminance(),
            )
        }
    }

    /**
     * A card is an object on this ground, which is the finding it was built from.
     *
     * Measured in L* rather than in a contrast ratio, because this is a question about
     * two dark surfaces and WCAG's ratio is dominated by its own +0.05 floor down
     * there: the void's card and the slate's differ by a factor of two perceptually and
     * by 0.08 in that ratio, which is a number that cannot fail.
     *
     * The reference players are the target and the reason for the figure. Hot Player
     * lifts its card 8.43 L* off its page and TiviMate 9.81; the void lifts its own
     * 4.18, and that gap is what read as hollow beside them. Eight is the floor rather
     * than the aim — the shipped value is 8.47.
     */
    @Test
    fun `a card stands off the slate as far as it does in the reference players`() {
        val page = lstar(slate.background)
        val card = lstar(slate.backgroundElevated)
        assertTrue(
            "the slate's card lifts ${"%.2f".format(card - page)} L*, under the 8 the " +
                "reference players clear",
            card - page >= 8f,
        )
    }

    /**
     * Text is readable on both grounds and on a card on each, at the threshold the
     * product already claims elsewhere.
     *
     * The card is included and was not before. On the void a card is a film a few per
     * cent above the page, so measuring the page was very nearly measuring both; on
     * this ground a card is a solid 8.5 L* up, and an ink that clears AA on the page
     * can fail on the surface most of the words are actually set on.
     */
    @Test
    fun `the four inks clear AA on both grounds, page and card`() {
        for ((name, colors) in listOf("void" to dark, "slate" to slate)) {
            for ((surface, ground) in listOf(
                "page" to colors.background,
                "card" to colors.backgroundElevated,
            )) {
                for ((role, ink) in listOf(
                    "onBackground" to colors.onBackground,
                    "onBackgroundStrong" to colors.onBackgroundStrong,
                    "description" to colors.description,
                    "onBackgroundVariant" to colors.onBackgroundVariant,
                )) {
                    val ratio = contrast(ink, ground)
                    assertTrue(
                        "$name/$surface/$role is ${"%.2f".format(ratio)}:1, under 4.5",
                        ratio >= 4.5f,
                    )
                }
            }
        }
    }

    /**
     * The brand does not change with the ground.
     *
     * Azure, violet, amber, aqua and ember are what Castivio *is*; re-picking them for
     * a second ground would be a second brand, and the request was one product with two
     * grounds. The pale ground had to move which *step* carried a role, because a hue
     * picked to glow on near-black is a pastel on near-white. This one does not, and
     * that is the dividend of the second ground being dark.
     */
    @Test
    fun `the accents are the same on both`() {
        assertEquals(dark.accent, slate.accent)
        assertEquals(dark.live, slate.live)
        assertEquals(dark.success, slate.success)
        assertEquals(dark.warning, slate.warning)
        assertEquals(dark.danger, slate.danger)
        assertEquals(dark.logoTints, slate.logoTints)
        assertEquals(dark.primary, slate.primary)
        assertEquals(dark.secondary, slate.secondary)
    }

    /**
     * The four card hues are the same *entries*, not two sets that happen to agree.
     *
     * A duplicated value is one edit away from being two opinions about what colour the
     * M3U card is, and this is the assertion that keeps the answer single.
     */
    @Test
    fun `the four card hues are shared, not copied`() {
        assertEquals(Palette.Azure50, dark.hueAzure)
        assertEquals(Palette.Violet50, dark.hueViolet)
        assertEquals(Palette.Success, dark.hueGreen)
        assertEquals(Palette.Amber, dark.hueAmber)

        assertEquals(dark.hueAzure, slate.hueAzure)
        assertEquals(dark.hueViolet, slate.hueViolet)
        assertEquals(dark.hueGreen, slate.hueGreen)
        assertEquals(dark.hueAmber, slate.hueAmber)

        assertEquals(0.34f, dark.discTop, 0f)
        assertEquals(0.08f, dark.discFoot, 0f)
        assertEquals(0.46f, dark.discEdge, 0f)
        assertEquals(0.30f, dark.backdropGlowWarm, 0f)
        assertEquals(0.26f, dark.backdropGlowCool, 0f)
    }

    /**
     * A glyph is legible on its own disc, on the lifted ground.
     *
     * This is the failure a lifted page produces and a near-black one does not: the
     * disc is a tint of the hue over the page, so raising the page raises the disc, and
     * at the void's own 34% the azure glyph measures 2.92:1 and the violet 2.59:1
     * against a disc drawn on this ground. 3:1 rather than 4.5 because these are glyphs
     * and not words, which is the threshold WCAG sets for a graphical object.
     *
     * Composited by hand, over the *card*, because that is where a disc is actually
     * drawn — a ratio taken against the page would be measuring a picture nobody sees.
     */
    @Test
    fun `a glyph reads on its own disc on the slate`() {
        for ((name, hue) in listOf(
            "azure" to slate.hueAzure,
            "violet" to slate.hueViolet,
            "green" to slate.hueGreen,
            "amber" to slate.hueAmber,
        )) {
            val disc = over(hue, slate.discTop, slate.backgroundElevated)
            val ratio = contrast(slate.discGlyph(hue), disc)
            assertTrue(
                "slate/$name: the glyph is ${"%.2f".format(ratio)}:1 on its disc, under 3",
                ratio >= 3f,
            )
        }
    }

    /**
     * The aurora is softer on the lifted ground than on the void.
     *
     * The same two glows in the same two corners at the same radii on both, so strength
     * is the only thing that separates a bloom on near-black from a wash on a page five
     * L* higher — and it is the number a partial port leaves alone.
     */
    @Test
    fun `the slate lays its glows on more softly than the void`() {
        assertTrue(
            "the slate's warm glow ${slate.backdropGlowWarm} is not softer than the " +
                "void's ${dark.backdropGlowWarm}",
            slate.backdropGlowWarm < dark.backdropGlowWarm,
        )
        assertTrue(
            "the slate's cool glow ${slate.backdropGlowCool} is not softer than the " +
                "void's ${dark.backdropGlowCool}",
            slate.backdropGlowCool < dark.backdropGlowCool,
        )
    }

    /** The flag both palettes carry, since the two derived recipes read it. */
    @Test
    fun `each palette knows which one it is`() {
        assertTrue(slate.isSlate)
        assertTrue(!dark.isSlate)
    }

    /**
     * CIE L*, the perceptual lightness this ground was designed in.
     *
     * Relative luminance separates two dark surfaces by a hundredth and tells you
     * nothing about whether an eye can see the difference; L* is the scale on which
     * "a card stands off its page" is a number rather than an opinion.
     */
    private fun lstar(color: Color): Float {
        val y = color.luminance()
        val f = if (y > 0.008856f) {
            Math.cbrt(y.toDouble()).toFloat()
        } else {
            7.787f * y + 16f / 116f
        }
        return 116f * f - 16f
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
