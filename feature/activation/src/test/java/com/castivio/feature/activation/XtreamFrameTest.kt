package com.castivio.feature.activation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Xtream form's frame: where it scrolls, where it does not, and that nothing in it
 * is cut off where it does not.
 *
 * ## Arithmetic, for the reason the playlist screen's budget is arithmetic
 *
 * Robolectric does not lay text out -- every `Text` is 35dp tall there whatever its
 * style -- so a rendered test of "the form fits the smallest television" would be an
 * assertion about the harness on a frame with a few dp to spare. `XtreamFormTest` asks
 * Compose the questions it answers truthfully: which nodes exist, whether anything is
 * scrollable, where focus goes. This file asks whether the places add up, with the
 * components' real heights, recounted here rather than read from [xtreamFloor] so that
 * a change to either side has to agree with the other.
 *
 * ## The six surfaces the form was approved for
 *
 * Two handsets, where the form scrolls inside the frame the playlist and portal screens
 * draw there; two televisions and two tablets, where it does not scroll at all, the
 * frame reaches the bottom of the band, and a field's error goes to the note under
 * Connect rather than under the field -- so the height counted here is the height with
 * any number of errors.
 */
class XtreamFrameTest {

    private data class Surface(val name: String, val width: Dp, val height: Dp, val tv: Boolean) {
        override fun toString() = name
    }

    private val handsets = listOf(
        Surface("handset 851x393", 851.dp, 393.dp, tv = false),
        Surface("handset 800x360", 800.dp, 360.dp, tv = false),
    )

    private val held = listOf(
        Surface("television 960x540", 960.dp, 540.dp, tv = true),
        Surface("television 1280x720", 1280.dp, 720.dp, tv = true),
        Surface("tablet 960x600", 960.dp, 600.dp, tv = false),
        Surface("tablet 1280x800", 1280.dp, 800.dp, tv = false),
    )

    private fun metrics(s: Surface) = sourceMetricsFor(tv = s.tv, width = s.width, height = s.height)

    private fun band(s: Surface): Dp = m3uBand(metrics(s), s.height)

    private fun button(s: Surface): Dp = Sizing.minTarget(s.tv)

    private fun frame(s: Surface): XtreamFrameMetrics =
        xtreamFrame(s.width, s.height, band(s), metrics(s).bandTop, button(s))

    /**
     * The frame's contents, top to bottom: the pad, the name, the server, the username and
     * the password -- each of the three a box with Paste in it -- Connect, the note and the
     * pad, and [errorsUnder] lines of error under fields, for the arrangement that writes
     * them there.
     */
    private fun content(f: XtreamFrameMetrics, button: Dp, errorsUnder: Int = 0): Dp =
        f.pad + NAME_FIELD + f.fieldGap + URL_CHROME + button + f.fieldGap + URL_CHROME + button +
            f.fieldGap + URL_CHROME + button + f.actionGap + button + f.noteGap + NOTE + f.pad +
            ERROR_LINE * errorsUnder

    /** `need` fits `room` to the dp rather than to the bit; see `M3uSpreadTest.fits`. */
    private fun fits(need: Dp, room: Dp): Boolean = need.value <= room.value + TOLERANCE

    @Test
    fun `the floor is the controls and nothing else`() {
        for (button in listOf(Sizing.minTouchTarget, Sizing.minTvTarget)) {
            assertEquals(
                "the floor with a ${button.value}dp button",
                (NAME_FIELD + (URL_CHROME + button) * 3 + button + NOTE).value,
                xtreamFloor(button).value,
                TOLERANCE,
            )
        }
        assertEquals("a thumb's floor", 366f, xtreamFloor(Sizing.minTouchTarget).value, TOLERANCE)
        assertEquals("a remote's floor", 398f, xtreamFloor(Sizing.minTvTarget).value, TOLERANCE)
    }

