package com.castivio.feature.activation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.DeviceClass
import com.castivio.core.design.theme.LocalDeviceClass
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where the source choice actually puts its four elements.
 *
 * ## Two versions of this file shipped a broken screen. Here is what each missed
 *
 * The screen was rebuilt to put its two cards side by side, the mockup measured
 * with no overflow, CI went green, and the APK came up **stacked and scrolling**
 * on the reporter's handset — twice.
 *
 * The first time there was no test at all: `ActivationFrameTest` checked a
 * predicate and `ActivationLayoutTest` composes a different screen, so "the two
 * cards are in a row" was a claim nothing in the repository could evaluate.
 *
 * The second time this file existed and passed, which was worse. It forced
 * `LocalDeviceClass` to the value it wanted and then asserted the layout that
 * value produces. But the device class **was the broken input**: the screen asked
 * whether the window was 840dp wide, the window is 873 and gives the layout 827
 * of it once `safeDrawing` has taken the display cutout, and which side of the
 * line the platform's own figure falls on varies by handset. A test that supplies
 * the faulty input cannot fail for the faulty input.
 *
 * So the device class is still provided here, but one frame is composed twice:
 * once as [DeviceClass.Expanded], which is what a 873dp handset ought to report,
 * and once as [DeviceClass.Compact], which is the lie the reporter's phone
 * actually told. Both have to produce the row. They do, because there is no
 * longer a condition: two `weight(1f)` halves divide whatever width exists, and
 * the activity is `screenOrientation="sensorLandscape"`, so the portrait frame
 * the old column existed for never reaches a user.
 *
 * ## What is asserted here, and what is asserted next door
 *
 * Robolectric does not lay text out — every `Text` measures 35dp tall whatever
 * its style, as `ActivationBudgetTest` documents at length. Four of them here, so
 * the screen comes out 371dp in this harness against the 320 it will really be.
 *
 * The two frames with room for that inflation are asserted whole, vertically as
 * well as horizontally. The 360dp frame is not: 371 does not fit in 360, and an
 * assertion that failed there would be an assertion about the harness rather than
 * about the screen. Fit on the frames with least to spare belongs to
 * `SourceChoiceBudgetTest`, which computes it on the JVM from the line heights
 * `CastivioType` declares.
 *
 * Everything else the harness measures truthfully and every frame is held to it:
 * horizontal placement, order, the direction the row resolves, the absence of a
 * scroll, and the vertical *sequence* — heading above the cards, cards above
 * Back — which is the property the scroll used to break.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-land")
class SourceChoiceLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    /** The reporter's handset: 873dp of window, 827 once the cutout is taken. */
    @Test
    fun `an Expanded handset gets the row, whole and unscrollable`() {
        compose.show(HANDSET, DeviceClass.Expanded)
        compose.assertRow(HANDSET)
        compose.assertVerticallyInside(HANDSET)
    }

    /**
     * The same handset, reporting the class it actually reported.
     *
     * [DeviceClass.Compact] is the lie: `screenWidthDp` is not the width this
     * screen is given, and on the reporter's phone the bucket came out below
     * `Expanded`. The row has to appear regardless, and this is the test that
     * fails the day somebody puts the condition back.
     */
    @Test
    fun `the same handset gets the row even when it calls itself Compact`() {
        compose.show(HANDSET, DeviceClass.Compact)
        compose.assertRow(HANDSET)
        compose.assertVerticallyInside(HANDSET)
    }

    /**
     * The frame the composition was drawn at, in its own class.
     *
     * The one frame here that gets the television's numbers — 48dp of overscan
     * and `Spacing.xxxl` inside the cards — because [DeviceClass.Television] is
     * the one thing about the device this screen still legitimately reads.
     */
    @Test
    fun `a television gets the row, whole and unscrollable`() {
        compose.show(TELEVISION, DeviceClass.Television)
        compose.assertRow(TELEVISION)
        compose.assertVerticallyInside(TELEVISION)
    }

    /**
     * The narrowest landscape frame Castivio ships to, still a row.
     *
     * There is no width at which this screen stacks. `weight(1f)` divides the
     * space it is given rather than asking for a size, so the row cannot overflow
     * horizontally — it can only make two smaller cards.
     */
    @Test
    fun `the shortest frame gets the row too`() {
        compose.show(SHORT, DeviceClass.Compact)
        compose.assertRow(SHORT)
    }

    /**
     * Start to end, with nothing in the source that knows which end is which.
     *
     * A `Row` resolves its own direction. The risk this guards is somebody later
     * "correcting" an order they saw in one language with an index or an offset,
     * which would read fine in that language and be backwards in the other.
     */
    @Test
    fun `Xtream leads on the right when the layout runs right to left`() {
        compose.show(HANDSET, DeviceClass.Expanded, LayoutDirection.Rtl)
        val xtream = compose.bounds(ActivationTags.SOURCE_XTREAM)
        val m3u = compose.bounds(ActivationTags.SOURCE_M3U)
        assertTrue(
            "RTL: Xtream at ${xtream.left} is not right of M3U at ${m3u.left}",
            xtream.left > m3u.left,
        )
    }

    /** And the mirror of it, which is the same composition and no extra code. */
    @Test
    fun `Xtream leads on the left when the layout runs left to right`() {
        compose.show(HANDSET, DeviceClass.Expanded, LayoutDirection.Ltr)
        val xtream = compose.bounds(ActivationTags.SOURCE_XTREAM)
        val m3u = compose.bounds(ActivationTags.SOURCE_M3U)
        assertTrue(
            "LTR: Xtream at ${xtream.left} is not left of M3U at ${m3u.left}",
            xtream.left < m3u.left,
        )
    }

    /* -------------------------------------------------------------------- */

    /**
     * One row of equal halves, in order, inside the width, with nothing to scroll.
     *
     * "No vertical scroll" is the absence of a scroll action rather than a failed
     * attempt to scroll: a screen that fits has nothing to scroll, and one that
     * did not fit would have grown one — that is precisely how the stacked
     * version behaved.
     */
    private fun ComposeContentTestRule.assertRow(frame: Frame) {
        val heading = bounds(ActivationTags.SOURCE_HEADING)
        val xtream = bounds(ActivationTags.SOURCE_XTREAM)
        val m3u = bounds(ActivationTags.SOURCE_M3U)
        val back = bounds(ActivationTags.SOURCE_BACK)

        println(
            "source choice ${frame.width}x${frame.height} — " +
                "heading ${heading.top}..${heading.bottom} | " +
                "Xtream ${xtream.left}..${xtream.right} @ ${xtream.top}..${xtream.bottom} | " +
                "M3U ${m3u.left}..${m3u.right} @ ${m3u.top}..${m3u.bottom} | " +
                "back ${back.top}..${back.bottom}",
        )

        // Side by side: they share a horizontal band and do not overlap across it.
        assertTrue(
            "${frame.width}: not one row — Xtream ${xtream.top}..${xtream.bottom}, " +
                "M3U ${m3u.top}..${m3u.bottom}",
            xtream.top < m3u.bottom && m3u.top < xtream.bottom,
        )
        assertTrue(
            "${frame.width}: the cards overlap horizontally",
            xtream.right <= m3u.left || m3u.right <= xtream.left,
        )
        assertTrue(
            "${frame.width}: the cards are not equal halves — " +
                "${xtream.width} against ${m3u.width}",
            abs((xtream.width - m3u.width).value) <= 1f,
        )

        // Heading, cards, Back — in that order down the screen. This is the
        // sequence the scroll destroyed by moving the page under all three.
        assertTrue(
            "${frame.width}: the heading is not above the cards",
            heading.bottom <= xtream.top,
        )
        assertTrue(
            "${frame.width}: Back is not below the cards",
            back.top >= xtream.bottom && back.top >= m3u.bottom,
        )

        // Inside the width, all four. Widths Robolectric measures honestly.
        for ((what, box) in frame.elements(heading, xtream, m3u, back)) {
            assertTrue(
                "${frame.width}: $what runs past a side — " +
                    "${box.left}..${box.right} of ${frame.width}",
                box.left >= 0.dp && box.right <= frame.width,
            )
        }

        onAllNodes(hasScrollAction()).assertCountEquals(0)
    }

    /**
     * And inside the height, on the frames with room for the harness's fake text.
     *
     * Not a weaker claim than the horizontal one, just a claim this harness can
     * only make where its own inflation leaves room — see the note on the class.
     */
    private fun ComposeContentTestRule.assertVerticallyInside(frame: Frame) {
        val heading = bounds(ActivationTags.SOURCE_HEADING)
        for ((what, box) in frame.elements(
            heading,
            bounds(ActivationTags.SOURCE_XTREAM),
            bounds(ActivationTags.SOURCE_M3U),
            bounds(ActivationTags.SOURCE_BACK),
        )) {
            assertTrue("${frame.height}: $what starts above the frame, at ${box.top}", box.top >= 0.dp)
            assertTrue(
                "${frame.height}: $what runs past the bottom — ${box.bottom} of ${frame.height}",
                box.bottom <= frame.height,
            )
        }
    }

    private data class Frame(val width: Dp, val height: Dp) {
        fun elements(heading: DpRect, xtream: DpRect, m3u: DpRect, back: DpRect) = listOf(
            "the heading" to heading,
            "Xtream" to xtream,
            "M3U" to m3u,
            "Back" to back,
        )
    }

    /**
     * The reporter's device, as the layout receives it.
     *
     * 827 rather than the window's 873: `ActivationSurface` applies
     * `windowInsetsPadding(safeDrawing)` before this screen sees anything, and on
     * that handset the display cutout takes 41dp of the leading edge in
     * landscape. Testing the window width would be testing a frame the screen is
     * never given.
     */
    private val HANDSET = Frame(827.dp, 393.dp)
    private val TELEVISION = Frame(960.dp, 540.dp)

    /**
     * Narrower than anything Castivio expects, at the shortest height it ships to.
     *
     * 360dp is `ActivationLayoutTest`'s floor and this screen's too; 568 is a
     * width chosen to be below any landscape handset rather than to model one.
     * The point is that no width produces a column.
     */
    private val SHORT = Frame(568.dp, 360.dp)

    /**
     * The screen, at a stated frame and a stated device class.
     *
     * The class is stated rather than derived, and one of the frames states a
     * value that is *wrong for it* on purpose: nothing in this screen may consult
     * the class to choose between a row and a column, so a hostile value has to
     * change nothing about the placement. What the class legitimately still
     * decides is the edge inset — 48dp of overscan on a television, 24 elsewhere
     * — which is why the television frame is given its own.
     *
     * Provided *inside* [CastivioTheme], which resolves its own from the
     * configuration; the inner provider wins. That is what lets one Robolectric
     * qualifier serve every frame here, instead of three `@Config`s each hoping
     * to map to the class it means — which would be a test of
     * `rememberDeviceClass` rather than of this screen.
     */
    private fun ComposeContentTestRule.show(
        frame: Frame,
        device: DeviceClass,
        direction: LayoutDirection = LayoutDirection.Rtl,
    ) = setContent {
        CastivioTheme {
            CompositionLocalProvider(
                LocalDeviceClass provides device,
                LocalLayoutDirection provides direction,
            ) {
                Stage(frame) {
                    SourceChoiceScreen(onXtream = {}, onPlaylist = {}, onBack = {})
                }
            }
        }
    }

    /** `requiredSize`, so the frame is imposed rather than negotiated. */
    @Composable
    private fun Stage(frame: Frame, content: @Composable () -> Unit) {
        Box(Modifier.requiredSize(frame.width, frame.height)) { content() }
    }

    private fun ComposeContentTestRule.bounds(tag: String): DpRect =
        onNodeWithTag(tag).getUnclippedBoundsInRoot()
}
