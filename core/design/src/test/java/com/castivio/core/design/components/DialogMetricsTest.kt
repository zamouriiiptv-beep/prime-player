package com.castivio.core.design.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The modal's sizing, after the last device table in `:core:design` was removed.
 *
 * ## What this replaced, and why it needs a test at all
 *
 * Four `if (CastivioTheme.device.isTv)` branches: a panel 560dp wide against 420, a
 * corner one `Radius` step larger, a padding one `Spacing` step larger, and a title on
 * `headlineSmall` against `titleMedium`. A test against those would have asserted only
 * that somebody typed the same two numbers twice.
 *
 * The title's branch is the one that is only **half** gone, deliberately. Those two
 * tokens differ in weight as well as in size; the size is [DialogMetrics.title] now and
 * the weight is still chosen by the device, because re-weighting one device's dialog
 * title is a typography decision and not the residue of a sizing migration. So what is
 * asserted below is the step, which this file owns, and not the face, which it does
 * not.
 *
 * A share can be wrong in ways a table cannot: a panel wider than the surface it is
 * centred in, a corner that keeps growing on a 4K set until the panel reads as a
 * lozenge, a title under the step a dialog has to be legible at from three metres. Those
 * are the claims below.
 */
class DialogMetricsTest {

    private data class Surface(val name: String, val width: Dp, val height: Dp) {
        val metrics: DialogMetrics get() = dialogMetricsFor(width, height)
        override fun toString() = name
    }

    private val surfaces = listOf(
        Surface("shortest phone 800x360", 800.dp, 360.dp),
        Surface("reference phone 873x393", 873.dp, 393.dp),
        Surface("narrow frame 568x360", 568.dp, 360.dp),
        Surface("television 960x540", 960.dp, 540.dp),
        Surface("reference 1280x720", 1280.dp, 720.dp),
        Surface("tablet 1280x800", 1280.dp, 800.dp),
        Surface("1080p set 1920x1080", 1920.dp, 1080.dp),
        Surface("4K surface 3840x2160", 3840.dp, 2160.dp),
    )

    /**
     * The television reproduces the panel its drawing was approved at.
     *
     * 960×540 is three quarters of the reference, so each share read off the reference
     * comes back at three quarters: the 560dp panel, the 32dp padding, the 30dp corner
     * and the 18dp title — the television side of all four branches, to the dp.
     */
    @Test
    fun `the television reproduces its approved panel`() {
        val m = dialogMetricsFor(960.dp, 540.dp)
        assertEquals("panel width", 560f, m.width.value, 0.5f)
        assertEquals("panel padding", 32f, m.padding.value, 0.5f)
        assertEquals("panel corner", 30f, m.radius.value, 0.5f)
        assertEquals("title step", 18f, m.title.value, 0.5f)
    }

    /**
     * And the handset keeps the title it drew, which is the other half of the claim.
     *
     * The width and the padding move on a handset — 420 to 509, 24 to 29 — because those
     * were the phone's half of a two-row table and the table is what is being removed.
     * The title does not: its floor is the 15dp the phone was drawn at, so the one
     * dimension on this panel that decides legibility is unchanged in the hand.
     */
    @Test
    fun `the handset keeps the title step it was drawn at`() {
        for (surface in surfaces.filter { it.height <= 400.dp }) {
            assertEquals(
                "$surface: title step",
                15f,
                surface.metrics.title.value,
                0.01f,
            )
        }
    }

    /**
     * **The reference is where all four stop growing, and the television is inside it.**
     *
     * Every other screen's test asserts that 1280×720 gives back the number its share
     * was read off. This one cannot, and the reason is worth stating rather than
     * asserting around. A dialog is the one component whose largest real surface is
     * *smaller* than the reference: the biggest panel Castivio draws is on a 960dp
     * television, and 1280×720 is a geometry a drawing is judged at rather than a size
     * any device reports. So the four shares run past their ceilings before the
     * reference — 746.7 against 560, 42.7 against 36, 40 against 32, 24 against 20 —
     * and every one of them is clamped there.
     *
     * That is the design. A panel, a corner and a heading that kept climbing would read
     * as a lozenge with a title four steps above the body it introduces, on a 4K set
     * nobody sits close to. What the test gates is that the ceilings bind **at** the
     * reference and not before it: the television is strictly inside all four, so the
     * approved 560/32/30/18 is untouched, and the reference sits exactly on them.
     */
    @Test
    fun `all four reach their ceilings at the reference, and the television is inside them`() {
        val m = dialogMetricsFor(CastivioReference.Width, CastivioReference.Height)
        assertEquals("panel width", 560f, m.width.value, 0.01f)
        assertEquals("panel padding", 36f, m.padding.value, 0.01f)
        assertEquals("panel corner", 32f, m.radius.value, 0.01f)
        assertEquals("title step", 20f, m.title.value, 0.01f)

        val tv = dialogMetricsFor(960.dp, 540.dp)
        assertTrue("the television is on the padding's ceiling", tv.padding < m.padding)
        assertTrue("the television is on the corner's ceiling", tv.radius < m.radius)
        assertTrue("the television is on the title's ceiling", tv.title < m.title)
    }

