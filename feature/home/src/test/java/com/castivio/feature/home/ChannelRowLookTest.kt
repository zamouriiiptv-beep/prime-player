package com.castivio.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The remote and the player are two facts, and a channel row says each on its own.
 *
 * Violet is where the remote is; the mark is what the player is showing. These pin the rule
 * the row is drawn from, without a screen: neither fact may read the other.
 */
class ChannelRowLookTest {

    @Test
    fun `moving the remote does not move the mark`() {
        // The playing row, with the remote on it and then off it.
        val under = rowLook(focused = true, playing = true)
        val away = rowLook(focused = false, playing = true)
        assertTrue("the mark is there with the remote on it", under.mark)
        assertTrue("and it is still there when the remote has gone on", away.mark)
    }

    @Test
    fun `a stream starting does not move the violet`() {
        val before = rowLook(focused = true, playing = false)
        val after = rowLook(focused = true, playing = true)
        assertTrue(before.surface && after.surface)
        assertEquals("the violet and its ring are the same either way", before.ring, after.ring)
    }

    @Test
    fun `the violet follows the remote and only the remote`() {
        assertTrue(rowLook(focused = true, playing = false).surface)
        assertTrue(rowLook(focused = true, playing = true).surface)
        assertFalse("a playing row without the remote is not violet", rowLook(focused = false, playing = true).surface)
        assertFalse(rowLook(focused = false, playing = false).surface)
    }

    @Test
    fun `the mark follows the player and only the player`() {
        assertTrue(rowLook(focused = false, playing = true).mark)
        assertFalse("the remote on a row says nothing about playing", rowLook(focused = true, playing = false).mark)
        assertFalse(rowLook(focused = false, playing = false).mark)
    }

    @Test
    fun `the ring goes with the violet so focus does not depend on a colour`() {
        for (focused in listOf(true, false)) {
            for (playing in listOf(true, false)) {
                val look = rowLook(focused, playing)
                assertEquals(look.surface, look.ring)
            }
        }
    }

    @Test
    fun `a row is playing only when the player's stream is its own and it was pressed`() {
        val url = "http://provider.tv/live/2.ts"
        assertTrue(isPlayingRow("2", url, pressedId = "2", playingUrl = url))
        assertFalse("another channel", isPlayingRow("2", url, pressedId = "2", playingUrl = "http://provider.tv/live/3.ts"))
        assertFalse("nothing playing", isPlayingRow("2", url, pressedId = "2", playingUrl = null))
        assertFalse("an empty address is not an answer", isPlayingRow("2", "", pressedId = "2", playingUrl = null))
    }

    @Test
    fun `a stream listed twice marks only the row that was pressed`() {
        val url = "http://provider.tv/live/7.ts"
        val rows = listOf("a" to url, "b" to url, "c" to "http://provider.tv/live/8.ts")
        for (pressed in listOf("a", "b", "c")) {
            val marked = rows.filter { (id, stream) -> isPlayingRow(id, stream, pressedId = pressed, playingUrl = url) }
            assertTrue("at most one row, whichever was pressed: $marked", marked.size <= 1)
        }
        assertEquals(listOf("b" to url), rows.filter { (id, stream) -> isPlayingRow(id, stream, "b", url) })
        assertTrue("and nothing pressed marks none", rows.none { (id, stream) -> isPlayingRow(id, stream, null, url) })
    }

    // ------------------------------------------------------------------ which names scroll

    @Test
    fun `the row the remote is on scrolls its name`() {
        assertTrue(nameScrolls(focused = true, pressed = false))
    }

    @Test
    fun `the row that was pressed scrolls its name though a touch never focused it`() {
        assertTrue(nameScrolls(focused = false, pressed = true))
    }

    @Test
    fun `a row neither focused nor pressed stands cut to fit`() {
        assertFalse(nameScrolls(focused = false, pressed = false))
    }

    // ---------------------------------------------------------- the second press expands

    @Test
    fun `the first press on a channel plays it and the second makes the picture large`() {
        val url = "http://provider.tv/live/7.ts"
        assertFalse("nothing asked for yet: the press plays", isExpandingPress("a", url, pressedId = null, askedUrl = null))
        assertTrue("the channel is in the picture: the press expands", isExpandingPress("a", url, pressedId = "a", askedUrl = url))
    }

    @Test
    fun `a press on another channel plays it, whatever is in the picture`() {
        val url = "http://provider.tv/live/7.ts"
        assertFalse(isExpandingPress("b", "http://provider.tv/live/8.ts", pressedId = "a", askedUrl = url))
    }

    @Test
    fun `a stream listed twice expands only from the row that was pressed`() {
        val url = "http://provider.tv/live/7.ts"
        assertTrue(isExpandingPress("a", url, pressedId = "a", askedUrl = url))
        assertFalse(isExpandingPress("b", url, pressedId = "a", askedUrl = url))
    }

    @Test
    fun `a channel pressed on an earlier visit has no player to expand and plays again`() {
        assertFalse(isExpandingPress("a", "http://provider.tv/live/7.ts", pressedId = "a", askedUrl = null))
    }
}
