package com.castivio.feature.activation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioReference
import com.castivio.core.design.theme.Sizing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The playlist screen fits, on every surface, without scrolling.
 *
 * ## The two defects this file exists to prevent, and both shipped
 *
 * **The drawing did not appear.** The thresholds asked for 960dp of width and 400dp of
 * band; a landscape handset is 851x393, which leaves 303dp under the header, so the
 * screen fell back to a single column — two fields and nothing else, on the one device
 * anybody had in their hand.
 *
 * **Then Connect fell off the bottom.** Lowering the thresholds put the drawing on the
 * handset and put the button 57dp below the viewport, reachable only by scrolling to
 * it. Every gate stayed green through both, because no test asked what a handset
 * renders.
 *
 * ## So the claim here is arithmetic, not appearance
 *
 * The screen has no scroll. Its parts are therefore a budget: `m3uSpread` is handed
 * the band and picks a composition whose counted height fits inside it. These tests
 * recount that budget independently — the panel's fixed parts, the words under the
 * illustration, and the y of Connect's bottom edge — and assert all three against the
 * band on every surface the application ships to.
 *
 * The figures below are the components' real measurements, not their nominal ones: a
 * label row carrying "optional" is 18dp because `labelSmall` leads at 18, and a button
 * on a television is 56 because `CastivioButton` floors itself at the D-pad target.
 * Both were wrong in a first pass and both are why this is counted rather than eyed.
 */
class M3uSpreadTest {

    private fun metrics(width: Dp, height: Dp, tv: Boolean) =
        sourceMetricsFor(tv = tv, width = width, height = height)

    private fun band(width: Dp, height: Dp, tv: Boolean): Dp =
        m3uBand(metrics(width, height, tv), height)

    private fun spread(width: Dp, height: Dp, tv: Boolean): M3uMetrics {
        val m = metrics(width, height, tv)
        return m3uSpread(width - m.edge * 2, m3uBand(m, height), tv)
    }

    /**
     * `need` fits inside `band`, to the dp rather than to the bit.
     *
     * The illustration's height is `band - words`, so recomputing `art + words` is a
     * subtraction and an addition of the same `Float`, which round-trips one unit in
     * the last place above where it started: CI reported 407.98584 against 407.9858
     * and called it an overflow. Four hundredths of a thousandth of a dp is not one —
     * Compose rounds the layout to whole pixels long before it gets there — and a test
     * that treats it as one is asserting the arithmetic of `Float`, not the claim.
     */
    private fun fits(need: Dp, band: Dp): Boolean = need.value <= band.value + TOLERANCE

    /** Where Connect's bottom edge sits inside the panel, counted from its top. */
    private fun connectEnds(s: M3uMetrics): Dp =
        if (s.chrome.full) {
            16.dp + HEAD + 24.dp + NAME_FIELD + 16.dp + URL_FIELD + 24.dp + s.button
        } else {
            12.dp + COMPACT_HEAD + 12.dp + URL_FIELD + 12.dp + s.button
        }

    /** What the words under the illustration cost, plus the cards where they are drawn. */
    private fun leftNeeds(s: M3uMetrics): Dp =
        s.art + pitchNeeds(s.chrome) + if (s.facts) 32.dp + s.factHeight else 0.dp

    /**
     * **The drawing reaches the handset the project is tested on, and Connect is on
     * the screen.**
     *
     * 851x393 in landscape: 40dp of header, 33.5 of stage, 303.5 of band. The panel
     * at full dress wants 402 of that, so the screen draws the compact composition —
     * the illustration, its claim, the URL field, Paste and Connect — and the button
     * ends 200dp down, with a hundred to spare.
     */
    @Test
    fun `the handset draws the screen and keeps Connect inside it`() {
        val w = 851.dp
        val h = 393.dp
        val b = band(w, h, tv = false)

        assertEquals("the band", 303.5f, b.value, 1f)
        assertTrue("the handset draws two columns", fitsSpread(w, b))

        val s = spread(w, h, tv = false)
        assertFalse("the band cannot afford the full panel", s.chrome.full)
        assertEquals("the illustration column", 322.9f, s.left.value, 1f)
        assertEquals("the panel", 420f, (s.measure - s.left - s.gap).value, 1f)
        assertEquals("the illustration", 185.3f, s.art.value, 1f)

        assertEquals("Connect ends at", 200f, connectEnds(s).value, 1f)
        assertTrue("Connect ends at ${connectEnds(s)} in $b", fits(connectEnds(s), b))
        assertTrue("the panel needs ${panelNeeds(s.chrome, s.button)}", fits(panelNeeds(s.chrome, s.button), b))
        assertTrue("the illustration column needs ${leftNeeds(s)}", fits(leftNeeds(s), b))
    }

