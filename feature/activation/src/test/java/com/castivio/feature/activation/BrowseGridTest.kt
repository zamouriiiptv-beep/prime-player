package com.castivio.feature.activation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The local-media browsers' grid, after the feature's last device branches were removed.
 *
 * ## What this replaced
 *
 * Three `if (CastivioTheme.device.isTv)` reads that lived as top-level tokens:
 * `BrowseItemGap` (8 against 4), `BrowseTileGap` (16 against 8) and `BrowseTileMin`
 * (240 against 150). They are [SourceMetrics.itemGap], [SourceMetrics.tileGap] and
 * [SourceMetrics.tileMin] now, read off the surface.
 *
 * ## Why the claims are about the grid rather than the numbers
 *
 * `MediaBrowseLayoutTest` composes the real grid and asks Compose where it put things,
 * which is the right question for placement — but it runs on three frames, and the
 * failure a share can produce is on the surfaces nobody composed. The minimum feeds
 * `GridCells.Adaptive`, so getting it wrong does not clip or overflow: the grid silently
 * becomes **one column**, a wall of one tile per row, on a frame nobody looked at. That
 * is the claim gated here, at every height between the shortest and a 4K set.
 */
class BrowseGridTest {

    private data class Surface(val name: String, val width: Dp, val height: Dp, val tv: Boolean) {
        val metrics: SourceMetrics get() = sourceMetricsFor(tv, width, height)
        override fun toString() = name
    }

    private val surfaces = listOf(
        Surface("shortest phone 800x360", 800.dp, 360.dp, tv = false),
        Surface("narrow frame 568x360", 568.dp, 360.dp, tv = false),
        Surface("the reporter's handset 827x393", 827.dp, 393.dp, tv = false),
        Surface("reference phone 873x393", 873.dp, 393.dp, tv = false),
        Surface("television 960x540", 960.dp, 540.dp, tv = true),
        Surface("reference 1280x720", 1280.dp, 720.dp, tv = false),
        Surface("tablet 1280x800", 1280.dp, 800.dp, tv = false),
        Surface("1080p set 1920x1080", 1920.dp, 1080.dp, tv = true),
        Surface("4K surface 3840x2160", 3840.dp, 2160.dp, tv = true),
    )

    /**
     * The television reproduces the grid its drawing was approved at.
     *
     * 960×540 is three quarters of the reference, so all three shares come back at three
     * quarters: a 240dp minimum, 16dp between tiles, 8dp between rows — the television
     * side of all three branches, to the dp, and the same three tiles across it drew.
     */
    @Test
    fun `the television reproduces its approved grid`() {
        val m = sourceMetricsFor(tv = true, width = 960.dp, height = 540.dp)
        assertEquals("tile minimum", 240f, m.tileMin.value, 0.5f)
        assertEquals("between tiles", 16f, m.tileGap.value, 0.5f)
        assertEquals("between rows", 8f, m.itemGap.value, 0.5f)
        assertEquals("tiles across", 3, columns(m, 960.dp))
    }

    /**
     * The two gaps give back what they were read off; the minimum is already capped.
     *
     * The gaps are shares of the height and 720 is inside their bounds, so the reference
     * returns 21.3 and 10.7 — the numbers the television's 16 and 8 were scaled from.
     * The minimum is not: a quarter of 1280 is 320 and its ceiling is 240, so the
     * reference sits above it. That is the ceiling doing its job rather than a share
     * being wrong — a 16:9 tile wider than 240dp stops being a grid cell and starts
     * being a hero, and the television is the largest surface the 240 was drawn for.
     */
    @Test
    fun `the reference gives back the gaps, and the minimum is on its ceiling`() {
        val m = sourceMetricsFor(
            tv = false,
            width = CastivioReference.Width,
            height = CastivioReference.Height,
        )
        assertEquals("tile minimum", 240f, m.tileMin.value, 0.5f)
        assertEquals("between tiles", 21.3f, m.tileGap.value, 0.2f)
        assertEquals("between rows", 10.7f, m.itemGap.value, 0.2f)
    }

    /** Every one of the three is bounded at both ends, on every surface. */
    @Test
    fun `every dimension stays inside its bounds`() {
        for (surface in surfaces) {
            val m = surface.metrics
            assertTrue("$surface: the tile minimum is ${m.tileMin}", m.tileMin in 150.dp..240.dp)
            assertTrue("$surface: the tile gap is ${m.tileGap}", m.tileGap in 8.dp..24.dp)
            assertTrue("$surface: the row gap is ${m.itemGap}", m.itemGap in 4.dp..12.dp)
        }
    }

