package com.castivio.feature.licence

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Sizing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vertical budget, on the JVM, where the numbers are the device's.
 *
 * ## Why not in the layout test
 *
 * Because the layout harness cannot measure text. Under Robolectric every `Text`
 * comes back 35dp tall whatever its declared style, which inflates the identity
 * column by about forty — more than any of these frames has to spare. An
 * assertion about fit made there would be an assertion about the harness.
 *
 * So the split is: `LicenceLayoutTest` asserts that Compose **places** every
 * element and none of them is zero, and this asserts that the places it puts
 * them **add up**, from the same [LicenceMetrics] the screen is built from and
 * the line heights `CastivioType` declares. Two claims, each measured where it
 * can be measured honestly.
 *
 * ## The navigation bar is not optional
 *
 * Every margin here is required to survive a 24dp navigation bar appearing. The
 * screen runs immersive, so on a settled device the bars are gone — but
 * `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` means a swipe brings one back for a
 * few seconds, `safeDrawing` padding appears, and the band loses 24dp. A
 * `Column` that no longer fits does not clip and does not scroll: it hands
 * **zero** height to whatever it measured last. Budgeting only for the settled
 * case would be budgeting for the case that cannot fail.
 */
class LicenceBudgetTest {

    /**
     * The three frames the design was drawn at, and the whole display each gives.
     *
     * Not "screen minus the system bars": `:app` is edge-to-edge and this screen
     * is immersive, so it is handed every dp. The bar is subtracted explicitly,
     * below, where it is a stated worst case rather than a hidden assumption.
     */
    private val frames = listOf(
        Triple("reference phone 873x393", false, 393.dp),
        Triple("shortest phone 800x360", false, 360.dp),
        Triple("television 960x540", true, 540.dp),
    )

    private fun title(tv: Boolean): Dp =
        (if (tv) CastivioType.headlineLarge else CastivioType.headlineMedium).lineHeight.value.dp

    private val legal: Dp get() = CastivioType.bodySmall.lineHeight.value.dp
    private val overline: Dp get() = CastivioType.overline.lineHeight.value.dp
    private val caption: Dp get() = CastivioType.bodySmall.lineHeight.value.dp

    private fun spare(frame: Dp, tv: Boolean, insets: Dp = 0.dp, legalLines: Int = 1): Dp {
        val m = licenceMetricsFor(tv, frame - insets)
        return m.bandHeight(frame - insets, title(tv), legal, legalLines) -
            m.columnHeight(overline)
    }

    private fun codeSpare(frame: Dp, tv: Boolean, insets: Dp = 0.dp): Dp {
        val m = licenceMetricsFor(tv, frame - insets)
        return m.bandHeight(frame - insets, title(tv), legal) - m.codeHeight(caption)
    }

    @Test
    fun `the identity column fits its band on every frame`() {
        for ((name, tv, frame) in frames) {
            val room = spare(frame, tv)
            assertTrue(
                "$name: the column overruns its band by ${-room}. A Column that does " +
                    "not fit hands zero height to the status line and then to the plans.",
                room >= 0.dp,
            )
        }
    }

    /**
     * The code zone too, which is not the same claim.
     *
     * The identity column is the taller of the two on every frame today. That is
     * a fact about the current numbers and not a law, and a gate that measured
     * only the column would go on passing while the QR quietly overran.
     *
     * The caption is budgeted at three lines, which is what the mockup measures
     * as the worst case across the nine stress languages — German on the
     * reference frame, English on the tight one. It was two, on reasoning rather
     * than measurement, until somebody looked.
     */
    @Test
    fun `the code zone fits its band on every frame`() {
        for ((name, tv, frame) in frames) {
            val room = codeSpare(frame, tv)
            assertTrue("$name: the code zone overruns its band by ${-room}", room >= 0.dp)
        }
    }

    /**
     * And it still fits with a navigation bar swiped back.
     *
     * The one that matters. A television has no bars, so it is exempt by fact
     * rather than by exception — `safeDrawing` is zero there.
     */
    @Test
    fun `the column still fits with the navigation bar showing`() {
        for ((name, tv, frame) in frames) {
            if (tv) continue
            val room = spare(frame, tv, insets = NAV_BAR)
            assertTrue(
                "$name: with a ${NAV_BAR} navigation bar the column overruns by ${-room}. " +
                    "The bars are hidden, but a swipe brings them back and the layout " +
                    "has to survive the seconds they are there.",
                room >= 0.dp,
            )
        }
    }

    /**
     * Nothing in the column is smaller than the frame's own floor.
     *
     * **The frame's floor**, not one floor for all frames. A television is driven
     * by a D-pad and `Sizing.minTvTarget` is 56dp; asserting the 48dp phone
     * minimum everywhere is exactly what let the sibling screen's copy control
     * ship 8dp short, and then let every `CastivioButton` in the application do
     * the same. The one number that was wrong was the one number nothing checked.
     */
    @Test
    fun `every control clears the floor for its frame`() {
        for ((name, tv, frame) in frames) {
            val m = licenceMetricsFor(tv, frame)
            val floor = Sizing.minTarget(tv)

            assertTrue(
                "$name: the copy control is ${m.target}, below the $floor floor",
                m.target >= floor,
            )
            assertTrue(
                "$name: the capsule is ${m.capsule} and cannot hold a $floor target",
                m.capsule >= floor,
            )
            // A plan card is the button. It is not a fixed-size control, so the
            // claim is on its floor rather than on a modifier.
            assertTrue(
                "$name: a plan card is ${m.planMinHeight}, below the $floor floor",
                m.planHeight(overline) >= floor,
            )
        }
    }

