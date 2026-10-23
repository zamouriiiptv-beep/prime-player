package com.castivio.feature.home

import com.castivio.domain.PinChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The password dialog's conversation, with no screen in it: what each thing typed leads to.
 *
 * Playing, locking and unlocking are one question -- is it the password -- and changing it is three:
 * the password as it is, the one it is to become, and that one again. The vault's answers are passed in
 * as plain booleans, so none of this needs a device or a file.
 */
class PinFlowTest {

    private fun stay(turn: PinTurn): PinPromptState = (turn as PinTurn.Stay).state

    // ------------------------------------------------------- play, lock and unlock: one question

    @Test
    fun `playing, locking and unlocking ask one question`() {
        for (purpose in listOf(PinPurpose.Play, PinPurpose.Lock, PinPurpose.Unlock)) {
            assertEquals("$purpose", PinStep.Check, PinPromptState.start(purpose).step)
        }
    }

    @Test
    fun `the right password at a check grants it`() {
        val state = PinPromptState.start(PinPurpose.Play)

        assertEquals(PinTurn.Granted, state.entered("0000", right = true))
    }

    @Test
    fun `a wrong password at a check refuses it, says so, and clears the keypad`() {
        val state = PinPromptState.start(PinPurpose.Play)

        val refused = stay(state.entered("1234", right = false))

        assertEquals(PinStep.Check, refused.step)
        assertEquals(PinNotice.Wrong, refused.notice)
        assertEquals("the keypad clears on a new attempt", state.attempt + 1, refused.attempt)
    }

    @Test
    fun `a second refusal is a new attempt too, so the same sentence clears the keypad again`() {
        val first = stay(PinPromptState.start(PinPurpose.Play).entered("1234", right = false))
        val second = stay(first.entered("4321", right = false))

        assertEquals(PinNotice.Wrong, second.notice)
        assertEquals(first.attempt + 1, second.attempt)
    }

    @Test
    fun `only a check and the current-password step ask the stored password`() {
        assertTrue(PinPromptState.start(PinPurpose.Play).checksPassword)
        assertTrue(PinPromptState.start(PinPurpose.Change).checksPassword)
        assertFalse(PinPromptState(PinStep.New("0000")).checksPassword)
        assertFalse(PinPromptState(PinStep.Repeat("0000", "4821")).checksPassword)
    }

    // ----------------------------------------------------------------- changing the password

    @Test
    fun `a change begins with the password as it is`() {
        assertEquals(PinStep.Current, PinPromptState.start(PinPurpose.Change).step)
    }

    @Test
    fun `a wrong current password stays at the first question`() {
        val state = PinPromptState.start(PinPurpose.Change)

        val refused = stay(state.entered("9999", right = false))

        assertEquals(PinStep.Current, refused.step)
        assertEquals(PinNotice.Wrong, refused.notice)
    }

    @Test
    fun `the right current password moves on to the new one, and remembers it`() {
        val next = stay(PinPromptState.start(PinPurpose.Change).entered("0000", right = true))

        assertEquals(PinStep.New("0000"), next.step)
        assertNull(next.notice)
    }

    @Test
    fun `the new password is asked again before it is kept`() {
        val atNew = PinPromptState(PinStep.New("0000"))

        val next = stay(atNew.entered("4821", right = false))

        assertEquals(PinStep.Repeat("0000", "4821"), next.step)
    }

    @Test
    fun `the same digits twice are saved, replacing the current password`() {
        val atRepeat = PinPromptState(PinStep.Repeat("0000", "4821"))

        assertEquals(PinTurn.Save(current = "0000", new = "4821"), atRepeat.entered("4821", right = false))
    }

    @Test
    fun `digits that differ the second time throw both away and start the new password over`() {
        val atRepeat = PinPromptState(PinStep.Repeat("0000", "4821"))

        val back = stay(atRepeat.entered("4822", right = false))

        assertEquals("not saved, and not kept as a half-answer", PinStep.New("0000"), back.step)
        assertEquals(PinNotice.Mismatch, back.notice)
        assertEquals(atRepeat.attempt + 1, back.attempt)
    }

    @Test
    fun `a notice does not follow the viewer to the next question`() {
        val refused = stay(PinPromptState.start(PinPurpose.Change).entered("9999", right = false))
        val right = stay(refused.entered("0000", right = true))

        assertNull(right.notice)
    }

    @Test
    fun `a stored password ends the change and says so`() {
        val done = PinPromptState(PinStep.Repeat("0000", "4821")).saved(PinChange.Changed)

        assertTrue(done.changed)
        assertNull(done.notice)
    }

    @Test
    fun `a change the store did not take begins again from the password as it is`() {
        for (result in listOf(PinChange.WrongCurrent, PinChange.Malformed)) {
            val state = PinPromptState(PinStep.Repeat("0000", "4821"), attempt = 2).saved(result)

            assertEquals("$result", PinStep.Current, state.step)
            assertEquals(PinNotice.Wrong, state.notice)
            assertFalse(state.changed)
            assertEquals(3, state.attempt)
        }
    }

    @Test
    fun `the whole change, typed through, ends with the new password stored`() {
        var state = PinPromptState.start(PinPurpose.Change)
        state = stay(state.entered("0000", right = true))
        state = stay(state.entered("4821", right = false))
        val turn = state.entered("4821", right = false)

        assertEquals(PinTurn.Save("0000", "4821"), turn)
    }
}