    /**
     * **The grid never becomes a list.**
     *
     * `GridCells.Adaptive` falls back to a single column when the minimum does not fit
     * the width it is given, and it does it silently — no clip, no overflow, nothing a
     * layout test on three frames would see. A wall of one tile per row is a different
     * screen from the one that was designed, so two is the floor and it is asserted on
     * every surface rather than on the handset `MediaBrowseLayoutTest` composes.
     */
    @Test
    fun `every surface fits at least two tiles in a row`() {
        for (surface in surfaces) {
            assertTrue(
                "$surface: the grid composed ${columns(surface.metrics, surface.width)} column(s)",
                columns(surface.metrics, surface.width) >= 2,
            )
        }
    }

    /**
     * **A tile is never drawn below the minimum it asked for.**
     *
     * The arithmetic `Adaptive` performs, restated: with `c` columns in `A` of width and
     * `g` between them, a tile is `(A + g) / c - g`, and `c` is the largest count for
     * which that is at least the minimum. Asserting it here means the minimum is a real
     * floor rather than a number handed to a library and trusted.
     */
    @Test
    fun `a tile is never narrower than the minimum it asked for`() {
        for (surface in surfaces) {
            val m = surface.metrics
            val tile = tileWidth(m, surface.width)
            assertTrue("$surface: a $tile tile under a ${m.tileMin} minimum", tile >= m.tileMin)
        }
    }

    /** Sizes only move one way as the surface grows, and then they stop. */
    @Test
    fun `sizes only grow with the surface, and stop`() {
        val ladder = listOf(
            568.dp to 360.dp,
            800.dp to 360.dp,
            873.dp to 393.dp,
            1280.dp to 800.dp,
            1920.dp to 1080.dp,
            3840.dp to 2160.dp,
        ).map { (w, h) -> sourceMetricsFor(tv = false, width = w, height = h) }

        for ((smaller, larger) in ladder.zipWithNext()) {
            assertTrue("the tile minimum shrank", larger.tileMin >= smaller.tileMin)
            assertTrue("the tile gap shrank", larger.tileGap >= smaller.tileGap)
            assertTrue("the row gap shrank", larger.itemGap >= smaller.itemGap)
        }

        val huge = ladder.last()
        assertEquals("the 4K tile minimum is unbounded", 240f, huge.tileMin.value, 0.01f)
        assertEquals("the 4K tile gap is unbounded", 24f, huge.tileGap.value, 0.01f)
        assertEquals("the 4K row gap is unbounded", 12f, huge.itemGap.value, 0.01f)
    }

    /**
     * Every surface between the shortest and the largest holds.
     *
     * The sweep phases 1 to 4 established, applied to the grid: every height a dp apart,
     * at five aspect ratios, on both kinds of device — never one column, never a tile
     * under its minimum, never a negative gap.
     */
    @Test
    fun `every surface between the shortest and the largest holds`() {
        var fewest = Int.MAX_VALUE
        var fewestAt = ""
        var narrowest = Dp.Infinity
        var narrowestAt = ""

        var height = 330.dp
        while (height <= 2160.dp) {
            for (aspect in listOf(16f / 9f, 1.85f, 2f, 2.2f, 2.4f)) {
                val width = height * aspect
                val kinds = if (height >= 480.dp) listOf(false, true) else listOf(false)
                for (tv in kinds) {
                    val m = sourceMetricsFor(tv, width, height)
                    val where = "${width.value.toInt()}x${height.value.toInt()} tv=$tv"
                    val c = columns(m, width)
                    val tile = tileWidth(m, width)

                    if (c < fewest) {
                        fewest = c
                        fewestAt = where
                    }
                    if (tile < narrowest) {
                        narrowest = tile
                        narrowestAt = where
                    }

                    assertTrue("$where: a $tile tile under a ${m.tileMin} minimum", tile >= m.tileMin)
                    assertTrue("$where: the tile gap is ${m.tileGap}", m.tileGap in 8.dp..24.dp)
                    assertTrue("$where: the row gap is ${m.itemGap}", m.itemGap in 4.dp..12.dp)
                }
            }
            height += 1.dp
        }

        println(
            "browse sweep — fewest columns $fewest at $fewestAt | " +
                "narrowest tile $narrowest at $narrowestAt",
        )

        assertTrue("the grid composed $fewest column(s) at $fewestAt", fewest >= 2)
    }

    /* ------------------------------------------------------------------ the model */

    /** What the content box is: the surface, less the stage's margin on both sides. */
    private fun available(m: SourceMetrics, width: Dp): Dp = width - m.frame.edge * 2

    /** What `GridCells.Adaptive` chooses: as many as fit at no less than the minimum. */
    private fun columns(m: SourceMetrics, width: Dp): Int =
        ((available(m, width) + m.tileGap) / (m.tileMin + m.tileGap)).toInt().coerceAtLeast(1)

    private fun tileWidth(m: SourceMetrics, width: Dp): Dp {
        val c = columns(m, width)
        return (available(m, width) - m.tileGap * (c - 1)) / c
    }
}
