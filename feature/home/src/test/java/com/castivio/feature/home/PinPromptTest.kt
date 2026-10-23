package com.castivio.feature.home

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.domain.ChannelLocks
import com.castivio.domain.PinDigest
import com.castivio.domain.PinKeeper
import com.castivio.domain.PinSlot
import com.castivio.domain.StoredPin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The password dialog as a viewer meets it: the keypad, the dots, what a wrong password says, and the
 * three questions of changing it.
 *
 * The real dialog is composed over the real vault -- `PinKeeper` -- with a file and a digest that live in
 * memory. What each answer leads to is `PinFlowTest`'s; this is that the screen asks, shows and clears as
 * it says, and that a touch and a remote's number keys both get there.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w851dp-h393dp-land")
class PinPromptTest {

    @get:Rule
    val compose = createComposeRule()

    private class MemorySlot : PinSlot {
        var stored: StoredPin? = null
        override suspend fun read() = stored
        override suspend fun write(pin: StoredPin) { stored = pin }
    }

    private class ReadableDigest : PinDigest {
        private var n = 0
        override fun newSalt() = "s${n++}"
        override fun of(salt: String, pin: String) = "$salt/$pin"
    }

    private class NoLocks : ChannelLocks {
        override val locked: StateFlow<Set<String>?> = MutableStateFlow(emptySet())
        override fun lock(channelId: String) {}
        override fun unlock(channelId: String) {}
    }

    private val keeper = PinKeeper(MemorySlot(), ReadableDigest())
    private val model = ChannelLockViewModel(NoLocks(), keeper)

    private var granted = 0
    private var dismissed = 0

    private fun open(purpose: PinPurpose) {
        compose.setContent {
            CastivioTheme {
                // Something focusable behind the dialog, in the corner an arrow key would reach first.
                Box(Modifier.size(40.dp).testTag("behind").focusable())
                PinPrompt(purpose = purpose, onGranted = { granted++ }, onDismiss = { dismissed++ }, model = model)
            }
        }
        compose.waitForIdle()
    }

    /** Taps the digits. What the dialog does with a complete entry is asynchronous: wait for it where it matters. */
    private fun type(digits: String) {
        for (d in digits) compose.onNodeWithText(d.toString()).performClick()
        compose.waitForIdle()
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size == 1

    /** Waits for [text] to be on screen, once: a step of the dialog is a coroutine away from the last digit. */
    private fun showing(text: String) = compose.waitUntil(WAIT_MS) { shown(text) }

    private fun dots(entered: Int) = hasContentDescription("$entered of 4 digits")

    private fun dotsAre(entered: Int) = compose.waitUntil(WAIT_MS) {
        compose.onAllNodes(dots(entered)).fetchSemanticsNodes().isNotEmpty()
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }

    // ----------------------------------------------------------------------- playing a locked channel

    @Test
    fun `a locked channel asks for the password, in words that say what it is for`() {
        open(PinPurpose.Play)

        showing("Locked channel")
        showing("Enter the password to play this channel.")
    }

    @Test
    fun `the right password grants it once, and a wrong one does not`() {
        open(PinPurpose.Play)

        type("1111")
        showing("Wrong password.")
        assertEquals("a wrong password grants nothing", 0, granted)

        type("0000")
        compose.waitUntil(WAIT_MS) { granted == 1 }
        assertEquals(1, granted)
    }

    @Test
    fun `the dots count the digits typed and a wrong password clears them`() {
        open(PinPurpose.Play)
        dotsAre(0)

        type("12")
        dotsAre(2)

        type("34")
        // Refused: the keypad starts again from nothing.
        showing("Wrong password.")
        dotsAre(0)
    }

    @Test
    fun `the same wrong password twice is refused twice`() {
        open(PinPurpose.Play)

        type("1111")
        showing("Wrong password.")
        dotsAre(0)

        // Four digits in, and the same sentence on screen: only the clearing says it was refused again.
        type("1111")
        dotsAre(0)

        showing("Wrong password.")
        assertEquals(0, granted)
    }

    @Test
    fun `delete takes the last digit back`() {
        open(PinPurpose.Play)

        type("123")
        compose.onNode(hasContentDescription("Delete")).performClick()

        dotsAre(2)
    }

    @Test
    fun `cancel closes it and grants nothing`() {
        open(PinPurpose.Play)

        compose.onNodeWithText("Cancel").performClick()

        assertEquals(1, dismissed)
        assertEquals(0, granted)
    }

    // ------------------------------------------------------------------ locking and unlocking

    @Test
    fun `locking and unlocking ask for the password in their own words`() {
        open(PinPurpose.Lock)
        showing("Lock channel")
        showing("Enter the password to lock this channel.")
    }

    @Test
    fun `unlocking says so`() {
        open(PinPurpose.Unlock)
        showing("Unlock channel")
        showing("Enter the password to unlock this channel.")
    }

    // ------------------------------------------------------------------------- a remote's keys

    @Test
    fun `the remote starts on the keypad, in the middle of it`() {
        open(PinPurpose.Play)

        compose.onNodeWithText("5").assertIsFocused()
    }

    @Test
    fun `number keys type the password without touching the keypad`() {
        open(PinPurpose.Play)

        compose.onRoot().performKeyInput {
            pressKey(Key.Zero); pressKey(Key.Zero); pressKey(Key.Zero); pressKey(Key.Zero)
        }

        compose.waitUntil(WAIT_MS) { granted == 1 }
        assertEquals(1, granted)
    }

    @Test
    fun `the arrows stay inside the dialog and never reach what is behind it`() {
        open(PinPurpose.Play)

        compose.onRoot().performKeyInput {
            repeat(6) { pressKey(Key.DirectionUp) }
            repeat(6) { pressKey(Key.DirectionLeft) }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("behind").assertIsNotFocused()
    }

    @Test
    fun `the number pad of a keyboard types too, and backspace takes a digit back`() {
        open(PinPurpose.Play)

        compose.onRoot().performKeyInput {
            pressKey(Key.NumPad1); pressKey(Key.NumPad2); pressKey(Key.Backspace)
        }

        dotsAre(1)
    }

    // ---------------------------------------------------------------- changing the password

    @Test
    fun `changing the password asks three things in order`() {
        open(PinPurpose.Change)
        showing("Current password")

        type("0000")
        showing("New password")

        type("4821")
        showing("Repeat the password")
    }

    @Test
    fun `a wrong current password stays on the first question`() {
        open(PinPurpose.Change)

        type("9999")

        showing("Current password")
        showing("Wrong password.")
    }

    @Test
    fun `the same new password twice is stored, and the old one stops working`() {
        open(PinPurpose.Change)
        type("0000")
        type("4821")
        type("4821")

        showing("Password changed")
        runBlocking {
            assertTrue(keeper.matches("4821"))
            assertFalse(keeper.matches("0000"))
        }
        assertEquals("nothing is granted until the notice is closed", 0, granted)

        compose.onNodeWithText("OK").performClick()
        assertEquals(1, granted)
    }

    @Test
    fun `new passwords that differ are not stored, and the new one is asked for again`() {
        open(PinPurpose.Change)
        type("0000")
        type("4821")
        type("4822")

        showing("New password")
        showing("The digits didn't match. Choose the new password again.")
        runBlocking {
            assertTrue("the old one still works", keeper.matches("0000"))
            assertFalse(keeper.matches("4821"))
            assertFalse(keeper.matches("4822"))
        }
    }
}
