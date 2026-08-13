package com.castivio.feature.activation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
 * The four screens the media source opens, and the one property they exist to hold.
 *
 * ## What is different about these four
 *
 * Every screen before them fits by construction: four cards divide a known box, and
 * `SourceChoiceBudgetTest` computes whether the box is big enough. These do not fit by
 * construction — a library is however many files the device holds, and `CLAUDE.md`
 * sizes this product for 400,000 of them. So the claim is not "it fits". The claim is:
 *
 * > the frame never scrolls; only the band between the header and Back moves.
 *
 * That is what stops a long list doing to Back what the source choice's overflow did
 * to it twice — pushing it off the bottom of a television, with every gate green. It
 * is asserted here directly, with a list long enough to overflow every frame in the
 * file: exactly one scrollable node, it is the list, and Back is inside the container
 * regardless.
 *
 * ## Why the fixture is used rather than a list written here
 *
 * `DebugFixtures` is what the debug build actually draws, so a test against it is a
 * test of the composition on the reviewer's device rather than of a convenient
 * neighbour of it. It is also chosen to be awkward — a name longer than any row is
 * wide, mixed scripts, durations from seconds to hours — and a test that quietly
 * substituted three short names would stop exercising any of that.
 *
 * ## What Robolectric may and may not be asked
 *
 * Text does not lay out here: every `Text` measures 35dp whatever its style, as
 * `ActivationBudgetTest` documents. So nothing below asserts a height that a piece of
 * type contributes to, and nothing asserts that a screen fits. Everything below is
 * either a containment claim, a relation between two boxes, or a size a modifier
 * imposes outright — `aspectRatio` and `defaultMinSize` are honest in this harness
 * because neither consults a font.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-land")
class MediaBrowseLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    /* --------------------------------------------------- the frame does not scroll */

    /**
     * The video wall, on three frames, with more videos than any of them holds.
     *
     * One test per frame rather than one loop, so a failure names the frame it failed
     * on instead of the first one it reached.
     */
    @Test
    fun `the video library scrolls its grid and not its frame`() {
        compose.showVideos(HANDSET, DeviceClass.Expanded)
        compose.assertShell(HANDSET, ActivationTags.LIBRARY_HEADING, ActivationTags.LIBRARY_CONTAINER, ActivationTags.LIBRARY_BACK)
        compose.assertOnlyTheContentScrolls(ActivationTags.LIBRARY_GRID)
    }

    @Test
    fun `the video library holds its shape on a television`() {
        compose.showVideos(TELEVISION, DeviceClass.Television)
        compose.assertShell(TELEVISION, ActivationTags.LIBRARY_HEADING, ActivationTags.LIBRARY_CONTAINER, ActivationTags.LIBRARY_BACK)
        compose.assertOnlyTheContentScrolls(ActivationTags.LIBRARY_GRID)
    }

    @Test
    fun `the video library holds its shape on the shortest frame`() {
        compose.showVideos(SHORT, DeviceClass.Compact)
        compose.assertShell(SHORT, ActivationTags.LIBRARY_HEADING, ActivationTags.LIBRARY_CONTAINER, ActivationTags.LIBRARY_BACK)
        compose.assertOnlyTheContentScrolls(ActivationTags.LIBRARY_GRID)
    }

    @Test
    fun `the audio library scrolls its list and not its frame`() {
        compose.showTracks(HANDSET, DeviceClass.Expanded)
        compose.assertShell(HANDSET, ActivationTags.LIBRARY_HEADING, ActivationTags.LIBRARY_CONTAINER, ActivationTags.LIBRARY_BACK)
        compose.assertOnlyTheContentScrolls(ActivationTags.LIBRARY_LIST)
    }

    @Test
    fun `the audio library holds its shape on a television`() {
        compose.showTracks(TELEVISION, DeviceClass.Television)
        compose.assertShell(TELEVISION, ActivationTags.LIBRARY_HEADING, ActivationTags.LIBRARY_CONTAINER, ActivationTags.LIBRARY_BACK)
        compose.assertOnlyTheContentScrolls(ActivationTags.LIBRARY_LIST)
    }

    @Test
    fun `the video picker scrolls its list and not its frame`() {
        compose.showPicker(HANDSET, DeviceClass.Expanded, PickerKind.Video)
        compose.assertShell(HANDSET, ActivationTags.PICKER_HEADING, ActivationTags.PICKER_CONTAINER, ActivationTags.PICKER_BACK)
        compose.assertOnlyTheContentScrolls(ActivationTags.PICKER_LIST)
    }

    @Test
    fun `the audio picker scrolls its list and not its frame`() {
        compose.showPicker(HANDSET, DeviceClass.Expanded, PickerKind.Audio)
        compose.assertShell(HANDSET, ActivationTags.PICKER_HEADING, ActivationTags.PICKER_CONTAINER, ActivationTags.PICKER_BACK)
        compose.assertOnlyTheContentScrolls(ActivationTags.PICKER_LIST)
    }

    @Test
    fun `the audio picker holds its shape on a television`() {
        compose.showPicker(TELEVISION, DeviceClass.Television, PickerKind.Audio)
        compose.assertShell(TELEVISION, ActivationTags.PICKER_HEADING, ActivationTags.PICKER_CONTAINER, ActivationTags.PICKER_BACK)
        compose.assertOnlyTheContentScrolls(ActivationTags.PICKER_LIST)
    }

    /**
     * Where the path band sits: inside the container, above the list.
     *
     * The picker's one structural difference from a library, and the one place its
     * content can be pushed out from — a band added above a `weight(1f)` child takes
     * its space from that child, which is correct, and takes it from Back the moment
     * the child stops being the flexible one.
     */
    @Test
    fun `the picker puts its path inside the container and above the list`() {
        compose.showPicker(HANDSET, DeviceClass.Expanded, PickerKind.Video)

        val panel = compose.bounds(ActivationTags.PICKER_CONTAINER)
        val path = compose.bounds(ActivationTags.PICKER_PATH)
        val list = compose.bounds(ActivationTags.PICKER_LIST)

        assertTrue(
            "the path ${path.top}..${path.bottom} is not inside the container " +
                "${panel.top}..${panel.bottom}",
            path.top >= panel.top && path.bottom <= panel.bottom,
        )
        assertTrue(
            "the path ${path.bottom} is not above the list ${list.top}",
            path.bottom <= list.top,
        )
    }

    /* --------------------------------------------------------------- the empty case */

    /**
     * Nothing to show, and the shell is unchanged.
     *
     * A release build reaches this on every one of the four, because `MediaStore` is
     * the slice after this one. So it is not an edge case in this build — it is what
     * the product does — and the property that matters is that the container keeps its
     * shape rather than collapsing onto Back.
     */
    @Test
    fun `an empty video library keeps its container and its Back`() {
        compose.show(HANDSET, DeviceClass.Expanded) {
            VideoLibraryScreen(videos = emptyList(), onPlay = {}, onBack = {})
        }
        compose.assertShell(HANDSET, ActivationTags.LIBRARY_HEADING, ActivationTags.LIBRARY_CONTAINER, ActivationTags.LIBRARY_BACK)
        compose.onAllNodes(hasScrollAction()).assertCountEquals(0)
    }

    @Test
    fun `an empty audio library keeps its container and its Back`() {
        compose.show(HANDSET, DeviceClass.Expanded) {
            AudioLibraryScreen(tracks = emptyList(), onPlay = {}, onBack = {})
        }
        compose.assertShell(HANDSET, ActivationTags.LIBRARY_HEADING, ActivationTags.LIBRARY_CONTAINER, ActivationTags.LIBRARY_BACK)
        compose.onAllNodes(hasScrollAction()).assertCountEquals(0)
    }

    @Test
    fun `an empty folder keeps its container and its Back`() {
        compose.show(HANDSET, DeviceClass.Expanded) {
            FilePickerScreen(
                kind = PickerKind.Video,
                path = PATH,
                entries = emptyList(),
                onOpen = {},
                onBack = {},
            )
        }
        compose.assertShell(HANDSET, ActivationTags.PICKER_HEADING, ActivationTags.PICKER_CONTAINER, ActivationTags.PICKER_BACK)
        compose.onAllNodes(hasScrollAction()).assertCountEquals(0)
    }

    /* ------------------------------------------------------------------- the items */

    /**
     * Every tile is the shape a video is, and every tile in a band is one width.
     *
     * `GridCells.Adaptive` is given a minimum and chooses the count, which is the whole
     * reason one figure serves a handset and a television. What it must not do is
     * produce a ragged wall — and a ratio and a width are two numbers Robolectric
     * measures honestly, because `aspectRatio` never asks a font anything.
     */
    @Test
    fun `every video tile is sixteen by nine and every column is one width`() {
        compose.showVideos(HANDSET, DeviceClass.Expanded)

        val tiles = compose.onAllNodesWithTag(ActivationTags.BROWSE_TILE).boundsList()
        assertTrue("no tiles were composed at all", tiles.isNotEmpty())

        val widths = tiles.map { it.width }
        assertTrue(
            "the tiles are not one width — $widths",
            abs((widths.max() - widths.min()).value) <= 1f,
        )
        tiles.forEach { tile ->
            val ratio = tile.width.value / tile.height.value
            assertTrue(
                "a tile is ${tile.width}x${tile.height}, a ratio of $ratio rather than 16:9",
                abs(ratio - 16f / 9f) <= 0.02f,
            )
        }
    }

    /**
     * More than one column on a frame wide enough for more than one.
     *
     * The failure this guards is a grid that silently becomes a list: `Adaptive` falls
     * back to a single column when the minimum does not fit, and 827dp against a 150dp
     * minimum fits five. One column here would mean the minimum, the padding or the
     * container's width had drifted, and a wall of one tile per row is a different
     * screen from the one that was approved.
     */
    @Test
    fun `the grid puts more than one tile in a row on a handset`() {
        compose.showVideos(HANDSET, DeviceClass.Expanded)

        val tiles = compose.onAllNodesWithTag(ActivationTags.BROWSE_TILE).boundsList()
        val topBand = tiles.minOf { it.top }
        val firstRow = tiles.count { abs((it.top - topBand).value) <= 1f }

        assertTrue("the grid composed $firstRow tile(s) in its first row", firstRow >= 2)
    }

    /**
     * Every row is a target before it is a line.
     *
     * `defaultMinSize` imposes the floor outright, so this is honest here even though
     * the text inside the row is not: a row that lost the modifier would collapse to
     * the harness's 35dp and fail, and on a device it would collapse to the height of
     * one line of type, which is the same defect.
     */
    @Test
    fun `every list row is at least a touch target tall`() {
        compose.showTracks(HANDSET, DeviceClass.Expanded)

        val rows = compose.onAllNodesWithTag(ActivationTags.BROWSE_ROW).boundsList()
        assertTrue("no rows were composed at all", rows.isNotEmpty())
        rows.forEach {
            assertTrue("a row is ${it.height}, under the ${TOUCH_FLOOR} floor", it.height >= TOUCH_FLOOR)
        }
    }

    /** And on a television, where the floor is higher and the rows are the same code. */
    @Test
    fun `every list row is at least a remote target tall on a television`() {
        compose.showTracks(TELEVISION, DeviceClass.Television)

        val rows = compose.onAllNodesWithTag(ActivationTags.BROWSE_ROW).boundsList()
        assertTrue("no rows were composed at all", rows.isNotEmpty())
        rows.forEach {
            assertTrue("a row is ${it.height}, under the ${REMOTE_FLOOR} floor", it.height >= REMOTE_FLOOR)
        }
    }

    /* ---------------------------------------------------------------- the direction */

    /**
     * The mark is opposite the title, and it mirrors.
     *
     * This is the assertion that exists because the placement was got wrong: the mark
     * was pinned to one physical corner in both languages, which reads as correct in
     * whichever language it is checked in. "Opposite the title" is a comparison, so it
     * is asserted as one, in both directions, on the same composition.
     */
    @Test
    fun `right to left puts the mark left of the title`() {
        compose.showVideos(HANDSET, DeviceClass.Expanded, LayoutDirection.Rtl)

        val heading = compose.bounds(ActivationTags.LIBRARY_HEADING)
        val mark = compose.bounds(ActivationTags.BROWSE_MARK)

        // The comparison is between the two leading edges rather than between the mark's
        // trailing edge and the title's leading one. Robolectric's idea of how wide a
        // string is has nothing to do with a device's, so a claim that leans on either
        // box's *width* is a claim about the harness. Which box starts further along the
        // row is a claim about the layout, and it is the one being made.
        assertTrue(
            "RTL: the mark at ${mark.left} is not left of the title at ${heading.left}",
            mark.left < heading.left,
        )
        compose.assertShell(HANDSET, ActivationTags.LIBRARY_HEADING, ActivationTags.LIBRARY_CONTAINER, ActivationTags.LIBRARY_BACK)
    }

    @Test
    fun `left to right puts the mark right of the title`() {
        compose.showVideos(HANDSET, DeviceClass.Expanded, LayoutDirection.Ltr)

        val heading = compose.bounds(ActivationTags.LIBRARY_HEADING)
        val mark = compose.bounds(ActivationTags.BROWSE_MARK)

        assertTrue(
            "LTR: the mark at ${mark.left} is not right of the title at ${heading.left}",
            mark.left > heading.left,
        )
        compose.assertShell(HANDSET, ActivationTags.LIBRARY_HEADING, ActivationTags.LIBRARY_CONTAINER, ActivationTags.LIBRARY_BACK)
    }

    /**
     * Back is centred on the container, in both directions.
     *
     * The property the shared footer is built to have: two rules of `weight(1f)` put
     * Back on the container's true centre rather than on the centre of what is left
     * over, and those are the same point only when the two sides are equal. Asserted on
     * a browse screen as well as on the chooser, because the footer is one composable
     * and a change to it has to be caught wherever it is used.
     */
    @Test
    fun `Back is centred on the container in Arabic`() {
        compose.showTracks(HANDSET, DeviceClass.Expanded, LayoutDirection.Rtl)
        compose.assertBackIsCentred("RTL")
    }

    /**
     * The mirror of it — a separate test rather than a second pass of a loop, because
     * `setContent` may be called once per rule and a loop over both directions throws
     * on its second turn rather than asserting anything.
     */
    @Test
    fun `Back is centred on the container in English`() {
        compose.showTracks(HANDSET, DeviceClass.Expanded, LayoutDirection.Ltr)
        compose.assertBackIsCentred("LTR")
    }

    private fun ComposeContentTestRule.assertBackIsCentred(direction: String) {
        val panel = bounds(ActivationTags.LIBRARY_CONTAINER)
        val back = bounds(ActivationTags.LIBRARY_BACK)
        val leading = back.left - panel.left
        val trailing = panel.right - back.right

        assertTrue(
            "$direction: Back is not centred — $leading leading, $trailing trailing",
            abs((leading - trailing).value) <= 1f,
        )
    }

    /* ------------------------------------------------------------------- behaviour */

    /** Back leaves, and the item plays. Two lambdas, and neither may be the other. */
    @Test
    fun `each control calls what it names`() {
        val pressed = mutableListOf<String>()
        compose.show(HANDSET, DeviceClass.Expanded) {
            AudioLibraryScreen(
                tracks = DebugFixtures.tracks(),
                onPlay = { pressed += "play $it" },
                onBack = { pressed += "back" },
            )
        }

        compose.onAllNodesWithTag(ActivationTags.BROWSE_ROW)[0].performClick()
        assertEquals("the first row did not play the first track", listOf("play 0"), pressed)

        pressed.clear()
        compose.onNodeWithTag(ActivationTags.LIBRARY_BACK).performClick()
        assertEquals("Back did not leave", listOf("back"), pressed)
    }

    /**
     * A picker's first row walks up, and it is not a file.
     *
     * The row order is the design: the way out of a folder is the first thing a remote's
     * focus reaches, rather than a control in a corner it has to be steered to.
     */
    @Test
    fun `the picker's first row is the way out of the folder`() {
        val opened = mutableListOf<Int>()
        compose.show(HANDSET, DeviceClass.Expanded) {
            FilePickerScreen(
                kind = PickerKind.Video,
                path = PATH,
                entries = DebugFixtures.folder(PickerKind.Video, PARENT),
                onOpen = { opened += it },
                onBack = {},
            )
        }

        compose.onAllNodesWithTag(ActivationTags.BROWSE_ROW)[0].performClick()
        assertEquals("the first row is not index 0", listOf(0), opened)

        val entries = DebugFixtures.folder(PickerKind.Video, PARENT)
        assertEquals(
            "the first entry is not the parent",
            PickerEntry.EntryKind.Parent,
            entries.first().kind,
        )
    }

    /* -------------------------------------------------------------------------- */

    /**
     * The shell: heading above the container, container inside the frame, Back inside
     * the container. Asserted on every screen and every frame in this file.
     *
     * Back's containment is the whole point. It is the element a long list pushes out,
     * it is the only way off these screens, and it is the failure that shipped twice on
     * a neighbouring screen without a single gate noticing.
     */
    private fun ComposeContentTestRule.assertShell(
        frame: Frame,
        headingTag: String,
        containerTag: String,
        backTag: String,
    ) {
        val heading = bounds(headingTag)
        val panel = bounds(containerTag)
        val back = bounds(backTag)

        println(
            "browse ${frame.width}x${frame.height} — heading ${heading.top}..${heading.bottom} " +
                "| container ${panel.left}..${panel.right} x ${panel.top}..${panel.bottom} " +
                "| back ${back.left}..${back.right} x ${back.top}..${back.bottom}",
        )

        assertTrue(
            "the heading ${heading.bottom} is not above the container ${panel.top}",
            heading.bottom <= panel.top,
        )
        assertTrue(
            "Back ${back.left}..${back.right} is not inside the container " +
                "${panel.left}..${panel.right}",
            back.left >= panel.left && back.right <= panel.right,
        )
        assertTrue(
            "Back ${back.top}..${back.bottom} is not inside the container " +
                "${panel.top}..${panel.bottom}",
            back.top >= panel.top && back.bottom <= panel.bottom,
        )

        // Inside the frame, every band of it. Widths and horizontal placement are
        // measured honestly here whatever the harness does to type.
        for ((what, box) in listOf("the heading" to heading, "the container" to panel, "Back" to back)) {
            assertTrue(
                "$what runs past a side — ${box.left}..${box.right} of ${frame.width}",
                box.left >= 0.dp && box.right <= frame.width,
            )
        }
    }

    /**
     * Exactly one scrollable node, and it is the content.
     *
     * The count is the assertion. Zero would mean the list is not scrollable and the
     * items past the fold are unreachable; two would mean the page has grown a scroll
     * of its own, which is the failure this whole shell is shaped to prevent — and the
     * one that takes Back off the bottom of a television.
     */
    private fun ComposeContentTestRule.assertOnlyTheContentScrolls(contentTag: String) {
        onAllNodes(hasScrollAction()).assertCountEquals(1)
        onNodeWithTag(contentTag).assertExists()
    }

    private data class Frame(val width: Dp, val height: Dp)

    /** The same three frames the source choice is measured on, for the same reasons. */
    private val HANDSET = Frame(827.dp, 393.dp)
    private val TELEVISION = Frame(960.dp, 540.dp)
    private val SHORT = Frame(568.dp, 360.dp)

    /** `Sizing.minTarget`, written out so a change to it fails here rather than hides. */
    private val TOUCH_FLOOR = 48.dp
    private val REMOTE_FLOOR = 56.dp

    /**
     * Resource strings, resolved here rather than in the composition under test.
     *
     * They are the two the picker is handed by the route. Their exact wording is not
     * what this file is about, and a test that read them from resources would be
     * asserting the translation rather than the layout.
     */
    private val PATH = "Internal storage"
    private val PARENT = "Parent folder"

    private fun ComposeContentTestRule.showVideos(
        frame: Frame,
        device: DeviceClass,
        direction: LayoutDirection = LayoutDirection.Rtl,
    ) = show(frame, device, direction) {
        VideoLibraryScreen(videos = DebugFixtures.videos(), onPlay = {}, onBack = {})
    }

    private fun ComposeContentTestRule.showTracks(
        frame: Frame,
        device: DeviceClass,
        direction: LayoutDirection = LayoutDirection.Rtl,
    ) = show(frame, device, direction) {
        AudioLibraryScreen(tracks = DebugFixtures.tracks(), onPlay = {}, onBack = {})
    }

    private fun ComposeContentTestRule.showPicker(
        frame: Frame,
        device: DeviceClass,
        kind: PickerKind,
        direction: LayoutDirection = LayoutDirection.Rtl,
    ) = show(frame, device, direction) {
        FilePickerScreen(
            kind = kind,
            path = PATH,
            entries = DebugFixtures.folder(kind, PARENT),
            onOpen = {},
            onBack = {},
        )
    }

    /**
     * A screen at a stated frame and a stated device class.
     *
     * The device class is provided *inside* [CastivioTheme], which resolves its own
     * from the configuration; the inner provider wins. That is what lets one Robolectric
     * qualifier serve every frame in the file rather than one `@Config` per frame each
     * hoping to map to the class it means.
     */
    private fun ComposeContentTestRule.show(
        frame: Frame,
        device: DeviceClass,
        direction: LayoutDirection = LayoutDirection.Rtl,
        screen: @Composable () -> Unit,
    ) = setContent {
        CastivioTheme {
            CompositionLocalProvider(
                LocalDeviceClass provides device,
                LocalLayoutDirection provides direction,
            ) {
                Box(Modifier.requiredSize(frame.width, frame.height)) { screen() }
            }
        }
    }

    private fun ComposeContentTestRule.bounds(tag: String): DpRect =
        onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun SemanticsNodeInteractionCollection.boundsList(): List<DpRect> =
        fetchSemanticsNodes().indices.map { this[it].getUnclippedBoundsInRoot() }
}