    /**
     * Each frame gets the set the mockup drew for it.
     *
     * Worth its own assertion because the equivalent was wrong once on the
     * sibling and nothing caught it: the gate was reading a height 48dp short of
     * the display, which put the 873×393 phone below the threshold and gave it
     * the short phone's tighter numbers. It looked fine, and it was the wrong
     * drawing.
     */
    @Test
    fun `each frame gets the metric set the mockup drew for it`() {
        val short = licenceMetricsFor(tv = false, available = 360.dp)
        val phone = licenceMetricsFor(tv = false, available = 393.dp)
        val tv = licenceMetricsFor(tv = true, available = 540.dp)

        assertEquals("the 800x360 frame is not on the short set", 26.dp, short.edge)
        assertEquals("the 873x393 frame is not on the reference set", 30.dp, phone.edge)
        assertEquals("the television is not on the TV set", 48.dp, tv.edge)

        // One price token for both phones, and no per-frame exception.
        //
        // Asserted rather than left to review, because it was an exception for
        // one commit -- headlineMedium at 800x360 -- and the way that comes back
        // is somebody needing eight dp in a hurry and remembering that it used
        // to be allowed. It is not allowed: four dp of card padding and six of
        // outer margin buy the same eight without touching the hierarchy.
        assertEquals(
            "the shortest frame has grown a price exception again",
            CastivioType.headlineLarge,
            short.priceStyle,
        )
        assertEquals(CastivioType.headlineLarge, phone.priceStyle)
        assertEquals(CastivioType.displayMedium, tv.priceStyle)
    }

    /**
     * The bands are the sibling's, to the dp.
     *
     * The two screens are the same three-band composition in the same frame, so
     * their bands must be identical numbers. If they ever differ, one of the two
     * has changed a margin and the pair has stopped reading as one product.
     *
     * These are also the numbers `design/mockups/licence.html` now measures —
     * 284/259/337 — which is the whole point of having corrected the drawing.
     */
    @Test
    fun `the bands are the measured ones`() {
        val expected = mapOf(
            Triple("reference phone 873x393", false, 393.dp) to 284.dp,
            Triple("shortest phone 800x360", false, 360.dp) to 264.dp,
            Triple("television 960x540", true, 540.dp) to 337.dp,
        )
        for ((frame, band) in expected) {
            val (name, tv, height) = frame
            val m = licenceMetricsFor(tv, height)
            assertEquals(
                "$name: the band is not the height the mockup measures",
                band,
                m.bandHeight(height, title(tv), legal),
            )
        }
    }

    /**
     * The legal footer gets room for two lines, and that is gated rather than
     * hoped for.
     *
     * ## Why this test exists before the sentence does
     *
     * The final legal wording has not been written — it is a legal question and
     * not a design one, and inventing it would be worse than showing a bracket.
     * But the *space* it lands in is a design question, and answering it now is
     * the difference between a wording change and a layout renegotiation.
     *
     * The placeholder is one line in English and a real sentence will not be one
     * line in German, so one line was never a safe assumption. It was an
     * assumption anyway until this test.
     *
     * ## What fits
     *
     * | frame | 1 line | 1 + bar | 2 lines | 2 + bar |
     * |---|---|---|---|---|
     * | 873×393 | 35dp | 44dp ¹ | 15dp | 24dp ¹ |
     * | 800×360 | 35dp | 11dp | 15dp | **−9dp** |
     * | TV | 35dp | — | 15dp | — |
     *
     * ¹ A bar takes the 873dp frame under the 380dp threshold, so it adopts the
     * tighter metric set and gains margin rather than losing it.
     *
     * Every case fits but one, and [the compound case] below states exactly what
     * that one does instead of failing silently.
     */
    @Test
    fun `the legal footer has room for two lines on every frame`() {
        for ((name, tv, frame) in frames) {
            val room = spare(frame, tv, legalLines = 2)
            assertTrue(
                "$name: a two-line legal footer overruns the band by ${-room}. The " +
                    "wording is not written yet, so the room for it is the part that " +
                    "has to be settled in advance.",
                room >= 0.dp,
            )
        }
    }

    /**
     * The compound case, stated as an assertion so it cannot drift unnoticed.
     *
     * Two-line footer **and** a navigation bar transiently on screen **and** the
     * shortest phone. This is the one combination that does not fit, by 9dp, and
     * pinning the number means a change that makes it worse shows up here rather
     * than on somebody's handset.
     *
     * What happens in those two or three seconds is not a lost control. The
     * column measures capsules → plans → status line and gives what is left to
     * the child measured last, so the **status sentence** is what loses height;
     * every capsule, card and target keeps its size, and the sentence returns
     * when the bar does. `LicenceLayoutTest` proves that ordering.
     */
    @Test
    fun `the one case that does not fit is the one that degrades safely`() {
        val room = spare(360.dp, tv = false, insets = NAV_BAR, legalLines = 2)
        assertEquals(
            "the shortest phone with a two-line footer and the navigation bar " +
                "showing is no longer 9dp short. If it got better, say so here and " +
                "in LicenceMetrics; if it got worse, the status line is losing more " +
                "than a sentence.",
            (-9).dp,
            room,
        )
    }

    /** A gesture bar, and the widest a navigation bar gets in landscape. */
    private val NAV_BAR = 24.dp
}
