package com.castivio.feature.activation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.castivio.core.design.theme.CastivioFrame
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.DeviceClass
import com.castivio.core.design.theme.LocalDeviceClass
import com.castivio.core.design.theme.castivioFrame
import com.castivio.domain.ProviderSource
import com.castivio.domain.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * Every screen that wears the shared header, on every frame, in both directions.
 *
 * ## What this file is for
 *
 * The per-screen layout tests each measure one screen well. None of them measures the
 * property the frame system exists to create: that the screens put the brand, the title
 * and the way back **in the same place**, on the same four frames, whichever direction
 * the reader's language runs.
 *
 * That is a claim about the set, so it is gated over the set. A screen added later that
 * builds a header of its own does not fail its own test — it fails this one, which is
 * the point.
 *
 * ## Eight passes in one composition, and why
 *
 * `setContent` may be called once per rule, so a loop that re-composed per frame would
 * throw on its second turn. Every pass is therefore composed at once, each in a box of
 * exactly its frame's size — `requiredSize` overrides the incoming constraints, so a
 * pass is measured against its own frame and not against the room the column has left.
 * Nodes are then read positionally, and every assertion is relative to that pass's own
 * stage, so the eight never have to know where in the column they landed.
 *
 * ## Both directions, and what "four languages" can honestly mean here
 *
 * Direction is the half of language that changes layout, and it is measured in both. The
 * other half — how long a word is in Portuguese — cannot be: Robolectric does not lay
 * text out, and every `Text` measures the same height whatever its style, as
 * `ActivationBudgetTest` documents. A test claiming four languages in this harness would
 * be measuring the harness.
 *
 * So the four-language pass belongs where type is real, which is `design/mockups/` under
 * a browser. What is asserted here is everything that does not depend on a glyph:
 * containment, the header's three-slot geometry, and the target floors.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-land")
class FrameSweepTest {

    @get:Rule
    val compose = createComposeRule()

    /* ------------------------------------------------------------------- the frames */

    private data class Pass(
        val name: String,
        val width: Dp,
        val height: Dp,
        val tv: Boolean,
        val direction: LayoutDirection,
    ) {
        val frame: CastivioFrame get() = castivioFrame(tv, height)
        val device: DeviceClass get() = if (tv) DeviceClass.Television else DeviceClass.Expanded
        override fun toString() = "$name ${if (direction == LayoutDirection.Rtl) "RTL" else "LTR"}"
    }

    /**
     * The four frames, each read twice.
     *
     * The order is fixed and load-bearing: nodes are found positionally, so pass *i*'s
     * tagged node is the *i*th of its tag. Adding a frame in the middle would silently
     * re-index every assertion, which is why the list is built once here and every test
     * walks it in the same order.
     */
    private val passes: List<Pass> = listOf(
        Triple("television 960x540", 960.dp to 540.dp, true),
        Triple("tablet 1280x800", 1280.dp to 800.dp, false),
        Triple("reference phone 873x393", 873.dp to 393.dp, false),
        Triple("shortest phone 800x360", 800.dp to 360.dp, false),
    ).flatMap { (name, size, tv) ->
        listOf(LayoutDirection.Rtl, LayoutDirection.Ltr).map { direction ->
            Pass(name, size.first, size.second, tv, direction)
        }
    }

    /* ------------------------------------------------- the frame table, on its own */

    /**
     * Each surface reaches the frame it was drawn for.
     *
     * First, because everything below is only meaningful if it is. The equivalent was
     * wrong once on the activation screen and nothing caught it: the gate read a height
     * 48dp short of the display, the 873dp phone fell under the threshold, and it drew
     * the short phone's table. It looked fine. It was the wrong drawing.
     */
    @Test
    fun `each surface reaches the frame it was drawn for`() {
        val reached = passes.associate { it.name to it.frame }
        assertEquals(CastivioFrame.Television, reached.getValue("television 960x540"))
        assertEquals(CastivioFrame.Tablet, reached.getValue("tablet 1280x800"))
        assertEquals(CastivioFrame.Phone, reached.getValue("reference phone 873x393"))
        assertEquals(CastivioFrame.ShortPhone, reached.getValue("shortest phone 800x360"))
    }