    /**
     * **Both handsets scroll, inside the frame the other two forms draw there.** The same
     * frame, to the dp, with the same margin under it as the gap above it; the form at
     * the sizing system's full spacing, because it scrolls and has no reason to be
     * cramped; and taller than the frame, because a scroll that had nothing to scroll
     * would be a scroll the screen did not need.
     */
    @Test
    fun `both handsets scroll inside the frame the playlist and portal draw`() {
        for (s in handsets) {
            val f = frame(s)
            val shared = formFrame(s.width, s.height, band(s), metrics(s).bandTop, button(s))

            assertTrue("$s: the form does not scroll", f.scroll)
            assertEquals("$s: the frame", shared.height.value, f.height.value, TOLERANCE)
            assertEquals("$s: either side of the form", shared.padH.value, f.padH.value, TOLERANCE)
            assertEquals("$s: the margin under the frame", metrics(s).bandTop.value, (band(s) - f.height).value, TOLERANCE)
            assertFalse(
                "$s: the form is ${content(f, button(s))} in a ${f.height} frame -- it would fit unscrolled",
                fits(content(f, button(s)), f.height),
            )
        }
    }

    /**
     * The handset the frames were approved on spaces the form exactly as the playlist and
     * portal frames are spaced there -- 12 inside, 12 between the fields, 16 above Connect,
     * 8 above the note, 16 either side -- and the shortest handset by the same shares.
     */
    @Test
    fun `the approved handset spaces the form at the sizing system's full size`() {
        val f = frame(handsets[0])

        assertEquals("inside the frame, top and bottom", 12f, f.pad.value, 0.05f)
        assertEquals("between the fields", 12f, f.fieldGap.value, 0.05f)
        assertEquals("above Connect", 16f, f.actionGap.value, 0.05f)
        assertEquals("above the note", 8f, f.noteGap.value, 0.05f)
        assertEquals("either side", 16f, f.padH.value, 0.05f)

        val short = frame(handsets[1])
        assertEquals("the shortest handset, between the fields", 11f, short.fieldGap.value, 0.05f)
        assertEquals("the shortest handset, above Connect", 14.65f, short.actionGap.value, 0.05f)
    }

    /**
     * **Televisions and tablets hold the whole form, without a scroll.** The frame is the
     * whole band -- so the margin under it is the stage's, which the sizing system draws
     * equal to the gap above it -- and the form fits inside it with every gap still a gap,
     * however small the smallest television makes it.
     */
    @Test
    fun `televisions and tablets hold the whole form without scrolling`() {
        for (s in held) {
            val f = frame(s)
            val m = metrics(s)

            assertFalse("$s: the form scrolls", f.scroll)
            assertEquals("$s: the frame is not the whole band", band(s).value, f.height.value, TOLERANCE)
            assertTrue("$s: the form needs ${content(f, button(s))} of ${f.height}", fits(content(f, button(s)), f.height))
            assertEquals("$s: the margins above and under the frame", m.bandTop.value, m.stageBottom.value, 1f)

            for ((gap, value) in listOf(
                "inside the frame" to f.pad,
                "between the fields" to f.fieldGap,
                "above Connect" to f.actionGap,
                "above the note" to f.noteGap,
            )) {
                assertTrue("$s: $gap is $value", value > 0.dp)
            }
        }
    }

    /**
     * The smallest television is the tight one, and these are its numbers: 418dp of band
     * against a 398dp form -- three boxes carrying Paste at a remote's floor -- so the
     * spacing is under a fifth of its full size. Every control keeps its height; only the
     * air between them gives, and none of it runs out.
     */
    @Test
    fun `the smallest television shares out what the controls leave`() {
        val s = held[0]
        val f = frame(s)

        assertEquals("the band", 418f, band(s).value, 0.1f)
        assertEquals("inside the frame", 2.9f, f.pad.value, 0.1f)
        assertEquals("between the fields", 2.9f, f.fieldGap.value, 0.1f)
        assertEquals("above Connect", 3.8f, f.actionGap.value, 0.1f)
        assertEquals("above the note", 1.9f, f.noteGap.value, 0.1f)
        assertEquals("what is left over", 0f, (f.height - content(f, button(s))).value, 0.05f)
    }

    /**
     * And the two larger surfaces have room for the full spacing, which is where the
     * spacing stops: the rest is shared above and below the form inside the frame rather
     * than spent on gaps the sizing system bounds.
     */
    @Test
    fun `the larger surfaces space the form at full size`() {
        for (s in listOf(held[1], held[3])) {
            val f = frame(s)
            val full = s.height

            assertEquals("$s: inside the frame", minOf(full.value * 22f / 720f, 24f), f.pad.value, 0.05f)
            assertEquals("$s: between the fields", minOf(full.value * 22f / 720f, 24f), f.fieldGap.value, 0.05f)
            assertEquals("$s: above Connect", minOf(full.value * 29.3f / 720f, 32f), f.actionGap.value, 0.05f)
            assertEquals("$s: above the note", minOf(full.value * 14.7f / 720f, 16f), f.noteGap.value, 0.05f)
            assertTrue("$s: the form needs ${content(f, button(s))} of ${f.height}", fits(content(f, button(s)), f.height))
        }
    }

