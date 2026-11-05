package com.castivio.feature.activation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.components.BODY_LEADING
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
 * So fit is computed here, on the JVM, from [SourceMetrics] — the same declarations
 * the screen is built from, read rather than copied, so a change to a share or a bound
 * reaches this budget instead of silently invalidating it.
 *
 * ## The question this file asks changed direction
 *
 * It used to add the content up and check it fitted the frame. The grid is sized from
 * the frame now — `weight(1f)` — and the cards take what the header
 * leaves, so overflow is structural: a weighted
 * child cannot push its siblings out, and the sum is the frame by construction.
 *
 * What can go wrong instead is the opposite. If the frame is short enough, the derived
 * card becomes smaller than the type inside it and the text clips rather than the
 * layout overflowing — a quieter failure and a worse one. So this file derives the card
 * height the way the layout does, and asserts it holds the card's type.
 *
 * ## What it holds, and what it stopped holding (2026-11-05)
 *
 * It asserted that the card held the name over *two* lines of description with the disc at
 * its full size, on every frame. The cards are shorter than that since the footnote and the
 * two tiers: the reference phone's is 69.6dp against 83.8dp for two lines, the television's 98.7dp
 * against 104dp, the shortest phone's 61.7dp against 81.3dp. The owner's decision, on those numbers, was to
 * hold what the layout can keep and say the rest:
 *
 *  - the **name and one line of description** fit inside the card's padding, on every frame
 *    but the shortest phone, which the owner left out of the budget;
 *  - the **disc** is no smaller than [DISC_FLOOR] of its size: it is the one thing in the card that gives, because it is sized
 *    with `size()` and takes what the padding leaves;
 *  - **a second line of description**, when a language wraps one, is no longer promised room: the
 *    screen cuts it at its `maxLines`, as it always has.
 */
class SourceChoiceBudgetTest {

    /* Read off the sizing system the screen is built from, at a font scale of one --
     * which is what the drawings in `design/mockups/` are rendered at. A user-raised
     * scale is the accessibility pass, not this one. */

    private val INSET_ALLOWANCE = 24.dp

    /**
     * The surfaces, each with the width it actually has.
     *
     * The width used to be absent, because the numbers came from a table chosen by
     * height alone. They are shares of both axes now — a card's disc off the height,
     * the footnote's cell count off the width — so a surface is a size and not a
     * number, and the four here are real devices rather than four rows.
     */
    private data class Surface(val name: String, val width: Dp, val height: Dp, val tv: Boolean) {
        override fun toString() = name
    }

    private val SHORTEST = Surface("shortest phone", 800.dp, 360.dp, tv = false)
    private val HANDSET = Surface("reference phone", 873.dp, 393.dp, tv = false)
    private val TABLET = Surface("tablet", 1280.dp, 800.dp, tv = false)
    private val TELEVISION = Surface("television", 960.dp, 540.dp, tv = true)

    private val frames = listOf(SHORTEST, HANDSET, TABLET, TELEVISION)

    /** The frames the card's budget is held on: all but the shortest phone, which the owner left out of it. */
    private val fitted = listOf(HANDSET, TABLET, TELEVISION)

    /** How much of its size the disc keeps at least, when the card's padding leaves it less room than that. */
    private val DISC_FLOOR = 0.8f

    /** The same surface with a navigation bar on it: shorter, and exactly as wide. */
    private fun Surface.lessBar(inset: Dp) = copy(height = height - inset)

    private fun metrics(s: Surface) = sourceMetricsFor(tv = s.tv, width = s.width, height = s.height)

    /** The card the layout will derive, from the surface it is actually given. */
    private fun card(s: Surface): Dp = metrics(s).cardHeight(s.height)

    /**
     * What one card needs to hold its own type.
     *
     * The disc and the text block sit side by side, so the taller of the two decides,
     * and the padding is paid twice. Nothing here is a constant: every figure is the
     * frame's own, which is what makes this a check on the table rather than a second
     * opinion about it.
     */
    private fun cardNeeds(s: Surface, detailLines: Int): Dp {
        val m = metrics(s)
        val title = m.fsCard * 1.35f
        val gap = m.cardPad * 0.6f
        val text = title + gap + m.fsDetail * 1.5f * detailLines
        return m.cardPad * 2 + maxOf(m.disc, text)
    }

