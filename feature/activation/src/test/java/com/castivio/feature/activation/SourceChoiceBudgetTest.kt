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
 * `SourceChoiceLayoutTest` asks Compose where it put things, and that is the
 * right question for placement — the row, the order, the direction, the absence
 * of a scroll. It cannot answer *fit*, because Robolectric does not lay text out:
 * every `Text` measures 35dp there whatever its declared style, which inflates
 * this screen by about 50dp. On a 393dp frame that turns a comfortable layout
 * into a failing assertion about the harness. `ActivationBudgetTest` made the
 * same separation for the address screen and for the same reason.
 *
 * So fit is computed here, on the JVM, from the line heights [CastivioType]
 * declares and the tokens the screen is built from. Read rather than copied: a
 * change to the type scale or to [Spacing] reaches this budget instead of
 * silently invalidating it.
 *
 * ## What overran, and by how much
 *
 * The screen used to stack its two cards, and stacked it needed 424dp against
 * the 393 a landscape handset has. It was inside `verticalScroll`, so the 31dp
 * became a scroll rather than a clip — the title left the top of the screen or
 * Back left the bottom, depending on where the user had pushed the page. Side by
 * side the two cards occupy one band instead of two, and the same content is
 * [SIDE_BY_SIDE] tall.
 *
 * The frame is fixed now, which means an overrun would no longer scroll: it would
 * clip, silently, at whichever end the centring arrangement pushed past. That is
 * the failure this file is here to make loud.
 */
class SourceChoiceBudgetTest {

    /* Read off the type scale, at a font scale of one -- which is what the
     * frames in `design/mockups/` are drawn at. A user-raised scale is the
     * accessibility pass, not this one. */
    private val headline = CastivioType.headlineMedium.lineHeight.value.dp
    private val subtitle = CastivioType.bodyLarge.lineHeight.value.dp
    private val cardTitle = CastivioType.titleLarge.lineHeight.value.dp
    private val cardDetail = CastivioType.bodySmall.lineHeight.value.dp

    /** Title over subtitle, `Spacing.sm` apart, as `Heading` composes them. */
    private val heading: Dp get() = headline + Spacing.sm + subtitle

    /**
     * One card: its vertical padding around a title and a line.
     *
     * @param detailLines what a translation does to the second line. One is
     *   Arabic and English; the parameter is here because the margin below is
     *   the answer to "and if it wraps?".
     */
    private fun card(tv: Boolean, detailLines: Int = 1): Dp {
        val pad = if (tv) Spacing.xxxl else Spacing.xl
        return pad + cardTitle + Spacing.xs + cardDetail * detailLines + pad
    }

    /**
     * Everything, top to bottom.
     *
     * `screenPadding` at both ends, `Spacing.xxl` between the three children, and
     * the row is the height of one card because the two sit beside each other.
     * Back is a `CastivioButton`, whose floor is [Sizing.minTarget] — its text is
     * shorter than that on every locale in the set, so the floor is the height.
     */
    private fun height(tv: Boolean, detailLines: Int = 1): Dp {
        val edge = if (tv) Spacing.tvOverscan else Spacing.screen
        return edge + heading + Spacing.xxl + card(tv, detailLines) + Spacing.xxl +
            Sizing.minTarget(tv) + edge
    }

    /** What the shipped screen comes to on a landscape handset. */
    private val SIDE_BY_SIDE: Dp get() = height(tv = false)

    /**
     * The frames, as `ActivationLayoutTest` states them.
     *
     * The shortest is 360dp and it is not hypothetical — it is the frame that
     * made the card's vertical padding a per-device figure, because a television's
     * `Spacing.xxxl` overran it by 8dp.
     */
    private val SHORTEST = 360.dp
    private val HANDSET = 393.dp
    private val TELEVISION = 540.dp

    /**
     * What a landscape gesture bar costs, budgeted even though activation runs
     * immersive: transient bars are one swipe away, and a layout that only fits
     * while the system cooperates breaks in a photograph somebody sends us.
     */
    private val INSET_ALLOWANCE = 24.dp

    @Test
    fun `the screen fits every frame it is drawn for`() {
        val short = SHORTEST - height(tv = false)
        val handset = HANDSET - height(tv = false)
        val tv = TELEVISION - height(tv = true)

        println(
            "source choice budget — handset content $SIDE_BY_SIDE | " +
                "800x360 $short | 827x393 $handset | TV 960x540 $tv | " +
                "with a $INSET_ALLOWANCE bar: ${short - INSET_ALLOWANCE}",
        )

        assertTrue("the shortest frame overruns by ${-short}", short > 0.dp)
        assertTrue("the reference handset overruns by ${-handset}", handset > 0.dp)
        assertTrue("the television overruns by ${-tv}", tv > 0.dp)
    }

    /**
     * It still fits with the system bars back, on the frame with least to spare.
     */
    @Test
    fun `every frame still fits when the navigation bar comes back`() {
        for ((name, tv, frame) in listOf(
            Triple("shortest phone", false, SHORTEST),
            Triple("reference handset", false, HANDSET),
            Triple("television", true, TELEVISION),
        )) {
            val margin = frame - height(tv) - INSET_ALLOWANCE
            assertTrue("the $name overruns by ${-margin} with a bar back", margin > 0.dp)
        }
    }

    /**
     * And it fits where a translation wraps the detail line to two.
     *
     * The card grows by one `bodySmall` line and nothing else does. This is the
     * headroom the deep card padding spends, and the number to look at first if a
     * language ever does overrun: taking the card's vertical padding from
     * `Spacing.xxxl` to `Spacing.xl` returns 48dp without touching the type.
     */
    @Test
    fun `every frame still fits when a translation wraps the detail line`() {
        val short = SHORTEST - height(tv = false, detailLines = 2)
        val handset = HANDSET - height(tv = false, detailLines = 2)
        val tv = TELEVISION - height(tv = true, detailLines = 2)

        println("source choice budget — wrapped detail: 360 $short | 393 $handset | 540 $tv")

        assertTrue("a wrapped detail overruns the shortest frame by ${-short}", short > 0.dp)
        assertTrue("a wrapped detail overruns the handset by ${-handset}", handset > 0.dp)
        assertTrue("a wrapped detail overruns the television by ${-tv}", tv > 0.dp)
    }

    /**
     * The stacked layout did not fit, which is why it is gone.
     *
     * Stated as a test rather than left in a comment so the premise is checked
     * against the same tokens as the conclusion. If a future spacing change ever
     * made a column fit on a landscape handset, this fails and somebody gets to
     * re-open a decision on evidence instead of inheriting it.
     */
    @Test
    fun `stacking the cards would not have fitted`() {
        val stacked = Spacing.screen + heading + Spacing.xl + card(tv = false) + Spacing.xl +
            card(tv = false) + Spacing.xl + Sizing.minTarget(false) + Spacing.screen
        println("source choice budget — stacked would be $stacked against $HANDSET")
        assertTrue("the stacked column was $stacked, which fits after all", stacked > HANDSET)
    }
}
