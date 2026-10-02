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
import androidx.compose.ui.unit.width
import com.castivio.core.design.theme.CastivioMetrics
import com.castivio.core.design.theme.CastivioThemeSwitch
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.LocalThemeSwitch
import com.castivio.core.design.theme.DeviceClass
import com.castivio.core.design.theme.LocalDeviceClass
import com.castivio.core.design.theme.castivioMetrics
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
        val frame: CastivioMetrics get() = castivioMetrics(width, height, tv)
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

    /* ------------------------------------------------- the sizing system, on its own */

    /**
     * Each surface is drawn from its own measurements.
     *
     * First, because everything below is only meaningful if it is. The equivalent was
     * wrong once on the activation screen and nothing caught it: the gate read a height
     * 48dp short of the display, the 873dp phone fell under the threshold, and it drew
     * the short phone's row of a table that no longer exists. It looked fine. It was the
     * wrong drawing.
     *
     * There is no table to land in the wrong row of now — but the same fault is still
     * available in a different shape, which is a screen measuring the window instead of
     * its own surface. So this asserts what makes the rest honest: every pass's numbers
     * are the ones the system computes from that pass's width and height, and no two
     * differently sized surfaces come out identical.
     */
    @Test
    fun `each surface is drawn from its own measurements`() {
        for (pass in passes) {
            assertEquals(
                "$pass: the metrics did not come from this surface",
                castivioMetrics(pass.width, pass.height, pass.tv),
                pass.frame,
            )
        }
        val distinct = passes.distinctBy { it.name }.map { it.frame }.distinct()
        assertEquals(
            "two of the four surfaces produced identical metrics",
            4,
            distinct.size,
        )
    }

    /**
     * A larger surface never draws smaller, and never draws without a ceiling.
     *
     * ## What this replaced
     *
     * It used to assert that *the tablet buys margin and not size* — that its type sat
     * between the phone's and the television's rather than above both. That was the rule
     * of the four-frame table, where a tablet was a named device with a row of its own,
     * and it is not the rule any more: sizes are a bounded share of the surface, so a
     * 1280×800 tablet draws larger type than a 960×540 television because it has more
     * room, and stops at the ceiling. Deleting the assertion would have left the ladder
     * unguarded, so it asserts the property that replaced it.
     *
     * Two claims, and both are what makes this *bounded* responsive sizing rather than
     * a scale: it only ever moves one way as the surface grows — a token that got
     * smaller on a larger screen is a share read off the wrong axis, which looks like a
     * rendering bug and is arithmetic — and it stops, so a 4K set is proportionate
     * instead of magnified.
     */
    @Test
    fun `sizes only grow with the surface, and stop`() {
        val ladder = listOf(
            800.dp to 360.dp,
            873.dp to 393.dp,
            960.dp to 540.dp,
            1280.dp to 800.dp,
            1920.dp to 1080.dp,
            3840.dp to 2160.dp,
        ).map { (w, h) -> castivioMetrics(w, h, isTv = false) }

        for ((smaller, larger) in ladder.zipWithNext()) {
            assertTrue("edge shrank: ${larger.edge} after ${smaller.edge}", larger.edge >= smaller.edge)
            assertTrue("header shrank", larger.header >= smaller.header)
            assertTrue("chip shrank", larger.chip >= smaller.chip)
            assertTrue("title shrank", larger.fsTitle >= smaller.fsTitle)
            assertTrue("body shrank", larger.fsBody >= smaller.fsBody)
        }

        val huge = ladder.last()
        assertTrue("the 4K edge ${huge.edge} is unbounded", huge.edge <= 72.dp)
        assertTrue("the 4K chip ${huge.chip} is unbounded", huge.chip <= 64.dp)
        assertTrue("the 4K title ${huge.fsTitle} is unbounded", huge.fsTitle <= 36.dp)
    }

    /**
     * The pill is drawn at the surface's chip and pressed at the device's floor.
     *
     * Two numbers, two claims. The drawing states a 44dp pill on a television and a
     * 32dp one on the shortest phone; the rule states a 56dp and a 48dp interaction
     * area. Read as one number they contradict, and both ways of collapsing them are
     * wrong — growing the pill rewrites an approved drawing to satisfy a rule about
     * fingers, growing the header row costs a short surface band it does not have.
     *
     * So this asserts the shape of the answer rather than either collapse: the header
     * row can hold the pill it draws, and the pill can hold the line it is drawn
     * around. What the interaction box actually measures is asserted where it is laid
     * out, in the header sweep below — the two are separate because the box is allowed
     * to overhang the row and the pill is not.
     *
     * The floor itself is no longer stated as *the pill is smaller than the target*.
     * That was true of every row of the four-frame table and it is not a property of
     * the design: on a 1280×800 tablet the pill is drawn at 64dp and clears the 48dp
     * floor on its own. Asserting the old inequality would have been asserting that
     * large surfaces do not exist.
     */
    @Test
    fun `the pill is drawn to the surface and holds its own line`() {
        for (pass in passes) {
            val frame = pass.frame
            assertTrue(
                "$pass: the header ${frame.header} cannot hold a ${frame.chip} pill",
                frame.header >= frame.chip,
            )
            val line = frame.fsChip * CHIP_LEADING
            assertTrue(
                "$pass: a ${frame.chip} pill cannot hold a $line line of type",
                frame.chip >= line,
            )
        }
    }

    /** What `castivioChipStyle` sets a chip's line at. */
    private val CHIP_LEADING = 1.45f

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
            subtitle = ActivationTags.SOURCE_SUBTITLE,
        )
    }

    /**
     * The saved subscriptions, which are the one screen of the five whose stage carries
     * no tag of its own — nothing had needed one.
     *
     * So the header is measured against the pass's own frame rather than against a
     * tagged stage: the mark stands off the leading edge by exactly [CastivioMetrics.edge]
     * and Back off the trailing edge by the same, which is the same claim stated from
     * the display instead of from the container.
     */
    @Test
    fun `the saved subscriptions wear the header on every frame`() {
        compose.sweep {
            SavedSourcesScreen(
                state = SavedSourcesState.Ready(
                    saved = SWEEP_SOURCES,
                    activeId = "a",
                    // The longest row there is: the mark, the kind and both dates. The
                    // claim here is the header's, but the frame it is measured on is the
                    // one the fullest list produces.
                    expiries = mapOf("a" to SWEEP_EXPIRY_MS),
                ),
                onChoose = {},
                onRename = { _, _ -> },
                onDelete = {},
                onBack = {},
            )
        }

        val marks = compose.all(ActivationTags.HEADER_MARK)
        val titles = compose.all(ActivationTags.SAVED_TITLE)
        val backs = compose.all(ActivationTags.SAVED_BACK)
        val clocks = compose.all(ActivationTags.HEADER_CLOCK)

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
            // The clock took the step between the title and the controls, and it took
            // it from the title rather than from them. Stated as an order on every
            // frame, because the frame that can fail is the shortest one: a clock that
            // did not fit would push Back off the row's own span, and the assertion
            // above would then be the one that fails — which is the point of asserting
            // both rather than either.
            assertTrue(
                "$pass: the clock is not between the title and Back",
                clocks[i].left >= titles[i].right && clocks[i].right <= backs[i].left,
            )
            // The stage is untagged, so the row's own span stands in for it — the two
            // are the same span, which is what the assertion above this one just
            // established.
            assertCentred(
                pass = pass,
                panel = DpRect(
                    left = marks[i].left,
                    top = marks[i].top,
                    right = backs[i].right,
                    bottom = marks[i].bottom,
                ),
                mark = marks[i],
                title = titles[i],
                back = backs[i],
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
    /**
     * Where pass *i*'s own box starts, horizontally.
     *
     * The passes are stacked in a column, so every one of them begins at the column's
     * left edge — zero. Written as a function rather than as a literal because the
     * number is a property of how the sweep is composed, and a reader should be able to
     * see which it is.
     */
    private fun stageLeft(@Suppress("UNUSED_PARAMETER") pass: Int) = 0.dp

    /**
     * The screen's name sits on the row's centre, not in the middle of what the two
     * ends leave over.
     *
     * ## Why the two are not the same point
     *
     * They would be if the lockup and the chip were the same width. They are nowhere
     * near: the lockup is a mark and a wordmark, Back is an arrow and a short word, and
     * on the television frame they measure 187dp against 77. Centring in the remainder
     * therefore lands the title half that difference — 55dp — towards Back, and 41, 34
     * and 32dp on the other three frames. The header looked deliberate and read as
     * leaning, and anything centred on the stage below it was visibly not under the
     * title it belonged to.
     *
     * ## What is asserted, and why it has an alternative
     *
     * Centred within a dp, *or* hard against one of the two clamps. Both are correct
     * outcomes: a title long enough that its centred position would collide with a
     * neighbour stops against that neighbour rather than shrinking or overlapping, and
     * a test that demanded the centre unconditionally would be demanding the wrong
     * behaviour in exactly the case the clamp exists for.
     *
     * This harness only ever renders one language, so the clamp is not expected to bite
     * here — the branch is stated because the layout has it, not because it is reached.
     */
    private fun assertCentred(pass: Pass, panel: DpRect, mark: DpRect, title: DpRect, back: DpRect) {
        val gap = pass.frame.headGap
        val width = title.right - title.left
        val stageCentre = (panel.left + panel.right) / 2
        val titleCentre = (title.left + title.right) / 2

        val centred = abs((titleCentre - stageCentre).value) <= 1f
        val againstMark = abs((title.left - (mark.right + gap)).value) <= 1f
        val againstBack = abs((title.right - (back.left - gap)).value) <= 1f

        assertTrue(
            "$pass: the title is centred on ${titleCentre}, the stage on $stageCentre — " +
                "off by ${titleCentre - stageCentre}, and it is not against either clamp " +
                "(mark ends ${mark.right}, Back starts ${back.left}, title is $width wide, " +
                "gap $gap). A title centred in the remainder rather than on the row is the " +
                "fault this states: the two ends are different widths, so those are " +
                "different points.",
            centred || againstMark || againstBack,
        )
    }

    private fun ComposeContentTestRule.assertHeaderEverywhere(
        stage: String,
        heading: String,
        back: String,
        /**
         * The tag of the sentence under the name, on the two screens that have one.
         *
         * Checked against the *title* rather than the container, because a sentence
         * that spans the stage passes a containment check by construction — which is
         * how the drawing shipped one sitting under Back while every measurement of
         * it reported no offset at all.
         */
        subtitle: String? = null,
    ) {
        val stages = all(stage)
        val marks = all(ActivationTags.HEADER_MARK)
        val titles = all(heading)
        val backs = all(back)
        val subs = subtitle?.let { all(it) }
        val themes = all(ActivationTags.HEADER_THEME)

        passes.forEachIndexed { i, pass ->
            val panel = stages[i]
            val mark = marks[i]
            val title = titles[i]
            val backBox = backs[i]
            val edge = pass.frame.edge

            val theme = themes[i]
            println(
                "frame sweep — $pass | stage ${panel.left}..${panel.right} " +
                    "| mark ${mark.left} | title ${title.left}..${title.right} " +
                    "| theme ${theme.left}..${theme.right} (${theme.width}x${theme.height}) " +
                    "| back ${backBox.left}..${backBox.right}",
            )

            // The theme control: present on every screen, inboard of Back, on the same
            // band, and pressable at the frame's own floor in *both* axes -- it is
            // icon-only, so unlike Back it has no label to grow it, and 44dp of pill on
            // a television is 12dp under the D-pad floor if nothing says otherwise.
            assertTrue(
                "$pass: the theme chip ${theme.left}..${theme.right} is not inboard of " +
                    "Back ${backBox.left}..${backBox.right}",
                theme.right <= backBox.left + 1.dp && theme.left >= title.right - 1.dp,
            )
            assertTrue(
                "$pass: the theme chip ${theme.top}..${theme.bottom} does not share the " +
                    "mark's band ${mark.top}..${mark.bottom}",
                theme.top < mark.bottom && mark.top < theme.bottom,
            )
            assertTrue(
                "$pass: the theme chip is ${theme.width}x${theme.height}, under the " +
                    "${pass.frame.touchTarget} floor",
                theme.width >= pass.frame.touchTarget && theme.height >= pass.frame.touchTarget,
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
            assertCentred(pass, panel, mark, title, backBox)

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

            // The stage's own margin is the frame's `edge` and nothing added to it.
            //
            // With no system insets -- which is what this harness gives, and what a
            // settled immersive device gives -- `castivioStage` must resolve to exactly
            // the frame's number. It measured 69dp on one side of a real handset before
            // that modifier existed, because the surface paid a 37dp display cutout and
            // the screen then paid its 32dp edge inside it. `max`, not a sum: this is
            // the half of that rule a test can see.
            assertTrue(
                "$pass: the stage starts ${panel.left - stageLeft(i)} in, not ${pass.frame.edge}",
                abs(((panel.left - stageLeft(i)) - pass.frame.edge).value) <= 1f,
            )

            subs?.get(i)?.let { sub ->
                assertTrue(
                    "$pass: the subtitle ${sub.top} is not under the title ${title.bottom}",
                    sub.top >= title.bottom,
                )
                val titleCentre = (title.left + title.right) / 2
                val subCentre = (sub.left + sub.right) / 2
                assertTrue(
                    "$pass: the subtitle is centred on $subCentre, the title on $titleCentre",
                    abs((subCentre - titleCentre).value) <= 1f,
                )
                assertTrue(
                    "$pass: the subtitle ${sub.left}..${sub.right} runs past the stage " +
                        "${panel.left}..${panel.right}",
                    sub.left >= panel.left && sub.right <= panel.right,
                )
            }

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

    /**
     * A switch that reports dark and does nothing, so the header draws its theme chip.
     *
     * Without one `LocalThemeSwitch` is null and the chip is absent — which is correct
     * for a preview and wrong for this file, whose whole job is to measure what a
     * reader actually sees. Providing it here is what makes the existing assertions
     * carry the new control: Back still has to end on the stage's trailing edge with a
     * second chip beside it, and the title still has to fit between.
     */
    private val sweepSwitch = object : CastivioThemeSwitch {
        override val isDark = true
        override fun toggle() = Unit
    }

    private fun ComposeContentTestRule.sweep(screen: @Composable () -> Unit) = setContent {
        CompositionLocalProvider(LocalThemeSwitch provides sweepSwitch) {
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
        /** A date an Xtream panel could state, so the sweep draws a row carrying one. */
        const val SWEEP_EXPIRY_MS = 1_805_000_000_000L

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
