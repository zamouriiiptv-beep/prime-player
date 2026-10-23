package com.castivio.domain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The password: four digits, 0000 until it is changed, and changed only by someone who has it.
 *
 * The slot and the digest are fakes, so this runs on a JVM with no device and no file. What the real
 * ones add -- a preferences file and SHA-256 -- is storage and arithmetic, neither of which is a rule.
 */
class PinKeeperTest {

    private class MemorySlot(var stored: StoredPin? = null) : PinSlot {
        var writes = 0
        override suspend fun read() = stored
        override suspend fun write(pin: StoredPin) {
            writes++
            stored = pin
        }
    }

    /** Salts count up, and the "digest" is readable on purpose, so a test can see what was stored. */
    private class CountingDigest : PinDigest {
        private var next = 0
        override fun newSalt() = "salt${next++}"
        override fun of(salt: String, pin: String) = "$salt/$pin"
    }

    private val slot = MemorySlot()
    private val keeper = PinKeeper(slot, CountingDigest())

    @Test
    fun `a device that was never touched has the default password`() = runTest {
        assertEquals("0000", PinRules.DEFAULT)
        assertTrue(keeper.matches("0000"))
        assertFalse(keeper.matches("1234"))
    }

    @Test
    fun `nothing is stored until the password is changed`() = runTest {
        keeper.matches("0000")
        assertNull(slot.stored)
        assertEquals(0, slot.writes)
    }

    @Test
    fun `a password is exactly four digits`() {
        assertTrue(PinRules.isWellFormed("0000"))
        assertTrue(PinRules.isWellFormed("9081"))
        assertFalse(PinRules.isWellFormed(""))
        assertFalse(PinRules.isWellFormed("123"))
        assertFalse(PinRules.isWellFormed("12345"))
        assertFalse(PinRules.isWellFormed("12a4"))
        assertFalse(PinRules.isWellFormed("12 4"))
        // Eastern Arabic digits are digits to a reader and are not what a keypad types.
        assertFalse(PinRules.isWellFormed("١٢٣٤"))
    }

    @Test
    fun `something that is not four digits is never the password`() = runTest {
        assertFalse(keeper.matches(""))
        assertFalse(keeper.matches("000"))
        assertFalse(keeper.matches("00000"))
        assertFalse(keeper.matches("abcd"))
    }

    @Test
    fun `the current password changes it`() = runTest {
        assertEquals(PinChange.Changed, keeper.change(current = "0000", new = "4821"))

        assertTrue(keeper.matches("4821"))
        assertFalse("the old one stops working", keeper.matches("0000"))
    }

    @Test
    fun `a wrong current password changes nothing`() = runTest {
        assertEquals(PinChange.WrongCurrent, keeper.change(current = "1111", new = "4821"))

        assertTrue(keeper.matches("0000"))
        assertFalse(keeper.matches("4821"))
        assertEquals(0, slot.writes)
    }

    @Test
    fun `a new password that is not four digits changes nothing`() = runTest {
        assertEquals(PinChange.Malformed, keeper.change(current = "0000", new = "12"))
        assertEquals(PinChange.Malformed, keeper.change(current = "0000", new = "12345"))
        assertEquals(PinChange.Malformed, keeper.change(current = "0000", new = "12ab"))

        assertTrue(keeper.matches("0000"))
        assertEquals(0, slot.writes)
    }

    @Test
    fun `the new password is asked for from then on, not the one before it`() = runTest {
        keeper.change("0000", "1357")

        assertEquals(PinChange.WrongCurrent, keeper.change(current = "0000", new = "2468"))
        assertEquals(PinChange.Changed, keeper.change(current = "1357", new = "2468"))
        assertTrue(keeper.matches("2468"))
        assertFalse(keeper.matches("1357"))
    }

    @Test
    fun `the digits are never what is stored`() = runTest {
        keeper.change("0000", "4821")

        val stored = slot.stored!!
        assertNotEquals("4821", stored.digest)
        assertFalse("the digits are not in the salt either", "4821" in stored.salt)
    }

    @Test
    fun `every change is salted afresh`() = runTest {
        keeper.change("0000", "4821")
        val first = slot.stored!!.salt
        keeper.change("4821", "4821")

        assertNotEquals(first, slot.stored!!.salt)
    }

    @Test
    fun `a password can be changed back to the default`() = runTest {
        keeper.change("0000", "4821")
        assertEquals(PinChange.Changed, keeper.change("4821", "0000"))

        assertTrue(keeper.matches("0000"))
        assertFalse(keeper.matches("4821"))
    }

    @Test
    fun `texts are the same only when every character is`() {
        assertTrue(sameText("4821", "4821"))
        assertFalse(sameText("4821", "4822"))
        assertFalse(sameText("4821", "3821"))
        assertFalse(sameText("4821", "482"))
        assertTrue(sameText("", ""))
    }
}
