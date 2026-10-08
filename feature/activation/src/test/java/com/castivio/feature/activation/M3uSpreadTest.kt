package com.castivio.feature.activation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The playlist screen's geometry: the band the header leaves, and the two-column
 * drawing that divides it.
 *
 * ## What this is for
 *
 * The screen is laid out from two numbers it computes rather than reads. [m3uBand] is
 * the arithmetic `Column` would do with a weighted child, written out because the two
 * columns have to know the height *before* they are measured; and [m3uSpread] turns
 * the stage's width into the drawing. Neither is checkable by eye across every surface
 * the application ships to, and both were wrong in an earlier draft — the band was
 * derived from the width, which on a wide, short window produced a band taller than
 * the stage.
 *
 * ## What it no longer has to police
 *
 * Two thresholds. While the playlist step lived in the shared scrolling column, one
 * decision lifted its measure cap from outside the stage and another chose the layout
 * from inside it, and the gap between them was a thing a test had to keep honest. The
 * step owns its viewport now, so there is one measurement and one answer.
 */
class M3uSpreadTest {

    /** The chooser's metrics for a surface, which is what the screen builds from. */
    private fun metrics(width: Dp, height: Dp, tv: Boolean) =
        sourceMetricsFor(tv = tv, width = width, height = height)

    /** What the stage leaves across. */
    private fun stageWidth(width: Dp, height: Dp, tv: Boolean): Dp =
        width - metrics(width, height, tv).edge * 2

    /**
     * **The reference leaves the band the drawing was approved in.**
     *
     * 720 less the stage's 32 and 29.3, less the header's 72 and the 29.3 under it, is
     * 557.4 — the band the two columns were drawn in. The header is the half of this
     * that is new: the screen used to have none, and the mockup always did.
     */
    @Test
    fun `the reference leaves the band the drawing was approved in`() {
        val m = metrics(CastivioReference.Width, CastivioReference.Height, tv = false)
        assertEquals("the header", 72f, m.header.value, 0.5f)
        assertEquals("the band", 557.4f, m3uBand(m, CastivioReference.Height).value, 0.5f)
    }

    /** The reference reproduces the approved drawing across, to the dp. */
    @Test
    fun `the reference reproduces the approved drawing`() {
        val width = stageWidth(CastivioReference.Width, CastivioReference.Height, tv = false)
        val s = m3uSpread(width)

        assertEquals("the stage", 1157.4f, width.value, 0.5f)
        assertEquals("the illustration column", 575f, s.left.value, 0.5f)
        assertEquals("the gap between the columns", 40f, s.gap.value, 0.5f)
        assertEquals("a fact card", 68f, s.factHeight.value, 0.5f)
        assertEquals("a fact's disc", 44f, s.factDisc.value, 0.5f)
        assertEquals("the panel's disc", 72f, s.panelDisc.value, 0.5f)
        assertEquals("the button", 60f, s.button.value, 0.5f)
    }

    /**
     * The illustration gets about the 330dp it was drawn at.
     *
     * The band is not free space: it is the drawing plus everything under it. This is
     * that sum written out, with the prose at its tallest — a headline and two lines
     * of body — so the figure is the floor rather than the best case.
     */
    @Test
    fun `the reference leaves the illustration the height it was drawn at`() {
        val m = metrics(CastivioReference.Width, CastivioReference.Height, tv = false)
        val band = m3uBand(m, CastivioReference.Height)
        val s = m3uSpread(stageWidth(CastivioReference.Width, CastivioReference.Height, tv = false))

        // Spacing.md, the pitch, Spacing.xxl, the facts.
        val words = 12f + HEADLINE_LINE + 4f + BODY_LINE * 2 + 32f + s.factHeight.value
        val art = band.value - words

        assertTrue("the illustration gets ${art}dp", art in 300f..400f)
    }

    /** The reference draws the spread. It is the surface the brief is about. */
    @Test
    fun `the reference draws the spread`() {
        val m = metrics(CastivioReference.Width, CastivioReference.Height, tv = false)
        assertTrue(
            fitsSpread(CastivioReference.Width, m3uBand(m, CastivioReference.Height)),
        )
    }

