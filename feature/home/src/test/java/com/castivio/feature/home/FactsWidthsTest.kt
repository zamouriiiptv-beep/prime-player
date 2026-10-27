package com.castivio.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the facts bar shares its width, as numbers.
 *
 * The bar had given each cuttable fact an equal share of what was left whether it needed it or not, so a short
 * category kept room it did not use and the genres were cut with empty bar beside them. The rule that replaced
 * it is pure, and these hold the cases the screen showed.
 */
class FactsWidthsTest {

    /** Everything fits: every fact keeps its own width and nothing is cut. */
    @Test
    fun `facts that fit keep their widths`() {
        val widths = shareWidths(natural = listOf(200, 20, 90, 20, 300), shrinks = listOf(true, false, false, false, true), available = 900)
        assertEquals(listOf(200, 20, 90, 20, 300), widths)
    }

    /** The screen's case: a short category and long genres; the genres get what the category does not use. */
    @Test
    fun `a short fact leaves its unused room to the long one`() {
        val natural = listOf(240, 20, 70, 20, 80, 20, 700)
        val shrinks = listOf(true, false, false, false, false, false, true)
        val widths = shareWidths(natural, shrinks, available = 900)
        assertEquals(240, widths[0])
        assertEquals(900 - (240 + 20 + 70 + 20 + 80 + 20), widths[6])
    }

    /** Two long ones that cannot both fit are cut alike. */
    @Test
    fun `two long facts share the room equally`() {
        val widths = shareWidths(natural = listOf(600, 20, 600), shrinks = listOf(true, false, true), available = 520)
        assertEquals(listOf(250, 20, 250), widths)
    }

    /** The ones that may not be cut keep their width however narrow the bar is. */
    @Test
    fun `fixed facts are never cut`() {
        val widths = shareWidths(natural = listOf(500, 20, 90), shrinks = listOf(true, false, false), available = 300)
        assertEquals(90, widths[2])
        assertEquals(20, widths[1])
        assertTrue(widths[0] in 0..190)
    }

    /** A bar too narrow for the fixed ones gives the cuttable ones nothing, and never a negative width. */
    @Test
    fun `no negative width`() {
        val widths = shareWidths(natural = listOf(400, 90, 400), shrinks = listOf(true, false, true), available = 50)
        assertEquals(listOf(0, 90, 0), widths)
    }

    /** The widths never add up to more than the bar has, when the fixed ones fit it. */
    @Test
    fun `the widths fit the bar`() {
        val natural = listOf(333, 20, 71, 20, 640)
        val shrinks = listOf(true, false, false, false, true)
        for (available in 200..1200 step 37) {
            val widths = shareWidths(natural, shrinks, available)
            assertTrue("$available -> ${widths.sum()}", widths.sum() <= available)
        }
    }
}
