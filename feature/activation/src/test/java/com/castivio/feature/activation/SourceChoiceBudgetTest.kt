package com.castivio.feature.activation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Sizing
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
 * So fit is computed here, on the JVM, from the line heights [CastivioType] declares
 * and the tokens the screen is built from. Read rather than copied: a change to the
 * type scale or to [Spacing] reaches this budget instead of silently invalidating it.
 *
 * ## The question this file asks changed direction
 *
 * It used to add the content up and check it fitted the frame. The container is sized
 * from the frame now — `weight(1f)` — and the cards take what it leaves, so overflow is
 * structural: a weighted child cannot push its siblings out, and the sum is the frame
 * by construction.
 *
 * What can go wrong instead is the opposite. If the frame is short enough, the derived
 * card becomes smaller than the type inside it and the text clips rather than the
 * layout overflowing — a quieter failure and a worse one. So this file derives the card
 * height the way the layout does, and asserts it stays above what a title over *two*
 * lines of description needs, on every frame.
 */
class SourceChoiceBudgetTest {

    /* Read off the type scale, at a font scale of one -- which is what the frames in
     * `design/mockups/` are drawn at. A user-raised scale is the accessibility pass,
     * not this one. */
    private val title = CastivioType.headlineMedium.lineHeight.value.dp
    private val cardTitle = CastivioType.titleLarge.lineHeight.value.dp
    private val cardDetail = CastivioType.bodySmall.lineHeight.value.dp

    /** The sentence under the container. One line, which is why it is that sentence. */
    private val terms = CastivioType.bodySmall.lineHeight.value.dp

    /* The tokens the screen is built from, read rather than copied so that a change
     * there reaches this budget instead of silently invalidating it. */
    private fun edge(tv: Boolean) = if (tv) Spacing.tvOverscan else Spacing.screen
    private fun titleGap(tv: Boolean) = if (tv) Spacing.lg else Spacing.sm
    private fun termsGap(tv: Boolean) = if (tv) Spacing.lg else Spacing.xs
    private fun containerPad(tv: Boolean) = if (tv) Spacing.lg else Spacing.sm
    private fun containerGap(tv: Boolean) = if (tv) Spacing.lg else Spacing.xs
    private fun gridGap(tv: Boolean) = if (tv) Spacing.lg else Spacing.sm
    private fun cardPad(tv: Boolean) = if (tv) Spacing.xl else Spacing.sm

    /** What the container is given: everything the fixed parts leave. */
    private fun container(frame: Dp, tv: Boolean): Dp =
        frame - 2 * edge(tv) - title - titleGap(tv) - termsGap(tv) - terms

    /** What one card is given, derived exactly as the layout derives it. */
    private fun card(frame: Dp, tv: Boolean): Dp {
        val grid = container(frame, tv) - 2 * containerPad(tv) -
            containerGap(tv) - Sizing.minTarget(tv)
        return (grid - gridGap(tv)) / 2
    }

    /**
     * What a card must be at least, to hold what is in it without clipping.
     *
     * The icon shares the title's line box rather than sitting above it, and
     * `Sizing.iconMd` is smaller than `titleLarge`'s line height, so it adds nothing —
     * which is the reason it is beside the words and not over them.
     */
    private fun cardNeeds(tv: Boolean, detailLines: Int): Dp =
        2 * cardPad(tv) + maxOf(cardTitle, Sizing.iconMd) + Spacing.xs +
            cardDetail * detailLines

    /**
     * What a landscape gesture bar costs, budgeted even though activation runs
     * immersive: transient bars are one swipe away, and a layout that only fits while
     * the system cooperates breaks in a photograph somebody sends us.
     */
    private val INSET_ALLOWANCE = 24.dp

    /**
     * The frames this screen is drawn for, named once.
     *
     * The shortest is an 800x360 landscape window, which is an ordinary 360dp-wide
     * phone turned sideways; the handset is the reference the design was approved at;
     * the television is 960x540 inside its overscan.
     */
    private val SHORTEST = 360.dp
    private val HANDSET = 393.dp
    private val TELEVISION = 540.dp

    private val frames = listOf(
        Triple("shortest phone", false, SHORTEST),
        Triple("reference handset", false, HANDSET),
        Triple("television", true, TELEVISION),
    )

    /**
     * The container reaches for the frame, which is the whole point of the shape.
     *
     * The reference the design was approved against has the surface covering most of
     * the screen rather than floating in the middle of it. Asserted as a proportion
     * rather than a number of dp, because the frames differ and the *look* is the
     * ratio.
     */
    @Test
    fun `the container fills most of the band on every frame`() {
        for ((name, tv, frame) in frames) {
            val band = frame - 2 * edge(tv)
            val share = container(frame, tv).value / band.value
            println("source choice budget — $name container ${container(frame, tv)} of $band")
            assertTrue(
                "the $name container is only ${(share * 100).toInt()}% of the band",
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
            val need = cardNeeds(tv, detailLines = 1)
            println("source choice budget — $name card $got, needs $need for one line")
            assertTrue("the $name card is $got against $need needed", got > need)
        }
    }

    /**
     * And it holds a description that wraps to two lines. This is the reserve.
     *
     * All four cards are the same height by construction, so a wrap in one language
     * costs the same in all four — which is why the reserve is measured against two
     * lines rather than one and a bit.
     */
    @Test
    fun `the derived card still holds a description that wraps`() {
        for ((name, tv, frame) in frames) {
            val got = card(frame, tv)
            val need = cardNeeds(tv, detailLines = 2)
            println("source choice budget — $name card $got, needs $need for two lines")
            assertTrue("the $name card is $got against $need needed when wrapped", got >= need)
        }
    }

    /**
     * It still holds one line with the system bars back.
     *
     * Activation runs immersive, so on a settled screen the insets are zero and this is
     * spent on nothing. A bar takes 24dp off the frame, which the container absorbs and
     * passes to the cards; what matters is that they stay above the floor.
     */
    @Test
    fun `every frame still holds its type when the navigation bar comes back`() {
        for ((name, tv, frame) in frames) {
            val got = card(frame - INSET_ALLOWANCE, tv)
            val need = cardNeeds(tv, detailLines = 1)
            println("source choice budget — $name with a bar: card $got, needs $need")
            assertTrue("the $name card falls to $got with a bar back", got > need)
        }
    }

    /**
     * A single column of four would not have fitted the handset, which is why the grid
     * is two by two.
     *
     * Stated as a test rather than left in a comment so the premise is checked against
     * the same tokens as the conclusion.
     */
    @Test
    fun `stacking the four cards would not have fitted the handset`() {
        val stacked = Spacing.screen + title + titleGap(false) +
            (cardNeeds(false, 1) * 4 + gridGap(false) * 3) + containerGap(false) +
            Sizing.minTarget(false) + termsGap(false) + terms + Spacing.screen

        println("source choice budget — a single column would be $stacked")

        assertTrue("a column of four was $stacked, which fits 393 after all", stacked > HANDSET)
    }
}