    /**
     * The tablet buys margin and not size, which is the whole rule in one assertion.
     *
     * Its frame is the largest and its type sits *between* the phone's and the set's. A
     * change that grows tablet type because the screen is bigger fails here, and it is
     * the change this system was built to prevent.
     */
    @Test
    fun `the tablet's extra room goes into margin, not into type`() {
        val tablet = CastivioFrame.Tablet
        val tv = CastivioFrame.Television
        val phone = CastivioFrame.Phone

        assertTrue(
            "the tablet's edge ${tablet.edge} is not the largest of the four",
            tablet.edge > tv.edge && tablet.edge > phone.edge,
        )
        assertTrue(
            "the tablet's title ${tablet.fsTitle} is not between the phone's " +
                "${phone.fsTitle} and the set's ${tv.fsTitle}",
            tablet.fsTitle >= phone.fsTitle && tablet.fsTitle <= tv.fsTitle,
        )
        assertTrue(
            "the tablet's body ${tablet.fsBody} is larger than the television's ${tv.fsBody}",
            tablet.fsBody <= tv.fsBody,
        )
    }

    /**
     * The pill is drawn at the frame's chip and pressed at the frame's floor.
     *
     * Two numbers, two claims. The drawing states a 44dp pill on a television and a
     * 34dp one on the shortest phone; the rule states a 56dp and a 48dp interaction
     * area. Read as one number they contradict, and both ways of collapsing them are
     * wrong — growing the pill rewrites an approved drawing to satisfy a rule about
     * fingers, growing the header row costs three frames 2 to 12dp of band.
     *
     * So this asserts the shape of the answer rather than either collapse: the pill
     * stays under the floor on every frame (which is what makes the second box
     * necessary), and the header row can hold the pill it draws. What the interaction
     * box actually measures is asserted where it is laid out, in the header sweep
     * below.
     */
    @Test
    fun `the drawn pill and the pressable box are two different sizes`() {
        for (pass in passes) {
            val frame = pass.frame
            assertTrue(
                "$pass: the header ${frame.header} cannot hold a ${frame.chip} pill",
                frame.header >= frame.chip,
            )
            assertTrue(
                "$pass: the pill ${frame.chip} already clears the ${frame.touchTarget} " +
                    "floor, so the interaction box around it is now dead weight -- " +
                    "collapse the two rather than leaving a box nothing needs",
                frame.chip < frame.touchTarget,
            )
        }
    }

    /**
     * The interaction box overhangs the row, and the overhang fits the stage's margin.
     *
     * This is the arithmetic that makes the two-box answer safe rather than merely
     * clever: the box is centred on a row shorter than itself, so it reaches
     * `(touchTarget - header) / 2` above the header into `stageTop` and the same below
     * into `bandTop`. If a frame ever tightens those margins past the overhang, the
     * control starts reaching into content — or off the display — and that is the
     * failure this states in numbers before anyone has to see it.
     */
    @Test
    fun `the overhang fits the margin above and below the header`() {
        for (pass in passes) {
            val frame = pass.frame
            val overhang = maxOf(0.dp, (frame.touchTarget - frame.header) / 2)
            assertTrue(
                "$pass: the box overhangs $overhang above a ${frame.stageTop} margin",
                overhang <= frame.stageTop,
            )
            assertTrue(
                "$pass: the box overhangs $overhang below a ${frame.bandTop} margin",
                overhang <= frame.bandTop,
            )
        }
    }

    /* ------------------------------------------------------- the header, everywhere */

    @Test
    fun `the source choice wears the header on every frame`() {
        compose.sweep { SourceChoiceScreen({}, {}, {}, {}, onBack = {}) }
        compose.assertHeaderEverywhere(
            stage = ActivationTags.SOURCE_CONTAINER,
            heading = ActivationTags.SOURCE_HEADING,
            back = ActivationTags.SOURCE_BACK,
        )
    }

    @Test
    fun `the media chooser wears the header on every frame`() {
        compose.sweep { MediaSourceScreen({}, {}, {}, {}, onBack = {}) }
        compose.assertHeaderEverywhere(
            stage = ActivationTags.MEDIA_CONTAINER,
            heading = ActivationTags.MEDIA_HEADING,
            back = ActivationTags.MEDIA_BACK,
        )
    }

    @Test
    fun `the video library wears the header on every frame`() {
        compose.sweep { VideoLibraryScreen(videos = SWEEP_VIDEOS, onPlay = {}, onBack = {}) }
        compose.assertHeaderEverywhere(
            stage = ActivationTags.LIBRARY_CONTAINER,
            heading = ActivationTags.LIBRARY_HEADING,
            back = ActivationTags.LIBRARY_BACK,
        )
    }

    @Test
    fun `the audio library wears the header on every frame`() {
        compose.sweep { AudioLibraryScreen(tracks = SWEEP_TRACKS, onPlay = {}, onBack = {}) }
        compose.assertHeaderEverywhere(
            stage = ActivationTags.LIBRARY_CONTAINER,
            heading = ActivationTags.LIBRARY_HEADING,
            back = ActivationTags.LIBRARY_BACK,
        )
    }

