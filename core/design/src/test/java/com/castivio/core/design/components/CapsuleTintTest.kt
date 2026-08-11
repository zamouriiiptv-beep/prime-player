package com.castivio.core.design.components

import androidx.compose.ui.graphics.Color
import com.castivio.core.design.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capsule tint, checked as arithmetic rather than as pixels.
 *
 * Three of these four claims are about things a layout test cannot see — a
 * gradient's direction, an average alpha, and a control being darker than what
 * holds it — and all three are the kind of mistake that ships looking almost
 * right. The functions are pure, so none of it needs a device.
 */
class CapsuleTintTest {

    /** A mid-tone of the aurora the pills are drawn over. */
    private val ground = Color(0xFF261F4E)

    /**
     * The deep end is always the end the copy control sits at.
     *
     * `Brush.horizontalGradient` paints left to right and does not know about
     * the paragraph, so the stops are reversed by hand for a right-to-left
     * layout. Backwards, and Arabic gets its brightest point exactly where the
     * control is — visible on every Castivio install in an RTL market and on
     * none of the ones the author would look at.
     */
    @Test
    fun `the gradient's deep end follows the layout direction`() {
        for (tint in listOf(CapsuleTint.Azure, CapsuleTint.Violet)) {
            val ltr = capsuleStops(tint, rtl = false)
            val rtl = capsuleStops(tint, rtl = true)

            // Left to right the control is on the right, so the last stop is
            // the deep one; right to left it is the first.
            assertTrue("$tint: LTR does not deepen rightward", ltr.last().alpha < ltr.first().alpha)
            assertTrue("$tint: RTL does not deepen leftward", rtl.first().alpha < rtl.last().alpha)
            assertEquals("$tint: the two directions are not mirrors", ltr, rtl.reversed())
        }
    }

    /**
     * The pill is the brightness it has always been, redistributed.
     *
     * `glassFill` is `GlassLow`, white at 3.9%. The brief was to change the
     * glass's temperature and depth and *not* to make it brighter, so the two
     * stops have to average what the flat fill was — otherwise the screen gains
     * a lift nobody asked for and the tint gets blamed for it.
     */
    @Test
    fun `the two stops average the flat fill they replace`() {
        for (tint in listOf(CapsuleTint.Azure, CapsuleTint.Violet)) {
            val mean = capsuleStops(tint, rtl = false).map { it.alpha }.average()
            assertEquals("$tint lifts the pill", Palette.GlassLow.alpha.toDouble(), mean, 0.005)
        }
    }

    /** The control is a well in the surface, never a disc on top of it. */
    @Test
    fun `the copy control is darker than the pill it sits in`() {
        for (tint in listOf(CapsuleTint.Azure, CapsuleTint.Violet)) {
            val well = requireNotNull(capsuleWell(tint))
            val deepest = capsuleStops(tint, rtl = false).last()
            // Both are drawn over the same aurora, so comparing what each does
            // to a mid-tone of it is comparing the two surfaces themselves.
            assertTrue(
                "$tint's control is not darker than its pill",
                over(well, ground) < over(deepest, ground),
            )
        }
    }

    /** And an untinted capsule is untouched, which is what the licence screen relies on. */
    @Test
    fun `no tint draws exactly what it always drew`() {
        assertTrue(capsuleStops(CapsuleTint.None, rtl = false).isEmpty())
        assertTrue(capsuleStops(CapsuleTint.None, rtl = true).isEmpty())
        assertNull(capsuleWell(CapsuleTint.None))
        assertEquals(
            Palette.GlassEdgeSoft,
            capsuleEdge(CapsuleTint.None, Palette.GlassEdgeSoft),
        )
    }

    /** Luminance of [layer] composited over [ground], which is what the eye gets. */
    private fun over(layer: Color, ground: Color): Float {
        val a = layer.alpha
        val r = ground.red + (layer.red - ground.red) * a
        val g = ground.green + (layer.green - ground.green) * a
        val b = ground.blue + (layer.blue - ground.blue) * a
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }
}
