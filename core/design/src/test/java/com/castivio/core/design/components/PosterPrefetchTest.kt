package com.castivio.core.design.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which posters are asked for ahead of a wall, as numbers.
 *
 * The rule is what keeps the request honest: it never reaches past the end of the list, which a paged list
 * answers with an exception, and it asks for nothing until something is on screen to measure from.
 */
class PosterPrefetchTest {

    /** Two rows of five after the last card drawn: the next ten, in order. */
    @Test
    fun `the posters after the last one shown are asked for`() {
        assertEquals((10..19).toList(), postersAhead(lastShown = 9, ahead = 10, count = 300).toList())
    }

    /** A list that ends sooner is asked for only as far as it goes. */
    @Test
    fun `the request stops at the end of the list`() {
        assertEquals((12..13).toList(), postersAhead(lastShown = 11, ahead = 10, count = 14).toList())
        assertTrue(postersAhead(lastShown = 13, ahead = 10, count = 14).isEmpty())
    }

    /** Nothing shown means nothing to measure from; the cards on screen ask for themselves. */
    @Test
    fun `nothing is asked for before a card is shown`() {
        assertTrue(postersAhead(lastShown = -1, ahead = 10, count = 300).isEmpty())
    }

    @Test
    fun `an empty list or no look-ahead asks for nothing`() {
        assertTrue(postersAhead(lastShown = 4, ahead = 10, count = 0).isEmpty())
        assertTrue(postersAhead(lastShown = 4, ahead = 0, count = 300).isEmpty())
    }
}
