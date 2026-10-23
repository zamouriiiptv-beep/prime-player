package com.castivio.feature.home

import com.castivio.domain.HiddenKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the board is told while it is choosing what to hide: which kind is being chosen, what is ticked,
 * and -- the point of there being one value for it -- that bouquets and channels are two modes that
 * never mark each other's rows.
 */
class HidingUiTest {

    private fun ui(hiding: Hiding?) =
        HidingUi(hiding = hiding, toggle = {}, confirm = {}, cancel = {}, press = {}, hold = {})

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
    fun `choosing bouquets marks bouquets, ticked or not, and no channel`() {
        val choosing = ui(Hiding(HiddenKind.BOUQUET, setOf("g1")))

        assertTrue(choosing.active)
        assertTrue(choosing.bouquets)
        assertFalse(choosing.channels)
        assertEquals(RowPick(picked = true, favorite = false), choosing.bouquetMarkOf("g1"))
        assertEquals(RowPick(picked = false, favorite = false), choosing.bouquetMarkOf("g2"))
        assertNull(choosing.channelMarkOf("g1"))
    }

    @Test
    fun `choosing channels marks channels, ticked or not, and no bouquet`() {
        val choosing = ui(Hiding(HiddenKind.CHANNEL, setOf("c1", "c2")))

        assertTrue(choosing.channels)
        assertFalse(choosing.bouquets)
        assertEquals(RowPick(picked = true, favorite = false), choosing.channelMarkOf("c2"))
        assertEquals(RowPick(picked = false, favorite = false), choosing.channelMarkOf("c3"))
        assertNull(choosing.bouquetMarkOf("c1"))
    }

    @Test
    fun `a tick is put back by the same press, and the ticks keep no order`() {
        val first = Hiding(HiddenKind.CHANNEL).picked.toggled("b").toggled("a")
        assertEquals(setOf("a", "b"), first)
        assertEquals(setOf("a"), first.toggled("b"))
    }
}
