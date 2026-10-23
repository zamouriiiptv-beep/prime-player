package com.castivio.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** A typed search, reduced to the question it asks: the same question is the same value. */
class SearchTermTest {

    @Test
    fun `case does not make a different search`() {
        assertEquals("bein sports", searchTermOf("BeIN SPORTS"))
        assertEquals(searchTermOf("bein sports"), searchTermOf("BEIN sports"))
    }

    @Test
    fun `extra spaces at either end and between words are not part of it`() {
        assertEquals("bein sports", searchTermOf("   bein    sports  "))
        assertEquals("bein", searchTermOf("bein "))
        assertEquals("bein", searchTermOf(" bein"))
        assertEquals("bein sports", searchTermOf("bein\tsports\n"))
    }

    @Test
    fun `punctuation separates words and is not searched for`() {
        assertEquals("bein sports", searchTermOf("bein, sports!"))
        assertEquals("fr secret story hd", searchTermOf("FR| SECRET STORY HD"))
        assertEquals("tom jerry", searchTermOf("tom - jerry"))
    }

    @Test
    fun `nothing searchable is empty`() {
        assertEquals("", searchTermOf(""))
        assertEquals("", searchTermOf("    "))
        assertEquals("", searchTermOf("--- ***"))
    }

    @Test
    fun `digits are words`() {
        assertEquals("4k 24", searchTermOf(" 4K  24 "))
    }

    @Test
    fun `any script is a word`() {
        assertEquals("новости", searchTermOf("Новости"))
        assertEquals("قناة الجزيرة", searchTermOf("  قناة   الجزيرة "))
    }

    @Test
    fun `different words are different searches`() {
        assertNotEquals(searchTermOf("bein"), searchTermOf("bein sports"))
    }

    @Test
    fun `a term is its own fixed point`() {
        for (typed in listOf("BeIN  Sports ", "İstanbul TV", "fr| secret", "قناة  الجزيرة")) {
            val once = searchTermOf(typed)
            assertEquals(once, searchTermOf(once))
        }
    }
}