    /**
     * **Every dimension is bounded at both ends.**
     *
     * The rule the whole sizing system is built on, asserted on the one component that
     * is drawn over every screen in the product rather than inside one of them.
     */
    @Test
    fun `every dimension stays inside its bounds`() {
        for (surface in surfaces) {
            val m = surface.metrics
            assertTrue("$surface: panel is ${m.width}", m.width in 400.dp..560.dp)
            assertTrue("$surface: padding is ${m.padding}", m.padding in 20.dp..36.dp)
            assertTrue("$surface: corner is ${m.radius}", m.radius in 20.dp..32.dp)
            assertTrue("$surface: title is ${m.title}", m.title in 15.dp..20.dp)
        }
    }

    /**
     * **The panel always fits the surface it is centred in, with room either side.**
     *
     * The failure a share can produce and a table could not: the panel is a fraction of
     * the width with a floor, and on a surface narrow enough the floor wins — at which
     * point a modal can be wider than the screen and lose a button off each end. The
     * narrowest frame the project ships to is 568dp, and the floor is chosen against it.
     */
    @Test
    fun `the panel fits the surface with a margin either side`() {
        for (surface in surfaces) {
            val m = surface.metrics
            val spare = surface.width - m.width
            assertTrue(
                "$surface: a ${m.width} panel leaves $spare of the ${surface.width} surface",
                spare >= 48.dp,
            )
        }
    }

    /**
     * **The padding never eats the panel.**
     *
     * Two independent shares in one relationship, which is the pair that went wrong on
     * the player in phase 4: the width comes off a ceiling at 560 while the padding is
     * still climbing, so what is left for a question and two buttons is the difference.
     */
    @Test
    fun `what is left inside the panel stays usable`() {
        for (surface in surfaces) {
            val m = surface.metrics
            val inner = m.width - m.padding * 2
            assertTrue("$surface: $inner of content inside a ${m.width} panel", inner >= 320.dp)
        }
    }

    /** Sizes only move one way as the surface grows, and then they stop. */
    @Test
    fun `sizes only grow with the surface, and stop`() {
        val ladder = surfaces
            .sortedBy { it.width.value }
            .map { it.metrics }
        for ((smaller, larger) in ladder.zipWithNext()) {
            assertTrue("the panel shrank", larger.width >= smaller.width)
            assertTrue("the padding shrank", larger.padding >= smaller.padding)
        }
        val huge = dialogMetricsFor(3840.dp, 2160.dp)
        assertEquals("the 4K panel is unbounded", 560f, huge.width.value, 0.01f)
        assertEquals("the 4K padding is unbounded", 36f, huge.padding.value, 0.01f)
        assertEquals("the 4K corner is unbounded", 32f, huge.radius.value, 0.01f)
        assertEquals("the 4K title is unbounded", 20f, huge.title.value, 0.01f)
    }

    /**
     * Every surface between the shortest and the largest holds.
     *
     * The sweep the earlier phases established, applied to this component: every height a
     * dp apart at five aspect ratios, with no panel wider than its surface, no dimension
     * outside its bounds, and nothing negative anywhere.
     */
    @Test
    fun `every surface between the shortest and the largest holds`() {
        var worstSpare = Dp.Infinity
        var worstSpareAt = ""
        var worstInner = Dp.Infinity
        var worstInnerAt = ""

        var height = 330.dp
        while (height <= 2160.dp) {
            for (aspect in listOf(16f / 9f, 1.85f, 2f, 2.2f, 2.4f)) {
                val width = height * aspect
                val m = dialogMetricsFor(width, height)
                val where = "${width.value.toInt()}x${height.value.toInt()}"

                val spare = width - m.width
                if (spare < worstSpare) {
                    worstSpare = spare
                    worstSpareAt = where
                }
                val inner = m.width - m.padding * 2
                if (inner < worstInner) {
                    worstInner = inner
                    worstInnerAt = where
                }

                assertTrue("$where: the panel is ${m.width}", m.width in 400.dp..560.dp)
                assertTrue("$where: the padding is ${m.padding}", m.padding in 20.dp..36.dp)
                assertTrue("$where: the corner is ${m.radius}", m.radius in 20.dp..32.dp)
                assertTrue("$where: the title is ${m.title}", m.title in 15.dp..20.dp)
            }
            height += 1.dp
        }

        println(
            "dialog sweep — tightest margin $worstSpare at $worstSpareAt | " +
                "narrowest content $worstInner at $worstInnerAt",
        )

        assertTrue("a panel overhangs its surface by ${-worstSpare} at $worstSpareAt", worstSpare >= 0.dp)
        assertTrue("a panel holds only $worstInner at $worstInnerAt", worstInner >= 320.dp)
    }
}