    /**
     * **The approved television drawing is untouched.**
     *
     * Every figure read off the 1280x720 mockup, including the one the compact path
     * exists to protect: the illustration is 330dp, to the dp, and the three cards are
     * in a row.
     */
    @Test
    fun `the reference reproduces the approved drawing`() {
        val w = CastivioReference.Width
        val h = CastivioReference.Height
        val m = metrics(w, h, tv = false)
        val s = spread(w, h, tv = false)

        assertEquals("the stage", 1157.4f, (w - m.edge * 2).value, 0.5f)
        assertEquals("the header", 72f, m.header.value, 0.5f)
        assertEquals("the band", 557.4f, band(w, h, tv = false).value, 0.5f)
        assertEquals("the illustration column", 575f, s.left.value, 0.5f)
        assertEquals("the illustration", 330f, s.art.value, 0.5f)
        assertEquals("the gap between the columns", 40f, s.gap.value, 0.5f)
        assertEquals("a fact card", 68f, s.factHeight.value, 0.5f)
        assertEquals("a fact's disc", 44f, s.factDisc.value, 0.5f)
        assertEquals("the panel's disc", 72f, s.panelDisc.value, 0.5f)
        assertEquals("the button", 60f, s.button.value, 0.5f)

        assertTrue("the reference draws the full panel", s.chrome.full)
        assertTrue("the reference draws the three cards", s.facts)
    }

    /** And it holds the whole of it without scrolling, which is where that was promised. */
    @Test
    fun `the reference holds the whole drawing without scrolling`() {
        val b = band(CastivioReference.Width, CastivioReference.Height, tv = false)
        val s = spread(CastivioReference.Width, CastivioReference.Height, tv = false)

        assertTrue("the illustration column needs ${leftNeeds(s)} of $b", fits(leftNeeds(s), b))
        assertTrue("the panel needs ${panelNeeds(s.chrome, s.button)} of $b", fits(panelNeeds(s.chrome, s.button), b))
        assertTrue("Connect ends at ${connectEnds(s)} of $b", fits(connectEnds(s), b))
    }

    /**
     * The smallest television keeps the full panel, which is the 8dp this budget was
     * recounted for: its band is 418 and the full panel with a 56dp D-pad button is
     * 410. The three cards go — a 418dp column cannot draw three names — and nothing
     * else does.
     */
    @Test
    fun `the smallest television keeps the full panel`() {
        val s = spread(960.dp, 540.dp, tv = true)
        val b = band(960.dp, 540.dp, tv = true)

        assertEquals("the band", 418f, b.value, 1f)
        assertEquals("the button is at the D-pad floor", Sizing.minTvTarget.value, s.button.value, 0.5f)
        assertTrue("the television draws the full panel", s.chrome.full)
        assertFalse("its column is too narrow for three cards", s.facts)
        assertTrue("the panel needs ${panelNeeds(s.chrome, s.button)} of $b", fits(panelNeeds(s.chrome, s.button), b))
        assertTrue("the illustration column needs ${leftNeeds(s)} of $b", fits(leftNeeds(s), b))
    }

