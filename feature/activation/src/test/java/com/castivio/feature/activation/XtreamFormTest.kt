package com.castivio.feature.activation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.NativeKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.DeviceClass
import com.castivio.core.design.theme.LocalDeviceClass
import com.castivio.core.design.theme.Sizing
import com.castivio.domain.activation.ActivationForm
import com.castivio.domain.activation.ActivationUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Xtream screen as Compose places it, on the six surfaces it was approved for.
 *
 * ## What is asked here, and what is asked next door
 *
 * Robolectric does not lay text out -- every `Text` is 35dp tall here -- so whether the
 * form *fits* a television's frame is `XtreamFrameTest`'s question, answered with the
 * components' real heights. This file asks what the harness answers truthfully: which
 * elements are composed, whether anything scrolls and where, which field says what is
 * wrong and where it says it, where focus goes on a remote and on a keyboard, and what
 * the password's show and hide does. All of that is structure, and a harness with the
 * wrong text metrics still gets structure right.
 *
 * Each surface is the screen in the container production builds around it --
 * [ActivationSurface] at [isFixedViewport]'s own answer for the step -- with the device
 * class a television reports where the surface is one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1920dp-h1080dp-land")
class XtreamFormTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var focus: FocusManager

    /* ------------------------------------------------------- the six surfaces */

    @Test
    fun `the 851x393 handset draws the whole form and scrolls it inside the frame`() = handset(HANDSET)

    @Test
    fun `the 800x360 handset draws the whole form and scrolls it inside the frame`() = handset(SHORT_HANDSET)

    @Test
    fun `the 960x540 television draws the whole form without a scroll`() = held(SMALL_TV)

    @Test
    fun `the 1280x720 television draws the whole form without a scroll`() = held(TV)

    @Test
    fun `the 960x600 tablet draws the whole form without a scroll`() = held(SMALL_TABLET)

    @Test
    fun `the 1280x800 tablet draws the whole form without a scroll`() = held(TABLET)

    /**
     * A handset scrolls the form, and only the form: the one scrollable node is inside
     * the frame, the header with its title and Back is outside it, and Connect at the
     * bottom is reachable by scrolling to it.
     */
    private fun handset(s: Surface) {
        compose.show(s)
        compose.assertWholeForm(s)

        compose.onAllNodes(hasScrollAction()).assertCountEquals(1)
        compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag(ActivationTags.XTREAM_FRAME)))
            .assertExists("$s: the scroll is not inside the frame")
        for (tag in listOf(ActivationTags.XTREAM_HEADING, ActivationTags.XTREAM_BACK)) {
            compose.onNodeWithTag(tag, useUnmergedTree = true)
                .assert(!hasAnyAncestor(hasScrollAction())) { "$s: $tag scrolls with the form" }
        }
        compose.onNodeWithText(CONNECT).performScrollTo().assertIsDisplayed()
    }

    /** A television or a tablet has nothing to scroll: the frame holds the form. */
    private fun held(s: Surface) {
        compose.show(s)
        compose.assertWholeForm(s)

        compose.onAllNodes(hasScrollAction()).assertCountEquals(0)
    }

    /**
     * Every element, once: the header's title and Back, the frame, the four fields in the
     * order a provider's e-mail lists them, Paste, the show and hide, Connect and the
     * note -- which, on an empty form, says what to fill in.
     */
    private fun ComposeContentTestRule.assertWholeForm(s: Surface) {
        for (tag in listOf(
            ActivationTags.XTREAM_HEADING,
            ActivationTags.XTREAM_BACK,
            ActivationTags.XTREAM_FRAME,
            ActivationTags.XTREAM_NAME,
            ActivationTags.XTREAM_SERVER,
            ActivationTags.XTREAM_USERNAME,
            ActivationTags.XTREAM_PASSWORD,
            ActivationTags.XTREAM_REVEAL,
            ActivationTags.XTREAM_NOTE,
        )) {
            onAllNodes(hasTestTag(tag), useUnmergedTree = true).assertCountEquals(1)
        }
        onNodeWithText(PASTE).assertExists("$s: the server field has no Paste")
        onNodeWithText(CONNECT).assertExists("$s: there is no Connect")
        onNodeWithText(NOTE_EMPTY).assertExists("$s: the note does not say what to fill in")

        val tops = FIELDS.map { bounds(it).top }
        assertEquals("$s: the fields are not one under the other, in order", tops.sorted(), tops)
        val frame = bounds(ActivationTags.XTREAM_FRAME)
        for (tag in FIELDS) {
            val field = bounds(tag)
            assertTrue("$s: $tag runs past the frame's sides", field.left >= frame.left && field.right <= frame.right)
        }
    }

    /* ---------------------------------------------------------------- errors */

    /**
     * A handset writes a field's error under that field, as the other two forms do: the
     * username, which is too long, is taller than the password beside it by the line,
     * and the note under Connect keeps saying what to fill in.
     */
    @Test
    fun `a handset writes an error under its own field`() {
        compose.show(HANDSET, form = ActivationForm.Xtream(username = "u".repeat(TOO_LONG)))

        assertTrue(
            "the username's error is not under it",
            bounds(ActivationTags.XTREAM_USERNAME).height > bounds(ActivationTags.XTREAM_PASSWORD).height,
        )
        compose.onNodeWithText(NOTE_EMPTY).assertExists()
        compose.textField(ActivationTags.XTREAM_USERNAME).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
    }

    @Test
    fun `a television says the first error under Connect and keeps every field its height`() =
        errorsInTheNote(SMALL_TV)

    @Test
    fun `a tablet says the first error under Connect and keeps every field its height`() =
        errorsInTheNote(SMALL_TABLET)

    /**
     * Where the form does not scroll, two fields wrong: the server, which comes first, is
     * named in the note under Connect, and neither field grows by a line the frame has
     * not got. Both still announce their own error, and both are still red -- which is
     * the border, and drawn from the same `error` the semantics carry.
     */
    private fun errorsInTheNote(s: Surface) {
        compose.show(
            s,
            form = ActivationForm.Xtream(serverUrl = "http://exa mple.com:8080", username = "u".repeat(TOO_LONG)),
        )

        assertEquals(
            "$s: the username grew by its error",
            bounds(ActivationTags.XTREAM_PASSWORD).height.value,
            bounds(ActivationTags.XTREAM_USERNAME).height.value,
            0.5f,
        )
        compose.onNodeWithText("Server URL: There is a space in there. A link cannot contain one.")
            .assertExists("$s: the note does not name the server's error")
        compose.onNodeWithText(NOTE_EMPTY).assertDoesNotExist()
        for (tag in listOf(ActivationTags.XTREAM_SERVER, ActivationTags.XTREAM_USERNAME)) {
            compose.textField(tag).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        }
    }

    /* ------------------------------------------------------------ the remote */

    @Test
    fun `on a television, down goes name, server, username, password, Connect`() = downTheForm(SMALL_TV)

    @Test
    fun `on a handset, down goes the same way through the scroll`() = downTheForm(HANDSET)

    /** Paste and the show and hide sit inside their fields, so neither is a stop on the way down. */
    private fun downTheForm(s: Surface) {
        compose.show(s, form = FILLED, canSubmit = true)
        compose.textField(ActivationTags.XTREAM_NAME).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.textField(ActivationTags.XTREAM_NAME).assertIsFocused()

        for (tag in listOf(ActivationTags.XTREAM_SERVER, ActivationTags.XTREAM_USERNAME, ActivationTags.XTREAM_PASSWORD)) {
            compose.runOnIdle { focus.moveFocus(FocusDirection.Down) }
            compose.textField(tag).assertIsFocused()
        }
        compose.runOnIdle { focus.moveFocus(FocusDirection.Down) }
        compose.onNodeWithText(CONNECT).assertIsFocused()
    }

    /* ---------------------------------------------------------- the keyboard */

    /**
     * Next goes to the next field down, which it did not do before: the field's own
     * action replaced the platform's and did nothing. Done on the password is Connect.
     */
    @Test
    fun `the keyboard's Next goes down the form and Done on the password connects`() {
        var submits = 0
        compose.show(HANDSET, form = FILLED, canSubmit = true, onSubmit = { submits++ })
        compose.textField(ActivationTags.XTREAM_NAME).performSemanticsAction(SemanticsActions.RequestFocus)

        for ((from, to) in listOf(
            ActivationTags.XTREAM_NAME to ActivationTags.XTREAM_SERVER,
            ActivationTags.XTREAM_SERVER to ActivationTags.XTREAM_USERNAME,
            ActivationTags.XTREAM_USERNAME to ActivationTags.XTREAM_PASSWORD,
        )) {
            compose.textField(from).performImeAction()
            compose.textField(to).assertIsFocused()
        }
        assertEquals("Next connected", 0, submits)

        compose.textField(ActivationTags.XTREAM_PASSWORD).performImeAction()
        assertEquals("Done on the password did not connect", 1, submits)
    }

    /* ---------------------------------------------------- the show and hide */

    /** Hidden until it is shown, by touch, and the control says which it will do. */
    @Test
    fun `the password is hidden until it is shown, and hidden again`() {
        compose.show(TABLET, form = FILLED)
        val password = compose.textField(ActivationTags.XTREAM_PASSWORD)

        password.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithContentDescription(SHOW).performClick()
        password.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
        compose.onNodeWithContentDescription(HIDE).performClick()
        password.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithContentDescription(SHOW).assertExists()
    }

    @Test
    fun `a remote reaches the show and hide across from the password, and presses it`() =
        revealByRemote(LayoutDirection.Ltr, FocusDirection.Right)

    /** In Arabic the field's trailing edge is its left one, and so is the way across. */
    @Test
    fun `in Arabic the way across is to the left`() =
        revealByRemote(LayoutDirection.Rtl, FocusDirection.Left)

    private fun revealByRemote(direction: LayoutDirection, across: FocusDirection) {
        compose.show(SMALL_TV, form = FILLED, direction = direction)
        compose.textField(ActivationTags.XTREAM_PASSWORD).performSemanticsAction(SemanticsActions.RequestFocus)

        compose.runOnIdle { focus.moveFocus(across) }
        val toggle = compose.onNodeWithTag(ActivationTags.XTREAM_REVEAL)
        toggle.assertIsFocused()

        toggle.pressCenter()
        compose.textField(ActivationTags.XTREAM_PASSWORD).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
        compose.onNodeWithContentDescription(HIDE).assertExists()
    }

    /**
     * **The show and hide does not change the field's height.** The password is exactly
     * as tall as the username above it, hidden and shown -- which is what `trailingFlush`
     * exists to guarantee: the line keeps a plain field's inset and the control takes the
     * height that leaves, rather than adding its own. And it is a whole target, never
     * shorter than a thumb's floor, because it is handed the box's whole height.
     */
    @Test
    fun `the password is exactly as tall as the username, hidden or shown`() = sameHeight(fontScale = 1f)

    /**
     * At a large font the line grows, and the show and hide grows with it. A control of
     * its own fixed height would leave the password shorter than the username here,
     * which is the case that does not show at the default scale.
     */
    @Test
    fun `the password keeps the username's height at a large font scale`() = sameHeight(fontScale = 1.5f)

    private fun sameHeight(fontScale: Float) {
        compose.show(TABLET, form = FILLED, fontScale = fontScale)

        val username = bounds(ActivationTags.XTREAM_USERNAME).height.value
        assertEquals("hidden", username, bounds(ActivationTags.XTREAM_PASSWORD).height.value, 0.5f)
        val toggle = bounds(ActivationTags.XTREAM_REVEAL).height
        assertTrue("the show and hide is $toggle tall", toggle >= Sizing.minTouchTarget)

        compose.onNodeWithContentDescription(SHOW).performClick()
        assertEquals("shown", username, bounds(ActivationTags.XTREAM_PASSWORD).height.value, 0.5f)
    }

    /* -------------------------------------------------------------------------- */

    private fun ComposeContentTestRule.show(
        s: Surface,
        form: ActivationForm.Xtream = ActivationForm.Xtream(),
        canSubmit: Boolean = false,
        onSubmit: () -> Unit = {},
        direction: LayoutDirection = LayoutDirection.Ltr,
        fontScale: Float = 1f,
    ) = setContent {
        CastivioTheme {
            CompositionLocalProvider(
                LocalDeviceClass provides if (s.tv) DeviceClass.Television else DeviceClass.Expanded,
                LocalLayoutDirection provides direction,
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                focus = LocalFocusManager.current
                Box(Modifier.requiredSize(s.width, s.height)) {
                    ActivationSurface(
                        fixedViewport = isFixedViewport(ActivationUiState(), ActivationStep.Xtream),
                    ) {
                        XtreamFormScreen(
                            form = form,
                            enabled = true,
                            canSubmit = canSubmit,
                            onName = {},
                            onServerUrl = {},
                            onUsername = {},
                            onPassword = {},
                            onSubmit = onSubmit,
                            onBack = {},
                        )
                    }
                }
            }
        }
    }

    /** The field's own input, inside the tagged field: the node that takes focus and text. */
    private fun ComposeContentTestRule.textField(tag: String): SemanticsNodeInteraction =
        onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)

    private fun bounds(tag: String): DpRect =
        compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    /** The remote's centre button, down and up, delivered to whatever has focus. */
    private fun SemanticsNodeInteraction.pressCenter() {
        performKeyPress(KeyEvent(NativeKeyEvent(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_DPAD_CENTER)))
        performKeyPress(KeyEvent(NativeKeyEvent(NativeKeyEvent.ACTION_UP, NativeKeyEvent.KEYCODE_DPAD_CENTER)))
    }

    private data class Surface(val name: String, val width: Dp, val height: Dp, val tv: Boolean) {
        override fun toString() = name
    }

    private companion object {
        val HANDSET = Surface("handset 851x393", 851.dp, 393.dp, tv = false)
        val SHORT_HANDSET = Surface("handset 800x360", 800.dp, 360.dp, tv = false)
        val SMALL_TV = Surface("television 960x540", 960.dp, 540.dp, tv = true)
        val TV = Surface("television 1280x720", 1280.dp, 720.dp, tv = true)
        val SMALL_TABLET = Surface("tablet 960x600", 960.dp, 600.dp, tv = false)
        val TABLET = Surface("tablet 1280x800", 1280.dp, 800.dp, tv = false)

        val FIELDS = listOf(
            ActivationTags.XTREAM_NAME,
            ActivationTags.XTREAM_SERVER,
            ActivationTags.XTREAM_USERNAME,
            ActivationTags.XTREAM_PASSWORD,
        )

        /** Everything a provider would send, so Connect is live and no field is wrong. */
        val FILLED = ActivationForm.Xtream(
            serverUrl = "http://example.com:8080",
            username = "viewer",
            password = "secret",
        )

        /** One past what a credential may be. */
        const val TOO_LONG = 129

        const val PASTE = "Paste"
        const val CONNECT = "Connect"
        const val SHOW = "Show password"
        const val HIDE = "Hide password"
        const val NOTE_EMPTY = "Enter the server URL, username and password to turn on Connect."
    }
}
