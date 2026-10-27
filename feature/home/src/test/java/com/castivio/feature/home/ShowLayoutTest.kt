package com.castivio.feature.home

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a show's page puts its episodes: beside the show from the film page's width, and never narrower than the
 * design draws.
 *
 * It had borrowed the board's 840dp, and the 833dp handset the page was designed on -- the one the mockup's frames are
 * drawn at -- got the stack, which no frame shows.
 */
class ShowLayoutTest {

    @Test
    fun `the handset the page was designed on stands the show beside its episodes`() {
        assertTrue(showSideBySide(833.dp))
        assertTrue(showSideBySide(873.dp))
        assertTrue(showSideBySide(960.dp))
    }

    @Test
    fun `a portrait phone stacks them`() {
        assertFalse(showSideBySide(411.dp))
        assertFalse(showSideBySide(599.dp))
        assertTrue(showSideBySide(600.dp))
    }

    @Test
    fun `the start pane is about 320dp on the handset and a little under 380 on the television, as drawn`() {
        assertEquals(320f, showPaneWidth(833.dp).value, 8f)
        assertEquals(378f, showPaneWidth(960.dp).value, 8f)
    }

    @Test
    fun `the start pane is held between a floor and a ceiling`() {
        assertEquals(260f, showPaneWidth(600.dp).value, 0.01f)
        assertEquals(400f, showPaneWidth(1_920.dp).value, 0.01f)
    }
}
