package com.castivio.feature.home

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.Sizing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Channels board's arithmetic, swept.
 *
 * ## Why this test carries more weight than usual
 *
 * Gradle cannot resolve the Android artifacts in this environment and there is no
 * emulator, so the board itself is verified in CI and on a device. What *can* be
 * checked here is the part that decides whether the board fits at all: every dimension
 * on it is a pure function of two numbers, and "does the panel still have room for its
 * columns at 720dp" is arithmetic rather than a screenshot.
 *
 * So these are not bounds tests for their own sake. Two of them —
 * [the columns keep a positive share of the width] and
 * [the bands keep a positive share of the height] — are the mechanical form of the
 * question "does anything clip on 16:9", asked at every surface this ships to rather
 * than at the one somebody photographed.
 */
class ChannelsMetricsTest {

    /**
     * **The controls keep the target floor; the list rows are allowed under it.**
     *
     * This assertion used to say the opposite, and it was the reason the board showed
     * three channels where its reference shows twelve. The arithmetic is not close:
     * twelve rows at a 56dp D-pad floor is 672dp of list, and a 16:9 surface 540dp tall
     * does not have it at any ceiling.
     *
     * So the floor moved to where it belongs. A circle in a strip of ten is a control a
     * remote lands *on* and a finger can miss by 8dp, and it still carries
     * `Sizing.minTarget`. A full-width channel row is moved *through*, and carries the
     * list floor instead. Both halves are asserted here so neither can drift: the
     * controls may not go under the target, and the rows may not silently climb back
     * over it and take the density with them.
     */
    @Test
    fun `the strip keeps the target floor and the rows keep the list floor`() {
        sweep { tv, width, height ->
            val m = channelsMetricsFor(tv, width, height)
            val floor = Sizing.minTarget(tv)
            assertTrue(
                "the action strip ${m.actions} is under the ${if (tv) "D-pad" else "thumb"} " +
                    "floor $floor at ${width}x$height",
                m.actions >= floor,
            )
            assertTrue(
                "a channel row is ${m.rowMin} at ${width}x$height, under the list floor",
                m.rowMin >= LIST_ROW_FLOOR,
            )
            assertTrue(
                "a rail entry is ${m.railMin} at ${width}x$height, under the list floor",
                m.railMin >= LIST_ROW_FLOOR,
            )
        }
    }

    /**
     * **The three columns always have room, and the middle one is never squeezed out.**
     *
     * The rail and the player well are fixed widths and the list takes what is left, so
     * a ceiling raised carelessly on either would not overflow — it would silently
     * starve the channel list, which is the column the screen exists for. Asserted as a
     * real minimum rather than as "greater than zero": a list narrower than its own row
     * furniture is a list nobody can read.
     */
    @Test
    fun `the columns keep a positive share of the width`() {
        sweep(from = SHIPPING_WIDTH) { tv, width, height ->
            val m = channelsMetricsFor(tv, width, height)
            val spent = m.edge * 2 + m.panelPad * 2 + m.rail + m.railGap + m.player + m.playerGap
            val list = width - spent
            assertTrue(
                "the channel list is $list at ${width}x$height — the rail and the well " +
                    "have taken the board",
                list >= LIST_MIN,
            )
        }
    }

    /**
     * **The fixed bands never eat the panel.**
     *
     * Header, remote bar, toolbar, the paddings and the gaps are all fixed heights; the
     * columns take the remainder. If the ceilings on those ever sum past the surface,
     * the columns get a negative share and the board clips from the bottom — which is
     * exactly the failure the reference's own 3:2 drawing hides, because it has 300 more
     * rows of height than a 16:9 television does.
     */
    @Test
    fun `the bands keep a positive share of the height`() {
        sweep(from = SHIPPING_WIDTH) { tv, width, height ->
            val m = channelsMetricsFor(tv, width, height)
            val columns = height - fixedHeight(m)
            assertTrue(
                "the columns get $columns at ${width}x$height — the bands have eaten " +
                    "the panel",
                columns >= COLUMNS_MIN,
            )
        }
    }

