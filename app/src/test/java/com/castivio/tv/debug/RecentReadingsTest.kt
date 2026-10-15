package com.castivio.tv.debug

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the clipboard is given after each load in a debuggable build. */
class RecentReadingsTest {

    @Test
    fun `each load is kept, oldest first, a blank line between`() {
        val readings = RecentReadings(capacity = 4)

        readings.add(listOf("── CHANNELS · run 1 · COLD · REPLACE ──", "full load 59.20 s"))
        val text = readings.add(listOf("── CHANNELS · run 2 · COLD · APPEND ──", "full load 36.85 s"))

        assertEquals(
            "── CHANNELS · run 1 · COLD · REPLACE ──\nfull load 59.20 s\n\n" +
                "── CHANNELS · run 2 · COLD · APPEND ──\nfull load 36.85 s",
            text,
        )
    }

    /** A reading survives the loads after it, up to the bound; past it, the oldest goes. */
    @Test
    fun `past the bound the oldest load is dropped`() {
        val readings = RecentReadings(capacity = 2)

        readings.add(listOf("run 1"))
        readings.add(listOf("run 2"))
        val text = readings.add(listOf("run 3"))

        assertEquals("run 2\n\nrun 3", text)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a clipboard that keeps nothing is a mistake`() {
        RecentReadings(capacity = 0)
    }
}
