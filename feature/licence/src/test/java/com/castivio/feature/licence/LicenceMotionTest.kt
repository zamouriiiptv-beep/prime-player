package com.castivio.feature.licence

import com.castivio.core.design.theme.MotionLevel
import com.castivio.domain.entitlement.EntitlementState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the licence screen animates, for how long, and in response to what.
 *
 * ## Why this is a plain JVM test and not a frame-stepped one
 *
 * Because the two claims worth making are both about a decision rather than
 * about a rendered frame: *how long* the crossover lasts, and *what* it watches.
 * Both are total functions, both are checked in microseconds, and neither can
 * be flaky.
 *
 * The frame-stepped version was written first and failed twice — not because the
 * animation was wrong but because driving Compose's test clock needs the
 * recomposition after a state write to land before the clock advances, and this
 * environment has no device to iterate that ordering against. A test that is
 * hard to get right and tells you nothing when it fails is worse than the two
 * below plus `LicencePolishTest`, which asserts the outcome with the clock left
 * alone.
 */
class LicenceMotionTest {

    /**
     * 200–250ms, which is the window the design asks for.
     *
     * Asserted as a range rather than as the exact number, because the number is
     * a choice inside the window and the window is the requirement. Changing 220
     * to 210 should not fail a test; changing it to 600 should.
     */
    @Test
    fun `the fade is inside the window the design calls for`() {
        for (level in MotionLevel.entries - MotionLevel.DISABLED) {
            val millis = stateFadeMillis(level)
            assertTrue(
                "$level fades for ${millis}ms, outside the 200-250ms window",
                millis in 200..250,
            )
        }
    }

    /**
     * Motion off means no animation, not a fast one.
     *
     * A user who has turned animation off has said what they want, and a 220ms
     * fade is exactly the kind of thing they turned it off to stop. Zero is the
     * value `LicenceFade` reads as "compose the target directly".
     */
    @Test
    fun `motion off is a swap and not a short fade`() {
        assertEquals(
            "motion is disabled and the screen still animates",
            0,
            stateFadeMillis(MotionLevel.DISABLED),
        )
    }

    /**
     * Reduced motion keeps the fade.
     *
     * Deliberate, and worth stating because the opposite is a reasonable guess.
     * Reduced-motion guidance is about *movement* — travel, parallax, scale —
     * which is what makes people ill. An opacity crossfade has none, and
     * removing it would leave a user who asked for less motion with a harder cut
     * than everybody else gets.
     */
    @Test
    fun `reduced motion still fades`() {
        assertNotEquals(
            "reduced motion removed a fade that has no movement in it",
            0,
            stateFadeMillis(MotionLevel.REDUCED),
        )
    }

    // -- What the crossfade watches ----------------------------------------

    private val base = LicenceUiState(
        licence = EntitlementState.Unknown,
        address = "2F:19:EB:20:44:7C",
        deviceKey = "482731",
    )

    /**
     * A change of condition is what animates.
     *
     * The key is the rendered view rather than the entitlement itself, so two
     * entitlements that draw the same chip and the same sentence do not animate
     * between themselves either — which is the behaviour, not an accident of it.
     */
    @Test
    fun `a change of entitlement changes the key`() {
        assertNotEquals(
            "the screen would not animate when the licence became active",
            fadeKey(base),
            fadeKey(base.copy(licence = EntitlementState.Lifetime)),
        )
    }

    /**
     * A copy confirmation is not.
     *
     * It answers something the user did a moment ago, and a fade between the
     * press and its acknowledgement is a delay dressed as a flourish. This is
     * the assertion that keeps the two apart: the crossfade is keyed on the
     * entitlement, so everything transient passes straight through it.
     */
    @Test
    fun `a copy confirmation does not`() {
        assertEquals(
            "copying an identifier would restart the crossfade",
            fadeKey(base),
            fadeKey(base.copy(lastCopied = Copied.Address)),
        )
    }

    /** Nor does a handoff opening, or failing. */
    @Test
    fun `nor does a portal handoff`() {
        assertEquals(
            "opening the portal would restart the crossfade",
            fadeKey(base),
            fadeKey(base.copy(opening = "annual")),
        )
        assertEquals(
            "a failed handoff would restart the crossfade",
            fadeKey(base),
            fadeKey(base.copy(failed = true)),
        )
    }

    /** And there is nothing to fade before the entitlement has been read. */
    @Test
    fun `an unread entitlement has no key`() {
        assertEquals(null, fadeKey(base.copy(licence = null)))
    }
}
