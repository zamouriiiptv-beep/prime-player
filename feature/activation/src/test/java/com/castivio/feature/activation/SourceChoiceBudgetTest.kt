package com.castivio.feature.activation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Spacing
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The source choice fits its viewport, with the numbers the device will use.
 *
 * ## Why this is a second file and not another assertion next door
 *
 * `SourceChoiceLayoutTest` asks Compose where it put things, and that is the right
 * question for placement — the grid, the equal cards, the order, the direction, the
 * absence of a scroll. It cannot answer *fit*, because Robolectric does not lay text
 * out: every `Text` measures 35dp there whatever its declared style, which inflates
 * four two-line cards by about 60dp. On a 360dp frame that turns a layout with 8dp to
 * spare into a failing assertion about the harness. `ActivationBudgetTest` made the
 * same separation for the address screen and for the same reason.
 *
 * So fit is computed here, on the JVM, from [SourceMetrics] — the same table the
 * screen is built from, read rather than copied, so a change to a frame's numbers
 * reaches this budget instead of silently invalidating it.
 *
 * ## The question this file asks changed direction
 *
 * It used to add the content up and check it fitted the frame. The grid is sized from
 * the frame now — `weight(1f)` — and the cards take what the header, the subtitle and
 * the assurance strip leave, so overflow is structural: a weighted
 * child cannot push its siblings out, and the sum is the frame by construction.
 *
 * What can go wrong instead is the opposite. If the frame is short enough, the derived
 * card becomes smaller than the type inside it and the text clips rather than the
 * layout overflowing — a quieter failure and a worse one. So this file derives the card
 * height the way the layout does, and asserts it stays above what a title over *two*
 * lines of description needs, on every frame.
 */
class SourceChoiceBudgetTest {

    /* Read off the frame table the screen is built from, at a font scale of one --
     * which is what the drawings in `design/mockups/` are rendered at. A user-raised
     * scale is the accessibility pass, not this one. */

    private val INSET_ALLOWANCE = 24.dp

    private val SHORTEST = 360.dp
    private val HANDSET = 393.dp
    private val TELEVISION = 540.dp

    private val TABLET = 800.dp

    private val frames = listOf(
        Triple("shortest phone", false, SHORTEST),
        Triple("reference phone", false, HANDSET),
        Triple("tablet", false, TABLET),
        Triple("television", true, TELEVISION),
    )

    private fun metrics(frame: Dp, tv: Boolean) = sourceMetricsFor(tv = tv, available = frame)

    /** The card the layout will derive, from the frame it is actually given. */
    private fun card(frame: Dp, tv: Boolean): Dp = metrics(frame, tv).cardHeight(frame)

    /**
     * What one card needs to hold its own type.
     *
     * The disc and the text block sit side by side, so the taller of the two decides,
     * and the padding is paid twice. Nothing here is a constant: every figure is the
     * frame's own, which is what makes this a check on the table rather than a second
     * opinion about it.
     */
    private fun cardNeeds(frame: Dp, tv: Boolean, detailLines: Int): Dp {
        val m = metrics(frame, tv)
        val title = m.fsCard * 1.35f
        val gap = m.cardPad * 0.6f
        val text = title + gap + m.fsDetail * 1.5f * detailLines
        return m.cardPad * 2 + maxOf(m.disc, text)
    }

    /**
     * The screen reaches for the frame, which is the whole point of the shape.
     *
     * The reference this was approved against fills the screen rather than floating in
     * the middle of it. Asserted as a proportion rather than a number of dp, because
     * the frames differ and the *look* is the ratio.
     */
    @Test
    fun `the content fills most of the band on every frame`() {
        for ((name, tv, frame) in frames) {
            val m = metrics(frame, tv)
            val band = frame - m.stageTop - m.stageBottom
            val content = m.gridHeight(frame) + m.strip
            val share = content.value / band.value
            println("source choice budget — $name content $content of $band")
            assertTrue(
                "the $name content is only ${(share * 100).toInt()}% of the band",
                share >= 0.70f,
            )
        }
    }

    /**
     * The derived card holds a title and one line, with room to spare.
     *
     * This is the claim that replaced "the content fits the frame". The layout cannot
     * overflow; it can only squeeze, and squeezing shows up as clipped type.
     */
    @Test
    fun `the derived card is taller than the type inside it`() {
        for ((name, tv, frame) in frames) {
            val got = card(frame, tv)
            val need = cardNeeds(frame, tv, detailLines = 1)
            println("source choice budget — $name card $got, needs $need for one line")
            assertTrue("the $name card is $got against $need needed", got > need)
        }
    }

    /**
     * And it holds the description at the number of lines that frame draws.
     *
     * All four cards are the same height by construction, so a wrap in one language
     * costs the same in all four -- which is why the reserve is measured against the
     * frame's full line count rather than against one line and a bit.
     */
    @Test
    fun `the derived card still holds a description at its full line count`() {
        for ((name, tv, frame) in frames) {
            val m = metrics(frame, tv)
            val got = card(frame, tv)
            val need = cardNeeds(frame, tv, detailLines = m.detailLines)
            println("source choice budget — $name card $got, needs $need for ${m.detailLines} lines")
            assertTrue("the $name card is $got against $need needed when wrapped", got >= need)
        }
    }

    /**
     * It still holds one line with the system bars back.
     *
     * Activation runs immersive, so on a settled screen the insets are zero and this is
     * spent on nothing. A bar takes 24dp off the frame, which the grid absorbs and
     * passes to the cards; what matters is that they stay above the floor.
     */
    @Test
    fun `every frame still holds its type when the navigation bar comes back`() {
        for ((name, tv, frame) in frames) {
            val short = frame - INSET_ALLOWANCE
            val got = card(short, tv)
            val need = cardNeeds(short, tv, detailLines = 1)
            println("source choice budget — $name with a bar: card $got, needs $need")
            assertTrue("the $name card falls to $got with a bar back", got > need)
        }
    }

    /**
     * Nothing on the screen is ever handed a negative height.
     *
     * The grid is what is left once the header, the subtitle and the strip
     * are placed, and "what is left" is the one number in this layout that
     * can go below zero. A `Column` does not clip when it does -- it hands zero to
     * whatever it measured last, which here is the four cards.
     */
    @Test
    fun `the grid is never handed less than nothing`() {
        for ((name, tv, frame) in frames) {
            for (inset in listOf(0.dp, INSET_ALLOWANCE)) {
                val grid = metrics(frame - inset, tv).gridHeight(frame - inset)
                assertTrue("$name with a $inset bar: the grid is $grid", grid > 0.dp)
            }
        }
    }

    /**
     * A single column of four would not have fitted the handset, which is why the grid
     * is two by two.
     *
     * Stated as a test rather than left in a comment so the premise is checked against
     * the same table as the conclusion.
     */
    @Test
    fun `stacking the four cards would not have fitted the handset`() {
        val m = metrics(HANDSET, tv = false)
        val stacked = m.stageTop + m.header + m.subtitle + m.bandTop +
            (cardNeeds(HANDSET, false, 1) * 4 + m.gridGap * 3) +
            m.stripGap + m.strip + m.stageBottom

        println("source choice budget — a single column would be $stacked")

        assertTrue("a column of four was $stacked, which fits 393 after all", stacked > HANDSET)
    }
}
