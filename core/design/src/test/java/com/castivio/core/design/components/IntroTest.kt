package com.castivio.core.design.components

import com.castivio.core.design.theme.MotionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The startup mark, checked against the film that was approved.
 *
 * ## Why the arithmetic and not the frames
 *
 * Because the approval was of a timeline, and a timeline is numbers. Every
 * claim below is one of the four beats the design states, and each is answered
 * in microseconds by a pure function that reads no clock and needs no device.
 *
 * What a rendered test would add on top is that the modifiers are attached,
 * which is one line of composition and the part that does not silently drift.
 * What it could not add is confidence about *timing* — this environment has no
 * device to iterate a frame-stepped animation against, and three attempts at
 * one on the licence screen's crossfade cost three CI rounds and proved
 * nothing.
 */
class IntroTest {

    // -- Whether it plays at all --------------------------------------------

    /**
     * Motion off means no intro, not a fast one.
     *
     * A user who has turned animation off has said what they want, and 1.2
     * seconds of brand is exactly the kind of thing they turned it off to stop.
     *
     * The caller reads this *before* composing anything, which is the part that
     * matters and the part a test can only half-see: an intro that ended on its
     * first frame would still paint that frame black over the first screen, so
     * "skipped" and "instant" are different behaviours and only one of them was
     * asked for. `MainActivity` seeds its state from this rather than checking
     * it in the condition, so the answer is fixed at launch.
     */
    @Test
    fun `motion off skips the intro`() {
        assertTrue("motion is off and the mark still plays", !playsIntro(MotionLevel.DISABLED))
    }

    /**
     * Reduced motion keeps it.
     *
     * Deliberate, and worth stating because the opposite is a reasonable guess.
     * Reduced-motion guidance is about *movement* — travel, parallax, large
     * scale changes — and this is an opacity ramp with 4% of scale on it. The
     * licence screen's crossfade is exempt for the same reason.
     */
    @Test
    fun `every other motion level keeps it`() {
        for (level in MotionLevel.entries - MotionLevel.DISABLED) {
            assertTrue("$level lost the intro", playsIntro(level))
        }
    }

    /**
     * The first fifth of a second is black, and that is not "nearly black".
     *
     * It is the beat the whole thing rests on: a mark that begins fading up
     * from frame zero reads as a slow app, and one that arrives out of real
     * darkness reads as a deliberate one.
     */
    @Test
    fun `nothing at all is drawn for the first 200ms`() {
        for (t in 0L..200L step 20L) {
            val f = introFrame(t)
            assertEquals("the mark is visible at ${t}ms", 0f, f.markAlpha, 0f)
            assertEquals("the glow is visible at ${t}ms", 0f, f.glowAlpha, 0f)
            assertEquals("the application shows at ${t}ms", 0f, f.appAlpha, 0f)
        }
    }

    /** 96% to 100%, and never past either end. */
    @Test
    fun `the mark scales from 96 to 100 percent and stops there`() {
        assertEquals(0.96f, introFrame(0L).markScale, 0.0005f)
        assertEquals(0.96f, introFrame(200L).markScale, 0.0005f)
        assertEquals(1.00f, introFrame(800L).markScale, 0.0005f)
        for (t in 800L..INTRO_MS step 25L) {
            assertEquals("the mark moved after it landed", 1f, introFrame(t).markScale, 0.0005f)
        }
    }

    /**
     * Full opacity at 620ms — before the scale settles, on purpose.
     *
     * Legible before it has finished moving is what stops a 600ms entrance
     * feeling like a 600ms wait.
     */
    @Test
    fun `the mark is fully opaque before it has finished settling`() {
        assertEquals(1f, introFrame(620L).markAlpha, 0.001f)
        assertTrue(
            "the mark reached full opacity at the same moment it stopped scaling",
            introFrame(620L).markScale < 1f,
        )
    }

    /**
     * The mark rises without ever going backwards.
     *
     * A decelerating curve should be monotonic; asserting it is how a future
     * change of easing to something with overshoot gets caught here rather than
     * on somebody's television, where an intro that flickers is unmissable.
     */
    @Test
    fun `the entrance never reverses`() {
        var previous = -1f
        for (t in 0L..800L step 10L) {
            val alpha = introFrame(t).markAlpha
            assertTrue("the mark dimmed at ${t}ms", alpha >= previous - 1e-6f)
            previous = alpha
        }
    }

