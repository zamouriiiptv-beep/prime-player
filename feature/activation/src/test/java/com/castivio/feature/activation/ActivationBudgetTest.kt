package com.castivio.feature.activation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioFrame
import com.castivio.core.design.theme.Sizing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The activation screen fits, on every frame it is drawn for.
 *
 * ## Why this is not part of the layout gate
 *
 * Because the layout gate cannot answer it. Robolectric does not lay text out —
 * every `Text` measures 35dp tall there whatever its declared style, and
 * `GraphicsMode.NATIVE` does not change that — which inflates the identity column
 * by roughly 40dp. On a frame whose whole margin is nine, an assertion about fit
 * made in that harness is an assertion about the harness.
 *
 * So the two claims are separated, and each is made where it can be made
 * honestly:
 *
 * - `ActivationLayoutTest` asks Compose what it **placed**. That is the bug that
 *   shipped — a band that measured zero — and only Compose can answer it.
 * - This asks whether the places add up, from the same [Metrics] the screen is
 *   built from and the line heights `CastivioType` declares. No runtime, no
 *   emulator, and the numbers are the device's.
 *
 * Neither is sufficient. A column that fits on paper but is composed into an
 * unbounded height still vanishes; a screen that is placed correctly on a frame
 * with 40dp of phantom text still tells you nothing about the real one.
 *
 * ## What "fits" has to mean
 *
 * Not "the pixels are inside the screen". A `Column` given less height than its
 * children need does not clip and does not scroll — it hands **zero** to the ones
 * measured last. Add playlist and Refresh are measured last. So an overrun of one
 * dp is not one dp of crowding, it is the two controls that start the whole flow
 * disappearing, which is exactly what a user photographed.
 */
class ActivationBudgetTest {

    /**
     * The two activation screens compose on the same stage.
     *
     * They did before this was written, because the numbers were typed twice and
     * typed the same. That is not a property, it is a coincidence with a maintainer
     * attached: the first edit to one table and the two screens stop being one
     * product, a dp at a time, with nothing failing and no way to see it but memory.
     *
     * [CastivioFrame] is the table now and both screens read it, so this asserts what
     * the refactor made true rather than hoping for it — and it fails the moment
     * either screen grows a local frame of its own again.
     */
    @Test
    fun `every screen composes on the same frame`() {
        for ((name, tv, frame) in listOf(
            Triple("shortest phone", false, 360.dp),
            Triple("reference phone", false, 393.dp),
            Triple("tablet", false, 800.dp),
            Triple("television", true, 540.dp),
        )) {
            assertEquals(
                "$name: the activation screen and the source choice are on different frames",
                metricsFor(tv = tv, available = frame).frame,
                sourceMetricsFor(tv = tv, available = frame).frame,
            )
        }
    }


    /**
     * What a swiped-back navigation bar costs the tallest thing on screen.
     *
     * Activation runs immersive, so on a settled screen the insets are zero and
     * this is spent on nothing. It is budgeted anyway: transient bars are one
     * swipe away, a device may refuse to hide them, and a layout that only fits
     * while the system is cooperating is a layout that breaks in a photograph
     * somebody sends us. 24dp is a landscape gesture bar; a landscape
     * three-button bar goes to the side and costs height nothing.
     */
    private val INSET_ALLOWANCE = 24.dp

    private fun spare(frame: Dp, tv: Boolean, inset: Dp = 0.dp): Dp {
        val usable = frame - inset
        val m = metricsFor(tv = tv, available = usable)
        return m.bandHeight(usable) - m.identityHeight()
    }

    /**
     * Every frame, with the margin each one has.
     *
     * The margins are asserted as a floor rather than an equality: pinning them
     * exactly would make every deliberate spacing change a two-file edit, and the
     * number that matters is whether it is positive.
     */
    @Test
    fun `the identity column fits the band it is given, on every frame`() {
        val short = spare(360.dp, tv = false)
        val phone = spare(393.dp, tv = false)
        val tv = spare(540.dp, tv = true)

        // Unconditional, because "it fits" is worth reading in a green log too --
        // the interesting number is how close the shortest frame is running.
        println(
            "activation budget — 800x360 $short | 873x393 $phone | TV 960x540 $tv | " +
                "with a $INSET_ALLOWANCE bar: ${spare(360.dp, false, INSET_ALLOWANCE)}",
        )

        assertTrue("the shortest phone overruns its band by ${-short}", short > 0.dp)
        assertTrue("the reference phone overruns its band by ${-phone}", phone > 0.dp)
        assertTrue("the television overruns its band by ${-tv}", tv > 0.dp)
    }

