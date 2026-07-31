package com.castivio.feature.activation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Sizing
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
     * The declared line heights this screen's height budget depends on.
     *
     * Read off the type scale rather than copied, so a change to `CastivioType`
     * reaches this budget instead of silently invalidating it. Taken at a font
     * scale of one, which is what the frames in `design/mockups/` are drawn at;
     * a user-raised scale is the accessibility pass, not this one.
     */
    private val overline = CastivioType.overline.lineHeight.value.dp
    private val legal = CastivioType.bodySmall.lineHeight.value.dp
    private val phoneTitle = CastivioType.headlineMedium.lineHeight.value.dp
    private val tvTitle = CastivioType.headlineLarge.lineHeight.value.dp

    private fun spare(frame: Dp, tv: Boolean): Dp {
        val m = metricsFor(tv = tv, available = frame)
        val band = m.bandHeight(
            frame = frame,
            title = if (tv) tvTitle else phoneTitle,
            legal = legal,
        )
        return band - m.identityHeight(overline)
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
        println("activation budget — 800x360 $short | 873x393 $phone | TV 960x540 $tv")

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
    fun `the shortest phone keeps the margin the design was approved with`() {
        val short = spare(360.dp, tv = false)
        assertTrue(
            "the shortest phone is down to $short of margin; the design was " +
                "approved with 9dp and there is nowhere left to take it from",
            short >= 9.dp,
        )
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
        )) {
            val m = metricsFor(tv = tv, available = frame)
            assertTrue(
                "$name: the copy control is ${m.target}",
                m.target >= Sizing.minTouchTarget,
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
        assertTrue("the 873x393 frame is not on the tall set", phone.edge == 30.dp)
        assertTrue("the two phone frames share a metric set", short != phone)
    }
}
