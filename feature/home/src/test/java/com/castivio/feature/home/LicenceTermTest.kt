package com.castivio.feature.home

import com.castivio.domain.entitlement.EntitlementState
import com.castivio.domain.entitlement.Plan
import com.castivio.domain.entitlement.ServiceFault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The licence term: nine states, one word each, and a date only where one is owed.
 *
 * This is the part of Home worth testing without an emulator. The rest of the screen
 * is a drawing — a weight, a padding, a hue — and a test of it would assert that the
 * drawing is the drawing. This mapping is different: it decides what a paying user is
 * told about the licence they paid for, and getting a case wrong is a refund and a
 * bad review rather than a misaligned card.
 *
 * Every case is enumerated on purpose. A tenth state added to [EntitlementState]
 * makes `licenceTerm` fail to compile on its `when`, which is the compiler holding
 * the invariant; this file is what catches the *quieter* failure of a new state being
 * bolted onto an existing branch that does not mean the same thing.
 */
class LicenceTermTest {

    private val expiry = 1_800_000_000_000L

    @Test
    fun `a trial says trial and carries the date it runs out`() {
        val term = licenceTerm(EntitlementState.TrialActive(expiry, daysRemaining = 5))

        assertEquals(R.string.home_licence_trial, term.word)
        assertEquals(expiry, term.atMs)
    }

    @Test
    fun `an annual licence says active and carries its date`() {
        val term = licenceTerm(EntitlementState.AnnualActive(expiry, daysRemaining = 200))

        assertEquals(R.string.home_licence_active, term.word)
        assertEquals(expiry, term.atMs)
    }

    @Test
    fun `an expired annual licence says when it lapsed`() {
        val term = licenceTerm(EntitlementState.AnnualExpired(expiry))

        assertEquals(R.string.home_licence_expired, term.word)
        assertEquals(expiry, term.atMs)
    }

    /**
     * The date is nullable in the state itself — a record repaired after a clock
     * rollback may not have one — and the card must then say that it expired without
     * saying when, rather than requiring a date it would have to invent.
     */
    @Test
    fun `an expired annual licence with no recorded date still says expired`() {
        val term = licenceTerm(EntitlementState.AnnualExpired())

        assertEquals(R.string.home_licence_expired, term.word)
        assertNull(term.atMs)
    }

    @Test
    fun `an expired trial says expired and has no date to give`() {
        val term = licenceTerm(EntitlementState.TrialExpired)

        assertEquals(R.string.home_licence_expired, term.word)
        assertNull(term.atMs)
    }

    /** Bought outright. The absent date is the fact, not a gap in it. */
    @Test
    fun `a lifetime licence never expires and prints no date`() {
        val term = licenceTerm(EntitlementState.Lifetime)

        assertEquals(R.string.home_licence_lifetime, term.word)
        assertNull(term.atMs)
    }

    @Test
    fun `a withdrawn licence says so rather than saying expired`() {
        val term = licenceTerm(EntitlementState.Revoked(revokedAtMs = expiry))

        assertEquals(R.string.home_licence_revoked, term.word)
        assertNull(term.atMs)
    }

    /**
     * The one mapping that would be a defect done the obvious way. The state holds
     * `lastKnownExpiresAtMs`, and printing it would put "until <date>" beside a word
     * that has just admitted the record could not be confirmed — the screen
     * contradicting itself in the same breath.
     */
    @Test
    fun `an unverified licence states no date even though it holds one`() {
        val term = licenceTerm(
            EntitlementState.VerificationUnavailable(
                lastKnownPlan = Plan.ANNUAL,
                lastKnownExpiresAtMs = expiry,
                graceEndedAtMs = expiry,
            ),
        )

        assertEquals(R.string.home_licence_unverified, term.word)
        assertNull(term.atMs)
    }

    @Test
    fun `a build with no licence server says unavailable, not expired`() {
        val term = licenceTerm(EntitlementState.ServiceUnavailable(ServiceFault.NOT_CONFIGURED))

        assertEquals(R.string.home_licence_unavailable, term.word)
        assertNull(term.atMs)
    }

    @Test
    fun `a device with nothing established yet is not accused of anything`() {
        assertEquals(R.string.home_licence_unknown, licenceTerm(EntitlementState.Unknown).word)
    }

    /** Before the first answer arrives. It reads as "nothing established", not "expired". */
    @Test
    fun `no state at all reads the same as nothing established`() {
        val term = licenceTerm(null)

        assertEquals(R.string.home_licence_unknown, term.word)
        assertNull(term.atMs)
    }

    /**
     * Only the two states that genuinely ran out may use the word "Expired". This is
     * the assertion that fails if a future state is bolted onto that branch because
     * it was the nearest-looking one.
     */
    @Test
    fun `nothing but a real lapse is called expired`() {
        val expired = listOf(
            EntitlementState.TrialExpired,
            EntitlementState.AnnualExpired(expiry),
        )
        val everythingElse = listOf(
            EntitlementState.TrialActive(expiry, 1),
            EntitlementState.AnnualActive(expiry, 1),
            EntitlementState.Lifetime,
            EntitlementState.Revoked(expiry),
            EntitlementState.VerificationUnavailable(Plan.ANNUAL, expiry, expiry),
            EntitlementState.ServiceUnavailable(ServiceFault.NOT_CONFIGURED),
            EntitlementState.Unknown,
        )

        for (state in expired) {
            assertEquals(R.string.home_licence_expired, licenceTerm(state).word)
        }
        for (state in everythingElse) {
            assertNotEquals(R.string.home_licence_expired, licenceTerm(state).word)
        }
    }
}