    /**
     * What the card's text block needs: the name, the gap the screen puts under it, and [detailLines] of description.
     * The gap and the leading are the screen's and the style's own, read and not copied.
     */
    private fun textNeeds(s: Surface, detailLines: Int): Dp {
        val m = metrics(s)
        return m.fsCard * 1.35f + m.cardPad * TEXT_GAP + m.fsDetail * BODY_LEADING * detailLines
    }

    /** The room inside the card's padding, which the disc and the text block share. */
    private fun inner(s: Surface): Dp = card(s) - metrics(s).cardPad * 2

    /**
     * The screen reaches for the frame, which is the whole point of the shape.
     *
     * The reference this was approved against fills the screen rather than floating in
     * the middle of it. Asserted as a proportion rather than a number of dp, because
     * the frames differ and the *look* is the ratio.
     */
    @Test
    fun `the content fills most of the band on every frame`() {
        for (s in frames) {
            val m = metrics(s)
            val band = s.height - m.stageTop - m.stageBottom
            val content = m.bandHeight(s.height)
            val share = content.value / band.value
            println("source choice budget — $s content $content of $band")
            assertTrue(
                "the $s content is only ${(share * 100).toInt()}% of the band",
                share >= 0.70f,
            )
        }
    }

    /**
     * The derived card holds the name and one line of description, and the disc no smaller than [DISC_FLOOR] of its size.
     *
     * This is the claim that replaced "the content fits the frame". The layout cannot
     * overflow; it can only squeeze, and squeezing shows up as clipped type.
     */
    @Test
    fun `the derived card is taller than the type inside it`() {
        for (s in fitted) {
            val room = inner(s)
            val need = textNeeds(s, detailLines = 1)
            val disc = metrics(s).disc
            println("source choice budget — $s room $room, the name and a line need $need, disc $disc")
            assertTrue("the $s card has $room inside it against $need needed", room >= need)
            assertTrue("the $s disc is squeezed to $room of $disc", room >= disc * DISC_FLOOR)
        }
    }

    /**
     * It still holds them with the system bars back.
     *
     * Activation runs immersive, so on a settled screen the insets are zero and this is
     * spent on nothing. A bar takes 24dp off the frame, which the grid absorbs and
     * passes to the cards; what matters is that they stay above the floor.
     */
    @Test
    fun `every frame still holds its type when the navigation bar comes back`() {
        for (s in fitted) {
            val short = s.lessBar(INSET_ALLOWANCE)
            val room = inner(short)
            val need = textNeeds(short, detailLines = 1)
            val disc = metrics(short).disc
            println("source choice budget — $s with a bar: room $room, needs $need, disc $disc")
            assertTrue("the $s card falls to $room inside it against $need with a bar back", room >= need)
            assertTrue("the $s disc is squeezed to $room of $disc with a bar back", room >= disc * DISC_FLOOR)
        }
    }

    /**
     * Nothing on the screen is ever handed a negative height.
     *
     * The band is what is left once the header is placed, and "what is left" is the one
     * number in this layout that can go below zero. A `Column` does not clip when it
     * does -- it hands zero to whatever it measured last, which here is the cards.
     */
    @Test
    fun `the band is never handed less than nothing`() {
        for (s in frames) {
            for (inset in listOf(0.dp, INSET_ALLOWANCE)) {
                val shorter = s.lessBar(inset)
                val m = metrics(shorter)
                val band = m.bandHeight(shorter.height)
                assertTrue("$s with a $inset bar: the band is $band", band > 0.dp)
                assertTrue(
                    "$s with a $inset bar: a leading card is ${m.leadHeight(shorter.height)}",
                    m.leadHeight(shorter.height) > 0.dp,
                )
            }
        }
    }

    /**
     * A column of four would still not fit the handset, which is why only two of them
     * take the full width.
     *
     * Stated as a test rather than left in a comment so the premise is checked against
     * the same table as the conclusion. It is the reason the two that are not a new
     * subscription share a row under the rule instead of each taking a band of their
     * own: the hierarchy is affordable, four full-width cards are not.
     */
    @Test
    fun `stacking all four cards would not have fitted the handset`() {
        val m = metrics(HANDSET)
        val stacked = m.stageTop + m.header + m.bandTop +
            (cardNeeds(HANDSET, 1) * 4 + m.gridGap * 3 + m.divider) +
            m.stageBottom

        println("source choice budget — a single column would be $stacked")

        assertTrue(
            "a column of four was $stacked, which fits ${HANDSET.height} after all",
            stacked > HANDSET.height,
        )
    }
}
