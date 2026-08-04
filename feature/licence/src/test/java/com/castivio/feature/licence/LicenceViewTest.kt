package com.castivio.feature.licence

import com.castivio.domain.entitlement.EntitlementState
import com.castivio.domain.entitlement.Plan
import com.castivio.domain.entitlement.ServiceFault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every entitlement state has a screen, and no two states that mean different
 * things share a sentence.
 *
 * ## Why this is a plain JVM test
 *
 * Because `licenceView` is a total function of one sealed value. It reads no
 * clock, touches no resources and needs no Compose runtime, which is the whole
 * reason the screen was designed to render `EntitlementState` rather than a
 * flattened copy of it. A mapping this cheap to check has no excuse for being
 * checked on a device.
 *
 * ## The three rules with teeth
 *
 * `design/licence-spec.md` §8.1 states them and this file enforces them, because
 * each one is a sentence that would be wrong in a way no compiler notices:
 *
 * 1. `VerificationUnavailable` never says "expired".
 * 2. `STORAGE_UNREADABLE` never says "no licence".
 * 3. A state a purchase cannot fix never offers a plan.
 */
class LicenceViewTest {

    /** Every case the sealed type has, so the list below cannot fall behind it. */
    private val everyState = listOf(
        EntitlementState.TrialActive(expiresAtMs = 0L, daysRemaining = 3),
        EntitlementState.AnnualActive(expiresAtMs = 0L, daysRemaining = 340),
        EntitlementState.Lifetime,
        EntitlementState.TrialExpired,
        EntitlementState.AnnualExpired,
        EntitlementState.Unknown,
        EntitlementState.VerificationUnavailable(
            lastKnownPlan = Plan.ANNUAL,
            lastKnownExpiresAtMs = null,
            graceEndedAtMs = 0L,
        ),
        EntitlementState.ServiceUnavailable(ServiceFault.NOT_CONFIGURED),
        EntitlementState.ServiceUnavailable(ServiceFault.STORAGE_UNREADABLE),
        EntitlementState.Revoked(revokedAtMs = null),
    )

    /**
     * Every state is reachable and renders something.
     *
     * The `when` in `licenceView` is exhaustive over a sealed interface, so a new
     * entitlement case breaks the build rather than this test — but a case that
     * exists and renders a blank chip would compile fine, and that is what this
     * catches.
     */
    @Test
    fun `every entitlement state produces a view`() {
        for (state in everyState) {
            val view = licenceView(state)
            assertTrue("$state produced no chip", view.chip != 0)
            assertNotNull("$state produced no status line", view.status)
        }
    }

    /** No two distinct states may be indistinguishable on screen. */
    @Test
    fun `states that mean different things do not share a sentence`() {
        val sentences = everyState.map { licenceView(it).status }
        val duplicates = sentences.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue(
            "two states render the same status line: $duplicates",
            duplicates.isEmpty(),
        )
    }

    /**
     * Rule 1. A fortnight offline is not an expiry.
     *
     * Reached only after `PricingConfig.offlineGraceMs`, so the device has been
     * away two weeks — a holiday, a broken router, an outage of ours. Telling
     * that user their licence expired is a support call and a bad review.
     */
    @Test
    fun `an unverified licence is never described as expired`() {
        val view = licenceView(
            EntitlementState.VerificationUnavailable(
                lastKnownPlan = Plan.ANNUAL,
                lastKnownExpiresAtMs = null,
                graceEndedAtMs = 0L,
            ),
        )
        assertEquals(R.string.licence_chip_verify, view.chip)
        assertEquals(R.string.licence_status_verify, view.status)
        assertTrue(
            "an unverified licence borrowed the expired state's wording",
            view.chip != R.string.licence_chip_expired &&
                view.status != R.string.licence_status_expired,
        )
        // A way back, not a dead end: the licence may well be fine.
        assertEquals(LicenceAction.Retry, view.action)
    }

