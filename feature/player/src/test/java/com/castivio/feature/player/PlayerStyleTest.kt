package com.castivio.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The caption settings, as claims a layout test on this runner cannot make.
 *
 * Robolectric measures every `Text` at the same height whatever its style — `PlayerLayoutTest`
 * documents that at length — so "a bigger setting makes a bigger caption" is not something a
 * composed test here can honestly assert. It is a claim about the mapping, so it is tested as
 * one, with no composition, no runner and no layout pass.
 */
class PlayerStyleTest {

    /**
     * Four sizes, all different, in order.
     *
     * The whole promise of the setting. Two steps that happened to resolve to the same point
     * size would be two rows in a sheet that do the same thing, which is worse than offering
     * three — and nothing else in the product would notice.
     */
    @Test
    fun `the four sizes are four different sizes, increasing`() {
        val points = SubtitleSize.entries.map { it.type().fontSize.value }

        assertEquals("a size resolved to the same type as another", points.distinct(), points)
        assertEquals("and they must go up, in the order the sheet lists them", points.sorted(), points)
        assertTrue("the smallest caption must still be readable", points.first() >= LEGIBLE)
    }

    /** The default is the middle of the four, which is what a default of a scale means. */
    @Test
    fun `the default size is neither the smallest nor the largest`() {
        val chosen = SubtitleStyle().size
        assertTrue(chosen != SubtitleSize.entries.first())
        assertTrue(chosen != SubtitleSize.entries.last())
    }

    /**
     * The default backdrop is not "none".
     *
     * White text on a white frame is invisible, and a viewer who has never opened this sheet
     * has not asked for that. The default carries an outline, which costs nothing of the
     * picture and cannot fail that way.
     */
    @Test
    fun `the default caption cannot vanish into a bright frame`() {
        assertTrue(
            "a default of None puts unoutlined white text over an unknown film",
            SubtitleStyle().backdrop != SubtitleBackdrop.None,
        )
    }

    /** Twelve points is the floor below which a caption is decoration rather than text. */
    private companion object {
        const val LEGIBLE = 12f
    }
}
