package com.castivio.feature.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import com.castivio.playback.api.MediaKind
import com.castivio.playback.api.PlaybackError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where the player puts things, on the three frames the layout contract names.
 *
 * ## Why a player needs this more than any screen before it
 *
 * Every other screen is a composition inside a visible container, and a control in the
 * wrong place looks wrong. Here the container is a film: the safe area has no edge to see,
 * so a button 6dp outside the overscan of a television the developer does not own looks
 * perfectly correct in every screenshot and is simply unreachable on the device.
 *
 * So the claims are measured rather than reviewed: nothing a thumb or a remote has to reach
 * is outside the safe area, every target is at least the frame's floor, the three bands do
 * not overlap, and the picture is never inset.
 *
 * ## What Robolectric may not be asked
 *
 * Text does not lay out here — every `Text` measures 35dp whatever its style, as
 * `ActivationBudgetTest` documents at length. So nothing below asserts a height that type
 * contributes to, and nothing asserts that the composition fits. Every assertion is a
 * containment claim, a relation between two boxes, or a size a modifier imposes outright.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-land")
class PlayerLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    /* ------------------------------------------------------------- the safe area */

    @Test
    fun `the handset keeps every control inside the safe area`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingLive())
        compose.assertSafe(HANDSET, PHONE_INSET)
    }

    @Test
    fun `the shortest frame keeps every control inside the safe area`() {
        compose.show(SHORT, DeviceClass.Compact, playingLive())
        compose.assertSafe(SHORT, PHONE_INSET)
    }

    /**
     * The television, where the inset is overscan rather than a cutout.
     *
     * 48dp, not 24. Broadcast panels crop the edge of the picture, so a control at 24dp on
     * a television is a control that may not be on the screen at all — and this is the
     * frame the tools row overflowed on in the drawing, in Arabic, with the timeshift
     * control added.
     */
    @Test
    fun `the television keeps every control inside the overscan inset`() {
        compose.show(TELEVISION, DeviceClass.Television, playingLive())
        compose.assertSafe(TELEVISION, TV_INSET)
    }

    /**
     * The case that actually overflowed: Arabic, on a television, behind the live edge.
     *
     * Timeshift adds "Back to live" to a row that already carries a guide and a channel
     * list, and Arabic labels are longer than the English ones the row was drawn with. The
     * fix was to let the row shrink its items rather than push them out — the labels
     * ellipsis, the targets do not — and this is the test that says so.
     */
    @Test
    fun `an Arabic television in timeshift keeps its tools row inside the safe area`() {
        compose.show(
            TELEVISION,
            DeviceClass.Television,
            playingLive().copy(behindLiveMs = FOUR_MINUTES),
            LayoutDirection.Rtl,
        )
        compose.assertSafe(TELEVISION, TV_INSET)

        val tools = compose.bounds(PlayerTags.TOOLS)
        val toLive = compose.bounds(PlayerTags.TO_LIVE)
        assertTrue(
            "back-to-live ${toLive.left}..${toLive.right} is outside the tools row " +
                "${tools.left}..${tools.right}",
            toLive.left >= tools.left - TOLERANCE && toLive.right <= tools.right + TOLERANCE,
        )
    }

    /* ----------------------------------------------------------------- the targets */

    /**
     * Every control is at least the frame's floor, in both dimensions.
     *
     * The floor is 48dp under a thumb and 56dp under a remote, and the distinction is the
     * one this product has got wrong twice — both times caught by eye on a photograph
     * rather than by a gate. "Back to live" was the specific casualty here: drawn inline in
     * the time row at 32dp, it measured as the only control in the player under the touch
     * floor, which is why it now lives in the tools row.
     */
    @Test
    fun `every control on a handset is at least the touch floor`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingLive())
        compose.assertTargets(PHONE_FLOOR)
    }

    @Test
    fun `every control on a television is at least the remote floor`() {
        compose.show(TELEVISION, DeviceClass.Television, playingLive())
        compose.assertTargets(TV_FLOOR)
    }

    @Test
    fun `every control in timeshift is still at least the floor`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingLive().copy(behindLiveMs = FOUR_MINUTES))
        compose.assertTargets(PHONE_FLOOR)
    }

    /* ------------------------------------------------------------------ the bands */

    /**
     * Three bands, in order, none of them touching.
     *
     * The claim `SpaceBetween` is supposed to give and the one a fixed offset would break:
     * on the 360dp frame there is little enough room that a centre cluster which grew by a
     * row would run into the timeline, and nothing about that looks wrong in a screenshot
     * taken on a 540dp television.
     *
     * One test per frame rather than a loop, because `setContent` may be called once per
     * rule -- a loop would throw on its second turn instead of asserting anything.
     */
    @Test
    fun `the bands do not overlap on a handset`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingLive())
        compose.assertBands(HANDSET)
    }

    @Test
    fun `the bands do not overlap on the shortest frame`() {
        compose.show(SHORT, DeviceClass.Compact, playingLive())
        compose.assertBands(SHORT)
    }

    @Test
    fun `the bands do not overlap on a television`() {
        compose.show(TELEVISION, DeviceClass.Television, playingLive())
        compose.assertBands(TELEVISION)
    }

    /**
     * The picture fills the frame.
     *
     * A player that letterboxes its own surface inside a margin is a player showing a
     * smaller film than the screen it was given. Not asserted for picture-in-picture, where
     * the window belongs to Android, or for casting, where the video is on the television.
     */
    @Test
    fun `the video fills the frame`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingLive())
        val frame = compose.bounds(PlayerTags.ROOT)
        val video = compose.bounds(PlayerTags.VIDEO)

        assertEquals("the picture is not the width of the frame", frame.width.value, video.width.value, 0.5f)
        assertEquals("the picture is not the height of the frame", frame.height.value, video.height.value, 0.5f)
    }

    /* -------------------------------------------------------- the reserved strip */

    /**
     * The strip is the same height before the guide answers and after.
     *
     * The single most important assertion in this file, and the one that makes "EPG is off
     * the critical path" a fact rather than an intention. If these two differ, then the
     * moment the guide lands the timeline and the tools row move — with the user's thumb
     * already travelling toward a control that is no longer where they saw it.
     *
     * Measured on two compositions of the same screen rather than by reading a constant,
     * because a constant proves the constant and this proves the layout.
     */
    @Test
    fun `the programme strip reserves its height before the guide arrives`() {
        // One composition, and the guide arrives into it -- which is the event being
        // tested. Two separate compositions would compare two screens; this compares the
        // same screen before and after the thing that could move it.
        val programme = mutableStateOf<Programme?>(null)
        compose.setContent {
            CastivioTheme {
                CompositionLocalProvider(
                    LocalDeviceClass provides DeviceClass.Expanded,
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                ) {
                    Stage(HANDSET) {
                        PlayerScreen(
                            state = playingLive().copy(programme = programme.value),
                            actions = PlayerActions(),
                        )
                    }
                }
            }
        }

        val coldStrip = compose.bounds(PlayerTags.EPG)
        val coldTools = compose.bounds(PlayerTags.TOOLS)
        val coldTimeline = compose.bounds(PlayerTags.TIMELINE)

        programme.value = Programme(
            now = "الأخبار المسائية",
            window = "20:00 – 20:45",
            next = "التالي: وثائقي أعماق البحار",
            progress = 0.64f,
        )
        compose.waitForIdle()

        val warmStrip = compose.bounds(PlayerTags.EPG)
        val warmTools = compose.bounds(PlayerTags.TOOLS)
        val warmTimeline = compose.bounds(PlayerTags.TIMELINE)

        assertEquals(
            "the strip is $coldStrip cold and $warmStrip warm — the guide reflowed the screen",
            coldStrip.height.value,
            warmStrip.height.value,
            0.5f,
        )
        // And the consequence that actually matters: nothing below it moved. A strip that
        // kept its height while the row under it shifted would pass the first assertion
        // and still take the control out from under the user's thumb.
        assertEquals(
            "the timeline moved when the guide arrived",
            coldTimeline.top.value,
            warmTimeline.top.value,
            0.5f,
        )
        assertEquals(
            "the tools row moved when the guide arrived",
            coldTools.top.value,
            warmTools.top.value,
            0.5f,
        )
    }

    /* ------------------------------------------------------------- the three cards */

    /**
     * An unidentified failure offers both buttons.
     *
     * `UNKNOWN` rather than a decoder refusal, because the decoder cases are switched
     * automatically and never reach a card with the backup still unspent. This is the one
     * state where the person decides, and it is the state the dead-button regression was
     * about.
     *
     * The only one of the three that does, and the reason the other two are separate tests
     * rather than one parameterised sweep: what is being asserted is *absence*, and an
     * absence is only meaningful against a case where the thing is present.
     */
    @Test
    fun `the ordinary failure offers the backup engine and a retry`() {
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingLive().copy(picture = Picture.Failed(PlaybackError.UNKNOWN, canTryBackup = true)),
        )

        compose.onAllNodesWithTag(PlayerTags.ERROR_BACKUP).assertCountEquals(1)
        compose.onAllNodesWithTag(PlayerTags.ERROR_RETRY).assertCountEquals(1)
    }

    /**
     * Protected content offers a retry and nothing else.
     *
     * A different decoder on the same device lacks the same keys, so a backup button here
     * spends the user's time on a second identical failure and teaches them the button is a
     * lie.
     */
    @Test
    fun `the DRM card does not offer the backup engine`() {
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingLive().copy(picture = Picture.Failed(PlaybackError.DRM, canTryBackup = false)),
        )

        compose.onAllNodesWithTag(PlayerTags.ERROR_BACKUP).assertCountEquals(0)
        compose.onAllNodesWithTag(PlayerTags.ERROR_RETRY).assertCountEquals(1)
    }

    /** And a format neither engine can read: the same, for the same reason. */
    @Test
    fun `the unsupported card does not offer the backup engine`() {
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingLive().copy(
                picture = Picture.Failed(PlaybackError.UNSUPPORTED_FORMAT, canTryBackup = false),
            ),
        )

        compose.onAllNodesWithTag(PlayerTags.ERROR_BACKUP).assertCountEquals(0)
        compose.onAllNodesWithTag(PlayerTags.ERROR_RETRY).assertCountEquals(1)
    }

    /* ---------------------------------------------------------------- the loading */

    /**
     * The loading state composes a title and a spinner, and no chrome at all.
     *
     * Asserted as the absence of the bands rather than as the presence of the two, because
     * the requirement is about what is *not* done: chrome that is composed and then hidden
     * has still been measured and laid out, and the rule is that work which is not needed
     * does not happen.
     */
    @Test
    fun `nothing but the title and the spinner is composed before the first frame`() {
        // The clock is stopped before the composition, and that is not a workaround.
        // `Picture.Opening` draws an indefinite CircularProgressIndicator, whose animation
        // by definition never settles. With the test clock auto-advancing, the composition
        // is therefore never idle, and every assertion below — each of which waits for idle
        // first — blocks for as long as the runner will let it. Stopping the clock and
        // advancing exactly one frame composes, measures and lays out the screen once,
        // which is the entire state this test reads.
        compose.mainClock.autoAdvance = false
        compose.show(HANDSET, DeviceClass.Expanded, playingLive().copy(picture = Picture.Opening))
        compose.mainClock.advanceTimeByFrame()

        compose.onAllNodesWithTag(PlayerTags.TITLE).assertCountEquals(1)
        compose.onAllNodesWithTag(PlayerTags.TOP).assertCountEquals(0)
        compose.onAllNodesWithTag(PlayerTags.CENTRE).assertCountEquals(0)
        compose.onAllNodesWithTag(PlayerTags.BOTTOM).assertCountEquals(0)
        compose.onAllNodesWithTag(PlayerTags.EPG).assertCountEquals(0)
        compose.onAllNodesWithTag(PlayerTags.STATISTICS).assertCountEquals(0)
    }

    /**
     * The statistics panel is on the side opposite the title.
     *
     * A correction that came out of the drawing: at the start edge it covered the title in
     * Arabic. Asserted in both directions, because "the end edge" is two different physical
     * sides and a panel pinned to one of them passes in whichever language it was checked
     * in.
     */
    @Test
    fun `the statistics panel sits opposite the title in Arabic`() {
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingLive().copy(statistics = true),
            LayoutDirection.Rtl,
        )
        val title = compose.bounds(PlayerTags.TITLE)
        val panel = compose.bounds(PlayerTags.STATISTICS)
        assertTrue(
            "RTL: the panel at ${panel.left} is not left of the title at ${title.left}",
            panel.left < title.left,
        )
    }

    @Test
    fun `the statistics panel sits opposite the title in English`() {
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingLive().copy(statistics = true),
            LayoutDirection.Ltr,
        )
        val title = compose.bounds(PlayerTags.TITLE)
        val panel = compose.bounds(PlayerTags.STATISTICS)
        assertTrue(
            "LTR: the panel at ${panel.left} is not right of the title at ${title.left}",
            panel.left > title.left,
        )
    }

    /* -------------------------------------------------------------------------- */

    /**
     * Nothing a thumb or a remote reaches is outside the safe area.
     *
     * The sheet is excluded by construction rather than by exception — it is not composed
     * in these states — and if it were it would be right to exclude it: a sheet reaches the
     * screen edge by design, because it is a surface and not a control.
     */
    private fun ComposeContentTestRule.assertBands(frame: Frame) {
        val top = bounds(PlayerTags.TOP)
        val centre = bounds(PlayerTags.CENTRE)
        val bottom = bounds(PlayerTags.BOTTOM)

        println(
            "player ${frame.width}x${frame.height} — top ${top.height} " +
                "centre ${centre.height} bottom ${bottom.height} " +
                "| free ${centre.top - top.bottom}/${bottom.top - centre.bottom}",
        )

        assertTrue(
            "${frame.width}: the centre runs into the top bar — ${top.bottom} against ${centre.top}",
            centre.top >= top.bottom - TOLERANCE,
        )
        assertTrue(
            "${frame.width}: the bottom bar runs into the centre — " +
                "${centre.bottom} against ${bottom.top}",
            bottom.top >= centre.bottom - TOLERANCE,
        )
    }

    private fun ComposeContentTestRule.assertSafe(frame: Frame, expectedInset: Dp) {
        val safe = bounds(PlayerTags.SAFE)
        val root = bounds(PlayerTags.ROOT)

        assertEquals(
            "${frame.width}: the safe inset is ${safe.left - root.left}, not $expectedInset",
            expectedInset.value,
            (safe.left - root.left).value,
            0.5f,
        )

        val outside = mutableListOf<String>()
        for (tag in CONTROLS) {
            val nodes = onAllNodesWithTag(tag)
            if (nodes.fetchSemanticsNodes().isEmpty()) continue
            val box = nodes[0].getUnclippedBoundsInRoot()
            if (box.left < safe.left - TOLERANCE || box.right > safe.right + TOLERANCE ||
                box.top < safe.top - TOLERANCE || box.bottom > safe.bottom + TOLERANCE
            ) {
                outside += "$tag ${box.left}..${box.right} x ${box.top}..${box.bottom}"
            }
        }
        assertTrue(
            "${frame.width}x${frame.height}: outside the safe area " +
                "${safe.left}..${safe.right} x ${safe.top}..${safe.bottom} — $outside",
            outside.isEmpty(),
        )
    }

    /**
     * Every clickable node is at least [floor] in both dimensions.
     *
     * Found by the click action rather than by a list of tags, deliberately: a control
     * added tomorrow and not added to the list would be a control this gate never sees, and
     * the point of the gate is to catch the one nobody thought about.
     */
    private fun ComposeContentTestRule.assertTargets(floor: Dp) {
        val small = mutableListOf<String>()
        val nodes = onAllNodes(hasClickAction())
        for (i in nodes.fetchSemanticsNodes().indices) {
            val box = nodes[i].getUnclippedBoundsInRoot()
            if (box.width < floor - TOLERANCE || box.height < floor - TOLERANCE) {
                small += "${box.width}x${box.height}"
            }
        }
        assertTrue("controls under the $floor floor: $small", small.isEmpty())
    }

    /** A live channel, playing, with the guide not yet in. The commonest state there is. */
    private fun playingLive() = PlayerState(
        request = PlayerRequest(
            url = "http://provider.tv/live/1.ts",
            title = "الأولى المغربية",
            kind = MediaKind.LIVE,
            subtitle = "باقة المغرب",
            channelNumber = "104",
            epgChannelId = "aloula.ma",
            catchUpHours = 48,
        ),
        picture = Picture.Playing,
    )

    private data class Frame(val width: Dp, val height: Dp)

    /** The three the layout contract names, and the insets each one takes. */
    private val HANDSET = Frame(827.dp, 393.dp)
    private val SHORT = Frame(800.dp, 360.dp)
    private val TELEVISION = Frame(960.dp, 540.dp)

    private val PHONE_INSET = 24.dp
    private val TV_INSET = 48.dp
    private val PHONE_FLOOR = 48.dp
    private val TV_FLOOR = 56.dp

    private val FOUR_MINUTES = 4 * 60 * 1000L + 12_000L

    /**
     * Half a device-independent pixel, which is rounding rather than a defect.
     *
     * Typed as [Dp] because every comparison in this file is between two [Dp] bounds, and
     * a `Float` here does not add to one. It was a `Float`, and nothing said so for two
     * rounds of player work — this module was missing from the CI test list, so the file
     * was never compiled by anything that could fail.
     */
    private val TOLERANCE = 0.5.dp

    private val CONTROLS = listOf(
        PlayerTags.BACK, PlayerTags.LOCK, PlayerTags.CAST, PlayerTags.MORE,
        PlayerTags.PREVIOUS, PlayerTags.REPLAY, PlayerTags.PLAY,
        PlayerTags.FORWARD, PlayerTags.NEXT,
        PlayerTags.TIMELINE, PlayerTags.TOOLS, PlayerTags.TO_LIVE,
        PlayerTags.SPEED, PlayerTags.SUBTITLES, PlayerTags.AUDIO, PlayerTags.ASPECT,
        PlayerTags.GUIDE, PlayerTags.CHANNELS, PlayerTags.QUALITY, PlayerTags.FULLSCREEN,
        PlayerTags.EPG,
    )

    private fun ComposeContentTestRule.show(
        frame: Frame,
        device: DeviceClass,
        state: PlayerState,
        direction: LayoutDirection = LayoutDirection.Rtl,
    ) = setContent {
        CastivioTheme {
            CompositionLocalProvider(
                LocalDeviceClass provides device,
                LocalLayoutDirection provides direction,
            ) {
                Stage(frame) { PlayerScreen(state = state, actions = PlayerActions()) }
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
