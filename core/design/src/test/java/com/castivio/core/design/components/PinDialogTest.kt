package com.castivio.core.design.components

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The password dialog's rules, and the claim that its keypad fits the surfaces it is shown on.
 *
 * What is drawn -- the dots, the keys, the focus ring -- is the drawing's and the render's. What can be
 * asserted without a screen is asserted here: what typing does to the entry, which keys of a remote or
 * a keyboard are digits, where the words go, and that four rows of keys at the control floor are not
 * taller than the panel allows them to be.
 */
class PinDialogTest {

    private fun typed(text: String, length: Int = 4): PinEntry =
        text.fold(PinEntry(length)) { entry, c -> entry.push(c) }

    @Test
    fun `an entry takes digits one at a time and counts them`() {
        var entry = PinEntry(4)
        assertEquals(0, entry.count)
        assertFalse(entry.complete)

        entry = entry.push('4').push('8')

        assertEquals(2, entry.count)
        assertEquals("48", entry.digits)
        assertFalse(entry.complete)
    }

    @Test
    fun `the last digit completes it and nothing more is taken`() {
        val entry = typed("4821")

        assertTrue(entry.complete)
        assertEquals("4821", entry.digits)
        assertEquals("a fifth digit is ignored", "4821", entry.push('7').digits)
    }

    @Test
    fun `only digits are taken`() {
        assertEquals("", typed("ab-+ ").digits)
        assertEquals("12", typed("1a2b").digits)
        // Eastern Arabic digits are not what a keypad types, and the password is Western digits.
        assertEquals("", typed("١٢٣٤").digits)
    }

    @Test
    fun `deleting takes the last digit off and stops at empty`() {
        assertEquals("48", typed("482").pop().digits)
        assertEquals("", typed("4").pop().digits)
        assertEquals("", PinEntry(4).pop().digits)
        assertEquals("a complete entry can be taken back", "482", typed("4821").pop().digits)
    }

    @Test
    fun `the length is the caller's`() {
        assertTrue(typed("12", length = 2).complete)
        assertEquals("12", typed("1234", length = 2).digits)
    }

    @Test
    fun `the number keys of a remote and a keyboard are digits`() {
        val row = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
        val pad = listOf(
            Key.NumPad0, Key.NumPad1, Key.NumPad2, Key.NumPad3, Key.NumPad4,
            Key.NumPad5, Key.NumPad6, Key.NumPad7, Key.NumPad8, Key.NumPad9,
        )
        for ((digit, keys) in row.zip(pad).withIndex()) {
            val expected = PinKeyAction.Digit(('0' + digit))
            assertEquals("number row $digit", expected, pinKeyAction(keys.first))
            assertEquals("number pad $digit", expected, pinKeyAction(keys.second))
        }
    }

    @Test
    fun `backspace, delete and clear take a digit away`() {
        assertEquals(PinKeyAction.Delete, pinKeyAction(Key.Backspace))
        assertEquals(PinKeyAction.Delete, pinKeyAction(Key.Delete))
        assertEquals(PinKeyAction.Delete, pinKeyAction(Key.Clear))
    }

