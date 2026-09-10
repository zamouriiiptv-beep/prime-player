package com.castivio.feature.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioReference
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.castivioMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player's chrome sizing, asserted rather than looked at.
 *
 * ## What this is for
 *
 * `PlayerLayoutTest` asks Compose where it put things, and that is the right question
 * for placement. It cannot answer *size*, because Robolectric does not lay text out and
 * because the numbers it would be asserting were, until this migration, constants — a
 * test against a constant asserts only that somebody typed it twice.
 *
 * The eight device branches and thirteen constants this replaced were all decisions
 * about how large a control should be, taken by asking what kind of box this is or by
 * asking nothing. So the claims worth gating are the ones a share can get wrong: a
 * control under the floor a remote needs, a caption clearance that no longer clears the
 * chrome, a sheet that swallows the film.
 */
class PlayerMetricsTest {

    private data class Surface(val name: String, val width: Dp, val height: Dp, val tv: Boolean) {
        val metrics: PlayerMetrics get() = playerMetricsFor(tv, width, height)
        override fun toString() = name
    }

    private val surfaces = listOf(
        Surface("shortest phone 800x360", 800.dp, 360.dp, tv = false),
        Surface("reference phone 873x393", 873.dp, 393.dp, tv = false),
        Surface("television 960x540", 960.dp, 540.dp, tv = true),
        Surface("reference 1280x720", 1280.dp, 720.dp, tv = false),
        Surface("tablet 1280x800", 1280.dp, 800.dp, tv = false),
        Surface("1080p set 1920x1080", 1920.dp, 1080.dp, tv = true),
        Surface("4K surface 3840x2160", 3840.dp, 2160.dp, tv = true),
    )

    private val aspects = listOf(16f / 9f, 1.85f, 2f, 2.2f, 2.4f)

    /**
     * The television reproduces the drawing that was approved for it.
     *
     * 960×540 is three quarters of the reference, so every share read off the reference
     * comes back at three quarters — the television side of the eight `if (tv)` branches
     * this replaced, to the dp.
     */
    @Test
    fun `the television reproduces its approved numbers`() {
        val m = playerMetricsFor(tv = true, width = 960.dp, height = 540.dp)
        assertEquals("barGap", 16f, m.barGap.value, 0.5f)
        assertEquals("barGapLarge", 24f, m.barGapLarge.value, 0.5f)
        assertEquals("play", 80f, m.play.value, 0.5f)
        assertEquals("strip", 56f, m.strip.value, 0.5f)
        assertEquals("caption clearance", 196f, m.captionClearance.value, 0.5f)
    }

    /**
     * And the handset reproduces its own, which is the other half of the same claim.
     *
     * Four of these were one constant for every device — a 14dp thumb, a 3dp progress
     * line, a 32dp caption margin, a 68dp raise — and the handset is the surface those
     * constants were actually drawn for. So a share that lands on the television and
     * misses the handset has not reproduced the design, it has replaced it.
     */
    @Test
    fun `the handset stays within a dp or two of what it drew`() {
        val m = playerMetricsFor(tv = false, width = 873.dp, height = 393.dp)
        assertEquals("play", 64f, m.play.value, 2f)
        // The strip is the one figure that moved on a handset, and it moved *up*: it
        // was 44dp, which is 4dp under the 48dp a thumb needs, and its floor is the
        // device's target now. The buttons inside it already carried that floor, so
        // this is the row admitting the height its contents were already forcing.
        assertEquals("strip", 48f, m.strip.value, 0.5f)
        assertEquals("caption side margin", 32f, m.captionSide.value, 3f)
        assertEquals("caption edge margin", 28f, m.captionEdge.value, 3f)
        // The clearance is the one figure that moved on a handset, and upward: it is
        // the sum of the chrome now, and the chrome's inset grew with the shared stage.
        assertTrue("caption clearance ${m.captionClearance}", m.captionClearance >= 148.dp)
        assertEquals("caption raise", 68f, m.captionRaise.value, 5f)
        assertEquals("progress width", 96f, m.progressWidth.value, 5f)
    }