    /**
     * **Twelve channels are visible without scrolling, everywhere this ships.**
     *
     * The assertion the whole redesign exists for, and the one that would have caught
     * the defect it fixes: the board showed three rows on a 2340x1080 handset against a
     * reference showing twelve on the same glass. It asserted `rows >= 2` at the time,
     * and passed.
     *
     * Checked at every surface and both input models rather than at the one that was
     * photographed, because "it fits on the device I have" is what the old number meant.
     */
    @Test
    fun `the reference's twelve rows are visible`() {
        sweep(from = SHIPPING_WIDTH, aspects = TELEVISION_ASPECTS) { tv, width, height ->
            val m = channelsMetricsFor(tv, width, height)
            val rows = (columnHeight(m, height) / m.rowMin).toInt()
            assertTrue(
                "only $rows rows visible at ${width}x$height, against a reference of " +
                    "$CHANNELS_TARGET_ROWS",
                rows >= CHANNELS_TARGET_ROWS,
            )
        }
    }

    /**
     * And a usable list survives the shapes the reference was never drawn at.
     *
     * 21:9 at 800dp wide is 343dp tall — a surface where twelve rows would mean a row
     * of 28dp with the bands already at their floors, and no arrangement of the numbers
     * produces it. Rather than quietly weaken the assertion above to whatever the worst
     * aspect allows, the two are separate: the shapes this product is *designed* for
     * hold the reference's density, and the shapes it merely has to *survive* hold a
     * floor of their own.
     */
    @Test
    fun `every other shape still shows a usable list`() {
        sweep(from = SHIPPING_WIDTH) { tv, width, height ->
            val m = channelsMetricsFor(tv, width, height)
            val rows = (columnHeight(m, height) / m.rowMin).toInt()
            assertTrue("only $rows rows visible at ${width}x$height", rows >= MIN_ROWS_ANYWHERE)
        }
    }

    /**
     * **The bands never take a third of the screen again.**
     *
     * The measured defect, as arithmetic. A full-height dashboard header with a toolbar
     * under it took 295 of 1080 pixels before a channel was drawn — twenty-seven per
     * cent — and the reference spends eight. Counting rows alone would not have caught
     * it, because a short enough row hides a tall enough header; this asserts the split
     * directly, so neither band can grow back at the list's expense.
     */
    @Test
    fun `the bands leave the columns most of the screen`() {
        sweep(from = SHIPPING_WIDTH) { tv, width, height ->
            val m = channelsMetricsFor(tv, width, height)
            val share = columnHeight(m, height) / height
            assertTrue(
                "the columns get ${(share * 100).toInt()}% of the height at ${width}x$height",
                share >= COLUMN_SHARE_OF_HEIGHT,
            )
        }
    }

    /**
     * The category rail shows what the reference shows, for the same reason.
     *
     * Nine entries, and the rail is the column a viewer picks a bouquet from — a rail
     * showing three of a provider's four hundred categories is a scroll bar with a
     * decoration attached.
     */
    @Test
    fun `the rail shows as many categories as the reference`() {
        sweep(from = SHIPPING_WIDTH) { tv, width, height ->
            val m = channelsMetricsFor(tv, width, height)
            val entries = (columnHeight(m, height) / (m.railMin + m.railEntryGap)).toInt()
            assertTrue("only $entries categories visible at ${width}x$height", entries >= MIN_RAIL_ENTRIES)
        }
    }

    /** Every dimension is bounded at both ends, everywhere. That is the system's rule. */
    @Test
    fun `every dimension stays inside its bounds`() {
        sweep { tv, width, height ->
            val m = channelsMetricsFor(tv, width, height)
            listOf(
                "edge" to m.edge, "boardTop" to m.boardTop, "header" to m.header,
                "headerGap" to m.headerGap, "remote" to m.remote, "remoteGap" to m.remoteGap,
                "panelPad" to m.panelPad, "panelRadius" to m.panelRadius,
                "rail" to m.rail, "railGap" to m.railGap,
                "player" to m.player, "playerGap" to m.playerGap, "rowPadH" to m.rowPadH,
                "numberWidth" to m.numberWidth, "logoWidth" to m.logoWidth,
                "wellPad" to m.wellPad, "factChip" to m.factChip, "remoteDot" to m.remoteDot,
            ).forEach { (name, value) ->
                assertTrue("$name is $value at ${width}x$height", value > 0.dp)
                assertTrue("$name is $value, wider than the surface", value <= width)
            }
        }
    }

