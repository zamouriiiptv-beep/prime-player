package com.castivio.core.design.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The addresses whose pictures did not come -- see [ArtworkFailures].
 *
 * Time is a number the test sets, so nothing here sleeps and a busy machine cannot make a test fail.
 */
class ArtworkFailuresTest {

    private var now = 0L

    private fun failures(rememberedMs: Long = 1_000L, capacity: Int = 3) =
        ArtworkFailures(rememberedMs = rememberedMs, capacity = capacity) { now }

    @Test
    fun `an address that never failed is not remembered`() {
        assertFalse(failures().isRemembered("http://logos.example/a.png"))
    }

    @Test
    fun `a failure is believed until its time is up, and not a moment after`() {
        val memory = failures(rememberedMs = 1_000L)
        memory.record("a")

        now = 999L
        assertTrue("one millisecond short of the memory", memory.isRemembered("a"))
        now = 1_000L
        assertFalse("the memory is up", memory.isRemembered("a"))
    }

    @Test
    fun `an address whose time is up is asked for again, and can fail again`() {
        val memory = failures(rememberedMs = 1_000L)
        memory.record("a")
        now = 5_000L
        assertFalse(memory.isRemembered("a"))

        memory.record("a")

        assertTrue("a second failure is believed as the first was", memory.isRemembered("a"))
        now = 5_999L
        assertTrue(memory.isRemembered("a"))
        now = 6_000L
        assertFalse(memory.isRemembered("a"))
    }

    @Test
    fun `failing again starts the memory over`() {
        val memory = failures(rememberedMs = 1_000L)
        memory.record("a")
        now = 900L
        memory.record("a")

        now = 1_500L
        assertTrue("the first failure alone would be forgotten by now", memory.isRemembered("a"))
        now = 1_900L
        assertFalse(memory.isRemembered("a"))
    }

    @Test
    fun `each address is believed on its own account`() {
        val memory = failures()
        memory.record("a")

        assertTrue(memory.isRemembered("a"))
        assertFalse("another address is not made suspect by it", memory.isRemembered("b"))
    }

    @Test
    fun `past its capacity it lets go of the address asked about longest ago`() {
        val memory = failures(capacity = 3)
        memory.record("a")
        memory.record("b")
        memory.record("c")
        memory.record("d")

        assertFalse("the first in, never asked about since", memory.isRemembered("a"))
        assertTrue(memory.isRemembered("b"))
        assertTrue(memory.isRemembered("c"))
        assertTrue(memory.isRemembered("d"))
    }

    @Test
    fun `an address that is asked about is not the one let go`() {
        val memory = failures(capacity = 3)
        memory.record("a")
        memory.record("b")
        memory.record("c")
        assertTrue("a row scrolled back to", memory.isRemembered("a"))

        memory.record("d")

        assertTrue("a was used, so b is the oldest", memory.isRemembered("a"))
        assertFalse(memory.isRemembered("b"))
        assertTrue(memory.isRemembered("c"))
        assertTrue(memory.isRemembered("d"))
    }

    @Test
    fun `it is the same size for a long list as for a short one`() {
        val memory = failures(rememberedMs = 1_000_000L, capacity = 50)
        for (i in 0 until 10_000) memory.record("http://logos.example/$i.png")

        val kept = (0 until 10_000).count { memory.isRemembered("http://logos.example/$it.png") }

        assertEquals("never more than the capacity", 50, kept)
        assertTrue("and the latest are the ones kept", memory.isRemembered("http://logos.example/9999.png"))
    }

    @Test
    fun `a memory of no time or of no addresses is refused, not built`() {
        assertThrows(IllegalArgumentException::class.java) { ArtworkFailures(rememberedMs = 0, capacity = 3) }
        assertThrows(IllegalArgumentException::class.java) { ArtworkFailures(rememberedMs = 1_000, capacity = 0) }
    }
}