    @Test
    fun `the file picker wears the header on every frame`() {
        compose.sweep {
            FilePickerScreen(
                kind = PickerKind.Video,
                path = "/storage/emulated/0/Movies",
                entries = SWEEP_ENTRIES,
                onOpen = {},
                onBack = {},
            )
        }
        compose.assertHeaderEverywhere(
            stage = ActivationTags.PICKER_CONTAINER,
            heading = ActivationTags.PICKER_HEADING,
            back = ActivationTags.PICKER_BACK,
        )
    }

    /**
     * The saved subscriptions, which are the one screen of the five whose stage carries
     * no tag of its own — nothing had needed one.
     *
     * So the header is measured against the pass's own frame rather than against a
     * tagged stage: the mark stands off the leading edge by exactly [CastivioFrame.edge]
     * and Back off the trailing edge by the same, which is the same claim stated from
     * the display instead of from the container.
     */
    @Test
    fun `the saved subscriptions wear the header on every frame`() {
        compose.sweep {
            SavedSourcesScreen(
                state = SavedSourcesState.Ready(saved = SWEEP_SOURCES, activeId = "a"),
                onChoose = {},
                onAddXtream = {},
                onAddPlaylist = {},
                onBack = {},
            )
        }

        val marks = compose.all(ActivationTags.HEADER_MARK)
        val titles = compose.all(ActivationTags.SAVED_TITLE)
        val backs = compose.all(ActivationTags.SAVED_BACK)

        passes.forEachIndexed { i, pass ->
            // The stage is untagged here, so the row's own span is the claim: the mark
            // starts it, Back ends it, and the two are the frame's usable width apart.
            val span = backs[i].right - marks[i].left
            val usable = pass.width - pass.frame.edge * 2
            assertTrue(
                "$pass: the header spans $span, not the frame's usable $usable",
                abs((span - usable).value) <= 1f,
            )
            assertTrue(
                "$pass: the title is not between the mark and Back",
                titles[i].left >= marks[i].right && titles[i].right <= backs[i].left,
            )
            assertTrue(
                "$pass: Back's box is ${backs[i].height}, below the " +
                    "${pass.frame.touchTarget} floor",
                backs[i].height >= pass.frame.touchTarget,
            )
        }
    }

    /* ------------------------------------------------------------------ the claims */

    /**
     * The three slots, in the order the header declares, inside the stage.
     *
     * **In physical coordinates, in both directions.** That is the assertion, and it is
     * the one the per-screen tests had backwards until this pass: the row does not
     * mirror, only the text inside each slot does. So the mark stands off the stage's
     * leading physical edge by the frame's own margin and Back off the trailing one by
     * the same, in Arabic exactly as in English — and a screen that lets the row mirror
     * fails on one of the two passes rather than looking correct in whichever language
     * it happened to be read in.
     */
    private fun ComposeContentTestRule.assertHeaderEverywhere(
        stage: String,
        heading: String,
        back: String,
    ) {
        val stages = all(stage)
        val marks = all(ActivationTags.HEADER_MARK)
        val titles = all(heading)
        val backs = all(back)

        passes.forEachIndexed { i, pass ->
            val panel = stages[i]
            val mark = marks[i]
            val title = titles[i]
            val backBox = backs[i]
            val edge = pass.frame.edge

            println(
                "frame sweep — $pass | stage ${panel.left}..${panel.right} " +
                    "| mark ${mark.left} | title ${title.left}..${title.right} " +
                    "| back ${backBox.left}..${backBox.right}",
            )

            // The tag sits on the stage *inside* its own padding, so the stage's own
            // edges already are the frame's margin -- the mark starts on the stage's
            // leading edge and Back ends on its trailing one, with nothing between.
            // Stated in physical coordinates and asserted in both directions, because
            // the row does not mirror; only the text inside each slot does.
            assertTrue(
                "$pass: the mark starts at ${mark.left}, the stage at ${panel.left}",
                abs((mark.left - panel.left).value) <= 1f,
            )
            assertTrue(
                "$pass: Back ends at ${backBox.right}, the stage at ${panel.right}",
                abs((backBox.right - panel.right).value) <= 1f,
            )
            assertTrue(
                "$pass: the title ${title.left}..${title.right} is not between the mark " +
                    "(${mark.right}) and Back (${backBox.left})",
                title.left >= mark.right && title.right <= backBox.left,
            )

            // One row: the three share a band. A title pushed onto a second line, or a
            // chip fallen below the lockup, breaks this and nothing else.
            assertTrue(
                "$pass: the title ${title.top}..${title.bottom} does not share the mark's " +
                    "band ${mark.top}..${mark.bottom}",
                title.top < mark.bottom && mark.top < title.bottom,
            )
            assertTrue(
                "$pass: Back ${backBox.top}..${backBox.bottom} does not share the mark's band",
                backBox.top < mark.bottom && mark.top < backBox.bottom,
            )

            // The claim the two-box answer exists to make: what the layout handed the
            // control is the *floor*, not the pill. Measured rather than derived --
            // a slot clamped back to the row would report the row's height here, and
            // that is precisely how this shipped invisible before.
            assertTrue(
                "$pass: Back's box is ${backBox.height}, below the " +
                    "${pass.frame.touchTarget} floor -- the header has clamped it",
                backBox.height >= pass.frame.touchTarget,
            )

            // And the box stays on the display, overhang included. The horizontal
            // claim above is against the stage; this one is against the frame, because
            // the overhang is deliberately *outside* the header's own bounds.
            assertTrue(
                "$pass: Back's box ${backBox.top}..${backBox.bottom} leaves the " +
                    "${pass.height} frame",
                backBox.top >= panel.top - pass.frame.stageTop &&
                    backBox.bottom <= panel.bottom + pass.frame.stageBottom,
            )

            // The mark and the title are inside the stage they belong to, in both axes.
            for ((what, box) in listOf("the mark" to mark, "the title" to title)) {
                assertTrue(
                    "$pass: $what runs past the stage — ${box.left}..${box.right} of " +
                        "${panel.left}..${panel.right}",
                    box.left >= panel.left && box.right <= panel.right,
                )
                assertTrue(
                    "$pass: $what runs past the stage vertically — ${box.top}..${box.bottom} " +
                        "of ${panel.top}..${panel.bottom}",
                    box.top >= panel.top && box.bottom <= panel.bottom,
                )
            }
        }
    }