    /**
     * **The reference reproduces itself.**
     *
     * The shares were measured off a 2340x1080 screenshot, so this is the one test that
     * says the conversion was done and not merely described. A width of `w` in the
     * reference must land on `w / 2340` of the surface's width, and a height of `h` on
     * `h / 1080` of its height.
     *
     * Checked at 1280x720, which is the product's own reference geometry and a surface
     * where none of the bounds bind — so a cap quietly swallowing a share would show up
     * here rather than hide behind a clamp.
     */
    @Test
    fun `the reference geometry lands on the reference's own proportions`() {
        val m = channelsMetricsFor(tv = true, width = 1280.dp, height = 720.dp)

        // `actions` is deliberately absent: it is floored at the control target and at
        // 1280dp the floor wins (38.3dp of share against a 56dp D-pad floor). It is a
        // strip of circles a remote lands on, and that is the one column on this board
        // still held to the target — see `ChannelsMetrics`.
        assertNear("rail", 1280f * 494f / 2340f, m.rail)
        assertNear("player", 1280f * 986f / 2340f, m.player)
        assertNear("railGap", 1280f * 16f / 2340f, m.railGap)
        assertNear("playerGap", 1280f * 18f / 2340f, m.playerGap)
        assertNear("edge", 1280f * 22f / 2340f, m.edge)
        assertNear("logoWidth", 1280f * 60f / 2340f, m.logoWidth)

        assertNear("header", 720f * 88f / 1080f, m.header)
        assertNear("remote", 720f * 40f / 1080f, m.remote)
        assertNear("headerGap", 720f * 8f / 1080f, m.headerGap)
        assertNear("railMin", 720f * 100f / 1080f, m.railMin)
        assertNear("rowMin", 720f * 70f / 1080f, m.rowMin)
    }

    /**
     * **The logo never takes the name's room again.**
     *
     * The defect this board shipped with, in one line of arithmetic. The logo was
     * `108/1536` of the width — seven per cent — inside a list column that is thirty per
     * cent of it, and with the number plate and the row's own padding beside it the
     * channel *name* was left with about forty pixels. It rendered as a bare ellipsis on
     * every row, and the list was unreadable.
     *
     * So what is asserted is not the logo's size but the **name's**: whatever else the
     * row spends, more than half of it is left for the thing a viewer is reading.
     */
    @Test
    fun `the channel name keeps the majority of its row`() {
        sweep(from = SHIPPING_WIDTH) { tv, width, height ->
            val m = channelsMetricsFor(tv, width, height)
            val list = width - (m.edge * 2 + m.panelPad * 2 + m.actions + m.rail + m.railGap + m.player + m.playerGap)
            // Everything in the row that is not the name: the padding at both ends, the
            // logo, the number plate, and a gap on each side of the name.
            val furniture = m.rowPadH * 4 + m.logoWidth + m.numberWidth
            val name = list - furniture
            assertTrue(
                "the name gets $name of a ${list} row at ${width}x$height",
                name >= list * NAME_SHARE_OF_ROW,
            )
        }
    }

    /**
     * The preview plate is 16:9, because what sits in it is a picture.
     *
     * It was 430:312 — the plate of the first reference, a still whose third column held
     * a channel *identity* rather than a frame of video, and the squarer box was right
     * for that. The approved design shows the picture itself, and a picture has one
     * shape: anything else letterboxes live television inside a panel built to avoid
     * letterboxing.
     *
     * Kept as a test rather than deleted, and deliberately: this is the one number on the
     * board that a later tidy-up would be tempted to "round" to whatever the column
     * happens to be, and the whole point is that the column follows the picture.
     */
    @Test
    fun `the preview plate is sixteen by nine`() {
        assertEquals(16f / 9f, CHANNELS_PREVIEW_ASPECT, 0.001f)
    }

