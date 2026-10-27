package com.castivio.core.design.components

import com.castivio.core.design.theme.MotionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a poster's own colours come in beside it, as numbers.
 *
 * The colours are asked for after the poster is on screen, so they arrive a moment later; the fade is what
 * keeps that moment from reading as a flash. Whether the modifiers are attached is for a rendered test --
 * this holds the one decision that is a number: how long, and that "no motion" means none.
 */
class PosterBackdropFadeTest {

    /** Long enough to be a fade and not a pop, short enough that a scrolling grid never waits on it. */
    @Test
    fun `the colours fade in over a short, bounded time`() {
        for (level in MotionLevel.entries - MotionLevel.DISABLED) {
            val millis = backdropFadeMillis(level)
            assertTrue("$level fades the backdrop over ${millis}ms, outside 100-300ms", millis in 100..300)
        }
    }

    /** Motion off is a snap, not a quick animation: the user asked for no transitions at all. */
    @Test
    fun `motion off snaps`() {
        assertEquals(
            "the backdrop still fades when motion is disabled",
            0,
            backdropFadeMillis(MotionLevel.DISABLED),
        )
    }
}
