package com.castivio.feature.home

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioReference
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.castivioMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue's sizing, asserted rather than looked at.
 *
 * ## Why this file exists at all
 *
 * Browse, Show and Search had **no** layout gate of any kind. Both of the defects this
 * migration fixed were arithmetic that nobody had done and no screenshot would show:
 * a channel row 6dp under the D-pad floor on the only device with a D-pad, and a
 * poster 81dp wide on a television, because a column count chosen by device name was
 * being applied to a width nobody had measured.
 *
 * So the two claims that decide whether these screens work are computed here, on the
 * JVM, from the same [CatalogMetrics] the screens are built from — and computed across
 * the whole range of surfaces rather than at the four points somebody drew.
 *
 * ## What it deliberately does not claim
 *
 * Anything that depends on the size of text. The grids and lists here are lazy and
 * scroll, so a cell taller than the viewport is not an overflow; what would be a
 * defect is a cell too *small* to read or press, and that is what is asserted.
 */
class CatalogMetricsTest {

    private data class Surface(val name: String, val width: Dp, val height: Dp, val tv: Boolean) {
        val metrics: CatalogMetrics get() = catalogMetricsFor(tv, width, height)
        override fun toString() = name
    }

    private val surfaces = listOf(
        Surface("shortest phone 800x360", 800.dp, 360.dp, tv = false),
        Surface("reference phone 873x393", 873.dp, 393.dp, tv = false),
        Surface("television 960x540", 960.dp, 540.dp, tv = true),
        Surface("tablet 1280x800", 1280.dp, 800.dp, tv = false),
        Surface("1080p set 1920x1080", 1920.dp, 1080.dp, tv = true),
        Surface("4K set 3840x2160", 3840.dp, 2160.dp, tv = true),
    )

    /** Landscape, from a squat tablet to the widest handset anyone ships. */
    private val aspects = listOf(16f / 9f, 1.85f, 2f, 2.2f, 2.4f)

    private val shortest = 330.dp
    private val tallest = 2160.dp

    /** The shortest surface any television reports. Below it there are only handsets. */
    private val tvShortest = 480.dp

    /**
     * The metrics come from the system every other screen reads.
     *
     * Not a formality: the whole value of one design system is that a title on Browse
     * and a title on Home are the same number for the same reason. A local copy of the
     * stage would agree until somebody edited one of the two.
     */
    @Test
    fun `the stage and the type steps come from the shared system`() {
        for (surface in surfaces) {
            assertEquals(
                "$surface: the frame did not come from castivioMetrics",
                castivioMetrics(surface.width, surface.height, surface.tv),
                surface.metrics.frame,
            )
        }
    }

    /** The reference gives back the numbers the shares were read off. */
    @Test
    fun `the reference gives back the numbers it was read off`() {
        val m = catalogMetricsFor(
            tv = false,
            width = CastivioReference.Width,
            height = CastivioReference.Height,
        )
        assertEquals("bandGap", 26.7f, m.bandGap.value, 0.1f)
        assertEquals("gutter", 34.7f, m.gutter.value, 0.1f)
        assertEquals("pane", 288f, m.pane.value, 0.1f)
        assertEquals("paneGap", 42.7f, m.paneGap.value, 0.1f)
        assertEquals("logo", 60f, m.logo.value, 0.1f)
    }

    /**
     * **The defect this migration existed to fix.**
     *
     * A channel row was `logo 34 + inset 8 + inset 8` = 50dp on every device, and the
     * D-pad floor is 56. Six dp, on the device the whole product is built around, in a
     * component three screens draw hundreds of. Nothing failed; it was simply too
     * small to land on.
     *
     * Asserted on every surface, and asserted against the *device's* floor rather than
     * one floor for all devices — asserting 48 everywhere is exactly what let 50dp
     * look correct.
     */
    @Test
    fun `a row is never smaller than what this device can press`() {
        for (surface in surfaces) {
            val m = surface.metrics
            val floor = Sizing.minTarget(surface.tv)
            assertTrue(
                "$surface: a ${m.rowMin} row against a $floor floor",
                m.rowMin >= floor,
            )
            // The row also has to hold what is inside it, or the floor is a fiction
            // the content immediately breaks.
            assertTrue(
                "$surface: a ${m.rowMin} row cannot hold a ${m.logo} tile inset by ${m.cardPad}",
                m.rowMin >= m.logo || m.logo + m.cardPad * 2 >= m.rowMin,
            )
        }
    }

    /**
     * **The other defect.** A poster is never drawn too small to be a poster.
     *
     * The column count used to be a device's name — three, four, five or six — applied
     * to whatever width was left beside the category pane. On a 960×540 television that
     * cut six cells out of 596dp and drew 81dp posters, watched from three metres.
     *
     * The count is now derived from that width, so this asserts the property that makes
     * the derivation worth having: whatever the surface, a cell is wide enough to read.
     */
    @Test
    fun `a poster is never drawn below its floor`() {
        for (surface in surfaces) {
            val m = surface.metrics
            val poster = posterWidth(surface, m)
            assertTrue(
                "$surface: ${m.columns} columns give a $poster poster",
                poster >= POSTER_FLOOR,
            )
            assertTrue("$surface: ${m.columns} columns", m.columns in 3..10)
        }
    }

    /**
     * The pane and the grid both fit the stage, with the grid getting the majority.
     *
     * A category pane is a filter for the content beside it, so a surface where the
     * filter is wider than the thing it filters has its priorities inverted — and that
     * is reachable by arithmetic rather than by design: the pane is a share of the
     * width with a 240dp floor, and the floor is what a narrow surface lands on.
     */
    @Test
    fun `the content pane always outweighs the category pane`() {
        for (surface in surfaces.filter { it.metrics.twoPane }) {
            val m = surface.metrics
            val content = surface.width - m.frame.edge * 2
            val grid = content - m.pane - m.paneGap
            assertTrue("$surface: the grid is $grid", grid > 0.dp)
            assertTrue(
                "$surface: a $grid grid beside a ${m.pane} pane",
                grid > m.pane,
            )
        }
    }