    /** The metrics come from the system every other screen reads. */
    @Test
    fun `the stage comes from the shared system`() {
        for (surface in surfaces) {
            assertEquals(
                "$surface: the frame did not come from castivioMetrics",
                castivioMetrics(surface.width, surface.height, surface.tv),
                surface.metrics.frame,
            )
            assertEquals(
                "$surface: the inset is not the stage's own margin",
                surface.metrics.frame.edge,
                surface.metrics.inset,
            )
        }
    }

    /** The reference gives back the numbers the shares were read off. */
    @Test
    fun `the reference gives back the numbers it was read off`() {
        val m = playerMetricsFor(
            tv = false,
            width = CastivioReference.Width,
            height = CastivioReference.Height,
        )
        assertEquals("barGapLarge", 32f, m.barGapLarge.value, 0.2f)
        // 140.8, because the progress readout is one of the eleven read off the
        // handset rather than the television — see `PlayerMetrics` for the split.
        assertEquals("progress width", 140.8f, m.progressWidth.value, 0.2f)
    }

    /**
     * **Every control a viewer aims at clears the floor for its device.**
     *
     * The play control and the strip are the two the transport bar is built on, and both
     * used to be constants picked per device. A share can put either under the floor on
     * a surface nobody drew, which is precisely what a floor is for.
     */
    @Test
    fun `every control clears the floor for its device`() {
        for (surface in surfaces) {
            val m = surface.metrics
            val floor = Sizing.minTarget(surface.tv)
            assertTrue("$surface: the play control is ${m.play}", m.play >= floor)
            assertTrue("$surface: the strip is ${m.strip}", m.strip >= floor)
            // A scrubber thumb is dragged rather than tapped, so its floor is legibility
            // rather than a target: below this it stops reading as a handle.
            assertTrue("$surface: the thumb is ${m.thumb}", m.thumb >= 12.dp)
            assertTrue("$surface: the track is ${m.track}", m.track >= 3.dp)
        }
    }

    /**
     * **A caption always clears the chrome it is sitting above.**
     *
     * This is the one relationship on the screen where two independent shares have to
     * agree: the clearance is a share of the height and the chrome it clears is a strip
     * plus a play control plus the gaps and the inset. If the clearance ever came out
     * smaller than the thing it clears, captions would sit on the timeline — and it is
     * the kind of failure that only shows on the surfaces nobody screenshots.
     */
    @Test
    fun `a caption clears the chrome beneath it`() {
        for (surface in surfaces) {
            val m = surface.metrics
            val chrome = bottomChrome(surface, m)
            assertTrue(
                "$surface: a caption clears ${m.captionClearance} over a $chrome chrome",
                m.captionClearance > chrome,
            )
        }
    }

    /**
     * What the bottom of the chrome actually occupies, from the parts that build it.
     *
     * The inset the safe-area column keeps, the tools row, the gap above it, and the
     * timeline row — which is a touch target tall. Written from the layout rather than
     * from a remembered number, because this is the model that caught the defect: a
     * clearance declared as its own share came out 10dp *under* this on the reference
     * handset, and a caption would have sat on the timeline.
     */
    private fun bottomChrome(surface: Surface, m: PlayerMetrics): Dp =
        m.inset + m.strip + m.barGap + Sizing.minTarget(surface.tv)

    /**
     * The sheet never swallows the film, and never becomes a strip.
     *
     * It was `if (tv) 0.40f else 0.52f` — a bare fraction, which is the unbounded case
     * the sizing system forbids: 0.40 of a 1920dp television is 768dp of sheet over the
     * picture. The share is the handset's own now, with a ceiling on the result.
     */
    @Test
    fun `the sheet is bounded at both ends`() {
        for (surface in surfaces) {
            val m = surface.metrics
            val sheet = surface.width * m.sheetFraction
            assertTrue("$surface: the sheet is $sheet", sheet >= 320.dp)
            assertTrue("$surface: the sheet is $sheet", sheet <= 450.dp)
            assertTrue(
                "$surface: the sheet takes ${(m.sheetFraction * 100).toInt()}% of the width",
                m.sheetFraction <= 0.60f,
            )
        }
    }