    /**
     * The mark is perfectly still through the hold.
     *
     * "Still" is the requirement and it is easy to lose: the breath is on the
     * glow, and a breath that leaked into the mark would be a logo that
     * wobbles.
     */
    @Test
    fun `the mark does not move or fade during the hold`() {
        for (t in 800L..1100L step 20L) {
            val f = introFrame(t)
            assertEquals("the mark faded at ${t}ms", 1f, f.markAlpha, 0.0005f)
            assertEquals("the mark moved at ${t}ms", 1f, f.markScale, 0.0005f)
        }
    }

    /** And the glow does breathe, in scale, by about a percent. */
    @Test
    fun `the glow breathes and stays under two percent of scale`() {
        val scales = (800L..1100L step 10L).map { introFrame(it).glowScale }
        val swing = scales.max() - scales.min()
        assertTrue("the glow does not breathe at all", swing > 0.002f)
        assertTrue("the glow breathes by $swing, which is a pulse", swing < 0.02f)
    }

    /**
     * The glow never outshines the mark, at any frame.
     *
     * ## The version of this that was wrong
     *
     * It compared the two *layer* alphas, and failed — correctly. Through the
     * handover the breath is still running, so the glow's layer alpha is up to
     * 8% above the mark's for those hundred milliseconds. That is in the
     * approved film and is not a defect; it is what the film does.
     *
     * The rule is about light, and the glow's layer alpha is not its light: it
     * multiplies a gradient that peaks at [GLOW_PEAK]. A field at layer alpha
     * 0.54 draws its brightest pixel at 0.084, against a mark drawn at 0.5 in
     * full-strength violet. So the claim is made in the terms it is true in,
     * and the margin it holds by — better than six to one — is stated rather
     * than left as "well under".
     */
    @Test
    fun `the glow is never brighter than the mark`() {
        for (t in 0L..INTRO_MS step 5L) {
            val f = introFrame(t)
            val light = f.glowAlpha * GLOW_PEAK
            assertTrue(
                "at ${t}ms the field's brightest pixel is $light against a mark at ${f.markAlpha}",
                light <= f.markAlpha + 1e-6f,
            )
        }
    }

    /**
     * And the breath's overshoot is bounded, so it stays a breath.
     *
     * The layer alpha does exceed the mark's during the handover, by exactly
     * the breath. Pinned at 8% because that is the number the film was approved
     * with: if a change made it 40%, the glow would visibly outlive the mark on
     * the way out, and nothing else here would notice.
     */
    @Test
    fun `the glow outlives the mark by no more than the breath`() {
        for (t in 0L..INTRO_MS step 5L) {
            val f = introFrame(t)
            assertTrue(
                "at ${t}ms the glow layer is ${f.glowAlpha} against a mark at ${f.markAlpha}",
                f.glowAlpha <= f.markAlpha * 1.08f + 1e-6f,
            )
        }
    }

    /**
     * The handover happens in the last tenth of a second and finishes exactly.
     *
     * `finished` is what the caller watches to stop composing the intro, so an
     * `appAlpha` that reached 0.999 and stopped would leave a transparent
     * full-screen box over the application eating every touch.
     */
    @Test
    fun `the application is handed over in the last 100ms, completely`() {
        assertEquals("the handover started early", 0f, introFrame(1100L).appAlpha, 0f)
        assertTrue("the handover had not begun by 1150ms", introFrame(1150L).appAlpha > 0f)
        assertEquals("the handover did not complete", 1f, introFrame(INTRO_MS).appAlpha, 0f)
        assertTrue("the intro never reports itself finished", introFrame(INTRO_MS).finished)
        assertTrue("the intro finished early", !introFrame(INTRO_MS - 1).finished)
    }

    /** And it takes the mark and the glow with it. */
    @Test
    fun `nothing of the intro is left when it is over`() {
        val f = introFrame(INTRO_MS)
        assertEquals(0f, f.markAlpha, 0f)
        assertEquals(0f, f.glowAlpha, 0f)
    }

    /**
     * A frame clock that overshoots does not produce a fifth beat.
     *
     * `withFrameNanos` is not guaranteed to land on 1200; a dropped frame can
     * deliver 1216 instead. Clamping is what makes that the same picture as
     * 1200 rather than an extrapolation past the end of the film.
     */
    @Test
    fun `an overshooting clock still lands on the last frame`() {
        assertEquals(introFrame(INTRO_MS), introFrame(INTRO_MS + 400L))
    }
}