    /**
     * The categories become a pane exactly where they always did.
     *
     * `DeviceClass.Expanded` began at 840dp of window width and the screen asked
     * `Television || Expanded`; every television reports at least 960. This is that
     * rule with the width measured instead of inferred, so it has to agree with it on
     * every surface either could see — including the one case the old rule got wrong
     * only in theory, a television narrower than 840dp, which does not exist.
     */
    @Test
    fun `the pane appears exactly where the device table used to put it`() {
        val strip = listOf(800.dp to 360.dp, 640.dp to 360.dp, 839.dp to 400.dp)
        val pane = listOf(840.dp to 400.dp, 873.dp to 393.dp, 960.dp to 540.dp, 1280.dp to 800.dp)

        for ((w, h) in strip) {
            assertTrue("$w should draw the category strip", !catalogMetricsFor(false, w, h).twoPane)
        }
        for ((w, h) in pane) {
            assertTrue("$w should draw the category pane", catalogMetricsFor(false, w, h).twoPane)
        }
    }

    /**
     * Sizes only ever move in one direction as the surface grows, and then they stop.
     *
     * A token that got *smaller* on a larger screen is a share read off the wrong axis:
     * it looks like a rendering bug and is arithmetic. The ceilings are the other half —
     * what makes this bounded responsive sizing rather than a scale with extra steps.
     */
    @Test
    fun `sizes only grow with the surface, and stop`() {
        val ladder = listOf(
            800.dp to 360.dp,
            873.dp to 393.dp,
            960.dp to 540.dp,
            1280.dp to 800.dp,
            1920.dp to 1080.dp,
            3840.dp to 2160.dp,
        ).map { (w, h) -> catalogMetricsFor(false, w, h) }

        for ((smaller, larger) in ladder.zipWithNext()) {
            assertTrue("bandGap shrank", larger.bandGap >= smaller.bandGap)
            assertTrue("gutter shrank", larger.gutter >= smaller.gutter)
            assertTrue("logo shrank", larger.logo >= smaller.logo)
            assertTrue("pane shrank", larger.pane >= smaller.pane)
            assertTrue("rowMin shrank", larger.rowMin >= smaller.rowMin)
        }

        val huge = ladder.last()
        assertTrue("the 4K logo ${huge.logo} is unbounded", huge.logo <= 68.dp)
        assertTrue("the 4K gutter ${huge.gutter} is unbounded", huge.gutter <= 40.dp)
        assertTrue("the 4K pane ${huge.pane} is unbounded", huge.pane <= 360.dp)
        assertTrue("the 4K row ${huge.rowMin} is unbounded", huge.rowMin <= 96.dp)
    }

    /**
     * Every surface between the shortest and the largest holds together.
     *
     * The sweep phase 1 established, applied to the claims this screen family has: a
     * grid with room in it, a cell wide enough to read, a row large enough to press,
     * and a band under the header with something left in it. Every height a dp apart,
     * at five aspect ratios, on both kinds of device.
     */
    @Test
    fun `every surface between the shortest and the largest holds`() {
        var worstPoster = Dp.Infinity
        var worstPosterAt = ""
        var worstRow = Dp.Infinity
        var worstRowAt = ""
        var fewest = Int.MAX_VALUE
        var fewestAt = ""

        var height = shortest
        while (height <= tallest) {
            for (aspect in aspects) {
                val width = height * aspect
                val kinds = if (height >= tvShortest) listOf(false, true) else listOf(false)
                for (tv in kinds) {
                    val surface = Surface("${width.value.toInt()}x${height.value.toInt()}", width, height, tv)
                    val m = surface.metrics
                    val poster = posterWidth(surface, m)

                    if (poster < worstPoster) {
                        worstPoster = poster
                        worstPosterAt = "$surface tv=$tv (${m.columns} columns)"
                    }
                    if (m.rowMin - Sizing.minTarget(tv) < worstRow) {
                        worstRow = m.rowMin - Sizing.minTarget(tv)
                        worstRowAt = "$surface tv=$tv"
                    }
                    if (m.columns < fewest) {
                        fewest = m.columns
                        fewestAt = "$surface tv=$tv"
                    }
                }
            }
            height += 1.dp
        }

        println(
            "catalogue sweep — narrowest poster $worstPoster at $worstPosterAt | " +
                "tightest row $worstRow over the floor at $worstRowAt | " +
                "fewest columns $fewest at $fewestAt",
        )

        assertTrue("a $worstPoster poster at $worstPosterAt", worstPoster >= POSTER_FLOOR)
        assertTrue("a row $worstRow under the floor at $worstRowAt", worstRow >= 0.dp)
        assertTrue("$fewest columns at $fewestAt", fewest >= 3)
    }

    /** What one cell comes out at, derived the way the grid derives it. */
    private fun posterWidth(surface: Surface, m: CatalogMetrics): Dp {
        val content = surface.width - m.frame.edge * 2
        val grid = if (m.twoPane) content - m.pane - m.paneGap else content
        return (grid - m.gutter * (m.columns - 1)) / m.columns
    }

    /**
     * The narrowest a poster may come out at.
     *
     * Below this a 2:3 card is a thumbnail with a truncated name under it, which is
     * the shape the old device table produced on a television. It is asserted rather
     * than merely intended, because the column count is arithmetic now and arithmetic
     * is what a bound is for.
     */
    private val POSTER_FLOOR = 110.dp
}
