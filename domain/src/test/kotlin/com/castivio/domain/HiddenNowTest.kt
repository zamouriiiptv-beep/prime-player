package com.castivio.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What has been hidden while the app is open, as the rules a screen leaves rows out by. */
class HiddenNowTest {

    @Test
    fun `a hidden channel is that channel and no other`() {
        val now = HiddenNow().with(HiddenKind.CHANNEL, listOf("c1"))

        assertTrue(now.hidesChannel("c1", groupId = "sports"))
        assertFalse(now.hidesChannel("c2", groupId = "sports"))
    }

    @Test
    fun `a hidden bouquet hides every channel in it, and none outside it`() {
        val now = HiddenNow().with(HiddenKind.BOUQUET, listOf("sports"))

        assertTrue(now.hidesChannel("c1", groupId = "sports"))
        assertTrue(now.hidesChannel("c2", groupId = "sports"))
        assertFalse(now.hidesChannel("c3", groupId = "news"))
        assertFalse(now.hidesChannel("c4", groupId = null))
    }

    @Test
    fun `a write that failed takes back only what it was for`() {
        val now = HiddenNow()
            .with(HiddenKind.CHANNEL, listOf("c1", "c2"))
            .with(HiddenKind.BOUQUET, listOf("sports"))

        val back = now.without(HiddenKind.CHANNEL, listOf("c2"))

        assertEquals(setOf("c1"), back.channels)
        assertEquals(setOf("sports"), back.bouquets)
    }

    @Test
    fun `a restore of one kind leaves the other`() {
        val now = HiddenNow()
            .with(HiddenKind.CHANNEL, listOf("c1"))
            .with(HiddenKind.BOUQUET, listOf("sports"))

        assertEquals(HiddenNow(bouquets = setOf("sports")), now.cleared(HiddenKind.CHANNEL))
        assertEquals(HiddenNow(channels = setOf("c1")), now.cleared(HiddenKind.BOUQUET))
    }

    @Test
    fun `nothing hidden here hides nothing`() {
        assertFalse(HiddenNow().hidesChannel("c1", groupId = "sports"))
        assertEquals(HiddenNow(), HiddenItems.None.recent.value)
    }
}
