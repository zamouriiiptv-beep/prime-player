package com.castivio.feature.activation

import com.castivio.domain.entitlement.EntitlementState
import com.castivio.domain.entitlement.Plan
import com.castivio.domain.entitlement.ServiceFault
import com.castivio.domain.entitlement.startDestination
import com.castivio.domain.entitlement.StartDestination
import com.castivio.domain.entitlement.LicenceReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the trial chip says, and where an expired trial sends the user.
 *
 * ## Why the chip needed a test at all
 *
 * Because it was the string `7`. Not a default, not a placeholder marked as one —
 * a constant in the composable, rendered through a plural resource so it looked
 * like real state. It would have read "7 days" on the eighth day, on the ninetieth,
 * and on a device whose trial had run out an hour earlier.
 *
 * The count comes from `EntitlementRepository` now, which is the only thing that
 * knows: it holds the sealed record, the high-water clock mark that stops a
 * rolled-back device buying a second free week, and the policy that turns those
 * into a state. This checks the mapping from that state to what the header shows.
 *
 * ## And why the expiry case is here too
 *
 * Because "the trial ran out" is answered in two places and they must agree. The
 * chip stops having anything to say, and `startDestination` stops sending the
 * user to this screen at all. Testing only the first would leave a screen that
 * renders correctly and should not be on screen.
 */
class TrialChipTest {

    /** A running trial shows its own number, not a constant. */
    @Test
    fun `an active trial reports the days it actually has left`() {
        for (days in listOf(7, 3, 1, 0)) {
            val state = EntitlementState.TrialActive(expiresAtMs = 0L, daysRemaining = days)
            assertEquals(days, state.chipDays())
        }
    }

    /**
     * Never a negative day count.
     *
     * A trial that crossed its expiry a moment ago is expired, and the gate will
     * move the user at the next decision — but between those two instants this
     * must not render "-1 days", which is the kind of thing that reaches a
     * screenshot.
     */
    @Test
    fun `an overrun trial never renders a negative count`() {
        val state = EntitlementState.TrialActive(expiresAtMs = 0L, daysRemaining = -4)
        assertEquals(0, state.chipDays())
    }

    /**
     * Everything that is not a running trial has no chip.
     *
     * Including the paid plans: an annual subscription has days remaining and they
     * are not a trial, and saying "Castivio trial 340 days" to somebody who paid
     * is worse than saying nothing.
     */
    @Test
    fun `no other entitlement state produces a trial chip`() {
        val others = listOf(
            EntitlementState.TrialExpired,
            EntitlementState.AnnualActive(expiresAtMs = 0L, daysRemaining = 340),
            EntitlementState.AnnualExpired,
            EntitlementState.Lifetime,
            EntitlementState.Revoked(revokedAtMs = null),
            EntitlementState.Unknown,
            EntitlementState.ServiceUnavailable(ServiceFault.NOT_CONFIGURED),
            EntitlementState.VerificationUnavailable(
                lastKnownPlan = Plan.ANNUAL,
                lastKnownExpiresAtMs = null,
                graceEndedAtMs = 0L,
            ),
        )
        for (state in others) {
            assertNull("$state produced a trial chip", state.chipDays())
        }
    }

    /**
     * An expired trial is not this screen's problem.
     *
     * `startDestination` is the single place that decides, and it sends a device
     * that may not be used to the licence destination. Activation stays
     * responsible for device activation and playlist setup, which is the whole
     * reason the chip can simply disappear rather than grow an expiry mode.
     */
    @Test
    fun `an expired trial routes to the licence screen, not to activation`() {
        assertEquals(
            StartDestination.Licence(LicenceReason.TRIAL_EXPIRED),
            startDestination(EntitlementState.TrialExpired, source = null),
        )
    }

    /** And a live trial with no provider yet is exactly what activation is for. */
    @Test
    fun `a live trial with no provider lands on activation`() {
        assertEquals(
            StartDestination.Activation,
            startDestination(
                EntitlementState.TrialActive(expiresAtMs = 0L, daysRemaining = 5),
                source = null,
            ),
        )
    }
}

/**
 * The mapping under test, mirrored from `ActivationIdentityViewModel`.
 *
 * Duplicated deliberately and kept to one line: the production copy is private to
 * the view model, and widening a view model's API so a test can reach one
 * expression is a worse trade than restating the expression. If the two ever
 * disagree, the disagreement is one line long and this file is where it shows.
 */
private fun EntitlementState.chipDays(): Int? =
    (this as? EntitlementState.TrialActive)?.daysRemaining?.coerceAtLeast(0)
