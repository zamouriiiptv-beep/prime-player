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
     * **The compact column's title is the cheapest of its three optional
     * steps, and both landscape handsets this project is tested on can
     * already afford it.**
     *
     * A width under [SPREAD_MIN_WIDTH] forces the single column at every
     * height below, so what is under test here is never masked by the
     * spread. The title costs one line of `headlineMedium` plus a compact
     * `headGap` -- 44dp -- against the name field's own 78; both handsets
     * clear the cheaper threshold with room to spare, and neither clears the
     * name field's.
     */
    @Test
    fun `the compact column shows its title at both landscape handsets, but not the name field yet`() {
        val width = 700.dp

        val tall = band(width, 393.dp, tv = false)
        val short = band(width, 360.dp, tv = false)

        val dressTall = m3uColumnChrome(band = tall, button = Sizing.minTouchTarget)
        val dressShort = m3uColumnChrome(band = short, button = Sizing.minTouchTarget)

        assertFalse("851x393's column is not at full dress", dressTall.chrome.full)
        assertTrue("851x393's compact column shows the title", dressTall.title)
        assertFalse("851x393's band is a few dp short of the name field's own threshold", dressTall.chrome.name)
        assertTrue(
            "the title needs ${columnNeeds(dressTall, Sizing.minTouchTarget)} of $tall",
            fits(columnNeeds(dressTall, Sizing.minTouchTarget), tall),
        )

        assertFalse("800x360's column is not at full dress", dressShort.chrome.full)
        assertTrue("800x360's compact column shows the title too", dressShort.title)
        assertFalse("800x360 is further still from the name field's threshold", dressShort.chrome.name)
        assertTrue(
            "the title needs ${columnNeeds(dressShort, Sizing.minTouchTarget)} of $short",
            fits(columnNeeds(dressShort, Sizing.minTouchTarget), short),
        )
    }

    /**
     * Taller than either handset, but still below [SPREAD_MIN_WIDTH]'s own
     * width: the band can afford the title *and* the name field together.
     * Proof the name-field tier still exists, now priced with the title
     * ahead of it rather than priced alone.
     */
    @Test
    fun `a taller compact column affords the title and the name field together`() {
        val b = band(700.dp, 410.dp, tv = false)
        val dress = m3uColumnChrome(band = b, button = Sizing.minTouchTarget)

        assertFalse("not at full dress", dress.chrome.full)
        assertTrue("the title shows", dress.title)
        assertTrue("the band at $b can now afford the name field too", dress.chrome.name)
        assertTrue(
            "the column needs ${columnNeeds(dress, Sizing.minTouchTarget)} of $b",
            fits(columnNeeds(dress, Sizing.minTouchTarget), b),
        )
    }

    /**
     * Below the derived threshold even the title is given up, falling back to
     * exactly the column this screen drew before the container existed --
     * [COLUMN_FIXED_COMPACT] is that same figure, unchanged.
     */
    @Test
    fun `a band too short for the title falls back to the bare column`() {
        val b = band(700.dp, 290.dp, tv = false)
        val dress = m3uColumnChrome(band = b, button = Sizing.minTouchTarget)

        assertFalse("not at full dress", dress.chrome.full)
        assertFalse("the band at $b cannot afford the title on its own", dress.title)
        assertFalse("and so cannot afford the name field either", dress.chrome.name)
        assertTrue(
            "the bare column needs ${columnNeeds(dress, Sizing.minTouchTarget)} of $b",
            fits(columnNeeds(dress, Sizing.minTouchTarget), b),
        )
    }

    /**
     * The three boundaries the column's ladder added, swept to a tenth of a
     * dp from the shortest frame to just past where `full` itself takes
     * over. The claim is the same one the big sweep makes for the whole
     * screen -- nothing the column draws ever costs more than the band has.
     */
    @Test
    fun `the compact column's ladder never costs more than the band has`() {
        var worstMargin = Float.MAX_VALUE
        var worstAt = ""
        var bare = 0
        var titleOnly = 0
        var titleAndName = 0

        var height = 260.dp
        while (height <= 470.dp) {
            // A width under SPREAD_MIN_WIDTH, held fixed, so every height in
            // this range draws the column rather than the spread.
            val b = band(700.dp, height, tv = false)
            val dress = m3uColumnChrome(band = b, button = Sizing.minTouchTarget)
            val needs = columnNeeds(dress, Sizing.minTouchTarget)

            assertTrue("$height: the column needs $needs of $b", fits(needs, b))

            when {
                dress.chrome.full -> {}
                dress.chrome.name -> titleAndName++
                dress.title -> titleOnly++
                else -> bare++
            }

            val margin = (b - needs).value
            if (margin < worstMargin) {
                worstMargin = margin
                worstAt = "$height"
            }
            height += 0.1.dp
        }

        println(
            "compact column ladder sweep — $bare bare, $titleOnly title-only, " +
                "$titleAndName title+name, worst margin ${worstMargin}dp at $worstAt",
        )
        assertTrue("some swept height should be bare", bare > 0)
        assertTrue("some swept height should show the title alone", titleOnly > 0)
        assertTrue("some swept height should show the title and the name field", titleAndName > 0)
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
        var columnsWithName = 0
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
                        val dress = m3uColumnChrome(band = b, button = Sizing.minTarget(tv))
                        val needs = columnNeeds(dress, Sizing.minTarget(tv))
                        assertTrue("$where: the column needs $needs of $b", fits(needs, b))
                        if (dress.chrome.name && !dress.chrome.full) columnsWithName++
                    }
                }
            }
            height += 1.dp
        }

        println(
            "m3u fit sweep — $spreads spreads, $columns columns " +
                "($columnsWithName of them with the compact name field), tightest ${tightest}dp at $tightestAt",
        )
        assertTrue("no surface drew the spread", spreads > 0)
        assertTrue("no surface drew the column", columns > 0)
        assertTrue("no surface drew the compact column's name field", columnsWithName > 0)
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
