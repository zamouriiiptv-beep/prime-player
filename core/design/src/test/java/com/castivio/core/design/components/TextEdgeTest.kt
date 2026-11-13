package com.castivio.core.design.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a provider's words are set on a page that follows the language's direction.
 *
 * The page's own words are in the page's language and are set as they always were. The provider's -- an English title and
 * plot on an Arabic page -- run the other way, and are set against the same edge without being turned round.
 */
class TextEdgeTest {

    @Test
    fun `an English text reads left to right, an Arabic one right to left`() {
        assertEquals(false, TextEdge.readsRtl("EN - Ben-Hur (1959)"))
        assertEquals(true, TextEdge.readsRtl("الممثلون: Charlton Heston"))
    }

    @Test
    fun `the first letter with a direction decides, and what comes before it does not`() {
        assertEquals("a year and brackets before a Latin name", false, TextEdge.readsRtl("(1959) Ben-Hur"))
        assertEquals("a figure before an Arabic word", true, TextEdge.readsRtl("2024 فيلم"))
        assertEquals("the unit letters of an isolated duration are Latin letters", false, TextEdge.readsRtl("⁦3h 42m⁩ فيلم"))
    }

    @Test
    fun `a text with no letter has no direction of its own`() {
        assertNull(TextEdge.readsRtl(""))
        assertNull(TextEdge.readsRtl("1959"))
        assertNull(TextEdge.readsRtl(" - ( ) 10/04/2023"))
    }

    @Test
    fun `a letter outside the first plane is read by its code point and not by half of it`() {
        // U+10900 PHOENICIAN LETTER ALF, a right-to-left letter written as a surrogate pair.
        assertEquals(true, TextEdge.readsRtl("𐤀"))
    }

    @Test
    fun `an English text on an Arabic page is set against the end of its paragraph, which is the page's start edge`() {
        assertTrue(TextEdge.againstEnd(pageIsRtl = true, text = "In 25 AD, Judah Ben-Hur opposes the empire."))
    }

    @Test
    fun `the page's own words are set as they always were`() {
        assertFalse("Arabic on an Arabic page", TextEdge.againstEnd(pageIsRtl = true, text = "القصة"))
        assertFalse("English on an English page", TextEdge.againstEnd(pageIsRtl = false, text = "Plot"))
    }

    @Test
    fun `an Arabic text on a left to right page is set against the end of its paragraph, the page's left`() {
        assertTrue(TextEdge.againstEnd(pageIsRtl = false, text = "ملخص الفيلم"))
    }

    @Test
    fun `figures and symbols alone are set at the start, as the page reads`() {
        assertFalse(TextEdge.againstEnd(pageIsRtl = true, text = "1959"))
        assertFalse(TextEdge.againstEnd(pageIsRtl = false, text = ""))
    }
}
