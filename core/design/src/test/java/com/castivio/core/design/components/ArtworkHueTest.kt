package com.castivio.core.design.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which of the four hues a channel without a logo is drawn in: stable, in range, and not all the same. */
class ArtworkHueTest {

    @Test
    fun `a name falls on one of the four hues`() {
        val names = listOf("ES| DAZN BAR 1 FHD", "ES| DAZN 3 FHD", "DE| DYN PPV 6 HD", "", "é", "MOTOGP EVENTO 13 | No event")

        for (name in names) {
            val index = ArtworkHue.indexOf(name)
            assertTrue("$name -> $index", index in 0 until ArtworkHue.COUNT)
        }
    }

    @Test
    fun `a name always falls on the same hue, whatever the spaces around it`() {
        assertEquals(ArtworkHue.indexOf("ES| DAZN 3 FHD"), ArtworkHue.indexOf("ES| DAZN 3 FHD"))
        assertEquals(ArtworkHue.indexOf("ES| DAZN 3 FHD"), ArtworkHue.indexOf("  ES| DAZN 3 FHD "))
    }

    @Test
    fun `names that differ only in a number are not all one colour`() {
        val hues = (1..12).map { ArtworkHue.indexOf("DE| DYN PPV $it HD") }.toSet()

        // Twelve channels of one family: every hue is used, which is the variety the colour is for.
        assertEquals(ArtworkHue.COUNT, hues.size)
    }

    @Test
    fun `a longer list is spread, not piled on one hue`() {
        val counts = IntArray(ArtworkHue.COUNT)
        for (n in 1..400) counts[ArtworkHue.indexOf("ES| CHANNEL NAME $n")]++

        // 400 names over four hues: none of them is empty or holds more than half.
        for (count in counts) assertTrue(counts.joinToString(), count in 1..200)
    }
}
