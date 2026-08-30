package com.castivio.feature.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.DeviceClass
import com.castivio.core.design.theme.LocalDeviceClass
import com.castivio.playback.api.AspectMode
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
     */
    @Test
    fun `the jump controls call back with ten seconds in each direction`() {
        val jumps = mutableListOf<Long>()
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingLive(),
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

    /* --------------------------------------------------------------- the top-bar button */

    /**
     * The button at the end of the top bar does something.
     *
     * It did not. It carried the cast mark and cast is not built, and `PlayerRoute` never
     * bound `onCast` at all — so the one control in the top bar beside back and lock was a
     * picture. It shares what is playing now, which is something this product can do today.
     */
    @Test
    fun `the top-bar button shares what is playing`() {
        var shared = 0
        compose.show(
            HANDSET,
            DeviceClass.Expanded,
            playingFilm(),
            LayoutDirection.Rtl,
            PlayerActions(onShare = { shared++ }),
        )

        compose.onNodeWithTag(PlayerTags.CAST).performClick()

        assertEquals("the top-bar button is still a picture", 1, shared)
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
     * And in Arabic the bar fills from the right, so the same touch means the opposite time.
     *
     * The half of a mirrored slider that is silently wrong far more often than it is right:
     * a drag toward the right-hand edge in Arabic is a drag toward the *beginning* of the
     * film. Read left-to-right it would seek to the far end, which reads as a broken control
     * rather than a mirrored one.
     */
    @Test
    fun `dragging the bar in Arabic seeks from the right-hand edge`() {
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
            "a quarter in from the right-hand edge is a quarter into the film, not three",
            FILM_LENGTH * 0.25f,
            sought?.toFloat() ?: -1f,
            FILM_LENGTH * 0.03f,
        )
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

    /** Ninety minutes, as a `Float` because every scrubber assertion is a fraction of it. */
    private val FILM_LENGTH = 90f * 60f * 1000f

    /**
     * A tenth of the way across the picture.
     *
     * Far enough from the end edge to be film rather than sheet — the sheet takes 52% of a
     * handset — and far enough from the start edge to be clear of the back arrow.
     */
    private val FILM_SIDE = 0.1f

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
        actions: PlayerActions = PlayerActions(),
    ) = setContent {
        CastivioTheme {
            CompositionLocalProvider(
                LocalDeviceClass provides device,
                LocalLayoutDirection provides direction,
            ) {
                Stage(frame) { PlayerScreen(state = state, actions = actions) }
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