    /* ------------------------------------------------------------------- the harness */

    private fun ComposeContentTestRule.sweep(screen: @Composable () -> Unit) = setContent {
        CastivioTheme {
            Column {
                for (pass in passes) {
                    CompositionLocalProvider(
                        LocalDeviceClass provides pass.device,
                        LocalLayoutDirection provides pass.direction,
                    ) {
                        // `requiredSize`, so the pass is measured against its own frame
                        // rather than against whatever the column has left. That is the
                        // whole reason eight of them can share one composition.
                        Box(Modifier.requiredSize(pass.width, pass.height)) { screen() }
                    }
                }
            }
        }
    }

    /**
     * Every node carrying a tag, in composition order — which is [passes]' order.
     *
     * Asserted to be exactly as many as there are passes, because a screen that stopped
     * composing its header on one frame would otherwise shift every later index by one
     * and fail somewhere that says nothing about the cause.
     */
    private fun ComposeContentTestRule.all(tag: String): List<DpRect> {
        val nodes = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(
            "$tag was found ${nodes.size} times across ${passes.size} passes",
            passes.size,
            nodes.size,
        )
        // **Unclipped**, and that is load-bearing here rather than a preference: the
        // eight passes are stacked in a column taller than the root, so the ones below
        // the viewport are clipped away and the clipped rect of a pass that was never
        // on screen is empty. What is being measured is where the layout *put* things,
        // which is exactly what the unclipped bounds report.
        return nodes.indices.map {
            onAllNodesWithTag(tag, useUnmergedTree = true)[it].getUnclippedBoundsInRoot()
        }
    }

    private companion object {
        val SWEEP_VIDEOS = listOf(
            MediaTile(name = "Tears.of.Steel.2012.BluRay.x265.10bit.mkv", duration = "12:14"),
            MediaTile(name = "مباراة الأمس كاملة.mkv", duration = "2:07:55"),
            MediaTile(name = "timelapse_sunrise.webm", duration = "1:05"),
        )

        val SWEEP_TRACKS = listOf(
            MediaTile(name = "أم كلثوم - الأطلال.mp3", duration = "58:12"),
            MediaTile(name = "Miles Davis - So What.flac", duration = "9:22"),
        )

        val SWEEP_ENTRIES = listOf(
            PickerEntry(name = "Movies", detail = "12", kind = PickerEntry.EntryKind.Folder),
            PickerEntry(
                name = "Sintel.2010.1080p.mkv",
                detail = "14:48",
                kind = PickerEntry.EntryKind.File,
            ),
        )

        val SWEEP_SOURCES = listOf(
            ProviderSource(
                id = "a",
                kind = SourceKind.XTREAM,
                label = "اشتراكي الأساسي",
                url = "http://example.test:8080",
            ),
            ProviderSource(
                id = "b",
                kind = SourceKind.M3U_URL,
                label = "Backup playlist",
                url = "https://example.test/playlist.m3u8?token=abcdef",
            ),
        )
    }
}
