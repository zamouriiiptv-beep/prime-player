package com.castivio.feature.activation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The language picker's last two sizes, after they stopped asking what device this is.
 *
 * `if (tv) Spacing.md else Spacing.sm` set the gap between the grid's columns and the
 * inset inside a row; `if (tv) Spacing.sm else Spacing.xs` set the gap between a tick
 * and the name beside it. Two two-row tables, found in the final sweep of every `isTv`
 * in the product rather than in the audit that opened it — which is the argument for
 * mechanising the rule instead of re-reading the tree.
 *
 * The focus ring's weight is deliberately **not** here. It is still `if (tv) 2 else 1`,
 * and it is the one device branch this screen keeps: a ring is how a television says
 * where the remote is, a hairline that reads at 30cm does not read at three metres, and
 * a fractional-dp border is a border that disappears at some densities.
 */
class PickerGapsTest {

    private val widths = listOf(568.dp, 800.dp, 873.dp, 960.dp, 1280.dp, 1920.dp, 3840.dp)
    private val heights = listOf(330.dp, 360.dp, 393.dp, 540.dp, 720.dp, 1080.dp, 2160.dp)

    /** The television reproduces the two numbers it was drawn with, to the dp. */
    @Test
    fun `the television reproduces its approved gaps`() {
        assertEquals("between cells", 12f, cellGapFor(960.dp).value, 0.1f)
        assertEquals("tick to name", 8f, tickGapFor(540.dp).value, 0.1f)
    }

    /** Both are bounded at both ends, on every surface the product reaches. */
    @Test
    fun `both gaps stay inside their bounds`() {
        for (width in widths) {
            assertTrue("cell gap at $width is ${cellGapFor(width)}", cellGapFor(width) in 8.dp..18.dp)
        }
        for (height in heights) {
            assertTrue("tick gap at $height is ${tickGapFor(height)}", tickGapFor(height) in 4.dp..12.dp)
        }
    }

    /** Neither ever shrinks as the surface grows, and both stop. */
    @Test
    fun `both only grow with the surface, and stop`() {
        for ((a, b) in widths.zipWithNext()) {
            assertTrue("the cell gap shrank from $a to $b", cellGapFor(b) >= cellGapFor(a))
        }
        for ((a, b) in heights.zipWithNext()) {
            assertTrue("the tick gap shrank from $a to $b", tickGapFor(b) >= tickGapFor(a))
        }
        assertEquals("the 4K cell gap is unbounded", 18f, cellGapFor(3840.dp).value, 0.01f)
        assertEquals("the 4K tick gap is unbounded", 12f, tickGapFor(2160.dp).value, 0.01f)
    }

    /**
     * A row's inset never eats the column it is drawn in.
     *
     * The cell gap is spent twice on every row — once between the columns and once
     * inside each of them — so on the narrowest panel the grid draws, what is left for
     * a tick and the longest of thirty-seven names has to stay a readable column.
     */
    @Test
    fun `a name keeps its column at every width`() {
        var narrowest = Dp.Infinity
        var narrowestAt = ""
        var width = 330.dp * (16f / 9f)
        while (width <= 3840.dp) {
            val gap = cellGapFor(width)
            val inner = 216.dp - gap * 2 - 20.dp
            if (inner < narrowest) {
                narrowest = inner
                narrowestAt = "$width"
            }
            width += 1.dp
        }
        println("picker sweep — narrowest name column $narrowest at $narrowestAt")
        assertTrue("a name has only $narrowest at $narrowestAt", narrowest >= 150.dp)
    }
}
