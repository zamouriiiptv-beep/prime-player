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
import androidx.compose.ui.test.performClick
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where the source choice puts its four cards and its footer.
 *
 * ## Two versions of this file shipped a broken screen. Here is what each missed
 *
 * The screen was rebuilt to put its cards side by side, the mockup measured with no
 * overflow, CI went green, and the APK came up **stacked and scrolling** on the
 * reporter's handset — twice.
 *
 * The first time there was no test at all: `ActivationFrameTest` checked a predicate
 * and `ActivationLayoutTest` composes a different screen, so "the cards are in a row"
 * was a claim nothing in the repository could evaluate.
 *
 * The second time this file existed and passed, which was worse. It forced
 * `LocalDeviceClass` to the value it wanted and then asserted the layout that value
 * produces. But the device class **was the broken input**: the screen asked whether
 * the window was 840dp wide, the window is 873 and hands the layout 827 once
 * `safeDrawing` has taken the display cutout, and which side of the line the
 * platform's figure falls on varies by handset. A test that supplies the faulty input
 * cannot fail for the faulty input.
 *
 * So the device class is supplied here as a **hostile** value on most frames — the
 * narrowest the enum has, on frames far wider than it describes. Nothing in the screen
 * may consult it to decide the shape of the grid, and this is the file that fails the
 * day something does.
 *
 * ## What is asserted here, and what is asserted next door
 *
 * Robolectric does not lay text out: every `Text` measures 35dp whatever its style, as
 * `ActivationBudgetTest` documents at length. Four cards of two text lines each makes
 * this screen about 60dp taller in the harness than on a device. Vertical containment
 * is therefore asserted only on frames with room for that inflation, and fit on the
 * tight frames is computed from real type metrics in `SourceChoiceBudgetTest`.
 *
 * Everything the harness measures truthfully is asserted on every frame: equal widths,
 * equal heights, the two-by-two arrangement, reading order, direction, containment
 * across the width, and the absence of anything scrollable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-land")
class SourceChoiceLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    /* ------------------------------------------------------------ the four frames */

    /** The reporter's handset: 873dp of window, 827 once the cutout is taken. */
    @Test
    fun `an Expanded handset gets the grid, whole and unscrollable`() {
        compose.show(HANDSET, DeviceClass.Expanded)
        compose.assertGrid(HANDSET)
        compose.assertVerticallyInside(HANDSET)
    }

    /**
     * The same handset, reporting the class it actually reported.
     *
     * [DeviceClass.Compact] is the lie, and the grid has to appear regardless. This is
     * the test that fails the day somebody puts a width condition back.
     */
    @Test
    fun `the same handset gets the grid even when it calls itself Compact`() {
        compose.show(HANDSET, DeviceClass.Compact)
        compose.assertGrid(HANDSET)
        compose.assertVerticallyInside(HANDSET)
    }

    /** The television, in its own class, which is where the overscan inset applies. */
    @Test
    fun `a television gets the grid, whole and unscrollable`() {
        compose.show(TELEVISION, DeviceClass.Television)
        compose.assertGrid(TELEVISION)
        compose.assertVerticallyInside(TELEVISION)
    }

    /** The shortest frame the project ships to, at a width nothing real is this narrow. */
    @Test
    fun `the shortest frame gets the grid too`() {
        compose.show(SHORT, DeviceClass.Compact)
        compose.assertGrid(SHORT)
    }

    /** A square-ish frame, to pin that nothing here keys off an aspect ratio. */
    @Test
    fun `a square frame gets the grid too`() {
        compose.show(SQUARE, DeviceClass.Medium)
        compose.assertGrid(SQUARE)
    }

    /* ------------------------------------------------------------------- direction */

    /**
     * Start to end, with nothing in the source that knows which end is which.
     *
     * Asserted on the whole grid rather than on one pair: a `Row` resolves its own
     * direction, and the risk this guards is somebody "correcting" an order they saw
     * in one language with an index or an offset, which reads fine in that language
     * and is backwards in the other.
     */
    @Test
    fun `right to left puts Xtream top-right and the users card bottom-left`() {
        compose.show(HANDSET, DeviceClass.Expanded, LayoutDirection.Rtl)

        val xtream = compose.bounds(ActivationTags.SOURCE_XTREAM)
        val m3u = compose.bounds(ActivationTags.SOURCE_M3U)
        val local = compose.bounds(ActivationTags.SOURCE_LOCAL)
        val users = compose.bounds(ActivationTags.SOURCE_USERS)

        assertTrue("RTL: Xtream is not right of M3U", xtream.left > m3u.left)
        assertTrue("RTL: the local card is not right of the users card", local.left > users.left)
        assertTrue("RTL: Xtream is not above the local card", xtream.top < local.top)

        // Back at the start, Terms at the end -- the mirror of the English footer.
        val back = compose.bounds(ActivationTags.SOURCE_BACK)
        val terms = compose.bounds(ActivationTags.SOURCE_TERMS)
        assertTrue("RTL: Back is not on the right", back.left > terms.left)
    }

    /** And the mirror of it, which is the same composition and no extra code. */
    @Test
    fun `left to right puts Xtream top-left and the users card bottom-right`() {
        compose.show(HANDSET, DeviceClass.Expanded, LayoutDirection.Ltr)

        val xtream = compose.bounds(ActivationTags.SOURCE_XTREAM)
        val m3u = compose.bounds(ActivationTags.SOURCE_M3U)
        val local = compose.bounds(ActivationTags.SOURCE_LOCAL)
        val users = compose.bounds(ActivationTags.SOURCE_USERS)

        assertTrue("LTR: Xtream is not left of M3U", xtream.left < m3u.left)
        assertTrue("LTR: the local card is not left of the users card", local.left < users.left)
        assertTrue("LTR: Xtream is not above the local card", xtream.top < local.top)

        val back = compose.bounds(ActivationTags.SOURCE_BACK)
        val terms = compose.bounds(ActivationTags.SOURCE_TERMS)
        assertTrue("LTR: Back is not on the left", back.left < terms.left)
    }

    /* ------------------------------------------------------------------ behaviour */

    /**
     * Each card calls its own destination, and only its own.
     *
     * Four cards built from one composable is exactly the shape where a copied
     * argument goes unnoticed -- two cards wired to the same lambda look identical on
     * a screenshot and are a dead end on a device.
     */
    @Test
    fun `each card calls the destination it names`() {
        val pressed = mutableListOf<String>()
        compose.setContent {
            CastivioTheme {
                CompositionLocalProvider(LocalDeviceClass provides DeviceClass.Expanded) {
                    Stage(HANDSET) {
                        SourceChoiceScreen(
                            onXtream = { pressed += "xtream" },
                            onPlaylist = { pressed += "m3u" },
                            onLocalVideo = { pressed += "local" },
                            onSavedSources = { pressed += "users" },
                            onTerms = { pressed += "terms" },
                            onBack = { pressed += "back" },
                        )
                    }
                }
            }
        }

        for ((tag, expected) in listOf(
            ActivationTags.SOURCE_XTREAM to "xtream",
            ActivationTags.SOURCE_M3U to "m3u",
            ActivationTags.SOURCE_LOCAL to "local",
            ActivationTags.SOURCE_USERS to "users",
            ActivationTags.SOURCE_TERMS to "terms",
            ActivationTags.SOURCE_BACK to "back",
        )) {
            pressed.clear()
            compose.onNodeWithTag(tag).performClick()
            assertEquals("$tag called the wrong destination", listOf(expected), pressed)
        }
    }

    /* -------------------------------------------------------------------------- */

    /**
     * Four equal cards in two rows of two, in order, inside the width, unscrollable.
     *
     * "No vertical scroll" is the absence of a scroll action rather than a failed
     * attempt to scroll: a screen that fits has nothing to scroll, and one that did
     * not fit would have grown one — which is precisely how the stacked version
     * behaved.
     */
    private fun ComposeContentTestRule.assertGrid(frame: Frame) {
        val heading = bounds(ActivationTags.SOURCE_HEADING)
        val cards = CARD_TAGS.map { it to bounds(it) }
        val back = bounds(ActivationTags.SOURCE_BACK)
        val terms = bounds(ActivationTags.SOURCE_TERMS)

        println(
            "source choice ${frame.width}x${frame.height} — " +
                cards.joinToString(" | ") { (tag, b) ->
                    "${tag.substringAfterLast('.')} ${b.width}x${b.height} @${b.left},${b.top}"
                } + " || heading ${heading.top}..${heading.bottom} " +
                "back ${back.top}..${back.bottom} terms ${terms.left}..${terms.right}",
        )

        // Equal, to the dp, in both dimensions. This is the whole requirement: no card
        // may be shorter because its description is shorter, and none may be wider.
        val widths = cards.map { it.second.width }
        val heights = cards.map { it.second.height }
        assertTrue(
            "${frame.width}: the cards are not equal in width — $widths",
            abs((widths.max() - widths.min()).value) <= 1f,
        )
        assertTrue(
            "${frame.width}: the cards are not equal in height — $heights",
            abs((heights.max() - heights.min()).value) <= 1f,
        )

        val (xtream, m3u, local, users) = cards.map { it.second }

        // Two rows of two: each pair shares a band, and the rows do not.
        assertTrue("${frame.width}: row one is not one row", overlapVertically(xtream, m3u))
        assertTrue("${frame.width}: row two is not one row", overlapVertically(local, users))
        assertTrue(
            "${frame.width}: the rows overlap — row one ends ${xtream.bottom}, " +
                "row two starts ${local.top}",
            local.top >= xtream.bottom,
        )

        // Two columns: within a row the pair does not overlap, and the columns line up
        // between the rows. A grid whose lower row is offset is not a grid.
        assertTrue("${frame.width}: row one overlaps horizontally", disjoint(xtream, m3u))
        assertTrue("${frame.width}: row two overlaps horizontally", disjoint(local, users))
        assertTrue(
            "${frame.width}: the columns do not line up — ${xtream.left} vs ${local.left}",
            abs((xtream.left - local.left).value) <= 1f,
        )

        // Heading above the grid, footer below it. The sequence the scroll destroyed.
        assertTrue("${frame.width}: the heading is not above the grid", heading.bottom <= xtream.top)
        assertTrue("${frame.width}: Back is not below the grid", back.top >= local.bottom)
        assertTrue("${frame.width}: Terms is not below the grid", terms.top >= local.bottom)

        // Inside the width, every element. Widths Robolectric measures honestly.
        for ((what, box) in named(heading, cards.map { it.second }, back, terms)) {
            assertTrue(
                "${frame.width}: $what runs past a side — ${box.left}..${box.right} " +
                    "of ${frame.width}",
                box.left >= 0.dp && box.right <= frame.width,
            )
        }

        onAllNodes(hasScrollAction()).assertCountEquals(0)
    }

    /** And inside the height, on the frames with room for the harness's fake text. */
    private fun ComposeContentTestRule.assertVerticallyInside(frame: Frame) {
        val cards = CARD_TAGS.map { bounds(it) }
        for ((what, box) in named(
            bounds(ActivationTags.SOURCE_HEADING),
            cards,
            bounds(ActivationTags.SOURCE_BACK),
            bounds(ActivationTags.SOURCE_TERMS),
        )) {
            assertTrue("${frame.height}: $what starts above the frame, at ${box.top}", box.top >= 0.dp)
            assertTrue(
                "${frame.height}: $what runs past the bottom — ${box.bottom} of ${frame.height}",
                box.bottom <= frame.height,
            )
        }
    }

    private fun named(
        heading: DpRect,
        cards: List<DpRect>,
        back: DpRect,
        terms: DpRect,
    ): List<Pair<String, DpRect>> =
        listOf("the heading" to heading) +
            CARD_TAGS.map { it.substringAfterLast('.') }.zip(cards) +
            listOf("Back" to back, "Terms" to terms)

    private fun overlapVertically(a: DpRect, b: DpRect) = a.top < b.bottom && b.top < a.bottom

    private fun disjoint(a: DpRect, b: DpRect) = a.right <= b.left || b.right <= a.left

    private data class Frame(val width: Dp, val height: Dp)

    /**
     * The reporter's device, as the layout receives it.
     *
     * 827 rather than the window's 873: `ActivationSurface` applies
     * `windowInsetsPadding(safeDrawing)` before this screen sees anything, and on that
     * handset the display cutout takes 41dp of the leading edge in landscape. Testing
     * the window width would be testing a frame the screen is never given.
     */
    private val HANDSET = Frame(827.dp, 393.dp)
    private val TELEVISION = Frame(960.dp, 540.dp)

    /** The shortest height the project ships to, at a width nothing real reaches. */
    private val SHORT = Frame(568.dp, 360.dp)

    /** Neither landscape nor portrait, to pin that no ratio is consulted. */
    private val SQUARE = Frame(393.dp, 393.dp)

    /**
     * The screen, at a stated frame and a stated device class.
     *
     * Provided *inside* [CastivioTheme], which resolves its own from the
     * configuration; the inner provider wins. That is what lets one Robolectric
     * qualifier serve every frame here, instead of five `@Config`s each hoping to map
     * to the class it means — which would be a test of `rememberDeviceClass` rather
     * than of this screen.
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
                    SourceChoiceScreen(
                        onXtream = {},
                        onPlaylist = {},
                        onLocalVideo = {},
                        onSavedSources = {},
                        onTerms = {},
                        onBack = {},
                    )
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

    private companion object {
        /** Grid order, which is also reading order and focus order. */
        val CARD_TAGS = listOf(
            ActivationTags.SOURCE_XTREAM,
            ActivationTags.SOURCE_M3U,
            ActivationTags.SOURCE_LOCAL,
            ActivationTags.SOURCE_USERS,
        )
    }
}
