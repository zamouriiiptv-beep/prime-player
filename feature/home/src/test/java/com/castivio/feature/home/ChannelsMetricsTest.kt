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
     * A channel row and a rail entry are the two most-pressed controls on the board,
     * and the reference draws both **under** the D-pad floor once its 3:2 geometry is
     * mapped onto 16:9 — 47.8dp and 38.7dp against a 56dp target.
     *
     * This is the assertion that stops the picture winning. It failed by 8dp and 17dp
     * before the floors were applied, on the one device the board is designed for.
     */
    @Test
    fun `a row and a rail entry are never under the device's target`() {
        sweep { tv, width, height ->
            val m = channelsMetricsFor(tv, width, height)
            val floor = Sizing.minTarget(tv)
            assertTrue(
                "row ${m.rowMin} under the ${if (tv) "D-pad" else "thumb"} floor $floor " +
                    "at ${width}x$height",
                m.rowMin >= floor,
            )
            assertTrue(
                "rail entry ${m.railMin} under $floor at ${width}x$height",
                m.railMin >= floor,
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
     * At least this many channels are visible without scrolling, everywhere.
     *
     * The reference shows ten, on a 3:2 drawing. A 16:9 surface at a 56dp floor holds
     * about eight, and a 360dp handset fewer — but a list showing one row at a time is
     * not a list, so the floor is asserted rather than left to whatever the ceilings
     * happen to allow.
     */
    @Test
    fun `enough rows are visible to read the list`() {
        sweep(from = SHIPPING_WIDTH) { tv, width, height ->
            val m = channelsMetricsFor(tv, width, height)
            val columns = height - fixedHeight(m)
            val rows = (columns / m.rowMin).toInt()
            assertTrue("only $rows rows visible at ${width}x$height", rows >= MIN_VISIBLE_ROWS)
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
                "panelPad" to m.panelPad, "panelRadius" to m.panelRadius, "toolbar" to m.toolbar,
                "search" to m.search, "rail" to m.rail, "railGap" to m.railGap,
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
     * The shares were read off a 1536×1024 drawing and the product's reference geometry
     * is 1280×720, so this is the one test that says the conversion was done and not
     * merely described. A width of `w` in the reference must land on `w / 1536` of 1280,
     * and a height of `h` on `h / 1024` of 720.
     */
    @Test
    fun `the reference geometry lands on the reference's own proportions`() {
        val m = channelsMetricsFor(tv = true, width = 1280.dp, height = 720.dp)

        assertNear("rail", 1280f * 302f / 1536f, m.rail)
        assertNear("player", 1280f * 444f / 1536f, m.player)
        assertNear("railGap", 1280f * 24f / 1536f, m.railGap)
        assertNear("playerGap", 1280f * 34f / 1536f, m.playerGap)
        assertNear("edge", 1280f * 32f / 1536f, m.edge)

        assertNear("header", 720f * 76f / 1024f, m.header)
        assertNear("remote", 720f * 72f / 1024f, m.remote)
        assertNear("toolbar", 720f * 54f / 1024f, m.toolbar)
        assertNear("headerGap", 720f * 34f / 1024f, m.headerGap)

        // The two that do not reproduce the reference, and must not: both are floored
        // at the D-pad target, and the reference's own value is under it.
        assertEquals("row is the D-pad floor", Sizing.minTvTarget, m.rowMin)
        assertEquals("rail entry is the D-pad floor", Sizing.minTvTarget, m.railMin)
    }

    /** The preview plate keeps the reference's shape, which is not 16:9 and not square. */
    @Test
    fun `the preview plate keeps the reference's aspect`() {
        assertEquals(430f / 312f, CHANNELS_PREVIEW_ASPECT, 0.001f)
    }

    /* ------------------------------------------------------------------ helpers */

    /** Everything on the board whose height is fixed before the columns take the rest. */
    private fun fixedHeight(m: ChannelsMetrics): Dp =
        m.boardTop + m.boardBottom + m.header + m.headerGap +
            m.remoteGap + m.remote + m.panelPad * 2 + m.toolbar + m.toolbarGap

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
    private fun sweep(from: Int = MIN_WIDTH, check: (tv: Boolean, width: Dp, height: Dp) -> Unit) {
        for (tv in listOf(true, false)) {
            for (aspect in ASPECTS) {
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

        /** A list showing fewer than this is not a list. */
        const val MIN_VISIBLE_ROWS = 2
    }
}
