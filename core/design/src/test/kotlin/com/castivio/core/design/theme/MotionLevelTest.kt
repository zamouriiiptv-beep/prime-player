package com.castivio.core.design.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionLevelTest {

    // ------------------------------------------------------------- what each level does

    @Test
    fun `full animates everything the identity is made of`() {
        val level = MotionLevel.FULL

        assertTrue(level.backdropAnimates)
        assertTrue(level.focusTravels)
        assertTrue(level.meterAnimates)
        assertTrue(level.countsTick)
        assertTrue(level.rowScrollAnimates)
        assertEquals(ScreenTransition.Emphasised, level.screenTransition)
    }

    /**
     * Reduced stops the interface travelling but keeps it responsive. Focus in
     * particular: animating the lift is what makes a D-pad feel laggy on a weak box,
     * and it is the first thing a motion-sensitive user asks to lose.
     */
    @Test
    fun `reduced keeps the interface responsive and stops it travelling`() {
        val level = MotionLevel.REDUCED

        assertFalse(level.backdropAnimates)
        assertFalse(level.focusTravels)
        assertFalse(level.meterAnimates)
        assertFalse(level.countsTick)
        assertTrue(level.rowScrollAnimates)
        assertEquals(ScreenTransition.CrossFade, level.screenTransition)
    }

    @Test
    fun `disabled moves nothing at all`() {
        val level = MotionLevel.DISABLED

        assertFalse(level.backdropAnimates)
        assertFalse(level.focusTravels)
        assertFalse(level.meterAnimates)
        assertFalse(level.countsTick)
        assertFalse(level.rowScrollAnimates)
        assertEquals(ScreenTransition.None, level.screenTransition)
        assertEquals(0, level.transitionMillis)
    }

    @Test
    fun `transitions shorten as motion is reduced`() {
        assertTrue(MotionLevel.FULL.transitionMillis > MotionLevel.REDUCED.transitionMillis)
        assertTrue(MotionLevel.REDUCED.transitionMillis > MotionLevel.DISABLED.transitionMillis)
    }

    // ------------------------------------------------------------------ resolution

    @Test
    fun `an unmeasured device gets frame rate rather than effects`() {
        assertEquals(MotionLevel.REDUCED, resolveMotionLevel())
    }

    @Test
    fun `a capable device with nothing asked of it gets everything`() {
        assertEquals(
            MotionLevel.FULL,
            resolveMotionLevel(deviceCanAnimate = true),
        )
    }

    @Test
    fun `animations switched off at the platform level are an instruction`() {
        assertEquals(
            MotionLevel.DISABLED,
            resolveMotionLevel(systemAnimationsDisabled = true, deviceCanAnimate = true),
        )
    }

    @Test
    fun `the platform's softer preference asks for calm rather than stillness`() {
        assertEquals(
            MotionLevel.REDUCED,
            resolveMotionLevel(systemPrefersReducedMotion = true, deviceCanAnimate = true),
        )
    }

    @Test
    fun `switched off outright wins over merely preferring less`() {
        assertEquals(
            MotionLevel.DISABLED,
            resolveMotionLevel(
                systemAnimationsDisabled = true,
                systemPrefersReducedMotion = true,
                deviceCanAnimate = true,
            ),
        )
    }

    /**
     * The automatic choice is a starting point, never a ceiling. Both directions matter:
     * stillness on a capable box, and the full identity on a weak stick for a user who
     * would rather have it and live with the frame rate.
     */
    @Test
    fun `an explicit preference overrides the device in both directions`() {
        assertEquals(
            MotionLevel.DISABLED,
            resolveMotionLevel(MotionPreference.Disabled, deviceCanAnimate = true),
        )
        assertEquals(
            MotionLevel.FULL,
            resolveMotionLevel(MotionPreference.Full, deviceCanAnimate = false),
        )
        assertEquals(
            MotionLevel.REDUCED,
            resolveMotionLevel(MotionPreference.Reduced, deviceCanAnimate = true),
        )
    }

    /**
     * A user who set the level explicitly has already worked around whatever the
     * platform setting says. Quietly overriding them would make the setting a lie.
     */
    @Test
    fun `an explicit preference is honoured over the platform setting`() {
        assertEquals(
            MotionLevel.FULL,
            resolveMotionLevel(
                preference = MotionPreference.Full,
                systemAnimationsDisabled = true,
                systemPrefersReducedMotion = true,
            ),
        )
    }

    // -------------------------------------------------------------- capability mapping

    @Test
    fun `capability maps to a suggestion, not to a decision`() {
        assertEquals(MotionLevel.FULL, PerformanceProfile.FULL.suggestedMotion)
        assertEquals(MotionLevel.FULL, PerformanceProfile.BALANCED.suggestedMotion)
        assertEquals(MotionLevel.REDUCED, PerformanceProfile.LEAN.suggestedMotion)
    }

    /**
     * The acceptance test for the whole state design, stated as code: nothing about a
     * state is carried by motion alone, so every level is a complete experience.
     */
    @Test
    fun `every level is a usable level`() {
        for (level in MotionLevel.entries) {
            assertTrue("${level.name} must allow a screen transition to resolve",
                level.transitionMillis >= 0)
            assertEquals(level == MotionLevel.FULL, level.meterAnimates)
        }
    }
}
