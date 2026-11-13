package com.castivio.core.design.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * When a card draws a rating, which is decided apart from the drawing so it is held without a device.
 *
 * The mark is the gold star and the figure at the poster's top-end corner (drawn: `movies-poster-rating-ar`). It
 * shares that edge with nothing the card draws except its top-start badge, and the badge wins.
 */
class PosterRatingTest {

    @Test
    fun `a card with a rating and no badge draws the rating as it was given`() {
        assertEquals("7.1", ratingShown("7.1", hasBadge = false))
        assertEquals("10.0", ratingShown("10.0", hasBadge = false))
    }

    @Test
    fun `a card with its top-start badge draws no rating, so the badge is not crowded`() {
        assertNull(ratingShown("7.1", hasBadge = true))
    }

    @Test
    fun `no figure, or a blank one, is no mark`() {
        assertNull(ratingShown(null, hasBadge = false))
        assertNull(ratingShown("", hasBadge = false))
        assertNull("an empty pill would be a mark with nothing in it", ratingShown("   ", hasBadge = false))
    }
}
