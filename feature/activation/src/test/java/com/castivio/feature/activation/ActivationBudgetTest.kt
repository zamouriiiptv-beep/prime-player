package com.castivio.feature.activation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioMetrics
import com.castivio.core.design.theme.CastivioReference
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.castivioMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The activation screen fits — on every surface, not on the four somebody drew.
 *
 * ## Why this is not part of the layout gate
 *
 * Because the layout gate cannot answer it. Robolectric does not lay text out —
 * every `Text` measures 35dp tall there whatever its declared style, and
 * `GraphicsMode.NATIVE` does not change that — which inflates the identity column
 * by roughly 40dp. On a surface whose whole margin is twenty, an assertion about fit
 * made in that harness is an assertion about the harness.
 *
 * So the two claims are separated, and each is made where it can be made
 * honestly:
 *
 * - `ActivationLayoutTest` asks Compose what it **placed**. That is the bug that
 *   shipped — a band that measured zero — and only Compose can answer it.
 * - This asks whether the places add up, from the same [Metrics] the screen is
 *   built from. No runtime, no emulator, and the numbers are the device's.
 *
 * Neither is sufficient. A column that fits on paper but is composed into an
 * unbounded height still vanishes; a screen that is placed correctly on a surface
 * with 40dp of phantom text still tells you nothing about the real one.
 *
 * ## What changed when the device table went
 *
 * The screen used to be built from four rows of hand-written numbers, so this file
 * could only ever ask about those four rows. Every size is now a bounded share of the
 * measured surface, which means the question *"does it fit?"* finally has a domain —
 * and the last test in this file asserts the answer across the whole of it, one dp at
 * a time, rather than at four points with the space between them taken on trust.
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
     * The shortest surface Castivio composes activation for.
     *
     * Below this the identity column cannot hold two field cards, two buttons at their
     * touch floor and a reserved line, however the space is divided — the floors are
     * what a thumb and a camera need and they are not negotiable. The old device table
     * had the same limit at 325dp and nothing stated it; this states it, and the sweep
     * below proves everything above it holds.
     *
     * No landscape Android surface is this short. 360dp is the shortest this project
     * ships to and 344 is that with a transient navigation bar on screen.
     */
    private val SHORTEST = 330.dp

    /** The tallest thing anyone will run this on: a 4K set. */
    private val TALLEST = 2160.dp

    private data class Surface(val name: String, val width: Dp, val height: Dp, val tv: Boolean) {
        val metrics: Metrics get() = metricsFor(tv = tv, width = width, height = height)
        override fun toString() = name
    }

    private val surfaces = listOf(
        Surface("shortest phone 800x360", 800.dp, 360.dp, tv = false),
        Surface("reference phone 873x393", 873.dp, 393.dp, tv = false),
        Surface("tablet 1280x800", 1280.dp, 800.dp, tv = false),
        Surface("television 960x540", 960.dp, 540.dp, tv = true),
        Surface("1080p set 1920x1080", 1920.dp, 1080.dp, tv = true),
        Surface("4K set 3840x2160", 3840.dp, 2160.dp, tv = true),
    )

    /**
     * The two activation screens compose on the same stage.
     *
     * They did before this was written, because the numbers were typed twice and
     * typed the same. That is not a property, it is a coincidence with a maintainer
     * attached: the first edit to one table and the two screens stop being one
     * product, a dp at a time, with nothing failing and no way to see it but memory.
     *
     * [CastivioMetrics] is the system now and both screens read it, so this asserts what
     * the refactor made true rather than hoping for it — and it fails the moment
     * either screen grows a local frame of its own again.
     */
    @Test
    fun `every screen composes on the same metrics`() {
        for (surface in surfaces) {
            assertEquals(
                "$surface: the activation screen and the source choice are on different metrics",
                surface.metrics.frame,
                sourceMetricsFor(tv = surface.tv, width = surface.width, height = surface.height).frame,
            )
            assertEquals(
                "$surface: the metrics did not come from the system",
                castivioMetrics(surface.width, surface.height, surface.tv),
                surface.metrics.frame,
            )
        }
    }

    /**
     * The television still lands on the drawing that was approved for it.
     *
     * This is the whole claim that the migration reproduced the design rather than
     * replacing it: 960×540 is three quarters of the reference, so every share read
     * off the reference has to come back at three quarters — the television row of
     * the table that used to be here, to the dp.
     */
    @Test
    fun `the television reproduces its approved numbers`() {
        val m = metricsFor(tv = true, width = 960.dp, height = 540.dp)
        assertEquals("plate", 192f, m.plate.value, 0.5f)
        assertEquals("zoneWidth", 244f, m.zoneWidth.value, 0.5f)
        assertEquals("capsule", 72f, m.capsule.value, 0.5f)
        assertEquals("footer", 54f, m.footer.value, 0.5f)
        assertEquals("bandGap", 32f, m.bandGap.value, 0.5f)
        assertEquals("mark", 32f, m.mark.value, 0.5f)
        assertEquals("labelWidth", 106f, m.labelWidth.value, 0.5f)
        assertEquals("macSize", 30f, m.macSize.value, 0.5f)
    }

    /** And the reference gives back the numbers the shares were read off. */
    @Test
    fun `the reference gives back the numbers it was read off`() {
        val m = metricsFor(
            tv = true,
            width = CastivioReference.Width,
            height = CastivioReference.Height,
        )
        assertEquals("plate", 210f, m.plate.value, 0.5f) // at its ceiling by then
        assertEquals("capsule", 80f, m.capsule.value, 0.5f) // at its ceiling too
        assertEquals("footer", 60f, m.footer.value, 0.5f)
        assertEquals("labelWidth", 140f, m.labelWidth.value, 0.5f)
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

    private fun spare(surface: Surface, inset: Dp = 0.dp): Dp {
        val usable = surface.height - inset
        val m = metricsFor(tv = surface.tv, width = surface.width, height = usable)
        return m.bandHeight(usable) - m.identityHeight()
    }

    /**
     * Every surface, with the margin each one has.
     *
     * The margins are asserted as a floor rather than an equality: pinning them
     * exactly would make every deliberate spacing change a two-file edit, and the
     * number that matters is whether it is positive.
     */
    @Test
    fun `the identity column fits the band it is given, on every surface`() {
        // Unconditional, because "it fits" is worth reading in a green log too --
        // the interesting number is how close the shortest surface is running.
        println(
            "activation budget — " +
                surfaces.joinToString(" | ") { "$it ${spare(it)}" } +
                " | shortest with a $INSET_ALLOWANCE bar: ${spare(surfaces.first(), INSET_ALLOWANCE)}",
        )

        for (surface in surfaces) {
            val margin = spare(surface)
            assertTrue("$surface overruns its band by ${-margin}", margin > 0.dp)
        }
    }

    /**
     * The shortest surface is the one that decides everything, so its margin is
     * stated rather than left to be rediscovered.
     *
     * ## The number moved, and it is worth saying why
     *
     * It was 34dp and it is now 21. Nothing grew: the shares give a 360dp-tall surface
     * slightly more *outer* margin than the hand-drawn short-phone row did — 16/15
     * against 11/8 — because that row was drawn tight enough that the header looked
     * glued to the glass, and the composition pays the difference. What the 34dp was
     * really protecting is asserted directly now, one test down: the screen still fits
     * with a navigation bar on it. Under a bounded share a shorter surface shrinks its
     * own content, which is exactly what a fixed table could not do.
     *
     * If this fails upward the design got roomier and the number should be
     * updated. If it fails downward something grew, and the next thing to grow
     * takes Add playlist off the screen.
     */
    @Test
    fun `the shortest phone keeps a margin worth having`() {
        val short = spare(surfaces.first())
        assertTrue("the shortest phone is down to $short of margin", short >= 16.dp)
    }

    /**
     * Every surface still fits with the system bars back.
     *
     * This is the assertion the capsules were for. Before them the shortest frame
     * had 9dp of margin, so any vertical inset at all pushed the column past the
     * band -- and a column past its band does not clip or scroll, it hands zero
     * height to Add playlist and Refresh. That is the defect a real-device review
     * found, and taking the insets without first making room would have been the
     * same defect committed knowingly.
     */
    @Test
    fun `every surface still fits when the navigation bar comes back`() {
        for (surface in surfaces) {
            val margin = spare(surface, inset = INSET_ALLOWANCE)
            assertTrue(
                "$surface overruns by ${-margin} once a $INSET_ALLOWANCE navigation " +
                    "bar is on screen",
                margin > 0.dp,
            )
        }
    }

    /**
     * The QR side fits too, which stopped being obvious when the plate grew.
     *
     * The identity column is the taller of the two zones on most surfaces, so the band
     * has always been sized by it — and a gate that measured only the column would keep
     * passing while a 6% larger plate pushed the QR past the hairline. Measured rather
     * than assumed.
     */
    @Test
    fun `the QR zone fits its band on every surface, bars back or not`() {
        for (surface in surfaces) {
            for (inset in listOf(0.dp, INSET_ALLOWANCE)) {
                val usable = surface.height - inset
                val m = metricsFor(tv = surface.tv, width = surface.width, height = usable)
                val band = m.bandHeight(usable)
                val code = m.codeHeight()
                assertTrue(
                    "$surface with a $inset bar: the QR zone is $code in a $band band",
                    band - code > 0.dp,
                )
            }
        }
    }

    /**
     * The plate never fits its panel by overflowing it.
     *
     * The plate is a share of the height and the panel a share of the width, so on a
     * surface wide enough or short enough the two could in principle disagree — and a
     * QR wider than the panel holding it is not a crowded drawing, it is a symbol with
     * its quiet zone cut off, which is a symbol a camera cannot read.
     */
    @Test
    fun `the plate always fits inside its panel`() {
        for (surface in surfaces) {
            val m = surface.metrics
            val inner = m.zoneWidth - m.zonePad * 2
            assertTrue(
                "$surface: a ${m.plate} plate in a $inner panel",
                m.plate <= inner,
            )
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
        for (surface in surfaces) {
            val m = surface.metrics

            // The device's own floor, not one floor for every device. A television
            // is driven by a D-pad and `Sizing.minTvTarget` is 56dp; asserting
            // the 48dp phone minimum here is what let the TV copy control ship
            // 8dp short. The one number that was wrong was the one number
            // nothing checked.
            val floor = Sizing.minTarget(surface.tv)
            assertTrue(
                "$surface: the copy control is ${m.target}, below the $floor floor",
                m.target >= floor,
            )
            assertTrue(
                "$surface: the capsule is ${m.capsule} and cannot hold a $floor target",
                m.capsule >= floor,
            )
            assertTrue(
                "$surface: the button is ${m.button}, below the $floor floor",
                m.button >= floor,
            )
            // The column is measured with full-size targets in it, so a positive
            // margin is the statement that nothing had to be crushed to fit.
            assertTrue("$surface: the column does not fit at full size", spare(surface) > 0.dp)
        }
    }

    /**
     * Every surface between the shortest and the largest fits.
     *
     * ## The test the device table could not have
     *
     * The old gate asked four questions because there were four rows of numbers to ask
     * about, and a device that fell between two rows got whichever one it landed in
     * with nobody having looked at the result. There are no rows now — every size is a
     * bounded share of the measured surface — so "does it fit?" can be asked of the
     * whole domain, and it is: every height a dp apart, at three aspect ratios, with
     * and without a navigation bar.
     *
     * Both zones and both device kinds, because the identity column is the taller of
     * the two on a short surface and the code panel on a tall one, and a floor that
     * only one of them clears is a floor that has not been checked.
     */
    @Test
    fun `every surface between the shortest and the largest fits`() {
        var worst = Dp.Infinity
        var worstAt = ""

        var height = SHORTEST
        while (height <= TALLEST) {
            for (aspect in ASPECTS) {
                val width = height * aspect
                // A television never reports a short surface; asserting one would be
                // asserting about a device that does not exist, at the 56dp floor no
                // handset pays.
                val kinds = if (height >= TV_SHORTEST) listOf(false, true) else listOf(false)
                for (tv in kinds) {
                    for (inset in listOf(0.dp, INSET_ALLOWANCE)) {
                        val usable = height - inset
                        if (usable < SHORTEST) continue
                        val m = metricsFor(tv = tv, width = width, height = usable)
                        val band = m.bandHeight(usable)
                        val margin = minOf(band - m.identityHeight(), band - m.codeHeight())
                        if (margin < worst) {
                            worst = margin
                            worstAt = "${width.value.toInt()}x${usable.value.toInt()} tv=$tv"
                        }
                    }
                }
            }
            height += 1.dp
        }

        println("activation sweep — tightest margin $worst at $worstAt")
        assertTrue("the tightest surface overruns by ${-worst}, at $worstAt", worst > 0.dp)
    }

    /** The shortest surface any television reports. Below it there are only handsets. */
    private val TV_SHORTEST = 480.dp

    /** Landscape, from a squat tablet to the widest handset anyone ships. */
    private val ASPECTS = listOf(16f / 9f, 1.85f, 2f, 2.2f, 2.4f)
}
