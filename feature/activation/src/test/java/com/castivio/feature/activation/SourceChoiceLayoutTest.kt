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
import androidx.compose.ui.unit.height
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
 * What the source choice actually places, on each class of screen.
 *
 * ## Why this exists, and it is not a hypothetical
 *
 * The screen was rebuilt to put its two cards side by side inside a fixed
 * viewport, the mockup measured 864x444 with no overflow, CI went green, and the
 * APK came up **stacked and scrolling** on the reporter's device.
 *
 * Every gate held while that was true, because none of them was looking at this
 * screen. `ActivationFrameTest` checks [isFixedViewport], a predicate over a
 * state and a device class — it was correct, and it answered `false`.
 * `ActivationLayoutTest` composes `MacActivationScreen` and nothing else. So
 * "the two cards are in a row" was a claim no test in the repository could
 * evaluate, on any frame.
 *
 * The defect underneath was the condition: it asked `isTv`, and a 873dp phone in
 * landscape is [DeviceClass.Expanded]. Fixing the condition and not the blind
 * spot would leave the next device class to be found the same way — on somebody
 * else's screen, by eye.
 *
 * So this composes the screen and reads back where Compose put things.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-land")
class SourceChoiceLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * A narrow screen keeps the column it has always had.
     *
     * The change was never to put two cards abreast everywhere: at 393dp each
     * would be 170 wide, which is this fix turning into the next defect. This is
     * the half of the behaviour that must not move.
     */
    @Test
    fun `a compact screen stacks the cards`() {
        compose.show(DeviceClass.Compact, PORTRAIT)

        val xtream = compose.bounds(ActivationTags.SOURCE_XTREAM)
        val m3u = compose.bounds(ActivationTags.SOURCE_M3U)

        assertTrue(
            "not stacked: Xtream ends at ${xtream.bottom}, M3U starts at ${m3u.top}",
            m3u.top >= xtream.bottom,
        )
        assertSameWidth("Compact", xtream, m3u)
    }

    /**
     * The class the reporter's phone is in, and the one the first fix missed.
     *
     * 873dp in landscape: wide enough for two 420dp cards, and not a television.
     */
    @Test
    fun `a wide screen puts the cards in a row, whole and unscrollable`() {
        compose.show(DeviceClass.Expanded, WIDE_PHONE)
        assertRowFits(DeviceClass.Expanded, WIDE_PHONE)
    }

    /** And a television, which is the frame the composition was drawn at. */
    @Test
    fun `a television puts the cards in a row, whole and unscrollable`() {
        compose.show(DeviceClass.Television, TELEVISION)
        assertRowFits(DeviceClass.Television, TELEVISION)
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
        compose.show(DeviceClass.Expanded, WIDE_PHONE, LayoutDirection.Rtl)
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
        compose.show(DeviceClass.Expanded, WIDE_PHONE, LayoutDirection.Ltr)
        val xtream = compose.bounds(ActivationTags.SOURCE_XTREAM)
        val m3u = compose.bounds(ActivationTags.SOURCE_M3U)
        assertTrue(
            "LTR: Xtream at ${xtream.left} is not left of M3U at ${m3u.left}",
            xtream.left < m3u.left,
        )
    }

    /* -------------------------------------------------------------------- */

    /**
     * The four claims the reporter's screenshots disproved, asserted together.
     *
     * "No vertical scroll" is the absence of a scroll action rather than an
     * attempt to scroll: a screen that fits has nothing to scroll, and one that
     * did not fit would have grown one. The bounds are the other half — content
     * can run past a fixed container without the container ever becoming
     * scrollable, and that is clipping, which is what the title falling off the
     * top was.
     */
    private fun assertRowFits(device: DeviceClass, frame: Frame) {
        val xtream = compose.bounds(ActivationTags.SOURCE_XTREAM)
        val m3u = compose.bounds(ActivationTags.SOURCE_M3U)
        val back = compose.bounds(ActivationTags.SOURCE_BACK)

        // One row: they share a horizontal band and do not overlap across it.
        assertTrue(
            "$device: not one row — Xtream ${xtream.top}..${xtream.bottom}, " +
                "M3U ${m3u.top}..${m3u.bottom}",
            xtream.top < m3u.bottom && m3u.top < xtream.bottom,
        )
        assertTrue(
            "$device: the cards overlap horizontally",
            xtream.right <= m3u.left || m3u.right <= xtream.left,
        )
        assertSameWidth(device.name, xtream, m3u)

        // Whole, inside the frame. Back is checked by name because it is the
        // element that was falling off the bottom.
        for ((what, box) in listOf("Xtream" to xtream, "M3U" to m3u, "Back" to back)) {
            assertTrue("$device: $what starts above the frame, at ${box.top}", box.top >= 0.dp)
            assertTrue(
                "$device: $what runs past the bottom — ${box.bottom} of ${frame.height}",
                box.bottom <= frame.height,
            )
            assertTrue(
                "$device: $what runs past a side — ${box.left}..${box.right} of ${frame.width}",
                box.left >= 0.dp && box.right <= frame.width,
            )
        }

        // And nothing here scrolls, so a D-pad moves the focus and not the page.
        compose.onAllNodes(hasScrollAction()).assertCountEquals(0)
    }

    /** Equal halves, to the dp. A `weight` split can land a fraction either way. */
    private fun assertSameWidth(device: String, a: DpRect, b: DpRect) = assertTrue(
        "$device: the cards are not equal — ${a.width} against ${b.width}",
        abs((a.width - b.width).value) <= 1f,
    )

    private data class Frame(val width: Dp, val height: Dp)

    /** The reporter's device: 873dp in landscape, `Expanded`, not a television. */
    private val WIDE_PHONE = Frame(873.dp, 393.dp)
    private val TELEVISION = Frame(960.dp, 540.dp)
    private val PORTRAIT = Frame(393.dp, 873.dp)

    /**
     * The screen, at a stated device class and direction.
     *
     * [LocalDeviceClass] is provided *inside* [CastivioTheme], which resolves and
     * provides its own from the configuration; the inner provider wins. That is
     * what lets one Robolectric qualifier serve three device classes — the
     * alternative is three `@Config`s each hoping to map to the class it means,
     * which would be a test of `rememberDeviceClass` rather than of this screen.
     */
    private fun ComposeContentTestRule.show(
        device: DeviceClass,
        frame: Frame,
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