    /**
     * Rule 2. A record that will not open is not an absent record.
     *
     * `Unknown` means this device has never had a licence. `STORAGE_UNREADABLE`
     * means it had one and the key is gone — a keystore reset, a restore onto
     * another handset, or somebody editing the blob. Telling a paying customer
     * the first when the second is true is how a support queue fills up.
     */
    @Test
    fun `an unreadable record is never described as no licence`() {
        val unreadable = licenceView(
            EntitlementState.ServiceUnavailable(ServiceFault.STORAGE_UNREADABLE),
        )
        val absent = licenceView(EntitlementState.Unknown)

        assertTrue(
            "the unreadable state borrowed the no-licence wording",
            unreadable.chip != absent.chip && unreadable.status != absent.status,
        )
        assertEquals(LicenceTone.Bad, unreadable.tone)
        assertEquals(LicenceAction.Support, unreadable.action)
    }

    /**
     * Rule 3. Only offer a plan when buying one would help.
     *
     * A withdrawn licence and an unreachable licence server are not fixed by a
     * purchase, and a plan card in either place takes money for a problem the
     * money does not solve. Lifetime is the fourth: there is nothing left to
     * sell, and asking again would be asking twice.
     */
    @Test
    fun `no plan is offered where a purchase would not help`() {
        val noPlans = listOf(
            EntitlementState.Lifetime,
            EntitlementState.ServiceUnavailable(ServiceFault.NOT_CONFIGURED),
            EntitlementState.ServiceUnavailable(ServiceFault.STORAGE_UNREADABLE),
            EntitlementState.Revoked(revokedAtMs = null),
        )
        for (state in noPlans) {
            assertFalse("$state offered a plan", licenceView(state).offersPlans)
        }
        for (state in everyState - noPlans.toSet()) {
            assertTrue("$state offered no plan", licenceView(state).offersPlans)
        }
    }

    /**
     * A state with no plans always has something else to offer.
     *
     * A screen that blocks the app, offers nothing to buy and no action at all is
     * a dead end, and on a television a dead end is a user pressing back until
     * the app closes.
     */
    @Test
    fun `a state without plans always offers an action`() {
        for (state in everyState) {
            val view = licenceView(state)
            if (!view.offersPlans && state != EntitlementState.Lifetime) {
                assertNotNull("$state offers neither a plan nor an action", view.action)
            }
        }
    }

    /** Only a running trial counts days. An annual holder is not on a trial. */
    @Test
    fun `only a running trial carries a day count`() {
        assertTrue(licenceView(everyState.first()).countsDays)
        for (state in everyState.drop(1)) {
            assertFalse("$state counted trial days", licenceView(state).countsDays)
        }
    }

    /**
     * `danger` stays reserved for a fault, exactly as on the frozen sibling.
     *
     * Expiry is `warning`: nothing broke, there is a decision to make. A colour
     * that means "faulty" on one screen and "you should choose something" on the
     * next means nothing on either.
     */
    @Test
    fun `only genuine faults are drawn in the danger tone`() {
        val bad = everyState.filter { licenceView(it).tone == LicenceTone.Bad }
        assertEquals(
            setOf(
                EntitlementState.ServiceUnavailable(ServiceFault.STORAGE_UNREADABLE),
                EntitlementState.Revoked(revokedAtMs = null),
            ),
            bad.toSet(),
        )
        assertEquals(LicenceTone.Warning, licenceView(EntitlementState.TrialExpired).tone)
        assertEquals(LicenceTone.Warning, licenceView(EntitlementState.AnnualExpired).tone)
    }

    /** An active licence is the only green thing on the screen. */
    @Test
    fun `an active licence reads as good, and nothing else does`() {
        val good = everyState.filter { licenceView(it).tone == LicenceTone.Good }
        assertEquals(
            setOf(
                EntitlementState.AnnualActive(expiresAtMs = 0L, daysRemaining = 340),
                EntitlementState.Lifetime,
            ),
            good.toSet(),
        )
        assertNull(licenceView(EntitlementState.Lifetime).action)
    }
}
