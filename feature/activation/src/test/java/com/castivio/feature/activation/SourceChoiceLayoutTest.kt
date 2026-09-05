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
 * `ActivationBudgetTest` documents at length. Nine text nodes on this screen makes it
 * about 80dp taller in the harness than on a device, which is more than either target
 * frame has — so **vertical containment is not assertable here at all**, on any frame,
 * and no assertion pretends otherwise. Fit is computed from real type metrics in
 * `SourceChoiceBudgetTest`, on the JVM, where the numbers are the device's.
 *
 * Everything the harness measures truthfully is asserted on every frame: equal widths,
 * equal heights, the two-by-two arrangement, the container actually containing them,
 * the sentence being outside it, reading order, direction, symmetry across the width,
 * and the absence of anything scrollable. Those are all *relative* claims, and a
 * harness with the wrong text metrics still gets relative claims right.
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
    }

    /** The television, in its own class, which is where the overscan inset applies. */
    @Test
    fun `a television gets the grid, whole and unscrollable`() {
        compose.show(TELEVISION, DeviceClass.Television)
        compose.assertGrid(TELEVISION)
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

        compose.assertFooterSurvivesMirroring(HANDSET, "RTL")
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

        compose.assertFooterSurvivesMirroring(HANDSET, "LTR")
    }

    /**
     * Back reads the same in both directions, because the header does not mirror.
     *
     * This has now outlived three designs and the claim has survived all of them. It
     * once put Back at one end of a row and the terms sentence at the other and
     * asserted the two swapped with the direction; the sentence left that row, then
     * left the screen, and Back moved up into the header.
     *
     * What is being tested is unchanged: the control is placed by rule rather than by
     * side, so mirroring the screen must not move it. The header is pinned left to
     * right — the lockup is a signature and does not change edges per locale — so Back
     * holds the stage's trailing edge in Arabic and in English alike, and its distance
     * from that edge is the same number in both. A row that mirrored would fail this
     * in whichever direction it was not written for, which is exactly the mistake the
     * direction tests exist to catch.
     */
    private fun ComposeContentTestRule.assertFooterSurvivesMirroring(frame: Frame, direction: String) {
        val stage = bounds(ActivationTags.SOURCE_CONTAINER)
        val back = bounds(ActivationTags.SOURCE_BACK)
        val xtream = bounds(ActivationTags.SOURCE_XTREAM)

        // Inside the stage, in both axes and both directions.
        assertTrue(
            "$direction: Back ${back.left}..${back.right} is not inside the stage " +
                "${stage.left}..${stage.right}",
            back.left >= stage.left && back.right <= stage.right,
        )
        // Vertically the claim is against the display rather than the stage. The tagged
        // node is Back's *interaction* box, which is the frame's `touchTarget` and so
        // taller than the `chip` pill drawn inside it; centred on a shorter header row
        // it overhangs 1 to 6dp into the stage's own empty top margin. Asserting it
        // against the stage would assert that the target had been clamped to the row --
        // the defect this arrangement exists to fix.
        assertTrue(
            "$direction: Back ${back.top}..${back.bottom} leaves the ${frame.height} display",
            back.top >= 0.dp && back.bottom <= frame.height,
        )

        // On the stage's trailing edge, whichever direction the screen is read in.
        // This is the half of the statement that mirroring would break.
        assertTrue(
            "$direction: Back is not on the stage's trailing edge — ${back.right} " +
                "against ${stage.right}",
            abs((back.right - stage.right).value) <= 1f,
        )

        // And above the grid, because it is in the header now rather than under it.
        assertTrue(
            "$direction: Back ${back.bottom} is not above the first row ${xtream.top}",
            back.bottom <= xtream.top,
        )
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

        println(
            "source choice ${frame.width}x${frame.height} — " +
                cards.joinToString(" | ") { (tag, b) ->
                    "${tag.substringAfterLast('.')} ${b.width}x${b.height} @${b.left},${b.top}"
                } + " || heading ${heading.top}..${heading.bottom} " +
                "back ${back.top}..${back.bottom}",
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

        // Heading above the grid, grid above the sentence — the sequence the scroll
        // destroyed. It used to be stated against a glass container between the two;
        // there is no such surface now, the cards sit on the ground, so the sequence
        // is asserted against the grid itself. That is the claim that always
        // mattered: this screen reads top to bottom and does not scroll.
        //
        // Back has moved to the top of that sequence rather than the bottom of it,
        // which is what was asked for, so the assertion is inverted rather than
        // dropped. Putting it back under the cards fails here.
        val panel = bounds(ActivationTags.SOURCE_CONTAINER)
        assertTrue("${frame.width}: the heading is not above the grid", heading.bottom <= xtream.top)
        assertTrue("${frame.width}: Back is not above the grid", back.bottom <= xtream.top)

        // The stage really contains them: all four cards and Back inside its bounds,
        // none of them touching an edge. "Everything is on the screen" is otherwise
        // a claim only a screenshot can settle.
        for ((what, box) in CARD_TAGS.map { it.substringAfterLast('.') }.zip(cards.map { it.second }) +
            listOf("Back" to back)) {
            assertTrue(
                "${frame.width}: $what is not inside the container — " +
                    "${box.left}..${box.right} of ${panel.left}..${panel.right}",
                box.left >= panel.left && box.right <= panel.right,
            )
            // Back is measured against the display and the cards against the stage.
            // Back's tagged node is its interaction box, which is `touchTarget` tall
            // against a `chip`-tall pill, so it overhangs the header row by 1 to 6dp
            // into the stage's empty top margin on purpose. Every other element here
            // is content and belongs inside the stage.
            val top = if (what == "Back") 0.dp else panel.top
            val bottom = if (what == "Back") frame.height else panel.bottom
            assertTrue(
                "${frame.width}: $what is not inside the container vertically — " +
                    "${box.top}..${box.bottom} of $top..$bottom",
                box.top >= top && box.bottom <= bottom,
            )
            // Nothing collides with the screen's own edge. This used to be stated
            // against a glass container's inset; the cards are the outermost content
            // now and fill the stage's measure exactly, so the inset that matters is
            // the stage's own and the edge that matters is the frame's. Remove the
            // screen padding tomorrow and this fails, which is the point of it.
            assertTrue(
                "${frame.width}: $what touches the frame edge — " +
                    "${box.left}..${box.right} of ${frame.width}",
                box.left > 0.dp && box.right < frame.width,
            )
        }

        // The grid is symmetric on the frame: the margin outside the first column
        // equals the margin outside the last. It used to be the terms sentence that
        // carried this claim -- it filled the measure and was centred in it -- and the
        // grid is what fills the measure now.
        val leadingGap = minOf(xtream.left.value, m3u.left.value)
        val trailingGap = frame.width.value - maxOf(xtream.right.value, m3u.right.value)
        assertTrue(
            "${frame.width}: the grid is not centred — $leadingGap leading, " +
                "$trailingGap trailing",
            abs(leadingGap - trailingGap) <= 1f,
        )

        // Inside the width, every element. Widths Robolectric measures honestly.
        for ((what, box) in named(heading, cards.map { it.second }, back)) {
            assertTrue(
                "${frame.width}: $what runs past a side — ${box.left}..${box.right} " +
                    "of ${frame.width}",
                box.left >= 0.dp && box.right <= frame.width,
            )
        }

        onAllNodes(hasScrollAction()).assertCountEquals(0)
    }

    private fun named(
        heading: DpRect,
        cards: List<DpRect>,
        back: DpRect,
    ): List<Pair<String, DpRect>> =
        listOf("the heading" to heading) +
            CARD_TAGS.map { it.substringAfterLast('.') }.zip(cards) +
            listOf("Back" to back)

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
