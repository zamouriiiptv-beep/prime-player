package com.castivio.feature.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.clickable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.castivio.core.common.FrameSize
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.DeviceClass
import com.castivio.core.design.theme.LocalDeviceClass
import com.castivio.playback.api.AspectMode
import com.castivio.playback.api.MediaKind
import com.castivio.playback.api.PlaybackError
import com.castivio.playback.api.Track
import com.castivio.playback.api.TrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
        compose.showCrowded(HANDSET, DeviceClass.Expanded)
        compose.assertSafe(HANDSET, tv = false)
    }

    @Test
    fun `the shortest frame keeps every control inside the safe area`() {
        compose.showCrowded(SHORT, DeviceClass.Compact)
        compose.assertSafe(SHORT, tv = false)
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
        compose.showCrowded(TELEVISION, DeviceClass.Television)
        compose.assertSafe(TELEVISION, tv = true)
    }

    /**
     * The case that actually overflowed: Arabic, on a television, behind the live edge.
     *
     * Timeshift adds "Back to live" to a row that already carries the playlist, the screen's two controls
     * and, in Arabic, labels longer than the English ones the row was drawn with. The fix was to let the
     * pills give up their words rather than push the targets out -- the labels go, the targets do not --
     * and this is the test that says so.
     */
    @Test
    fun `an Arabic television in timeshift keeps its transport inside the safe area`() {
        compose.showCrowded(
            TELEVISION,
            DeviceClass.Television,
            playingLive().copy(behindLiveMs = FOUR_MINUTES),
            LayoutDirection.Rtl,
        )
        compose.assertSafe(TELEVISION, tv = true)

        val transport = compose.bounds(PlayerTags.TRANSPORT)
        val toLive = compose.bounds(PlayerTags.TO_LIVE)
        assertTrue(
            "back-to-live ${toLive.left}..${toLive.right} is outside the transport " +
                "${transport.left}..${transport.right}",
            toLive.left >= transport.left - TOLERANCE && toLive.right <= transport.right + TOLERANCE,
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
        compose.showCrowded(HANDSET, DeviceClass.Expanded)
        compose.assertTargets(PHONE_FLOOR)
    }

    @Test
    fun `every control on a television is at least the remote floor`() {
        compose.showCrowded(TELEVISION, DeviceClass.Television)
        compose.assertTargets(TV_FLOOR)
    }

    @Test
    fun `every control in timeshift is still at least the floor`() {
        compose.showCrowded(HANDSET, DeviceClass.Expanded, playingLive().copy(behindLiveMs = FOUR_MINUTES))
        compose.assertTargets(PHONE_FLOOR)
    }

    /* ------------------------------------------------------------------ the bands */

    /**
     * Two bands, in order, none of them touching -- and inside the lower one, the bar above the transport.
     *
     * The claim `SpaceBetween` is supposed to give and the one a fixed offset would break: on the 360dp
     * frame there is little enough room that a transport which grew by a row would run into the top bar,
     * and nothing about that looks wrong in a screenshot taken on a 540dp television.
     *
     * One test per frame rather than a loop, because `setContent` may be called once per rule -- a loop
     * would throw on its second turn instead of asserting anything.
     */
    @Test
    fun `the bands do not overlap on a handset`() {
        compose.showCrowded(HANDSET, DeviceClass.Expanded)
        compose.assertBands(HANDSET)
    }

    @Test
    fun `the bands do not overlap on the shortest frame`() {
        compose.showCrowded(SHORT, DeviceClass.Compact)
        compose.assertBands(SHORT)
    }

    @Test
    fun `the bands do not overlap on a television`() {
        compose.showCrowded(TELEVISION, DeviceClass.Television)
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

    /**
     * The surface is letterboxed to the picture, inside a region that still fills.
     *
     * The two halves of the aspect fix, asserted together because either alone is wrong.
     * A `SurfaceView` scales whatever the decoder writes to whatever size the view is, so
     * a surface left filling a 827x393 frame shows a 16:9 film stretched across it — which
     * is what reached a device. Sizing the view is what letterboxes it.
     *
     * The region keeps filling the frame: the black is part of the picture area, not a
     * margin around it, which is the claim `the video fills the frame` above still makes.
     */
    @Test
    fun `the surface is sized to the picture and not to the frame`() {
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingLive().copy(aspect = AspectMode.FIT, videoAspectRatio = 16f / 9f),
        )

        val region = compose.bounds(PlayerTags.VIDEO)
        val surface = compose.bounds(PlayerTags.SURFACE)

        assertEquals("the region must still be the whole frame", HANDSET.width.value, region.width.value, 0.5f)
        assertEquals(
            "827x393 is wider than 16:9, so the height is what runs out",
            region.height.value,
            surface.height.value,
            0.5f,
        )
        assertEquals(
            "and the width is what the ratio makes of that height",
            region.height.value * 16f / 9f,
            surface.width.value,
            1f,
        )
        assertTrue(
            "a letterboxed surface is narrower than its region",
            surface.width.value < region.width.value,
        )
    }

    /** A fixed ratio does not consult the source, which is the point of choosing one. */
    @Test
    fun `a chosen ratio ignores what the source declared`() {
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingLive().copy(aspect = AspectMode.RATIO_4_3, videoAspectRatio = 16f / 9f),
        )
        val surface = compose.bounds(PlayerTags.SURFACE)
        assertEquals(
            "4:3 was asked for and 4:3 is what the surface must be",
            4f / 3f,
            surface.width.value / surface.height.value,
            0.02f,
        )
    }

    /**
     * Fill takes the frame, and a shapeless source does too.
     *
     * The second half matters more than it looks: a sound file has no ratio, and a player
     * that letterboxed against a null would draw black bars around nothing.
     */
    @Test
    fun `fill and a shapeless source both take the whole frame`() {
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingLive().copy(aspect = AspectMode.FILL, videoAspectRatio = 16f / 9f),
        )
        val filled = compose.bounds(PlayerTags.SURFACE)
        assertEquals(HANDSET.width.value, filled.width.value, 0.5f)
        assertEquals(HANDSET.height.value, filled.height.value, 0.5f)
    }

    @Test
    fun `a source with no declared shape is not letterboxed`() {
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingLive().copy(aspect = AspectMode.FIT, videoAspectRatio = null),
        )
        val surface = compose.bounds(PlayerTags.SURFACE)
        assertEquals(HANDSET.width.value, surface.width.value, 0.5f)
        assertEquals(HANDSET.height.value, surface.height.value, 0.5f)
    }

    /* ------------------------------------------------------------ reaching the controls */

    /**
     * A tap on the picture is what brings the controls back.
     *
     * The defect: nothing on this screen ever called `onToggleControls`. The contract had
     * it, the auto-hide used it, and after four seconds the chrome went away with no way
     * left to reach play/pause at all.
     */
    @Test
    fun `tapping the picture asks for the controls`() {
        var asked = 0
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingLive().copy(controls = false),
            actions = PlayerActions(onToggleControls = { asked++ }),
        )

        compose.onNodeWithTag(PlayerTags.VIDEO).performClick()

        assertEquals("a tap on the picture did nothing", 1, asked)
    }

    /**
     * Except while locked, where the pill is the only way out.
     *
     * A lock a tap dismisses is not a lock.
     */
    @Test
    fun `tapping a locked picture does nothing`() {
        var asked = 0
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingLive().copy(locked = true),
            actions = PlayerActions(onToggleControls = { asked++ }),
        )

        compose.onNodeWithTag(PlayerTags.VIDEO).performClick()

        assertEquals("the lock was dismissed by a tap on the film", 0, asked)
    }

    /**
     * The jump controls reach their callback, with ten seconds in each direction.
     *
     * Written because the controls were reported dead on a device and the wiring read as
     * correct at every layer — which leaves three possibilities that look identical from a
     * photograph: the press not arriving, the source refusing to be sought, or the engine
     * accepting a position and staying put. This rules out the first at handset geometry,
     * with the chrome up and the full-screen tap target of the picture underneath it.
     *
     * On a channel that keeps a window behind it: a channel that cannot be sought draws no
     * jumps at all, which the test after this one says.
     */
    @Test
    fun `the jump controls call back with ten seconds in each direction`() {
        val jumps = mutableListOf<Long>()
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingLive().copy(seekable = true),
            LayoutDirection.Rtl,
            PlayerActions(onSeekBy = { jumps += it }),
        )

        compose.onNodeWithTag(PlayerTags.REPLAY).performClick()
        compose.onNodeWithTag(PlayerTags.FORWARD).performClick()

        assertEquals(
            "the presses did not reach the contract, or did not carry ten seconds",
            listOf(-10_000L, 10_000L),
            jumps,
        )
    }

    /* ------------------------------------------------------- controls that cannot act */

    /**
     * A channel that cannot be sought has no jumps.
     *
     * Most channels are a stream with no window behind it. There a press was counted, logged
     * as refused, and changed nothing on screen — two stops for the remote on the way along
     * the bar to nothing.
     */
    @Test
    fun `a channel that cannot be sought draws no jumps`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingLive())

        compose.onAllNodesWithTag(PlayerTags.REPLAY).assertCountEquals(0)
        compose.onAllNodesWithTag(PlayerTags.FORWARD).assertCountEquals(0)
    }

    /** A channel plays with its subtitles switched off in both engines, so no button offers a list of them. */
    @Test
    fun `a channel has no subtitles button`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingLive())

        compose.onAllNodesWithTag(PlayerTags.SUBTITLES).assertCountEquals(0)
    }

    /** And a film keeps its own. */
    @Test
    fun `a film keeps its subtitles button`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingFilm())

        compose.onNodeWithTag(PlayerTags.SUBTITLES).assertIsDisplayed()
    }

    /* ------------------------------------------------------------------- the settings */

    /** A broadcast played faster runs out of what was sent; slower, it leaves the window. */
    @Test
    fun `a channel's settings offer no speed`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingLive().copy(sheet = Sheet.Settings))

        compose.onAllNodesWithTag(PlayerTags.popoverRow("speed")).assertCountEquals(0)
    }

    /** And a film keeps its own. */
    @Test
    fun `a film's settings offer its speed`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingFilm().copy(sheet = Sheet.Settings))

        compose.onNodeWithTag(PlayerTags.popoverRow("speed")).assertIsDisplayed()
    }

    /** The same rule for the subtitles, which a channel never plays with. */
    @Test
    fun `a channel's settings offer no subtitles and a film's do`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingLive().copy(sheet = Sheet.Settings))
        compose.onAllNodesWithTag(PlayerTags.popoverRow("subtitles")).assertCountEquals(0)
        compose.onNodeWithTag(PlayerTags.popoverRow("quality")).assertIsDisplayed()
        compose.onNodeWithTag(PlayerTags.popoverRow("audio")).assertIsDisplayed()
        compose.onNodeWithTag(PlayerTags.popoverRow("aspect")).assertIsDisplayed()
    }

    @Test
    fun `a film's settings are the five rows of the reference, in its order`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingFilm().copy(sheet = Sheet.Settings))

        val tops = listOf("quality", "audio", "subtitles", "aspect", "speed")
            .map { compose.bounds(PlayerTags.popoverRow(it)).top }

        assertEquals("the rows are not in the reference's order: $tops", tops.sorted(), tops)
        assertEquals("a row repeats a place", 5, tops.distinct().size)
    }

    /**
     * The settings hang from the gear: under the top bar, at the end edge, inside the safe area.
     *
     * Asserted against the top bar's own box rather than a constant, because what is claimed is the
     * relation -- the gear stays visible above the panel it opened -- and not the number of dp between.
     */
    @Test
    fun `the settings hang under the top bar at the end edge`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingFilm().copy(sheet = Sheet.Settings))

        val top = compose.bounds(PlayerTags.TOP)
        val popover = compose.bounds(PlayerTags.POPOVER)
        val safe = compose.bounds(PlayerTags.SAFE)
        val gear = compose.bounds(PlayerTags.SETTINGS)

        assertTrue("the panel at ${popover.top} covers the top bar to ${top.bottom}", popover.top >= top.bottom - TOLERANCE)
        assertTrue("the panel ends at ${popover.right}, past the safe area ${safe.right}", popover.right <= safe.right + TOLERANCE)
        assertTrue(
            "the panel ${popover.left}..${popover.right} is not under the gear ${gear.left}..${gear.right}",
            gear.right <= popover.right + TOLERANCE && gear.right >= popover.left,
        )
    }

    @Test
    fun `the aspect row steps to the next shape`() {
        var asked: AspectMode? = null
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingFilm().copy(sheet = Sheet.Settings, aspect = AspectMode.FIT),
            LayoutDirection.Ltr,
            PlayerActions(onAspect = { asked = it }),
        )

        compose.onNodeWithTag(PlayerTags.popoverRow("aspect")).performClick()

        assertEquals(nextAspect(AspectMode.FIT), asked)
    }

    @Test
    fun `the speed row steps to the next speed`() {
        var asked: Float? = null
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingFilm().copy(sheet = Sheet.Settings, speed = 1f),
            LayoutDirection.Ltr,
            PlayerActions(onSpeed = { asked = it }),
        )

        compose.onNodeWithTag(PlayerTags.popoverRow("speed")).performClick()

        assertEquals(1.25f, asked)
    }

    @Test
    fun `the quality, sound and subtitle rows open their sheets`() {
        val opened = mutableListOf<Sheet?>()
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingFilm().copy(sheet = Sheet.Settings),
            LayoutDirection.Ltr,
            PlayerActions(onSheet = { opened += it }),
        )

        compose.onNodeWithTag(PlayerTags.popoverRow("quality")).performClick()
        compose.onNodeWithTag(PlayerTags.popoverRow("audio")).performClick()
        compose.onNodeWithTag(PlayerTags.popoverRow("subtitles")).performClick()

        assertEquals(listOf<Sheet?>(Sheet.Quality, Sheet.Audio, Sheet.Subtitles), opened)
    }

    /** The rows say what each setting is now, in the words the reference uses. */
    @Test
    fun `the rows say what each setting is now`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingFilm().copy(sheet = Sheet.Settings))

        compose.onNodeWithText(text(R.string.player_quality_auto)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.player_setting_off)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.player_aspect_fit)).assertIsDisplayed()
        compose.onNodeWithText(speedLabel(1f)).assertIsDisplayed()
    }

    /** The gear that opened the panel closes it, so the viewer is never looking for a way out. */
    @Test
    fun `the gear closes the settings it opened`() {
        val seen = mutableListOf<Sheet?>()
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingFilm().copy(sheet = Sheet.Settings),
            LayoutDirection.Ltr,
            PlayerActions(onSheet = { seen += it }),
        )

        compose.onNodeWithTag(PlayerTags.SETTINGS).performClick()

        assertEquals(listOf<Sheet?>(null), seen)
    }

    /* --------------------------------------------------------------------- the transport */

    /**
     * The play control is on the centre line of the bar, whatever the two ends hold.
     *
     * Previous and next, and the jumps, are either side of it and the playlist is at one end with the
     * screen's two controls at the other. The three groups share the row by weight, so the middle one is
     * the middle -- and this is the claim a row of `SpaceBetween` would have broken the first time the ends
     * held different numbers of things.
     */
    @Test
    fun `the play control is on the centre line of the transport`() {
        compose.showCrowded(HANDSET, DeviceClass.Expanded, playingFilm())

        val transport = compose.bounds(PlayerTags.TRANSPORT)
        val play = compose.bounds(PlayerTags.PLAY)

        assertEquals(
            "the play control is not in the middle of the transport",
            (transport.left + transport.right).value / 2f,
            (play.left + play.right).value / 2f,
            1f,
        )
    }

    @Test
    fun `the jumps sit either side of the play control in English`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingFilm(), LayoutDirection.Ltr)
        compose.assertJumpsFlankPlay()
    }

    /** And the same in Arabic: the player is laid out left to right in every language, so nothing mirrors. */
    @Test
    fun `the jumps sit either side of the play control in Arabic`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingFilm(), LayoutDirection.Rtl)
        compose.assertJumpsFlankPlay()
    }

    private fun ComposeContentTestRule.assertJumpsFlankPlay() {
        val back = bounds(PlayerTags.REPLAY)
        val play = bounds(PlayerTags.PLAY)
        val on = bounds(PlayerTags.FORWARD)

        assertTrue("back is not before the play control: ${back.right} against ${play.left}", back.right <= play.left + TOLERANCE)
        assertTrue("forward is not after the play control: ${on.left} against ${play.right}", on.left >= play.right - TOLERANCE)
    }

    /**
     * The steps are drawn where there is a list to step through, and only there.
     *
     * Previous and next once stood either side of the play control with nothing behind them -- no caller
     * ever gave the route a channel to step to. They are the channel keys' own, on the screen.
     */
    @Test
    fun `previous and next are drawn only with a list to step through`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingLive())

        compose.onAllNodesWithTag(PlayerTags.PREVIOUS).assertCountEquals(0)
        compose.onAllNodesWithTag(PlayerTags.NEXT).assertCountEquals(0)
    }

    @Test
    fun `previous and next step one place each way`() {
        val steps = mutableListOf<Int>()
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingLive(),
            LayoutDirection.Rtl,
            PlayerActions(onStep = { steps += it }),
        )

        compose.onNodeWithTag(PlayerTags.PREVIOUS).performClick()
        compose.onNodeWithTag(PlayerTags.NEXT).performClick()

        assertEquals("the presses did not carry one place each way", listOf(-1, 1), steps)
    }

    /**
     * The playlist pill is drawn where there is a list of channels to open, and only there.
     *
     * A film has none. And the top bar has no list icon at all now: it did what Back does, and the owner
     * asked for the one way into the list to be this pill.
     */
    @Test
    fun `the playlist pill is drawn only with a list to open`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingFilm())

        compose.onAllNodesWithTag(PlayerTags.PLAYLIST).assertCountEquals(0)
    }

    @Test
    fun `the playlist pill opens the list`() {
        var opened = 0
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingLive(),
            LayoutDirection.Ltr,
            PlayerActions(onPlaylist = { opened++ }),
        )

        compose.onNodeWithTag(PlayerTags.PLAYLIST).performClick()

        assertEquals(1, opened)
    }

    /* ------------------------------------------------------------- the list of channels */

    /** A stand-in for the shell's rows: it says how long the list is and draws two rows that choose. */
    private val rows: ChannelListContent = { rowHeight, onTotal, onClose ->
        LaunchedEffect(Unit) { onTotal(82) }
        Column {
            for (name in listOf("a", "b")) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .clickable { onClose() }
                        .testTag("row.$name"),
                )
            }
        }
    }

    @Test
    fun `the list opens as a panel with its title, its count and its rows`() {
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingLive().copy(sheet = Sheet.Playlist),
            LayoutDirection.Ltr,
            channelList = rows,
        )

        compose.onNodeWithTag(PlayerTags.PLAYLIST_PANEL).assertIsDisplayed()
        compose.onNodeWithTag(PlayerTags.PLAYLIST_COUNT).assertTextEquals(text(R.string.player_playlist_count).format("82"))
        compose.onNodeWithTag("row.a").assertIsDisplayed()
    }

    /** No rows given, no panel: a list nobody filled is not left standing open and empty. */
    @Test
    fun `without rows the list draws nothing`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingLive().copy(sheet = Sheet.Playlist), LayoutDirection.Ltr)

        compose.onAllNodesWithTag(PlayerTags.PLAYLIST_PANEL).assertCountEquals(0)
    }

    /**
     * The panel is at the end edge, inside the safe area, as wide as the design says, and leaves the pill
     * that opened it -- and the way back -- clear.
     */
    @Test
    fun `the list is at the end edge and leaves the pill and the way back clear`() {
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingLive().copy(sheet = Sheet.Playlist),
            LayoutDirection.Ltr,
            PlayerActions(onPlaylist = {}),
            channelList = rows,
        )

        val root = compose.bounds(PlayerTags.ROOT)
        val panel = compose.bounds(PlayerTags.PLAYLIST_PANEL)
        val pill = compose.bounds(PlayerTags.PLAYLIST)
        val back = compose.bounds(PlayerTags.BACK)
        val expected = playerMetricsFor(false, HANDSET.width, HANDSET.height).playlist

        assertEquals("the panel is not at the end edge", root.right.value, panel.right.value, 0.5f)
        assertEquals("the panel is not the width the design gives it", expected.value, panel.width.value, 0.5f)
        assertTrue("the pill ${pill.left}..${pill.right} is under the panel from ${panel.left}", pill.right <= panel.left + TOLERANCE)
        assertTrue("the way back is under the panel", back.right <= panel.left + TOLERANCE)
    }

    @Test
    fun `the mark in the panel closes it`() {
        val seen = mutableListOf<Sheet?>()
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingLive().copy(sheet = Sheet.Playlist),
            LayoutDirection.Ltr,
            PlayerActions(onSheet = { seen += it }),
            channelList = rows,
        )

        compose.onNodeWithTag(PlayerTags.PLAYLIST_CLOSE).performClick()

        assertEquals(listOf<Sheet?>(null), seen)
    }

    /** A row that chooses closes the list by the callback it was handed: the channel is what is left on screen. */
    @Test
    fun `a chosen row asks for the panel to close`() {
        val seen = mutableListOf<Sheet?>()
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingLive().copy(sheet = Sheet.Playlist),
            LayoutDirection.Ltr,
            PlayerActions(onSheet = { seen += it }),
            channelList = rows,
        )

        compose.onNodeWithTag("row.b").performClick()

        assertEquals(listOf<Sheet?>(null), seen)
    }

    /** A list of one says so in a line instead of showing its single row. */
    @Test
    fun `a list of one says there is nowhere else to go`() {
        val one: ChannelListContent = { _, onTotal, _ ->
            LaunchedEffect(Unit) { onTotal(1) }
        }
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingLive().copy(sheet = Sheet.Playlist),
            LayoutDirection.Ltr,
            channelList = one,
        )

        compose.onNodeWithTag(PlayerTags.PLAYLIST_ALONE).assertIsDisplayed()
    }

    /** The panel sits on the television's inset-free end edge too, and keeps its floor for the remote. */
    @Test
    fun `the list's mark is at least the floor on a television`() {
        compose.show(
            TELEVISION,
            DeviceClass.Television,
            playingLive().copy(sheet = Sheet.Playlist),
            LayoutDirection.Ltr,
            channelList = rows,
        )

        val mark = compose.bounds(PlayerTags.PLAYLIST_CLOSE)
        assertTrue("the mark is ${mark.width}", mark.width >= TV_FLOOR - TOLERANCE && mark.height >= TV_FLOOR - TOLERANCE)
    }

    /** One press fills the screen with the picture and the next fits it back. */
    @Test
    fun `fill takes the whole screen and the next press fits it back`() {
        val asked = mutableListOf<AspectMode>()
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingFilm().copy(aspect = AspectMode.FIT),
            LayoutDirection.Ltr,
            PlayerActions(onAspect = { asked += it }),
        )

        compose.onNodeWithTag(PlayerTags.FILL).performClick()

        assertEquals(listOf(AspectMode.FILL), asked)
    }

    @Test
    fun `fill on a filled screen fits it back`() {
        val asked = mutableListOf<AspectMode>()
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingFilm().copy(aspect = AspectMode.FILL),
            LayoutDirection.Ltr,
            PlayerActions(onAspect = { asked += it }),
        )

        compose.onNodeWithTag(PlayerTags.FILL).performClick()

        assertEquals(listOf(AspectMode.FIT), asked)
    }

    /* ----------------------------------------------------------------- the top bar */

    /**
     * The heart is drawn where there is an item to mark, and says which way it will turn.
     *
     * Not on a file from the device, which is nobody's favourite; the shell gives the route a value only
     * for a channel and for a film started from its page.
     */
    @Test
    fun `the heart is drawn only where there is something to mark`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingFilm())

        compose.onAllNodesWithTag(PlayerTags.FAVORITE).assertCountEquals(0)
    }

    @Test
    fun `the heart marks and says it will unmark`() {
        var pressed = 0
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingLive().copy(favorite = true),
            LayoutDirection.Ltr,
            PlayerActions(onFavorite = { pressed++ }),
        )

        compose.onNodeWithContentDescription(text(R.string.player_favorite_remove)).performClick()

        assertEquals(1, pressed)
        compose.onAllNodesWithContentDescription(text(R.string.player_favorite_add)).assertCountEquals(0)
    }

    @Test
    fun `an unmarked heart offers to mark`() {
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingLive().copy(favorite = false),
            LayoutDirection.Ltr,
            PlayerActions(onFavorite = {}),
        )

        compose.onNodeWithContentDescription(text(R.string.player_favorite_add)).assertIsDisplayed()
    }

    /** The reference is drawn left to right and so is the player, in Arabic too: the way back at the left, the gear at the right. */
    @Test
    fun `the top bar reads left to right in Arabic`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingFilm(), LayoutDirection.Rtl)

        val back = compose.bounds(PlayerTags.BACK)
        val title = compose.bounds(PlayerTags.TITLE)
        val more = compose.bounds(PlayerTags.MORE)

        assertTrue("the way back is not at the left: ${back.left} against ${title.left}", back.right <= title.left + TOLERANCE)
        assertTrue("the rest is not at the right: ${more.left} against ${title.right}", more.left >= title.right - TOLERANCE)
    }

    /**
     * What is under the title is what the engine has said, and the live pill when it is a channel.
     *
     * A stream that has shown nothing has no words about itself: a `FHD` that was the last channel's would
     * be worse than none.
     */
    @Test
    fun `the words under the title are only what is known`() {
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingLive().copy(videoSize = FrameSize(1920, 1080), videoAspectRatio = 16f / 9f),
            LayoutDirection.Ltr,
        )

        compose.onNodeWithText("FHD").assertIsDisplayed()
        compose.onNodeWithText("16:9").assertIsDisplayed()
        compose.onAllNodesWithText("H.264").assertCountEquals(0)
        compose.onAllNodesWithText("5.1").assertCountEquals(0)
    }

    @Test
    fun `a stream that has shown nothing has no words about itself`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingFilm(), LayoutDirection.Ltr)

        compose.onAllNodesWithText("FHD").assertCountEquals(0)
        compose.onAllNodesWithText("HD").assertCountEquals(0)
    }

    /**
     * A row that has run out of width drops whole chips and never shows a piece of one.
     *
     * It showed `2.0` as a `2` in a box with its edge sliced off, which reads as a fault. Measured as a
     * containment claim on every chip that is on the screen: each one lies inside the row.
     */
    @Test
    fun `a narrow top bar drops whole chips and never cuts one`() {
        compose.show(
            NARROW,
            DeviceClass.Compact,
            playingFilm().copy(
                videoSize = FrameSize(1920, 1080),
                videoAspectRatio = 16f / 9f,
                videoTracks = listOf(Track("v", TrackType.VIDEO, "AVC", selected = true)),
                audioTracks = listOf(Track("a", TrackType.AUDIO, "English", language = "en", channels = 2, selected = true)),
            ),
            LayoutDirection.Ltr,
        )

        val row = compose.bounds(PlayerTags.CHIPS)
        compose.onNodeWithText("FHD").assertIsDisplayed()
        val cut = mutableListOf<String>()
        for (word in listOf("FHD", "H.264", "16:9", "2.0", "ENG")) {
            val nodes = compose.onAllNodesWithText(word)
            // A chip the row left out is measured and never placed: it is not on the screen.
            if (nodes.fetchSemanticsNodes().firstOrNull()?.layoutInfo?.isPlaced != true) continue
            val box = nodes[0].getUnclippedBoundsInRoot()
            if (box.right > row.right + TOLERANCE) cut += "$word ${box.left}..${box.right} of ${row.right}"
        }
        assertTrue("a chip runs out of the row: $cut", cut.isEmpty())
    }

    /**
     * Share is the rest's, behind the three dots, now that the top bar holds the controls the reference
     * draws. Pressed there it still shares what is playing -- something this product can do today.
     */
    @Test
    fun `the three dots hold the share and it shares what is playing`() {
        var shared = 0
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingFilm().copy(sheet = Sheet.More),
            LayoutDirection.Rtl,
            PlayerActions(onShare = { shared++ }),
        )

        compose.onNodeWithText(text(R.string.player_share)).performClick()

        assertEquals("the share row is still a picture", 1, shared)
    }

    /** And the statistics are there too, so nothing the old settings offered was lost on the way. */
    @Test
    fun `the three dots hold the statistics`() {
        var asked = false
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingFilm().copy(sheet = Sheet.More),
            LayoutDirection.Ltr,
            PlayerActions(onStatistics = { asked = it }),
        )

        compose.onNodeWithText(text(R.string.player_statistics)).performClick()

        assertTrue(asked)
    }

    /* ---------------------------------------------------------------- the jump mark */

    /**
     * A jump says so on the picture.
     *
     * Ten seconds inside a long take changes almost nothing on screen and moves the head on
     * the bar by a hair, so a viewer looking at the picture — which is where they are
     * looking — cannot tell the control worked. The mark is the only proof they get, and
     * its absence is most of what "the buttons do nothing" felt like.
     */
    @Test
    fun `a jump leaves a mark on the picture`() {
        // The clock is stopped for the same reason the loading test stops it: the mark
        // takes itself away after 900ms, and an assertion that waits for the composition
        // to be idle would be waiting for exactly that. One frame is the state being read.
        compose.mainClock.autoAdvance = false
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingFilm().copy(lastJumpMs = 10_000, seekRequests = 1),
        )
        compose.mainClock.advanceTimeByFrame()

        compose.onAllNodesWithTag(PlayerTags.JUMP_MARK).assertCountEquals(1)
    }

    /** And it takes itself away, so it is not a label sitting over the film. */
    @Test
    fun `the mark goes away on its own`() {
        compose.mainClock.autoAdvance = false
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingFilm().copy(lastJumpMs = -10_000, seekRequests = 1),
        )
        compose.mainClock.advanceTimeByFrame()
        compose.onAllNodesWithTag(PlayerTags.JUMP_MARK).assertCountEquals(1)

        compose.mainClock.advanceTimeBy(2_000)

        compose.onAllNodesWithTag(PlayerTags.JUMP_MARK).assertCountEquals(0)
    }

    /** And a film nobody has jumped in carries no mark at all. */
    @Test
    fun `a film that has not been jumped carries no mark`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingFilm())
        compose.onAllNodesWithTag(PlayerTags.JUMP_MARK).assertCountEquals(0)
    }

    /**
     * The chrome is laid out inside the picture, not inside the window.
     *
     * On a 21:9 phone showing a 16:9 film the safe area filled the screen, so the clock at
     * each end of the timeline sat in the black pillar beside the picture — text floating
     * in the letterbox, pointing at nothing. Asserted on the two clocks rather than on the
     * safe box alone, because they are the elements that reach furthest into the bars and
     * the ones that were actually seen to be wrong.
     */
    @Test
    fun `the controls stay inside a letterboxed picture`() {
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingFilm().copy(aspect = AspectMode.FIT, videoAspectRatio = 16f / 9f),
        )

        val surface = compose.bounds(PlayerTags.SURFACE)
        val safe = compose.bounds(PlayerTags.SAFE)
        val position = compose.bounds(PlayerTags.POSITION)
        val duration = compose.bounds(PlayerTags.DURATION)

        assertTrue(
            "the frame is not letterboxed, so this test proves nothing: ${surface.width} of ${HANDSET.width}",
            surface.width < HANDSET.width - TOLERANCE,
        )
        assertTrue(
            "the safe area ${safe.left}..${safe.right} is outside the picture " +
                "${surface.left}..${surface.right}",
            safe.left >= surface.left - TOLERANCE && safe.right <= surface.right + TOLERANCE,
        )
        for ((name, box) in listOf("position" to position, "duration" to duration)) {
            assertTrue(
                "the $name clock at ${box.left}..${box.right} is in the black bar",
                box.left >= surface.left - TOLERANCE && box.right <= surface.right + TOLERANCE,
            )
        }
    }

    /** And when nothing is letterboxed the chrome is the whole frame, exactly as before. */
    @Test
    fun `an unletterboxed picture keeps the chrome on the whole frame`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingFilm())
        compose.assertSafe(HANDSET, tv = false)
    }

    /* ------------------------------------------------------------------ the scrubber */

    /**
     * The bar can be dragged, and it seeks to where the finger was let go.
     *
     * The bar was a read-out: it showed where the film was and offered no way to move it,
     * so the only way to reach a different part of an hour-long file was to press a
     * ten-second control six times a minute. This is the control that replaces that, and
     * the claim is the one that matters — the position asked for is the position under the
     * finger, not where the drag started.
     */
    @Test
    fun `dragging the bar seeks to where the finger was let go`() {
        var sought: Long? = null
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingFilm(),
            LayoutDirection.Ltr,
            PlayerActions(onSeekTo = { sought = it }),
        )

        compose.onNodeWithTag(PlayerTags.TIMELINE).performTouchInput {
            down(Offset(width * 0.2f, height / 2f))
            moveTo(Offset(width * 0.75f, height / 2f))
            up()
        }

        assertEquals(
            "the seek followed the start of the drag rather than its end",
            FILM_LENGTH * 0.75f,
            sought?.toFloat() ?: -1f,
            FILM_LENGTH * 0.03f,
        )
    }

    /**
     * And in Arabic the bar still fills from the left: the player is drawn one way in every language.
     *
     * It was mirrored, and a drag toward the right-hand edge in Arabic was a drag toward the *beginning* of
     * the film. The reference is drawn left to right and so is every video player a viewer has used, so the
     * same touch means the same time whatever language the words are in.
     */
    @Test
    fun `dragging the bar in Arabic seeks from the left-hand edge`() {
        var sought: Long? = null
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingFilm(),
            LayoutDirection.Rtl,
            PlayerActions(onSeekTo = { sought = it }),
        )

        compose.onNodeWithTag(PlayerTags.TIMELINE).performTouchInput {
            down(Offset(width * 0.75f, height / 2f))
            up()
        }

        assertEquals(
            "three quarters in from the left-hand edge is three quarters into the film",
            FILM_LENGTH * 0.75f,
            sought?.toFloat() ?: -1f,
            FILM_LENGTH * 0.03f,
        )
    }

    /**
     * A channel at its live edge draws no figure at the start of the bar -- nothing in the middle of the
     * screen says "live now" -- and one that is behind it says how far.
     */
    @Test
    fun `a channel at its live edge draws no figure and one behind it says how far`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingLive())
        compose.onAllNodesWithTag(PlayerTags.POSITION).assertCountEquals(0)
    }

    @Test
    fun `a channel behind its live edge says how far`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingLive().copy(behindLiveMs = FOUR_MINUTES))
        compose.onNodeWithTag(PlayerTags.POSITION).assertIsDisplayed()
    }

    /**
     * The figure at the end of the bar is what is left, as the reference draws it: `2:17:27` and `43:05`
     * on a film three hours long. A length says how big the film is; a remainder says how much of it there is
     * still to watch, which is what a viewer looks at the end of a bar to know.
     */
    @Test
    fun `the figure at the end of the bar is the time left`() {
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingFilm().copy(positionMs = (FILM_LENGTH / 2).toLong()),
        )

        compose.onNodeWithTag(PlayerTags.DURATION).assertTextEquals("45:00")
        compose.onNodeWithTag(PlayerTags.POSITION).assertTextEquals("45:00")
    }

    /** A press without a drag is a seek too: a tap on the bar puts the head where you tapped. */
    @Test
    fun `a tap on the bar seeks to that point`() {
        var sought: Long? = null
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingFilm(),
            LayoutDirection.Ltr,
            PlayerActions(onSeekTo = { sought = it }),
        )

        compose.onNodeWithTag(PlayerTags.TIMELINE).performTouchInput {
            down(Offset(width * 0.5f, height / 2f))
            up()
        }

        assertEquals(FILM_LENGTH * 0.5f, sought?.toFloat() ?: -1f, FILM_LENGTH * 0.03f)
    }

    /**
     * The head is on a film and not on a live channel.
     *
     * Live has no future to scrub into. A head sitting at the end of a full bar would invite
     * a drag that can only ever fail, and a control that cannot work is worse than no
     * control — it is the same reasoning that keeps the backup-engine button off the DRM
     * card.
     */
    @Test
    fun `a film has a head on the bar and a live channel does not`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingFilm())
        compose.onAllNodesWithTag(PlayerTags.THUMB).assertCountEquals(1)
    }

    @Test
    fun `a live channel has no head to drag`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingLive())
        compose.onAllNodesWithTag(PlayerTags.THUMB).assertCountEquals(0)
    }

    /**
     * The bar is reachable by a thumb even though the line is four device pixels.
     *
     * The visible bar and the region that answers a touch are deliberately different sizes.
     * A 4dp target is a twelfth of the touch floor and would be missed more often than hit,
     * which is exactly how a slider that "does not work" is built by accident.
     */
    @Test
    fun `the bar is at least the touch floor to press`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingFilm())
        val bar = compose.bounds(PlayerTags.TIMELINE)
        assertTrue(
            "the bar answers touches in a ${bar.height} strip, under the $PHONE_FLOOR floor",
            bar.height >= PHONE_FLOOR - TOLERANCE,
        )
    }

    /**
     * A tap on the film closes an open sheet, and closes nothing else.
     *
     * The defect: a sheet had exactly one way out, its close icon. A tap on the picture
     * beside it toggled the chrome *behind* the sheet and left the sheet where it was, which
     * on a handset — where the sheet covers half the screen and the icon is a small target
     * in a corner — reads as a panel that will not go away.
     *
     * The tap is taken on the picture side rather than at the node's centre, which on a
     * handset is under the sheet. In `Ltr` the sheet is at the end edge, so a tenth of the
     * way across is film.
     */
    @Test
    fun `tapping the film closes an open sheet instead of toggling the controls`() {
        var closed = 0
        var toggled = 0
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingLive().copy(sheet = Sheet.Audio),
            LayoutDirection.Ltr,
            PlayerActions(
                onToggleControls = { toggled++ },
                onSheet = { if (it == null) closed++ },
            ),
        )

        compose.tapTheFilm()

        assertEquals("the sheet is still open", 1, closed)
        assertEquals(
            "the tap went to the chrome behind the sheet instead of to the sheet",
            0,
            toggled,
        )
    }

    /** The statistics panel is on the same ladder, innermost first, exactly as back is. */
    @Test
    fun `tapping the film closes the statistics panel first`() {
        var closed = 0
        var toggled = 0
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingLive().copy(statistics = true, sheet = Sheet.Audio),
            LayoutDirection.Ltr,
            PlayerActions(
                onToggleControls = { toggled++ },
                onStatistics = { if (!it) closed++ },
            ),
        )

        compose.tapTheFilm()

        assertEquals("the panel over everything else was not the one dismissed", 1, closed)
        assertEquals(0, toggled)
    }

    /**
     * And with nothing over the picture the tap is still the way the chrome comes back.
     *
     * The regression the ladder above could introduce, asserted from the same tap position
     * as the two before it so that the three differ only in what is open.
     */
    @Test
    fun `tapping the film with nothing open still asks for the controls`() {
        var toggled = 0
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingLive().copy(controls = false),
            LayoutDirection.Ltr,
            PlayerActions(onToggleControls = { toggled++ }),
        )

        compose.tapTheFilm()

        assertEquals(1, toggled)
    }

    /** A press on the picture, clear of the sheet at the end edge. */
    private fun ComposeContentTestRule.tapTheFilm() {
        onNodeWithTag(PlayerTags.VIDEO).performTouchInput {
            click(Offset(width * FILM_SIDE, height / 2f))
        }
    }

    /* ------------------------------------------------------------------ the captions */

    /**
     * The words are on the screen.
     *
     * The claim this whole layer exists for, and it is worth stating baldly: before it,
     * selecting a subtitle track did select it, the decoder decoded it, and nothing
     * appeared — this screen has no `PlayerView` and therefore no `SubtitleView`.
     */
    @Test
    fun `caption lines are drawn`() {
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingFilm().copy(cues = listOf("مرحبًا", "كيف حالك؟")),
        )

        compose.onNodeWithTag(PlayerTags.CAPTIONS).assertIsDisplayed()
        compose.onNodeWithText("مرحبًا").assertIsDisplayed()
        compose.onNodeWithText("كيف حالك؟").assertIsDisplayed()
    }

    /** And a film with nothing to say draws no layer at all, not an empty box. */
    @Test
    fun `a film with no caption draws nothing`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingFilm())
        compose.onAllNodesWithTag(PlayerTags.CAPTIONS).assertCountEquals(0)
    }

    /**
     * A caption never sits under the controls.
     *
     * The one placement claim that matters: the bottom band carries a timeline and a row of
     * buttons, and words behind them are words nobody can read. Measured against the tools
     * row rather than against a constant, so a control added to that row tomorrow moves the
     * caption rather than quietly ending up on top of it.
     */
    @Test
    fun `a caption clears the controls while they are up`() {
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingFilm().copy(cues = listOf("مرحبًا"), controls = true),
        )

        val caption = compose.bounds(PlayerTags.CAPTIONS)
        val bottom = compose.bounds(PlayerTags.BOTTOM)

        assertTrue(
            "the caption at ${caption.bottom} runs into the controls at ${bottom.top}",
            caption.bottom <= bottom.top + TOLERANCE,
        )
    }

    /** And drops back down when they hide, rather than leaving a gap for nothing. */
    @Test
    fun `a caption drops when the controls hide`() {
        val controls = mutableStateOf(true)
        compose.setContent {
            CastivioTheme {
                CompositionLocalProvider(
                    LocalDeviceClass provides DeviceClass.Expanded,
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                ) {
                    Stage(HANDSET) {
                        PlayerScreen(
                            state = playingFilm().copy(
                                cues = listOf("مرحبًا"),
                                controls = controls.value,
                            ),
                            actions = PlayerActions(),
                        )
                    }
                }
            }
        }

        val lifted = compose.bounds(PlayerTags.CAPTIONS)
        controls.value = false
        compose.waitForIdle()
        val resting = compose.bounds(PlayerTags.CAPTIONS)

        assertTrue(
            "the caption stayed at ${lifted.top} with nothing above it to clear",
            resting.top > lifted.top + TOLERANCE,
        )
    }

    /**
     * The caption takes no touches.
     *
     * A caption that swallowed a tap would stop the controls appearing at the exact moment
     * there are words on screen and the viewer wants to pause and read them.
     */
    @Test
    fun `tapping a caption still reaches the picture`() {
        var toggled = 0
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingFilm().copy(cues = listOf("مرحبًا"), controls = false),
            LayoutDirection.Ltr,
            PlayerActions(onToggleControls = { toggled++ }),
        )

        // Where a caption sits with the chrome down: clear of the bottom edge by its own
        // margin, which on this frame is nine tenths of the way down.
        compose.onNodeWithTag(PlayerTags.VIDEO).performTouchInput {
            click(Offset(width / 2f, height * CAPTION_BAND))
        }

        assertEquals("the caption ate the tap", 1, toggled)
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
        compose.onAllNodesWithTag(PlayerTags.TRANSPORT).assertCountEquals(0)
        compose.onAllNodesWithTag(PlayerTags.BOTTOM).assertCountEquals(0)
        compose.onAllNodesWithTag(PlayerTags.STATISTICS).assertCountEquals(0)
    }

    /**
     * An opening lays the player's ground over the whole surface.
     *
     * The surface goes on showing the last frame it was given -- on a channel change, the
     * channel just left -- so the opening covers it rather than leaving it under the new name.
     * Covered and not removed: the surface is what the engine draws into, and it is still
     * there under the ground.
     */
    @Test
    fun `an opening covers the surface with the player's ground`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingLive().copy(picture = Picture.Opening))

        compose.onAllNodesWithTag(PlayerTags.SURFACE).assertCountEquals(1)
        compose.assertCovers(PlayerTags.COVER, PlayerTags.SURFACE)
    }

    /** The board's plate is where channels are changed, and it is covered the same way. */
    @Test
    fun `the board's plate is covered the same way`() {
        compose.setContent {
            CastivioTheme {
                CompositionLocalProvider(LocalDeviceClass provides DeviceClass.Expanded) {
                    Stage(PLATE) {
                        PlayerScreen(
                            state = playingLive().copy(picture = Picture.Opening),
                            actions = PlayerActions(),
                            mode = PlayerMode.Compact,
                        )
                    }
                }
            }
        }

        compose.assertCovers(PlayerTags.COVER, PlayerTags.SURFACE)
    }

    /**
     * A locked player that is opening -- the backup taking over a picture that failed -- still
     * covers the frame it no longer has, under the way out of the lock.
     */
    @Test
    fun `a locked opening is covered too`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingLive().copy(picture = Picture.Opening, locked = true))

        compose.onAllNodesWithTag(PlayerTags.LOCK_PILL).assertCountEquals(1)
        compose.assertCovers(PlayerTags.COVER, PlayerTags.SURFACE)
    }

    /** A picture that has arrived has nothing laid over it. */
    @Test
    fun `a playing picture has no ground over it`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingLive())

        compose.onAllNodesWithTag(PlayerTags.COVER).assertCountEquals(0)
    }

    /** The first second of an opening is the title on the player's ground, and nothing turning. */
    @Test
    fun `the first second of an opening shows the title and no spinner`() {
        compose.show(HANDSET, DeviceClass.Expanded, playingLive().copy(picture = Picture.Opening, longWait = false))

        compose.onAllNodesWithTag(PlayerTags.TITLE).assertCountEquals(1)
        compose.onAllNodesWithTag(PlayerTags.TOAST).assertCountEquals(0)
    }

    /**
     * An opening that has gone on says so: the spinner and its line, still over the ground.
     *
     * The clock is stopped for the same reason as above: an indefinite spinner never settles.
     */
    @Test
    fun `a long opening shows the spinner and its line over the ground`() {
        compose.mainClock.autoAdvance = false
        compose.show(HANDSET, DeviceClass.Expanded, playingLive().copy(picture = Picture.Opening, longWait = true))
        compose.mainClock.advanceTimeByFrame()

        compose.onAllNodesWithTag(PlayerTags.TOAST).assertCountEquals(1)
        compose.onAllNodesWithTag(PlayerTags.COVER).assertCountEquals(1)
    }

    private fun ComposeContentTestRule.assertCovers(over: String, under: String) {
        val cover = bounds(over)
        val surface = bounds(under)
        assertTrue(
            "the ground $cover does not cover the surface $surface",
            cover.left <= surface.left + TOLERANCE && cover.top <= surface.top + TOLERANCE &&
                cover.right >= surface.right - TOLERANCE && cover.bottom >= surface.bottom - TOLERANCE,
        )
    }

    /**
     * The statistics panel is on the side opposite the title.
     *
     * A correction that came out of the drawing: at the start edge it covered the title. The player is laid
     * out left to right in every language now, so the title is at the left and the panel at the right in
     * Arabic as in English -- asserted in both, because a panel pinned to one physical side passes in
     * whichever language it was checked in.
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
            "Arabic: the panel at ${panel.left} is not right of the title at ${title.left}",
            panel.left > title.left,
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
        val bottom = bounds(PlayerTags.BOTTOM)
        val timeline = bounds(PlayerTags.TIMELINE)
        val transport = bounds(PlayerTags.TRANSPORT)

        println(
            "player ${frame.width}x${frame.height} — top ${top.height} bottom ${bottom.height} " +
                "(bar ${timeline.height}, transport ${transport.height}) | free ${bottom.top - top.bottom}",
        )

        assertTrue(
            "${frame.width}: the bottom band runs into the top bar — ${top.bottom} against ${bottom.top}",
            bottom.top >= top.bottom - TOLERANCE,
        )
        assertTrue(
            "${frame.width}: the transport runs into the bar — ${timeline.bottom} against ${transport.top}",
            transport.top >= timeline.bottom - TOLERANCE,
        )
    }

    private fun ComposeContentTestRule.assertSafe(frame: Frame, tv: Boolean) {
        val safe = bounds(PlayerTags.SAFE)
        val root = bounds(PlayerTags.ROOT)

        // Derived from the surface the player was composed into, not from a constant.
        //
        // It was `24dp on a phone, 48 on a television` — `DeviceClass.screenPadding`,
        // the last device table on this screen. The inset is the stage's own margin
        // now, so a test that pinned either number would be pinning the table this
        // migration removed rather than the property the inset has: that every control
        // stays inside it, which is what the rest of this function asserts.
        val expectedInset = playerMetricsFor(tv, frame.width, frame.height).inset

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

    /** A film of a known length, which is what makes the bar something to drag. */
    private fun playingFilm() = PlayerState(
        request = PlayerRequest(
            url = "content://media/external/video/media/7",
            title = "الطريق إلى شفشاون",
            kind = MediaKind.VOD,
        ),
        picture = Picture.Playing,
        positionMs = 0,
        durationMs = FILM_LENGTH.toLong(),
        seekable = true,
    )

    /** A live channel, playing, with the guide not yet in. The commonest state there is. */
    private fun playingLive() = PlayerState(
        request = PlayerRequest(
            url = "http://provider.tv/live/1.ts",
            title = "الأولى المغربية",
            kind = MediaKind.LIVE,
            subtitle = "باقة المغرب",
            channelNumber = "104",
            catchUpHours = 48,
        ),
        picture = Picture.Playing,
    )

    private data class Frame(val width: Dp, val height: Dp)

    /** The three the layout contract names, and the insets each one takes. */
    private val HANDSET = Frame(827.dp, 393.dp)
    private val SHORT = Frame(800.dp, 360.dp)
    private val TELEVISION = Frame(960.dp, 540.dp)

    /** Narrower than any handset in landscape, so the top bar has to choose what to leave out. */
    private val NARROW = Frame(640.dp, 360.dp)

    /** The preview plate on the Channels board, where channels are changed. */
    private val PLATE = Frame(300.dp, 169.dp)

    private val PHONE_FLOOR = 48.dp
    private val TV_FLOOR = 56.dp

    private val FOUR_MINUTES = 4 * 60 * 1000L + 12_000L

    /** Ninety minutes, as a `Float` because every scrubber assertion is a fraction of it. */
    private val FILM_LENGTH = 90f * 60f * 1000f

    /**
     * A tenth of the way across the picture.
     *
     * Far enough from the end edge to be film rather than sheet — the sheet takes 52% of a
     * handset — and far enough from the start edge to be clear of the back arrow.
     */
    private val FILM_SIDE = 0.1f

    /** Nine tenths down the picture, which is where a caption rests with the chrome down. */
    private val CAPTION_BAND = 0.88f

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
        PlayerTags.BACK, PlayerTags.FAVORITE, PlayerTags.AUDIO, PlayerTags.SUBTITLES,
        PlayerTags.SETTINGS, PlayerTags.MORE,
        PlayerTags.PLAYLIST, PlayerTags.PREVIOUS, PlayerTags.REPLAY, PlayerTags.PLAY, PlayerTags.FORWARD,
        PlayerTags.NEXT, PlayerTags.TO_LIVE, PlayerTags.FILL, PlayerTags.LOCK,
        PlayerTags.TIMELINE, PlayerTags.CHIPS,
    )

    /**
     * Every control the screen can draw at once: a channel that was pressed in a list, with somewhere to go
     * back to, and a heart to turn. What the safe area, the floors and the bands have to hold for.
     */
    private fun ComposeContentTestRule.showCrowded(
        frame: Frame,
        device: DeviceClass,
        state: PlayerState = playingLive(),
        direction: LayoutDirection = LayoutDirection.Rtl,
    ) = show(
        frame,
        device,
        state.copy(favorite = false),
        direction,
        PlayerActions(onStep = {}, onPlaylist = {}, onFavorite = {}),
    )

    private fun ComposeContentTestRule.show(
        frame: Frame,
        device: DeviceClass,
        state: PlayerState,
        direction: LayoutDirection = LayoutDirection.Rtl,
        actions: PlayerActions = PlayerActions(),
        channelList: ChannelListContent? = null,
    ) = setContent {
        CastivioTheme {
            CompositionLocalProvider(
                LocalDeviceClass provides device,
                LocalLayoutDirection provides direction,
            ) {
                Stage(frame) { PlayerScreen(state = state, actions = actions, channelList = channelList) }
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

    /** A label as the screen resolves it, so a row is found by what it says. */
    private fun text(id: Int): String = RuntimeEnvironment.getApplication().getString(id)
}