    /* ------------------------------------------------------------------ helpers */

    /**
     * Everything on the board whose height is fixed before the columns take the rest.
     *
     * The toolbar band is absent from this sum because it is absent from the board. It
     * was a row of its own holding a search field, a sort control and a total, above
     * three columns that between them already had somewhere for all three — and it cost
     * a tenth of the height to say so twice.
     */
    private fun fixedHeight(m: ChannelsMetrics): Dp =
        m.boardTop + m.boardBottom + m.header + m.headerGap +
            m.remoteGap + m.remote + m.panelPad * 2

    /** What is left for the columns once [fixedHeight] is paid. */
    private fun columnHeight(m: ChannelsMetrics, height: Dp): Dp = height - fixedHeight(m)

    private fun assertNear(name: String, expected: Float, actual: Dp) {
        assertEquals(name, expected, actual.value, 0.01f)
    }

    /**
     * Every surface this ships to, at both input models.
     *
     * 1dp steps rather than a handful of samples: a bound that is wrong is usually wrong
     * over a narrow band, and three sampled widths is how such a band survives a test
     * suite. The aspects are the ones real hardware reports — a television, a tablet, a
     * handset in landscape, and the two extremes of a folding device.
     */
    private fun sweep(
        from: Int = MIN_WIDTH,
        aspects: List<Float> = ASPECTS,
        check: (tv: Boolean, width: Dp, height: Dp) -> Unit,
    ) {
        for (tv in listOf(true, false)) {
            for (aspect in aspects) {
                var w = from
                while (w <= MAX_WIDTH) {
                    check(tv, w.dp, (w / aspect).dp)
                    w += STEP
                }
            }
        }
    }

    private companion object {
        const val MIN_WIDTH = 330

        /**
         * The narrowest surface this product ships to, per `CLAUDE.md`: 800x360.
         *
         * The fit assertions start here rather than at [MIN_WIDTH], and the
         * difference is deliberate. Below roughly 700dp the board's fixed bands --
         * a header, a toolbar and a remote legend, each with a floor of its own --
         * genuinely do leave the columns too little to draw, and no ceiling can be
         * tuned out of that: it is three controls that may not shrink stacked on a
         * surface shorter than their sum. Asserting the fit down to 330dp would
         * therefore mean either deleting a band or lying about it, so the range is
         * the product's stated one and this note is the record of why.
         */
        const val SHIPPING_WIDTH = 800
        const val MAX_WIDTH = 2160
        const val STEP = 1
        val ASPECTS = listOf(16f / 9f, 16f / 10f, 3f / 2f, 21f / 9f, 4f / 3f)

        /** Narrower than this and the channel list cannot hold its own row furniture. */
        val LIST_MIN = 120.dp

        /** Shorter than this and the panel is a frame around nothing. */
        val COLUMNS_MIN = 100.dp

        /**
         * The smallest a list row may be drawn, control floor or not.
         *
         * Below this a row is a line of text with no box around it and focus has nothing
         * to land on visibly. It is the floor the class note calls the *list* floor, and
         * it is deliberately not `Sizing.minTarget` -- see the note for why those are
         * two different numbers.
         */
        val LIST_ROW_FLOOR = 26.dp

        /** The reference shows nine categories at once; the tightest shape holds seven. */
        const val MIN_RAIL_ENTRIES = 7

        /** A list this product has to survive on, where the reference's density cannot fit. */
        const val MIN_ROWS_ANYWHERE = 9

        /** How much of the height belongs to the columns rather than to the bands. */
        const val COLUMN_SHARE_OF_HEIGHT = 0.70f

        /** The shapes the board is designed for, as opposed to the ones it survives. */
        val TELEVISION_ASPECTS = listOf(16f / 9f, 16f / 10f)

        /** How much of a channel row belongs to the channel's name. */
        const val NAME_SHARE_OF_ROW = 0.5f
    }
}
