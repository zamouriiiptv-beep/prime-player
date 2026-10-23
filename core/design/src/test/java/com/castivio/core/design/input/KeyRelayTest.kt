package com.castivio.core.design.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who has the keys: nobody until a screen claims them, the newest claim, and only its own release. */
class KeyRelayTest {

    private class Log(private val takes: Boolean) : KeyRelay.KeyTaker {
        val seen = mutableListOf<Triple<Int, Boolean, Int>>()
        override fun take(keyCode: Int, down: Boolean, repeats: Int): Boolean {
            seen += Triple(keyCode, down, repeats)
            return takes
        }
    }

    @Test
    fun `with no claim every key is left to the focus`() {
        assertFalse(KeyRelay().offer(keyCode = 166, down = true, repeats = 0))
    }

    @Test
    fun `a claim is offered every key, and what it answers is what the activity does`() {
        val relay = KeyRelay()
        val taking = Log(takes = true)
        relay.claim(taking)

        assertTrue(relay.offer(166, down = true, repeats = 0))
        assertTrue(relay.offer(166, down = false, repeats = 0))
        assertEquals(listOf(Triple(166, true, 0), Triple(166, false, 0)), taking.seen)
    }

    @Test
    fun `a key the claim declines goes on to the focus`() {
        val relay = KeyRelay()
        relay.claim(Log(takes = false))

        assertFalse(relay.offer(19, down = true, repeats = 0))
    }

    @Test
    fun `a release gives the keys back`() {
        val relay = KeyRelay()
        val release = relay.claim(Log(takes = true))

        release()

        assertFalse(relay.offer(166, down = true, repeats = 0))
    }

    @Test
    fun `the newest claim has the keys`() {
        val relay = KeyRelay()
        val first = Log(takes = true)
        val second = Log(takes = true)
        relay.claim(first)
        relay.claim(second)

        relay.offer(166, down = true, repeats = 0)

        assertEquals(0, first.seen.size)
        assertEquals(1, second.seen.size)
    }

    @Test
    fun `a screen that was replaced and then goes away does not take the keys from the one that replaced it`() {
        val relay = KeyRelay()
        val releaseFirst = relay.claim(Log(takes = true))
        val second = Log(takes = true)
        relay.claim(second)

        releaseFirst()

        assertTrue(relay.offer(166, down = true, repeats = 0))
        assertEquals(1, second.seen.size)
    }

    @Test
    fun `releasing twice is harmless`() {
        val relay = KeyRelay()
        val release = relay.claim(Log(takes = true))
        release()
        val later = Log(takes = true)
        relay.claim(later)

        release()

        assertTrue(relay.offer(166, down = true, repeats = 0))
    }
}
