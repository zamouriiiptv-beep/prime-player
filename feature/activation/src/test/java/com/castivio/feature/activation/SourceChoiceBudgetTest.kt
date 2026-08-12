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
 * ## The frame that decides everything
 *
 * Not the reference handset — the shortest one. A 360dp-tall landscape window is an
 * ordinary 360dp-wide phone turned sideways, which is most of them, and four cards
 * carrying a description each leave it 8dp. Every spacing token on this screen was
 * chosen against that number: `Spacing.xxl` between the three groups overran it by 8
 * and `Spacing.xl` fits by 8.
 */
class SourceChoiceBudgetTest {

    /* Read off the type scale, at a font scale of one -- which is what the frames in
     * `design/mockups/` are drawn at. A user-raised scale is the accessibility pass,
     * not this one. */
    private val title = CastivioType.headlineMedium.lineHeight.value.dp
    private val cardTitle = CastivioType.titleLarge.lineHeight.value.dp
    private val cardDetail = CastivioType.bodySmall.lineHeight.value.dp

    /**
     * One card: its padding around a title line and a description.
     *
     * The icon shares the title's line box rather than sitting above it, and
     * `Sizing.iconMd` is smaller than `titleLarge`'s line height, so it adds nothing —
     * which is the reason it is beside the words and not over them.
     *
     * @param detailLines what a translation does to the description. One is Arabic and
     *   English; the parameter is here because the margin below is the answer to "and
     *   if it wraps?".
     */
    private fun card(tv: Boolean, detailLines: Int = 1): Dp {
        val pad = if (tv) Spacing.xl else Spacing.lg
        val titleRow = maxOf(cardTitle, Sizing.iconMd)
        return pad + titleRow + Spacing.xs + cardDetail * detailLines + pad
    }

    /** Two rows of cards, one grid gap between them. */
    private fun grid(tv: Boolean, detailLines: Int = 1): Dp =
        card(tv, detailLines) * 2 + (if (tv) Spacing.xl else Spacing.lg)

    /**
     * Everything, top to bottom.
     *
     * `screenPadding` at both ends, the group gap twice, and a footer whose height is
     * Back's — the Terms link shares that row and is shorter than it, so it costs the
     * column nothing.
     */
    private fun height(tv: Boolean, detailLines: Int = 1): Dp {
        val edge = if (tv) Spacing.tvOverscan else Spacing.screen
        val group = if (tv) Spacing.xxl else Spacing.xl
        return edge + title + group + grid(tv, detailLines) + group +
            Sizing.minTarget(tv) + edge
    }

    /**
     * What a landscape gesture bar costs, budgeted even though activation runs
     * immersive: transient bars are one swipe away, and a layout that only fits while
     * the system cooperates breaks in a photograph somebody sends us.
     */
    private val INSET_ALLOWANCE = 24.dp

    /** The frames, as `ActivationLayoutTest` states them. */
    private val SHORTEST = 360.dp
    private val HANDSET = 393.dp
    private val TELEVISION = 540.dp

    @Test
    fun `the screen fits every frame it is drawn for`() {
        val short = SHORTEST - height(tv = false)
        val handset = HANDSET - height(tv = false)
        val tv = TELEVISION - height(tv = true)

        println(
            "source choice budget — phone content ${height(tv = false)}, " +
                "tv content ${height(tv = true)} | 360 $short | 393 $handset | 540 $tv",
        )

        assertTrue("the shortest frame overruns by ${-short}", short > 0.dp)
        assertTrue("the reference handset overruns by ${-handset}", handset > 0.dp)
        assertTrue("the television overruns by ${-tv}", tv > 0.dp)
    }

    /**
     * The shortest frame's margin, pinned to the number it actually has.
     *
     * Eight dp is not comfortable and saying so in a comment would not stop the next
     * spacing change from spending it. If this fails downward something grew and the
     * screen is about to clip on a very ordinary phone; if it fails upward the layout
     * got roomier and the number here should be raised to lock the gain in.
     */
    @Test
    fun `the shortest frame keeps the margin the group gap bought it`() {
        val short = SHORTEST - height(tv = false)
        assertTrue("the shortest frame is down to $short", short >= 8.dp)
    }

    /**
     * It still fits with the system bars back — on the two frames that can take it.
     *
     * The shortest frame cannot, and that is stated rather than asserted away: 8dp of
     * margin does not absorb a 24dp gesture bar. Activation runs immersive, so on a
     * settled screen the insets are zero; a user who swipes the bar back on a 360dp
     * handset is the one case this screen has no room for, and it is a measured,
     * known limit rather than an unexamined one.
     */
    @Test
    fun `the roomier frames still fit when the navigation bar comes back`() {
        val handset = HANDSET - height(tv = false) - INSET_ALLOWANCE
        val tv = TELEVISION - height(tv = true) - INSET_ALLOWANCE

        println("source choice budget — with a $INSET_ALLOWANCE bar: 393 $handset | 540 $tv")

        assertTrue("the handset overruns by ${-handset} with a bar back", handset > 0.dp)
        assertTrue("the television overruns by ${-tv} with a bar back", tv > 0.dp)
    }

    /**
     * And it fits where a translation wraps one description to two lines.
     *
     * All four cards grow together — that is what the intrinsic pass in `SourceGrid`
     * buys — so the cost is two lines, not one. The reference handset absorbs it; the
     * shortest frame does not, which is the same 8dp said a second way.
     */
    @Test
    fun `the reference frames still fit when a translation wraps a description`() {
        val handset = HANDSET - height(tv = false, detailLines = 2)
        val tv = TELEVISION - height(tv = true, detailLines = 2)
        val short = SHORTEST - height(tv = false, detailLines = 2)

        println("source choice budget — wrapped description: 360 $short | 393 $handset | 540 $tv")

        assertTrue("a wrapped description overruns the handset by ${-handset}", handset > 0.dp)
        assertTrue("a wrapped description overruns the television by ${-tv}", tv > 0.dp)
    }

    /**
     * A single column of four would not have fitted, which is why there is not one.
     *
     * Stated as a test rather than left in a comment so the premise is checked against
     * the same tokens as the conclusion. The four cards stacked come to more than any
     * frame Castivio draws, including the television's.
     */
    @Test
    fun `stacking the four cards would not have fitted any frame`() {
        val stacked = Spacing.screen + title + Spacing.xl +
            (card(tv = false) * 4 + Spacing.lg * 3) + Spacing.xl +
            Sizing.minTarget(false) + Spacing.screen

        println("source choice budget — a single column would be $stacked")

        assertTrue("a column of four was $stacked, which fits 393 after all", stacked > HANDSET)
        assertTrue("a column of four was $stacked, which fits 540 after all", stacked > TELEVISION)
    }
}