    /** The smallest television draws it too; a handset and a short window do not. */
    @Test
    fun `the spread is chosen on the surfaces it was drawn for, and no others`() {
        fun spreads(w: Dp, h: Dp, tv: Boolean) = fitsSpread(w, m3uBand(metrics(w, h, tv), h))

        assertTrue("the smallest television", spreads(960.dp, 540.dp, tv = true))
        assertTrue("a 1080p set", spreads(1920.dp, 1080.dp, tv = true))
        assertTrue("a landscape tablet", spreads(1280.dp, 800.dp, tv = false))

        assertFalse("a 873dp handset", spreads(873.dp, 393.dp, tv = false))
        assertFalse("the shortest frame", spreads(800.dp, 360.dp, tv = false))
        assertFalse("wide but only 400 tall", spreads(1400.dp, 400.dp, tv = false))
    }

    /**
     * **Every surface that draws the spread has room for it, and every one that does
     * not falls back to a column that scrolls.**
     *
     * The sweep is over the band rather than the window, because the band is what the
     * two columns actually divide — and the band is `weight(1f)` of a bounded column,
     * so the claim worth asserting is not "it fits" (it structurally must) but that
     * the two columns and the gap fit *across*, and that nothing is below its floor.
     */
    @Test
    fun `the spread holds on every surface it is drawn on`() {
        var drawn = 0
        var narrowest = Float.MAX_VALUE
        var narrowestAt = ""

        var height = 360.dp
        while (height <= 2160.dp) {
            for (aspect in listOf(16f / 9f, 1.6f, 1.78f, 1.85f, 2f, 2.4f)) {
                val width = height * aspect
                for (tv in listOf(false, true)) {
                    val m = metrics(width, height, tv)
                    val band = m3uBand(m, height)
                    if (!fitsSpread(width, band)) continue
                    drawn++

                    val stage = width - m.edge * 2
                    val s = m3uSpread(stage)
                    val where = "${width.value.toInt()}x${height.value.toInt()} tv=$tv"
                    val panel = s.measure - s.left - s.gap

                    assertTrue("$where: the spread is ${s.measure} of $stage", s.measure <= stage)
                    assertTrue("$where: the spread is ${s.measure}", s.measure <= 1280.dp)
                    assertTrue("$where: the illustration column is ${s.left}", s.left >= 300.dp)
                    assertTrue("$where: the panel would be $panel", panel >= 420.dp)
                    assertTrue("$where: the gap is ${s.gap}", s.gap in 24.dp..56.dp)
                    assertTrue("$where: a fact is ${s.factHeight}", s.factHeight in 56.dp..84.dp)
                    assertTrue("$where: a fact's disc is ${s.factDisc}", s.factDisc in 34.dp..52.dp)
                    assertTrue("$where: the panel's disc is ${s.panelDisc}", s.panelDisc in 56.dp..88.dp)
                    assertTrue("$where: the button is ${s.button}", s.button in 48.dp..72.dp)

                    if (panel.value < narrowest) {
                        narrowest = panel.value
                        narrowestAt = where
                    }
                }
            }
            height += 1.dp
        }

        println("m3u spread sweep — $drawn surfaces, narrowest panel ${narrowest}dp at $narrowestAt")
        assertTrue("no surface drew the spread", drawn > 0)
    }

    /**
     * The band never goes negative, on any surface, including the ones too small to
     * draw the spread on.
     *
     * It is a subtraction, and a subtraction of four responsive numbers from a height
     * is exactly the shape that comes out below zero on the shortest frame. A negative
     * band hands `Modifier.height` a negative dp.
     */
    @Test
    fun `the band is never negative`() {
        var height = 300.dp
        while (height <= 2160.dp) {
            for (aspect in listOf(1.2f, 16f / 9f, 2f, 2.4f, 3f)) {
                val width = height * aspect
                for (tv in listOf(false, true)) {
                    val band = m3uBand(metrics(width, height, tv), height)
                    assertTrue(
                        "${width.value.toInt()}x${height.value.toInt()} tv=$tv: the band is $band",
                        band >= 0.dp,
                    )
                }
            }
            height += 1.dp
        }
    }

    private companion object {
        /** `CastivioType.headlineMedium`'s leading, which the pitch's title is set in. */
        const val HEADLINE_LINE = 32f

        /** `CastivioType.bodyMedium`'s, which its sentence is. */
        const val BODY_LINE = 22f
    }
}