    /** Sizes only ever move in one direction as the surface grows, and then they stop. */
    @Test
    fun `sizes only grow with the surface, and stop`() {
        val ladder = listOf(
            800.dp to 360.dp,
            873.dp to 393.dp,
            960.dp to 540.dp,
            1280.dp to 800.dp,
            1920.dp to 1080.dp,
            3840.dp to 2160.dp,
        ).map { (w, h) -> playerMetricsFor(tv = false, width = w, height = h) }

        for ((smaller, larger) in ladder.zipWithNext()) {
            assertTrue("barGap shrank", larger.barGap >= smaller.barGap)
            assertTrue("play shrank", larger.play >= smaller.play)
            assertTrue("strip shrank", larger.strip >= smaller.strip)
            assertTrue("thumb shrank", larger.thumb >= smaller.thumb)
            assertTrue("clearance shrank", larger.captionClearance >= smaller.captionClearance)
        }

        val huge = ladder.last()
        assertTrue("the 4K play control ${huge.play} is unbounded", huge.play <= 88.dp)
        assertTrue("the 4K strip ${huge.strip} is unbounded", huge.strip <= 64.dp)
        assertTrue("the 4K thumb ${huge.thumb} is unbounded", huge.thumb <= 22.dp)
        // The clearance is a sum of bounded parts rather than a bounded share, so its
        // ceiling is theirs: 72 of inset, 64 of strip, 22 of gap, 56 of target and 30
        // of air. Asserted against that sum rather than a number typed beside it.
        assertTrue(
            "the 4K clearance ${huge.captionClearance} is unbounded",
            huge.captionClearance <= 72.dp + 64.dp + 22.dp + 56.dp + 30.dp,
        )
    }

    /**
     * Every surface between the shortest and the largest holds.
     *
     * The sweep phases 1 to 3 established, applied to this screen's claims: every height
     * a dp apart, at five aspect ratios, on both kinds of device — no control under its
     * floor, no caption resting on the chrome, no sheet outside its bounds, and nothing
     * negative anywhere.
     */
    @Test
    fun `every surface between the shortest and the largest holds`() {
        var worstClearance = Dp.Infinity
        var worstClearanceAt = ""
        var worstControl = Dp.Infinity
        var worstControlAt = ""

        var height = 330.dp
        while (height <= 2160.dp) {
            for (aspect in aspects) {
                val width = height * aspect
                val kinds = if (height >= 480.dp) listOf(false, true) else listOf(false)
                for (tv in kinds) {
                    val m = playerMetricsFor(tv, width, height)
                    val where = "${width.value.toInt()}x${height.value.toInt()} tv=$tv"
                    val floor = Sizing.minTarget(tv)

                    val chrome = m.inset + m.strip + m.barGap + floor
                    val clearance = m.captionClearance - chrome
                    if (clearance < worstClearance) {
                        worstClearance = clearance
                        worstClearanceAt = where
                    }
                    val control = minOf(m.play, m.strip) - floor
                    if (control < worstControl) {
                        worstControl = control
                        worstControlAt = where
                    }

                    val sheet = width * m.sheetFraction
                    assertTrue("$where: the sheet is $sheet", sheet in 320.dp..450.dp)
                    assertTrue("$where: the thumb is ${m.thumb}", m.thumb >= 12.dp)
                    assertTrue("$where: the spinner is ${m.spinner}", m.spinner >= 44.dp)
                    assertTrue("$where: the inset is ${m.inset}", m.inset >= 24.dp)
                }
            }
            height += 1.dp
        }

        println(
            "player sweep — tightest caption clearance $worstClearance at $worstClearanceAt | " +
                "tightest control $worstControl over its floor at $worstControlAt",
        )

        assertTrue(
            "a caption sits ${-worstClearance} into the chrome at $worstClearanceAt",
            worstClearance >= 0.dp,
        )
        assertTrue(
            "a control is ${-worstControl} under its floor at $worstControlAt",
            worstControl >= 0.dp,
        )
    }

}
