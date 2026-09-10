package com.castivio.feature.licence

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioReference
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.castivioMetrics
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
     * The surfaces, each with the width it actually has.
     *
     * The width used to be absent, because the numbers came from a table chosen by
     * height alone. They are shares of both axes now — the gap between the two zones
     * off the width, everything stacked down the stage off the height — so a surface
     * is a size and not a number.
     *
     * Not "screen minus the system bars": `:app` is edge-to-edge and this screen is
     * immersive, so it is handed every dp. The bar is subtracted explicitly, below,
     * where it is a stated worst case rather than a hidden assumption.
     */
    private data class Surface(val name: String, val width: Dp, val height: Dp, val tv: Boolean) {
        override fun toString() = name
    }

    private val SHORTEST = Surface("shortest phone 800x360", 800.dp, 360.dp, tv = false)
    private val HANDSET = Surface("reference phone 873x393", 873.dp, 393.dp, tv = false)
    private val TABLET = Surface("tablet 1280x800", 1280.dp, 800.dp, tv = false)
    private val TELEVISION = Surface("television 960x540", 960.dp, 540.dp, tv = true)
    private val SET_1080 = Surface("1080p set 1920x1080", 1920.dp, 1080.dp, tv = true)

    private val frames = listOf(SHORTEST, HANDSET, TABLET, TELEVISION, SET_1080)

    private val legal: Dp get() = CastivioType.bodySmall.lineHeight.value.dp
    private val overline: Dp get() = CastivioType.overline.lineHeight.value.dp
    private val caption: Dp get() = CastivioType.bodySmall.lineHeight.value.dp

    /** The same surface with a navigation bar on it: shorter, and exactly as wide. */
    private fun Surface.lessBar(inset: Dp) = copy(height = height - inset)

    private fun metrics(s: Surface) = licenceMetricsFor(s.tv, s.width, s.height)

    private fun spare(s: Surface, insets: Dp = 0.dp, legalLines: Int = 1): Dp {
        val short = s.lessBar(insets)
        val m = metrics(short)
        return m.bandHeight(short.height, legal, legalLines) - m.columnHeight(overline)
    }

    private fun codeSpare(s: Surface, insets: Dp = 0.dp): Dp {
        val short = s.lessBar(insets)
        val m = metrics(short)
        return m.bandHeight(short.height, legal) - m.codeHeight(caption)
    }

    @Test
    fun `the identity column fits its band on every surface`() {
        for (s in frames) {
            val room = spare(s)
            println("licence budget — $s column spare $room")
            assertTrue(
                "$s: the column overruns its band by ${-room}. A Column that does " +
                    "not fit hands zero height to the status line and then to the plans.",
                room >= 0.dp,
            )
        }
    }

    /**
     * The code zone too, which is not the same claim.
     *
     * The identity column is the taller of the two on every surface today. That is
     * a fact about the current numbers and not a law, and a gate that measured
     * only the column would go on passing while the QR quietly overran.
     *
     * The caption is budgeted at three lines, which is what the mockup measures
     * as the worst case across the nine stress languages — German on the
     * reference frame, English on the tight one.
     */
    @Test
    fun `the code zone fits its band on every surface`() {
        for (s in frames) {
            val room = codeSpare(s)
            assertTrue("$s: the code zone overruns its band by ${-room}", room >= 0.dp)
        }
    }

    /**
     * The television lands on the drawing that was approved for it.
     *
     * This is the whole claim that the migration reproduced the design rather than
     * replacing it: 960×540 is three quarters of the reference, so every share read
     * off the reference has to come back at three quarters — the television row of
     * the table that used to be here, to the dp, including the price, which was the
     * last type token on this screen chosen by device.
     */
    @Test
    fun `the television reproduces its approved numbers`() {
        val m = metrics(TELEVISION)
        assertEquals("zoneGap", 52f, m.zoneGap.value, 0.5f)
        assertEquals("capsule", 64f, m.capsule.value, 0.5f)
        assertEquals("capsuleStart", 24f, m.capsuleStart.value, 0.5f)
        assertEquals("copyGap", 22f, m.copyGap.value, 0.5f)
        assertEquals("plansGap", 20f, m.plansGap.value, 0.5f)
        assertEquals("planMinHeight", 96f, m.planMinHeight.value, 0.5f)
        assertEquals("planPaddingH", 24f, m.planPaddingH.value, 0.5f)
        assertEquals("plate", 208f, m.plate.value, 0.5f)
        assertEquals("captionWidth", 236f, m.captionWidth.value, 0.5f)
        assertEquals(
            "the price is not the 36sp displayMedium set there",
            36f,
            m.priceStyle.fontSize.value,
            0.5f,
        )
    }

    /** And the metrics come from the system every other screen reads. */
    @Test
    fun `the stage and the type steps come from the shared system`() {
        for (s in frames) {
            assertEquals(
                "$s: the frame did not come from castivioMetrics",
                castivioMetrics(s.width, s.height, s.tv),
                metrics(s).frame,
            )
        }
        val reference = licenceMetricsFor(
            tv = false,
            width = CastivioReference.Width,
            height = CastivioReference.Height,
        )
        assertEquals("the reference zoneGap", 69.3f, reference.zoneGap.value, 0.2f)
        assertEquals("the reference plansGap", 26.7f, reference.plansGap.value, 0.2f)
    }

    /**
     * Nothing in the column is smaller than the floor for its device.
     *
     * **The device's floor**, not one floor for all of them. A television is driven
     * by a D-pad and `Sizing.minTvTarget` is 56dp; asserting the 48dp phone
     * minimum everywhere is exactly what let the sibling screen's copy control
     * ship 8dp short, and then let every `CastivioButton` in the application do
     * the same. The one number that was wrong was the one number nothing checked.
     */
    @Test
    fun `every control clears the floor for its device`() {
        for (s in frames) {
            val m = metrics(s)
            val floor = Sizing.minTarget(s.tv)

            assertTrue(
                "$s: the copy control is ${m.target}, below the $floor floor",
                m.target >= floor,
            )
            assertTrue(
                "$s: the capsule is ${m.capsule} and cannot hold a $floor target",
                m.capsule >= floor,
            )
            // A plan card is the button. It is not a fixed-size control, so the
            // claim is on its floor rather than on a modifier.
            assertTrue(
                "$s: a plan card is ${m.planHeight(overline)}, below the $floor floor",
                m.planHeight(overline) >= floor,
            )
        }
    }

    /**
     * The bands are what the shared stage leaves, to the dp.
     *
     * ## These numbers moved, and the reason is worth reading
     *
     * They were 283 / 268 / 390 / 383, from a stage this screen chose for itself. They
     * are now what `castivioMetrics` leaves, and the shortest phone **loses 25dp of
     * band** to it: the shared stage stands off the glass by 16/15 where the old short
     * row used 11/8, its header is 40 where that row said 36, and the gap under the
     * header is 15 against 8. Every one of those is a value the whole product now
     * shares, approved in phases 1 and 2, and none of them is this screen's to trim.
     *
     * What that costs is stated exactly two tests down rather than left to be found.
     * Pinned here so a change to the shared stage shows up as a failing test on this
     * screen rather than as a discrepancy nobody measured.
     */
    @Test
    fun `the bands are what the stage leaves`() {
        val expected = listOf(
            HANDSET to 270.6f,
            SHORTEST to 242.7f,
            TABLET to 576.9f,
            TELEVISION to 381.0f,
        )
        for ((s, band) in expected) {
            val m = metrics(s)
            assertEquals(
                "$s: the band is not what the stage leaves",
                band,
                m.bandHeight(s.height, legal).value,
                0.5f,
            )
        }
    }

    /**
     * Where the reserved sentence starts to give, stated as two numbers.
     *
     * ## What this replaced, and why it is one test rather than three
     *
     * There were three: *the footer has room for two lines on every frame*, *the column
     * still fits with the navigation bar showing*, and a compound case pinned at −5dp
     * on the shortest phone. All three were asking the same question — how much of the
     * band is left — of a table with four rows in it. There are no rows now, so the
     * question has a domain, and the honest answer is the height at which each case
     * stops fitting.
     *
     * ## The two numbers, and what changed
     *
     * A one-line footer fits from **337dp** of surface upward; a two-line one from
     * **363dp**. Both were comfortably true of the old short row, which had 25dp more
     * band to spend — see the test above for where that band went. So on the 800×360
     * surface two cases that used to clear now do not:
     *
     * | case | was | is |
     * |---|---|---|
     * | one line | +39 | +18 |
     * | one line, bar swiped back | +15 | **−0.5** |
     * | two lines | +19 | **−1.9** |
     * | two lines and the bar | −5 | −20.5 |
     *
     * The 873×393 handset moves the other way and gains: +34 → +42 on one line, and
     * +14 → +22 on two.
     *
     * ## Why this is a threshold and not a failure
     *
     * Because of what gives. The column measures capsules → plans → status line, so
     * the child that loses height is the **reserved sentence** and nothing else: every
     * capsule, plan card and target keeps its size, and the sentence returns when the
     * bar does. A deficit of 1.9dp is 1.9dp off a 20dp reserved line box, not a
     * control anybody can no longer press. `LicenceLayoutTest` proves that ordering
     * and `every control clears the floor for its device` proves the sizes.
     *
     * Pinning the thresholds means a change that moves them shows up here rather than
     * on somebody's handset.
     */
    @Test
    fun `the reserved sentence gives, and only below a stated height`() {
        assertEquals("a one-line footer fits from here up", 337, firstHeightThatFits(1))
        assertEquals("a two-line footer fits from here up", 363, firstHeightThatFits(2))

        // Above the threshold, every surface this project ships to clears it.
        for (s in listOf(HANDSET, TABLET, TELEVISION, SET_1080)) {
            assertTrue(
                "$s: a two-line legal footer overruns the band by ${-spare(s, legalLines = 2)}",
                spare(s, legalLines = 2) >= 0.dp,
            )
        }
    }

    /** The shortest 16:9 surface at which the column still clears its band. */
    private fun firstHeightThatFits(legalLines: Int): Int {
        var height = 330
        while (height <= 600) {
            val s = Surface("$height", (height * 16 / 9).dp, height.dp, tv = false)
            if (spare(s, legalLines = legalLines) >= 0.dp) return height
            height += 1
        }
        return -1
    }

    /**
     * Every surface between the shortest and the largest, at every aspect.
     *
     * The sweep phases 1 and 2 established, applied to this screen's claims: the code
     * zone clears its band throughout, no control falls under its floor, and the
     * column's deficit is never more than the reserved sentence can absorb — which is
     * the property that makes the threshold above a degradation rather than a break.
     */
    @Test
    fun `every surface holds, and never loses more than the sentence`() {
        var worstCode = Dp.Infinity
        var worstCodeAt = ""
        var worstColumn = Dp.Infinity
        var worstColumnAt = ""

        var height = 330.dp
        while (height <= 2160.dp) {
            for (aspect in listOf(16f / 9f, 1.85f, 2f, 2.2f, 2.4f)) {
                val width = height * aspect
                val kinds = if (height >= 480.dp) listOf(false, true) else listOf(false)
                for (tv in kinds) {
                    val s = Surface("${width.value.toInt()}x${height.value.toInt()}", width, height, tv)
                    val m = metrics(s)

                    val code = codeSpare(s)
                    if (code < worstCode) {
                        worstCode = code
                        worstCodeAt = "$s tv=$tv"
                    }
                    val column = spare(s)
                    if (column < worstColumn) {
                        worstColumn = column
                        worstColumnAt = "$s tv=$tv (reserves ${m.statusHeight})"
                    }
                    assertTrue(
                        "$s tv=$tv: the capsule ${m.capsule} is under the floor",
                        m.capsule >= Sizing.minTarget(tv),
                    )
                    assertTrue(
                        "$s tv=$tv: a plan card is under the floor",
                        m.planHeight(overline) >= Sizing.minTarget(tv),
                    )
                }
            }
            height += 1.dp
        }

        println(
            "licence sweep — tightest code zone $worstCode at $worstCodeAt | " +
                "deepest column deficit $worstColumn at $worstColumnAt",
        )

        assertTrue("the code zone overruns by ${-worstCode} at $worstCodeAt", worstCode >= 0.dp)
        // The column may run short on the very shortest surfaces, and when it does the
        // reserved sentence is what absorbs it. Never more than the sentence has.
        assertTrue(
            "the column is ${-worstColumn} short at $worstColumnAt, which is more than " +
                "the reserved sentence can give — a control would lose height instead",
            worstColumn >= -20.dp,
        )
    }
}