    @Test
    fun `the keys that move and press are not the keypad's`() {
        for (key in listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.DirectionCenter, Key.Enter, Key.Back, Key.A)) {
            assertNull("$key is left to whatever is focused", pinKeyAction(key))
        }
    }

    // ------------------------------------------------------------------ where the words go

    private fun keypadWidth(tv: Boolean): Dp = Sizing.minTarget(tv) * KEY_ASPECT * 3 + Spacing.xs * 2

    /** The room the panel leaves for its content: its width less its padding on both sides. */
    private fun inner(width: Dp, height: Dp): Dp {
        val m = dialogMetricsFor(width, height)
        val padding = if (height < 320.dp) Spacing.md else m.padding
        return minOf(maxOf(m.width, PIN_PANEL_MIN), width) - padding * 2
    }

    @Test
    fun `the words go beside the keypad on every landscape surface`() {
        for ((name, size, tv) in listOf(
            Triple("small phone 640x360", 640.dp to 360.dp, false),
            Triple("shortest phone 800x360", 800.dp to 360.dp, false),
            Triple("phone 812x375", 812.dp to 375.dp, false),
            Triple("phone 851x393", 851.dp to 393.dp, false),
            Triple("narrow frame 568x360", 568.dp to 360.dp, false),
            Triple("television 960x540", 960.dp to 540.dp, true),
            Triple("1080p set 1920x1080", 1920.dp to 1080.dp, true),
        )) {
            assertTrue(
                "$name: ${inner(size.first, size.second)} leaves room for the words beside the keypad",
                pinBeside(inner(size.first, size.second), keypadWidth(tv)),
            )
        }
    }

    @Test
    fun `a handset held upright stacks them`() {
        assertFalse(pinBeside(inner(393.dp, 851.dp), keypadWidth(tv = false)))
        assertFalse(pinBeside(inner(360.dp, 800.dp), keypadWidth(tv = false)))
    }

    @Test
    fun `beside the keypad the words keep the width they are read in`() {
        for (width in listOf(568.dp, 640.dp, 800.dp, 851.dp, 960.dp, 1280.dp)) {
            val tv = width >= 960.dp
            val room = inner(width, if (tv) 540.dp else 360.dp)
            if (pinBeside(room, keypadWidth(tv))) {
                val words = room - keypadWidth(tv) - Spacing.lg
                assertTrue("$width: the words get $words", words >= WORDS_MIN)
            }
        }
    }

    // ------------------------------------------------------------------ whether it fits

    /**
     * **Four rows of keys, the title and the panel's own padding are never taller than the panel may be.**
     *
     * The keypad is the tallest thing the dialog holds when the words are beside it, and its keys are at
     * the control floor -- 48dp in the hand, 56 across a room -- which is not negotiable. What gives is the
     * share of the height the panel is allowed, which is why it takes [PIN_PANEL_FRACTION] and not the
     * fifth less every other dialog leaves. Measured on the shortest handset Castivio ships to, where it
     * is closest.
     */
    @Test
    fun `the keypad and the title fit the panel on every surface the board runs on`() {
        for ((name, size, tv) in listOf(
            Triple("small phone 640x360", 640.dp to 360.dp, false),
            Triple("shortest phone 800x360", 800.dp to 360.dp, false),
            Triple("phone 812x375", 812.dp to 375.dp, false),
            Triple("phone 833x385", 833.dp to 385.dp, false),
            Triple("phone 851x393", 851.dp to 393.dp, false),
            Triple("television 960x540", 960.dp to 540.dp, true),
            Triple("reference 1280x720", 1280.dp to 720.dp, true),
            Triple("tablet 1280x800", 1280.dp to 800.dp, false),
        )) {
            val (width, height) = size
            val m = dialogMetricsFor(width, height)
            val tight = height < 320.dp
            val padding = if (tight) Spacing.md else m.padding
            val rhythm = if (tight) Spacing.sm else Spacing.md
            val key = Sizing.minTarget(tv)

            val titleLine = (m.title.value * 1.44f).dp
            val keypad = key * 4 + Spacing.xs * 3
            val needed = padding * 2 + titleLine + rhythm + keypad
            val allowed = if (tight) height else height * PIN_PANEL_FRACTION

            assertTrue("$name: needs $needed of $allowed", needed <= allowed)
        }
    }

    @Test
    fun `a key is at the control floor in height and wider than it is tall`() {
        for (tv in listOf(false, true)) {
            val key = Sizing.minTarget(tv)
            assertTrue(key >= 48.dp)
            assertTrue(key * KEY_ASPECT > key)
        }
        assertEquals(56f, Sizing.minTarget(true).value, 0f)
    }
}
