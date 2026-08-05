package com.castivio.core.design.components

import com.castivio.core.design.theme.MotionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What focus does to a purchase card, as numbers.
 *
 * ## Why the numbers and not the frames
 *
 * Because the specification is a set of numbers — 180 to 220 milliseconds, a
 * scale of about 1.03, a heavier border, a glow — and every one of them can be
 * checked here in microseconds. Frame-stepping the real animation needs
 * Compose's test clock driven by hand, which this environment has no device to
 * iterate against; three attempts at that on the licence screen's crossfade
 * cost three CI rounds and proved nothing.
 *
 * What a rendered test would add on top of this is that the modifiers are
 * actually attached, and `LicenceLayoutTest` already places and measures the
 * cards in every entitlement state.
 */
class PlanCardFocusTest {

    /** The window the design states, asserted as a window. */
    @Test
    fun `focus animates inside the window the design calls for`() {
        for (level in MotionLevel.entries - MotionLevel.DISABLED) {
            val millis = planFocusMillis(level)
            assertTrue(
                "$level animates focus over ${millis}ms, outside the 180-220ms window",
                millis in 180..220,
            )
        }
    }

    /**
     * Motion off is a snap, not a quick animation.
     *
     * Zero is the value `focusSpec` reads as "use `snap()`". A short tween would
     * be a smaller version of the thing the user asked not to have.
     */
    @Test
    fun `motion off snaps`() {
        assertEquals(
            "a card still animates its focus when motion is disabled",
            0,
            planFocusMillis(MotionLevel.DISABLED),
        )
    }

    /**
     * Slight, and slight is the requirement.
     *
     * The shared button value is 1.05 and it is too much here: at 210dp wide it
     * moves a card's outer edge five dp, which on a row of two reads as the pair
     * shifting rather than one of them lifting. Bounded on both sides — a scale
     * that stopped growing would be as wrong as one that grew too far.
     */
    @Test
    fun `the focused card grows slightly and only slightly`() {
        assertTrue(
            "a focused card scales to $FOCUSED_SCALE, which is not slight",
            FOCUSED_SCALE in 1.02f..1.04f,
        )
    }

    /** Focus thickens the hairline rather than replacing it with a shape. */
    @Test
    fun `focus makes the border stronger`() {
        assertTrue(
            "the focused border is not heavier than the resting one",
            FOCUSED_STROKE > RESTING_STROKE,
        )
    }

    /**
     * And casts a glow, which an unfocused card does not.
     *
     * Zero at rest matters as much as non-zero focused: a row of cards all
     * casting a shadow is a row of cards that all look focused.
     */
    @Test
    fun `focus lifts the card off the background`() {
        assertTrue("a focused card casts no glow", FOCUSED_LIFT.value > 0f)
    }
}