    /** The thresholds are derived, and this is the derivation. */
    @Test
    fun `the thresholds are where two columns stop fitting`() {
        val s = spread(SPREAD_MIN_WIDTH, 540.dp, tv = false)
        assertTrue("the panel would be ${s.measure - s.left - s.gap}", (s.measure - s.left - s.gap).value >= 420f - TOLERANCE)
        assertTrue("the illustration column would be ${s.left}", s.left >= 300.dp)

        assertFalse("820dp is too narrow", fitsSpread(820.dp, 400.dp))
        assertTrue("840dp is wide enough", fitsSpread(840.dp, 400.dp))
        assertFalse("a short band cannot hold the compact panel", fitsSpread(1280.dp, 200.dp))
        assertTrue("the handset's band can", fitsSpread(1280.dp, SPREAD_BAND_MIN))
    }

    /**
     * **Nothing on this screen leaves the band, on any surface, ever.**
     *
     * The sweep the two shipped defects would both have been caught by. For every
     * height from the shortest frame to 4K, at every aspect the application sees, on a
     * thumb and on a remote: whichever composition the screen picks, the panel fits,
     * the illustration column fits, and Connect's bottom edge is inside the viewport.
     */
    @Test
    fun `nothing leaves the band on any surface`() {
        var spreads = 0
        var columns = 0
        var tightest = Float.MAX_VALUE
        var tightestAt = ""

        var height = 300.dp
        while (height <= 2160.dp) {
            for (aspect in listOf(16f / 9f, 1.6f, 1.78f, 1.85f, 2f, 2.17f, 2.4f, 2.8f, 3f)) {
                val width = height * aspect
                for (tv in listOf(false, true)) {
                    val b = band(width, height, tv)
                    val where = "${width.value.toInt()}x${height.value.toInt()} tv=$tv"
                    assertTrue("$where: the band is $b", b >= 0.dp)

                    if (fitsSpread(width, b)) {
                        spreads++
                        val s = spread(width, height, tv)
                        val panel = s.measure - s.left - s.gap

                        assertTrue("$where: the panel needs ${panelNeeds(s.chrome, s.button)} of $b", fits(panelNeeds(s.chrome, s.button), b))
                        assertTrue("$where: the column needs ${leftNeeds(s)} of $b", fits(leftNeeds(s), b))
                        assertTrue("$where: Connect ends at ${connectEnds(s)} of $b", fits(connectEnds(s), b))
                        assertTrue("$where: the panel is $panel across", panel.value >= 420f - TOLERANCE)
                        assertTrue("$where: the illustration column is ${s.left}", s.left >= 300.dp)
                        assertTrue("$where: the spread is ${s.measure}", s.measure <= 1280.dp)
                        assertTrue("$where: the illustration is ${s.art}", s.art in 100.dp..360.dp)
                        assertTrue("$where: the gap is ${s.gap}", s.gap in 24.dp..56.dp)
                        assertTrue("$where: a fact is ${s.factHeight}", s.factHeight in 56.dp..84.dp)

                        val slack = (b - maxOf(leftNeeds(s), panelNeeds(s.chrome, s.button))).value
                        if (slack < tightest) {
                            tightest = slack
                            tightestAt = where
                        }
                    } else {
                        columns++
                        val chrome = m3uChrome(full = b >= COLUMN_FIXED + Sizing.minTarget(tv))
                        val needs = columnNeeds(chrome, Sizing.minTarget(tv))
                        assertTrue("$where: the column needs $needs of $b", fits(needs, b))
                    }
                }
            }
            height += 1.dp
        }

        println("m3u fit sweep — $spreads spreads, $columns columns, tightest ${tightest}dp at $tightestAt")
        assertTrue("no surface drew the spread", spreads > 0)
        assertTrue("no surface drew the column", columns > 0)
    }

    private companion object {
        /** The panel's head at full dress: a disc of 72, against a title and two lines. */
        val HEAD: Dp = 80.dp

        /** And compact: a 40dp mark, which is taller than the one line beside it. */
        val COMPACT_HEAD: Dp = 40.dp

        /** Label row 18 (`labelSmall` carries "optional"), gap 4, box at the touch floor. */
        val NAME_FIELD: Dp = 70.dp

        /** Label row 16, gap 4, and a box floored by the Paste chip inside it. */
        val URL_FIELD: Dp = 76.dp

        /** A floor reached by subtracting `Float` dp cannot be asserted to the last bit. */
        const val TOLERANCE = 0.01f
    }
}
