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
 * ## Why the sample is written here
 *
 * It used to come from `DebugFixtures`, which the production build no longer has: the
 * four screens read the device now, and a fixture behind them would be a mock in the
 * product. So the gate states its own content, which is the right place for it — a
 * layout test should say what it is measuring.
 *
 * It is chosen to be awkward rather than flattering: a name longer than any row is wide,
 * Arabic among Latin and Latin among Arabic, durations from seconds to hours, and more of
 * each than the tallest frame here can show. A sample that fits proves nothing.
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
     * The mark holds the same physical edge in both directions.
     *
     * This assertion exists because the placement was got wrong, and it has now been
     * got wrong in both directions: the mark was first pinned to one corner while the
     * design called for it to mirror, and it was then still mirroring after the header
     * became `CastivioHeader`, which pins the whole row and mirrors only what each
     * element *says*. A signature that reassembles itself per locale is two signatures.
     *
     * So the claim is invariance rather than a comparison: the lockup starts at the
     * stage's leading edge whatever the language. Stated per direction, because
     * `setContent` may be called once per rule.
     */
    @Test
    fun `right to left leaves the mark on the stage's own edge`() {
        compose.showVideos(HANDSET, DeviceClass.Expanded, LayoutDirection.Rtl)
        compose.assertMarkIsPinned("RTL")
        compose.assertShell(HANDSET, ActivationTags.LIBRARY_HEADING, ActivationTags.LIBRARY_CONTAINER, ActivationTags.LIBRARY_BACK)
    }

    @Test
    fun `left to right leaves the mark on the stage's own edge`() {
        compose.showVideos(HANDSET, DeviceClass.Expanded, LayoutDirection.Ltr)
        compose.assertMarkIsPinned("LTR")
        compose.assertShell(HANDSET, ActivationTags.LIBRARY_HEADING, ActivationTags.LIBRARY_CONTAINER, ActivationTags.LIBRARY_BACK)
    }

    /**
     * The mark's own edge and the stage's are the same edge — the *physical* left, in
     * both languages, because `CastivioHeader` places the row left to right and lets
     * the words inside each slot run in their own direction.
     */
    private fun ComposeContentTestRule.assertMarkIsPinned(direction: String) {
        val stage = bounds(ActivationTags.LIBRARY_CONTAINER)
        val mark = bounds(ActivationTags.HEADER_MARK)

        assertTrue(
            "$direction: the mark starts at ${mark.left}, the stage at ${stage.left}",
            abs((mark.left - stage.left).value) <= 1f,
        )
    }

    /**
     * Back holds the row's outer end, in both directions.
     *
     * It used to be centred on the container, and that was a real property of a real
     * footer: two rules of `weight(1f)` put it on the container's true centre rather
     * than on the centre of what was left over. The footer is gone -- it was a band
     * that existed to hold one control -- and Back is now the header's one chip, at
     * the end of a row that does not mirror.
     *
     * So the property asserted is the one the new header is built to have, and it is
     * the stronger of the two: the chip's far edge is the stage's far edge, in Arabic
     * and in English alike. A control that drifts inward, or that swaps ends with the
     * mark, fails here.
     */
    @Test
    fun `Back holds the outer end in Arabic`() {
        compose.showTracks(HANDSET, DeviceClass.Expanded, LayoutDirection.Rtl)
        compose.assertBackIsPinned("RTL")
    }

    /**
     * The mirror of it — a separate test rather than a second pass of a loop, because
     * `setContent` may be called once per rule and a loop over both directions throws
     * on its second turn rather than asserting anything.
     */
    @Test
    fun `Back holds the outer end in English`() {
        compose.showTracks(HANDSET, DeviceClass.Expanded, LayoutDirection.Ltr)
        compose.assertBackIsPinned("LTR")
    }

    private fun ComposeContentTestRule.assertBackIsPinned(direction: String) {
        val stage = bounds(ActivationTags.LIBRARY_CONTAINER)
        val back = bounds(ActivationTags.LIBRARY_BACK)
        val mark = bounds(ActivationTags.HEADER_MARK)

        assertTrue(
            "$direction: Back ends at ${back.right}, the stage at ${stage.right}",
            abs((back.right - stage.right).value) <= 1f,
        )
        assertTrue(
            "$direction: Back at ${back.left} is not past the mark at ${mark.right}",
            back.left >= mark.right,
        )
    }

    /* ------------------------------------------------------------------- behaviour */

    /** Back leaves, and the item plays. Two lambdas, and neither may be the other. */
    @Test
    fun `each control calls what it names`() {
        val pressed = mutableListOf<String>()
        compose.show(HANDSET, DeviceClass.Expanded) {
            AudioLibraryScreen(
                tracks = SampleMedia.tracks,
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
                entries = SampleMedia.folder(PARENT),
                onOpen = { opened += it },
                onBack = {},
            )
        }

        compose.onAllNodesWithTag(ActivationTags.BROWSE_ROW)[0].performClick()
        assertEquals("the first row is not index 0", listOf(0), opened)

        val entries = SampleMedia.folder(PARENT)
        assertEquals(
            "the first entry is not the parent",
            PickerEntry.EntryKind.Parent,
            entries.first().kind,
        )
    }

    /* -------------------------------------------------------------------------- */

    /**
     * The shell: the heading and Back share the header's row, both inside the stage,
     * and the stage inside the frame. Asserted on every screen and every frame here.
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

        // The heading is *inside* the stage rather than above a container: the glass
        // pane the two used to bracket is gone, and the tag now marks the stage itself.
        // Both of the old claims survive in the two that replace it -- the heading is
        // within the stage, and it shares its band with Back, which is what "one header
        // row" means and what a title pushed onto a second line would break.
        assertTrue(
            "the heading ${heading.top}..${heading.bottom} is not inside the stage " +
                "${panel.top}..${panel.bottom}",
            heading.top >= panel.top && heading.bottom <= panel.bottom,
        )
        assertTrue(
            "the heading ${heading.top}..${heading.bottom} does not share a row with " +
                "Back ${back.top}..${back.bottom}",
            heading.top < back.bottom && back.top < heading.bottom,
        )
        assertTrue(
            "Back ${back.left}..${back.right} is not inside the container " +
                "${panel.left}..${panel.right}",
            back.left >= panel.left && back.right <= panel.right,
        )
        // Back's box is measured against the **display**, not the stage, and that is a
        // decision rather than a loosening. What is tagged is the interaction box, and
        // the interaction box is deliberately taller than the pill inside it: the pill
        // is the frame's `chip`, 44dp on a television and 34 on the shortest phone, and
        // the box is the frame's `touchTarget`, 56 and 48. Centred on a header row
        // shorter than itself it overhangs by half the difference -- 1dp, 3dp, 6dp --
        // into the stage's own top margin, which is empty by construction.
        //
        // Asserting it against the stage would therefore be asserting that the touch
        // target had been clamped back to the row, which is the defect this arrangement
        // exists to fix. What must still hold is that it stays on the screen.
        assertTrue(
            "Back ${back.top}..${back.bottom} leaves the ${frame.height} display",
            back.top >= 0.dp && back.bottom <= frame.height,
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
        VideoLibraryScreen(videos = SampleMedia.videos, onPlay = {}, onBack = {})
    }

    private fun ComposeContentTestRule.showTracks(
        frame: Frame,
        device: DeviceClass,
        direction: LayoutDirection = LayoutDirection.Rtl,
    ) = show(frame, device, direction) {
        AudioLibraryScreen(tracks = SampleMedia.tracks, onPlay = {}, onBack = {})
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
            entries = SampleMedia.folder(PARENT),
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

    /**
     * The content these gates measure.
     *
     * Deliberately hostile to the layout: the longest name is longer than any row is wide,
     * the scripts are mixed in both directions, and there are more items than the tallest
     * frame in the file can show.
     */
    private object SampleMedia {

        val videos = listOf(
            "Sintel.2010.1080p.mkv" to "14:48",
            "رحلة إلى الصحراء.mp4" to "1:52:07",
            "Big_Buck_Bunny_60fps.mp4" to "10:34",
            "holiday-clip-0042.mp4" to "0:47",
            "Tears.of.Steel.2012.BluRay.x265.10bit.HDR.Atmos.mkv" to "12:14",
            "drone-over-the-harbour-take-3.mov" to "3:09",
            "Cosmos.S01E04.mkv" to "44:21",
            "مباراة الأمس كاملة.mkv" to "2:07:55",
            "screen-record-2026-05-11.mp4" to "0:22",
            "Elephants_Dream_1024.avi" to "10:53",
            "wedding-final-cut-v7.mp4" to "1:18:40",
            "timelapse_sunrise.webm" to "1:05",
        ).map { (name, duration) -> MediaTile(name = name, duration = duration) }

        val tracks = listOf(
            "01 - Nocturne in E flat major.mp3" to "4:31",
            "أم كلثوم - الأطلال.mp3" to "58:12",
            "podcast-ep-114-the-long-one-about-everything.mp3" to "1:47:03",
            "voice-memo-2026-04-02.m4a" to "0:38",
            "Miles Davis - So What.flac" to "9:22",
            "فيروز - كيفك إنت.mp3" to "3:44",
            "track09.mp3" to "2:58",
            "Ravel - Boléro (complete).mp3" to "15:06",
            "ringtone_old.ogg" to "0:12",
            "live-set-warehouse-2025-continuous.mp3" to "2:31:19",
        ).map { (name, duration) -> MediaTile(name = name, duration = duration) }

        /** A picker's listing: the way up, some folders, then the files. */
        fun folder(parentLabel: String): List<PickerEntry> = buildList {
            add(PickerEntry(parentLabel, "", PickerEntry.EntryKind.Parent))
            listOf("DCIM", "Download", "Movies", "Music").forEach {
                add(PickerEntry(it, "", PickerEntry.EntryKind.Folder))
            }
            videos.forEach { add(PickerEntry(it.name, it.duration, PickerEntry.EntryKind.File)) }
        }
    }

    private fun ComposeContentTestRule.bounds(tag: String): DpRect =
        onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun SemanticsNodeInteractionCollection.boundsList(): List<DpRect> =
        fetchSemanticsNodes().indices.map { this[it].getUnclippedBoundsInRoot() }
}