    /**
     * The shortest frame is the one that decides everything, so its margin is
     * stated rather than left to be rediscovered.
     *
     * If this fails upward the design got roomier and the number should be
     * updated. If it fails downward something grew, and the next thing to grow
     * takes Add playlist off the screen.
     */
    @Test
    fun `the shortest phone keeps the margin the capsules bought it`() {
        val short = spare(360.dp, tv = false)
        assertTrue(
            "the shortest phone is down to $short of margin; the field cards left " +
                "it 40dp and the inset budget needs most of that",
            short >= 34.dp,
        )
    }

    /**
     * The frame still fits with the system bars back.
     *
     * This is the assertion the capsules were for. Before them the shortest frame
     * had 9dp of margin, so any vertical inset at all pushed the column past the
     * band -- and a column past its band does not clip or scroll, it hands zero
     * height to Add playlist and Refresh. That is the defect a real-device review
     * found, and taking the insets without first making room would have been the
     * same defect committed knowingly.
     */
    @Test
    fun `every frame still fits when the navigation bar comes back`() {
        for ((name, tv, frame) in listOf(
            Triple("shortest phone", false, 360.dp),
            Triple("reference phone", false, 393.dp),
            Triple("television", true, 540.dp),
            Triple("tablet", false, 800.dp),
        )) {
            val margin = spare(frame, tv, inset = INSET_ALLOWANCE)
            assertTrue(
                "$name overruns by ${-margin} once a $INSET_ALLOWANCE navigation " +
                    "bar is on screen",
                margin > 0.dp,
            )
        }
    }

    /**
     * The QR side fits too, which stopped being obvious when the plate grew.
     *
     * The identity column is the taller of the two zones on every frame at
     * today's numbers, so the band has always been sized by it — and a gate that
     * measured only the column would keep passing while a 6% larger plate pushed
     * the QR past the hairline. Measured rather than assumed.
     */
    @Test
    fun `the QR zone fits its band on every frame, bars back or not`() {
        for ((name, tv, frame) in listOf(
            Triple("shortest phone", false, 360.dp),
            Triple("reference phone", false, 393.dp),
            Triple("television", true, 540.dp),
            Triple("tablet", false, 800.dp),
        )) {
            for (inset in listOf(0.dp, INSET_ALLOWANCE)) {
                val usable = frame - inset
                val m = metricsFor(tv = tv, available = usable)
                val band = m.bandHeight(usable)
                val code = m.codeHeight()
                assertTrue(
                    "$name with a $inset bar: the QR zone is $code in a $band band",
                    band - code > 0.dp,
                )
            }
        }
    }

    /**
     * A control that cannot be pressed is a control that is not there.
     *
     * The layout gate asserts this for the copy controls, which are a fixed size
     * and survive its phantom text. It cannot assert it for the two buttons,
     * because they are what a squeezed column crushes first — so the claim is made
     * here, where the column's height is the device's rather than the harness's.
     */
    @Test
    fun `nothing in the column is smaller than a touch target`() {
        for ((name, tv, frame) in listOf(
            Triple("shortest phone", false, 360.dp),
            Triple("reference phone", false, 393.dp),
            Triple("television", true, 540.dp),
            Triple("tablet", false, 800.dp),
        )) {
            val m = metricsFor(tv = tv, available = frame)

            // The frame's own floor, not one floor for all frames. A television
            // is driven by a D-pad and `Sizing.minTvTarget` is 56dp; asserting
            // the 48dp phone minimum here is what let the TV copy control ship
            // 8dp short. The one number that was wrong was the one number
            // nothing checked.
            val floor = Sizing.minTarget(tv)
            assertTrue(
                "$name: the copy control is ${m.target}, below the $floor floor",
                m.target >= floor,
            )
            assertTrue(
                "$name: the capsule is ${m.capsule} and cannot hold a $floor target",
                m.capsule >= floor,
            )
            // The column is measured with full-size targets in it, so a positive
            // margin is the statement that nothing had to be crushed to fit.
            assertTrue("$name: the column does not fit at full size", spare(frame, tv) > 0.dp)
        }
    }

    /**
     * The frame threshold picks the set the design drew for each frame.
     *
     * Worth its own assertion because it was wrong once in a way nothing caught:
     * the gate was reading a height 48dp short of the display, which put the
     * 873×393 phone below the threshold and gave it the short phone's tighter
     * numbers. It looked fine, and it was the wrong drawing.
     */
    @Test
    fun `each frame gets the metric set the mockup drew for it`() {
        val short = metricsFor(tv = false, available = 360.dp)
        val phone = metricsFor(tv = false, available = 393.dp)

        assertTrue("the 800x360 frame is not on the short set", short.edge == 26.dp)
        assertTrue("the 873x393 frame is not on the tall set", phone.edge == 32.dp)
        assertTrue("the two phone frames share a metric set", short != phone)
    }
}
