package com.castivio.core.design.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sizing system's promises, asserted rather than trusted.
 *
 * Every one of these is a rule a future change could break without anything looking
 * wrong in a screenshot: a floor quietly removed, a ceiling raised, a fraction read
 * off the wrong axis. Castivio's whole interface depends on this file being right,
 * which is the argument for testing four lines of arithmetic this hard.
 */
class MetricsTest {

    private val surfaces = listOf(
        Surface("television 960x540", 960.dp, 540.dp, isTv = true),
        Surface("tablet 1280x800", 1280.dp, 800.dp, isTv = false),
        Surface("reference phone 873x393", 873.dp, 393.dp, isTv = false),
        Surface("shortest phone 800x360", 800.dp, 360.dp, isTv = false),
        Surface("4K set 3840x2160", 3840.dp, 2160.dp, isTv = true),
    )

    private data class Surface(val name: String, val width: Dp, val height: Dp, val isTv: Boolean) {
        val metrics: CastivioMetrics get() = castivioMetrics(width, height, isTv)
    }

    /**
     * The reference reproduces the drawing it was read off.
     *
     * The fractions came from 1280×720, so asking for 1280×720 has to give them back.
     * If this fails, someone has edited a fraction and the reference drawing and the
     * running app no longer agree about what Castivio looks like.
     */
    @Test
    fun `the reference gives back the numbers it was read off`() {
        val m = castivioMetrics(CastivioReference.Width, CastivioReference.Height, isTv = true)
        assertEquals(61.3f, m.edge.value, 0.1f)
        assertEquals(72f, m.header.value, 0.1f)
        assertEquals(58.7f, m.chip.value, 0.1f)
        assertEquals(29.3f, m.bandTop.value, 0.1f)
        assertEquals(34.7f, m.fsTitle.value, 0.1f)
    }

    /**
     * The television lands on the drawing that was approved for it.
     *
     * 960×540 is 3/4 of the reference, so every unbounded token should come out at
     * 3/4 — the television frame's own numbers, which is the whole claim that this
     * system reproduces the existing design rather than replacing it.
     */
    @Test
    fun `a 960 by 540 television reproduces its approved numbers`() {
        val m = castivioMetrics(960.dp, 540.dp, isTv = true)
        assertEquals(46f, m.edge.value, 0.5f)
        assertEquals(54f, m.header.value, 0.5f)
        assertEquals(44f, m.chip.value, 0.5f)
        assertEquals(22f, m.bandTop.value, 0.5f)
        assertEquals(26f, m.fsTitle.value, 0.5f)
        assertEquals(40f, m.brand.value, 0.5f)
    }

    /**
     * Nothing a finger has to hit is ever allowed to shrink.
     *
     * This is the failure a global scale produces and this system exists to prevent:
     * a correct-looking drawing of a control at 26dp. The floor is the device's, not
     * the surface's — 48 for a thumb, 56 for a remote — on every surface including
     * the shortest one this project ships to.
     */
    @Test
    fun `the pressable floor holds on every surface`() {
        for (surface in surfaces) {
            val floor = if (surface.isTv) 56.dp else 48.dp
            assertEquals(
                "${surface.name}: touch target",
                floor.value,
                surface.metrics.touchTarget.value,
                0.01f,
            )
        }
    }

    /** No text is ever drawn smaller than Castivio's smallest step, whatever the arithmetic asks. */
    @Test
    fun `type never falls below its floor`() {
        for (surface in surfaces) {
            val m = surface.metrics
            assertTrue("${surface.name}: title ${m.fsTitle}", m.fsTitle >= 18.dp)
            assertTrue("${surface.name}: label ${m.fsLabel}", m.fsLabel >= 13.dp)
            assertTrue("${surface.name}: body ${m.fsBody}", m.fsBody >= 12.dp)
            assertTrue("${surface.name}: chip ${m.fsChip}", m.fsChip >= 11.dp)
        }
    }

    /**
     * A very large surface stays proportionate rather than becoming a caricature.
     *
     * Without ceilings a 4K set draws a 58dp chip at 176 and a title at 104sp. The
     * bounds are what make this a *bounded* responsive system rather than a scale
     * with extra steps.
     */
    @Test
    fun `a 4K set is bounded rather than magnified`() {
        val m = castivioMetrics(3840.dp, 2160.dp, isTv = true)
        assertTrue("edge ${m.edge}", m.edge <= 72.dp)
        assertTrue("chip ${m.chip}", m.chip <= 64.dp)
        assertTrue("title ${m.fsTitle}", m.fsTitle <= 36.dp)
        assertTrue("band ${m.bandTop}", m.bandTop <= 32.dp)
    }

    /**
     * Sizes only ever move in one direction as the surface grows.
     *
     * A token that got *smaller* on a larger screen would be a fraction read off the
     * wrong axis — the kind of mistake that looks like a rendering bug and is
     * arithmetic. Checked across the ladder rather than at one point.
     */
    @Test
    fun `nothing shrinks as the surface grows`() {
        val ladder = listOf(
            castivioMetrics(800.dp, 360.dp, isTv = false),
            castivioMetrics(873.dp, 393.dp, isTv = false),
            castivioMetrics(960.dp, 540.dp, isTv = false),
            castivioMetrics(1280.dp, 720.dp, isTv = false),
        )
        for ((smaller, larger) in ladder.zipWithNext()) {
            assertTrue("edge", larger.edge >= smaller.edge)
            assertTrue("header", larger.header >= smaller.header)
            assertTrue("chip", larger.chip >= smaller.chip)
            assertTrue("title", larger.fsTitle >= smaller.fsTitle)
            assertTrue("body", larger.fsBody >= smaller.fsBody)
        }
    }

    /**
     * Four cards fit across, on every surface, with room for the longest name.
     *
     * The composition rule is the one thing this system exists to protect, so it is
     * asserted as arithmetic rather than left to a screenshot: the width left after
     * the margins, divided four ways with three gaps in it, has to leave a card wide
     * enough to be a card.
     */
    @Test
    fun `four cards fit across every surface`() {
        for (surface in surfaces) {
            val m = surface.metrics
            val content = surface.width - m.edge * 2
            val card = (content - m.bandTop * 3) / 4
            assertTrue(
                "${surface.name}: a card would be $card wide",
                card >= 140.dp,
            )
        }
    }
}