    /**
     * **Why an error goes to the note where the form does not scroll.** Written under its
     * field, one error is 24dp the smallest television has not got, and four are 96. The
     * note is a line the frame has already paid for, so it can carry any of them.
     */
    @Test
    fun `an error under a field would not fit where the form does not scroll`() {
        val s = held[0]
        val f = frame(s)

        assertTrue("one error under a field fits at $s", !fits(content(f, button(s), errorsUnder = 1), f.height))
        assertTrue("the form with every field wrong fits at $s", fits(content(f, button(s)), f.height))
    }

    /**
     * **Nothing is cut off, on any surface, and nothing scrolls that could have fitted.**
     *
     * Every height from 300 to 4K at every aspect the application sees, on a thumb and on
     * a remote: where the form does not scroll it fits its frame and its frame is the
     * band; where it does scroll, the band could not have held its controls at any
     * spacing. Every surface at least as tall as the smallest television holds it without
     * a scroll, and every handset held sideways scrolls.
     */
    @Test
    fun `nothing is cut off on any surface`() {
        var fitted = 0
        var scrolled = 0
        var tightest = Float.MAX_VALUE
        var tightestAt = ""

        var height = 300.dp
        while (height <= 2160.dp) {
            for (aspect in listOf(16f / 9f, 1.6f, 1.78f, 1.85f, 2f, 2.17f, 2.4f, 2.8f, 3f)) {
                for (tv in listOf(false, true)) {
                    val s = Surface("${(height * aspect).value.toInt()}x${height.value.toInt()} tv=$tv", height * aspect, height, tv)
                    val b = band(s)
                    val f = frame(s)

                    assertTrue("$s: the frame is ${f.height} of $b", fits(f.height, b) && f.height >= 0.dp)
                    assertTrue("$s: the spacing is out of its bounds", f.pad <= Spacing.xl && f.fieldGap <= Spacing.xl && f.actionGap <= Spacing.xxl && f.noteGap <= Spacing.lg)

                    if (f.scroll) {
                        scrolled++
                        assertTrue("$s scrolls with a $b band that holds the ${xtreamFloor(button(s))} form", b < xtreamFloor(button(s)))
                        assertTrue("$s scrolls, at least as tall as the smallest television", height < 540.dp)
                    } else {
                        fitted++
                        assertEquals("$s: the frame is not the band", b.value, f.height.value, TOLERANCE)
                        assertTrue("$s: the form needs ${content(f, button(s))} of ${f.height}", fits(content(f, button(s)), f.height))
                        val slack = (f.height - content(f, button(s))).value
                        if (slack < tightest) {
                            tightest = slack
                            tightestAt = s.name
                        }
                    }

                    if (!tv && height in 360.dp..440.dp && aspect >= 1.78f) {
                        assertTrue("$s: a handset held sideways does not scroll", f.scroll)
                    }
                }
            }
            height += 1.dp
        }

        println("xtream fit sweep — $fitted fitted, $scrolled scrolled, tightest ${tightest}dp at $tightestAt")
        assertTrue("no surface held the form", fitted > 0)
        assertTrue("no surface scrolled it", scrolled > 0)
    }

    private companion object {
        /** Label row 18 (`labelSmall` carries "optional"), `Spacing.xs`, a box at the touch floor. */
        val NAME_FIELD: Dp = 70.dp

        /** A field with Paste in it, less the chip: the server, the username, the password. */
        val URL_CHROME: Dp = 28.dp

        /** One line of `bodySmall`. */
        val NOTE: Dp = 20.dp

        /** An error under a field: `Spacing.xs` and a line of `bodySmall`. */
        val ERROR_LINE: Dp = 24.dp

        /** A sum of `Float` dp cannot be asserted to the last bit. */
        const val TOLERANCE = 0.01f
    }
}
