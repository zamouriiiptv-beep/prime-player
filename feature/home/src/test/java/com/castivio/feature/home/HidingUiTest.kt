package com.castivio.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the board is told while it is choosing what to hide: that the rail's bouquets and the list's
 * channels are both being chosen, and that a tick on one kind is never a tick on the other.
 */
class HidingUiTest {

    private fun ui(hiding: Hiding?) = HidingUi(
        hiding = hiding,
        toggleBouquet = {},
        toggleChannel = {},
        confirm = {},
        cancel = {},
        press = {},
        hold = {},
    )

    @Test
    fun `a board that is not choosing marks nothing`() {
        val none = HidingUi.None

        assertFalse(none.active)
        assertFalse(none.bouquets)
        assertFalse(none.channels)
        assertNull(none.bouquetMarkOf("g"))
        assertNull(none.channelMarkOf("c"))
    }

    @Test
    fun `choosing marks bouquets and channels at once, ticked or not`() {
        val choosing = ui(Hiding(bouquets = setOf("g1"), channels = setOf("c1")))

        assertTrue(choosing.active)
        assertTrue(choosing.bouquets)
        assertTrue(choosing.channels)
        assertEquals(RowPick(picked = true, favorite = false), choosing.bouquetMarkOf("g1"))
        assertEquals(RowPick(picked = false, favorite = false), choosing.bouquetMarkOf("g2"))
        assertEquals(RowPick(picked = true, favorite = false), choosing.channelMarkOf("c1"))
        assertEquals(RowPick(picked = false, favorite = false), choosing.channelMarkOf("c2"))
    }

    @Test
    fun `a tick on a bouquet is not a tick on a channel with the same id`() {
        val choosing = ui(Hiding(bouquets = setOf("x")))

        assertEquals(RowPick(picked = true, favorite = false), choosing.bouquetMarkOf("x"))
        assertEquals(RowPick(picked = false, favorite = false), choosing.channelMarkOf("x"))
    }

    @Test
    fun `just begun, every row has an empty circle and Hide selected has nothing to do`() {
        val begun = ui(Hiding())

        assertEquals(RowPick(picked = false, favorite = false), begun.bouquetMarkOf("g"))
        assertEquals(RowPick(picked = false, favorite = false), begun.channelMarkOf("c"))
        assertFalse(Hiding().any)
    }

    @Test
    fun `either kind ticked is something to hide`() {
        assertTrue(Hiding(bouquets = setOf("g")).any)
        assertTrue(Hiding(channels = setOf("c")).any)
    }

    @Test
    fun `a tick is put back by the same press, and the ticks keep no order`() {
        val first = Hiding().channels.toggled("b").toggled("a")
        assertEquals(setOf("a", "b"), first)
        assertEquals(setOf("a"), first.toggled("b"))
    }
}
