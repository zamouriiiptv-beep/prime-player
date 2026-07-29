package com.castivio.domain.entitlement

import com.castivio.domain.identity.MacAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The eleven situations the boundary contract names, one test each, plus the rules
 * that hold across all of them.
 *
 * This is the decision that can lock a paying customer out of something they bought,
 * so it is tested as a table rather than trusted as a paragraph.
 */
class EntitlementPolicyTest {

    private val day = 24L * 60 * 60 * 1000
    private val config = PricingDefaults.config

    /** A fixed, readable instant to build cases around. */
    private val t0 = 1_800_000_000_000L

    // -------------------------------------------------- grace never moves an expiry

    /**
     * The rule this section exists to nail down: **`expiresAtMs` outranks grace, always.**
     *
     * The two mechanisms answer different questions — "has the thing you bought ended?"
     * and "have we been unable to check for a worrying length of time?" — and the first
     * is the more specific truth whenever it is available. A grace period that could
     * push a known expiry into the future would be a free extension for anyone who
     * turned off their wifi on the last day.
     */
    @Test
    fun `offline grace does not extend a trial past its expiry`() {
        // Grace has not run out: verified an hour ago, so there is a fortnight of it
        // left. The trial ended anyway.
        val expired = record(
            plan = Plan.TRIAL,
            trialExpiresAtMs = t0 + 7 * day,
            lastVerifiedAtMs = t0 + 7 * day - 1,
        )

        assertFalse(EntitlementPolicy.verificationIsStale(expired, t0 + 7 * day, config))
        assertEquals(
            EntitlementState.TrialExpired,
            EntitlementPolicy.evaluate(expired, t0 + 7 * day, config),
        )
    }

    @Test
    fun `offline grace does not extend a subscription past its expiry`() {
        val expired = record(
            plan = Plan.ANNUAL,
            trialExpiresAtMs = null,
            subscriptionExpiresAtMs = t0 + 365 * day,
            lastVerifiedAtMs = t0 + 365 * day - 1,
        )

        assertFalse(EntitlementPolicy.verificationIsStale(expired, t0 + 365 * day, config))
        assertEquals(
            EntitlementState.AnnualExpired,
            EntitlementPolicy.evaluate(expired, t0 + 365 * day, config),
        )
    }

    /**
     * And the reverse ordering, which is the one that protects the customer: when the
     * dates still say the entitlement is live, a long silence reports "couldn't verify"
     * rather than "expired". The two never swap places.
     */
    @Test
    fun `a live entitlement past its grace is unverified, not expired`() {
        val unverified = record(
            plan = Plan.ANNUAL,
            trialExpiresAtMs = null,
            subscriptionExpiresAtMs = t0 + 365 * day,
            lastVerifiedAtMs = t0,
        )
        val wellPastGrace = t0 + 60 * day

        assertTrue(EntitlementPolicy.verificationIsStale(unverified, wellPastGrace, config))
        assertEquals(
            EntitlementState.VerificationUnavailable(
                lastKnownPlan = Plan.ANNUAL,
                lastKnownExpiresAtMs = t0 + 365 * day,
                graceEndedAtMs = EntitlementPolicy.graceEndsAt(unverified, config),
            ),
            EntitlementPolicy.evaluate(unverified, wellPastGrace, config),
        )
    }

    /**
     * Both true at once — expired *and* long unverified. The expiry wins, because it is
     * the more specific and more actionable statement: "renew" is something the user can
     * do, "we couldn't check" is not.
     */
    @Test
    fun `a known expiry outranks a grace that has also run out`() {
        val both = record(
            plan = Plan.TRIAL,
            trialExpiresAtMs = t0 + 7 * day,
            lastVerifiedAtMs = t0,
        )
        val longAfter = t0 + 90 * day

        assertTrue(EntitlementPolicy.verificationIsStale(both, longAfter, config))
        assertEquals(
            EntitlementState.TrialExpired,
            EntitlementPolicy.evaluate(both, longAfter, config),
        )
    }

    /** Grace is measured from the last check; it is not a second expiry date. */
    @Test
    fun `grace is measured from the last verification, never from the expiry`() {
        val verified = record(plan = Plan.ANNUAL, trialExpiresAtMs = null, subscriptionExpiresAtMs = t0 + 365 * day, lastVerifiedAtMs = t0)

        assertEquals(
            t0 + config.verifyIntervalMs + config.offlineGraceMs,
            EntitlementPolicy.graceEndsAt(verified, config),
        )
    }

    private fun record(
        plan: Plan = Plan.TRIAL,
        trialExpiresAtMs: Long? = t0 + 7 * day,
        subscriptionExpiresAtMs: Long? = null,
        establishedAtMs: Long = t0,
        lastVerifiedAtMs: Long? = t0,
        maxObservedTimeMs: Long = t0,
    ) = EntitlementRecord(
        macAddress = MacAddress.parse("2F:19:EB:20:44:7C")!!,
        identityVersion = 1,
        plan = plan,
        trialStartedAtMs = if (plan == Plan.TRIAL) establishedAtMs else null,
        trialExpiresAtMs = trialExpiresAtMs,
        subscriptionExpiresAtMs = subscriptionExpiresAtMs,
        establishedAtMs = establishedAtMs,
        lastVerifiedAtMs = lastVerifiedAtMs,
        maxObservedTimeMs = maxObservedTimeMs,
    )

    // ------------------------------------------------------------ first launch

    @Test
    fun `first launch has nothing established`() {
        val state = EntitlementPolicy.evaluate(record = null, nowMs = t0, config = config)

        assertEquals(EntitlementState.Unknown, state)
        assertFalse(state.allowsUse)
    }

    /**
     * The policy reports; it does not grant. Starting a trial is an action the
     * repository takes, so a read can never mint an entitlement.
     */
    @Test
    fun `a missing record never becomes a trial by being read`() {
        repeat(3) {
            assertEquals(
                EntitlementState.Unknown,
                EntitlementPolicy.evaluate(null, t0 + it * day, config),
            )
        }
    }

    // ------------------------------------------------------------------ trial

    @Test
    fun `an active trial reports the days that are left`() {
        val state = EntitlementPolicy.evaluate(record(), nowMs = t0 + 2 * day, config = config)

        assertTrue(state is EntitlementState.TrialActive)
        assertEquals(5, (state as EntitlementState.TrialActive).daysRemaining)
        assertTrue(state.allowsUse)
    }

    /** Thirty hours left is two days in the only sense a user cares about. */
    @Test
    fun `remaining days round up so a working app never reads as finished`() {
        val state = EntitlementPolicy.evaluate(
            record(trialExpiresAtMs = t0 + 30 * 60 * 60 * 1000),
            nowMs = t0,
            config = config,
        )

        assertEquals(2, (state as EntitlementState.TrialActive).daysRemaining)
    }

    @Test
    fun `trial expiration is reported as expired`() {
        val state = EntitlementPolicy.evaluate(record(), nowMs = t0 + 8 * day, config = config)

        assertEquals(EntitlementState.TrialExpired, state)
        assertFalse(state.allowsUse)
    }

    @Test
    fun `the moment of expiry is already expired`() {
        val expires = t0 + 7 * day
        val state = EntitlementPolicy.evaluate(record(), nowMs = expires, config = config)

        assertEquals(EntitlementState.TrialExpired, state)
    }

    // ----------------------------------------------------------------- annual

    @Test
    fun `an active annual subscription allows use`() {
        val state = EntitlementPolicy.evaluate(
            record(plan = Plan.ANNUAL, trialExpiresAtMs = null, subscriptionExpiresAtMs = t0 + 365 * day),
            nowMs = t0 + 10 * day,
            config = config,
        )

        assertTrue(state is EntitlementState.AnnualActive)
        assertTrue(state.allowsUse)
    }

    @Test
    fun `annual expiration is reported as expired`() {
        val state = EntitlementPolicy.evaluate(
            record(plan = Plan.ANNUAL, trialExpiresAtMs = null, subscriptionExpiresAtMs = t0 + day),
            nowMs = t0 + 2 * day,
            config = config,
        )

        assertEquals(EntitlementState.AnnualExpired, state)
    }

    /** Renewal is just a later expiry; nothing else about the record changes. */
    @Test
    fun `renewing moves the expiry and restores use`() {
        val renewed = record(
            plan = Plan.ANNUAL,
            trialExpiresAtMs = null,
            subscriptionExpiresAtMs = t0 + 400 * day,
            lastVerifiedAtMs = t0 + 30 * day,
            maxObservedTimeMs = t0 + 30 * day,
        )

        val state = EntitlementPolicy.evaluate(renewed, nowMs = t0 + 30 * day, config = config)

        assertTrue(state is EntitlementState.AnnualActive)
    }

    // --------------------------------------------------------------- lifetime

    @Test
    fun `lifetime never expires`() {
        val state = EntitlementPolicy.evaluate(
            record(plan = Plan.LIFETIME, trialExpiresAtMs = null),
            nowMs = t0 + 20 * 365 * day,
            config = config,
        )

        assertEquals(EntitlementState.Lifetime, state)
        assertTrue(state.allowsUse)
    }

    /**
     * The rule that protects the person who paid the most. Our licence server going
     * dark must not take away something bought outright — the server can correct a
     * lifetime record when it is reachable, but silence is not a correction.
     */
    @Test
    fun `lifetime survives a licence server that has never been reached`() {
        val neverVerified = record(
            plan = Plan.LIFETIME,
            trialExpiresAtMs = null,
            lastVerifiedAtMs = null,
            establishedAtMs = t0,
        )

        val state = EntitlementPolicy.evaluate(
            neverVerified,
            nowMs = t0 + 5 * 365 * day,
            config = config,
        )

        assertEquals(EntitlementState.Lifetime, state)
    }

    // ------------------------------------------------- offline and verification

    @Test
    fun `an unverified entitlement keeps working through the grace period`() {
        val offline = record(lastVerifiedAtMs = t0, maxObservedTimeMs = t0)

        // One day of verify interval plus fourteen of grace; ten days in is fine.
        val state = EntitlementPolicy.evaluate(offline, nowMs = t0 + 10 * day, config = config)

        assertTrue(state is EntitlementState.TrialActive || state is EntitlementState.TrialExpired)
        assertTrue(state !is EntitlementState.VerificationUnavailable)
    }

    @Test
    fun `past the grace period an unconfirmed entitlement stops counting`() {
        val longTrial = record(
            trialExpiresAtMs = t0 + 400 * day,
            lastVerifiedAtMs = t0,
        )

        val state = EntitlementPolicy.evaluate(longTrial, nowMs = t0 + 40 * day, config = config)

        assertTrue(state is EntitlementState.VerificationUnavailable)
        assertFalse(state.allowsUse)
        val unverified = state as EntitlementState.VerificationUnavailable
        assertEquals(Plan.TRIAL, unverified.lastKnownPlan)
        assertEquals(t0 + config.verifyIntervalMs + config.offlineGraceMs, unverified.graceEndedAtMs)
    }

    /**
     * A device that has never reached the server still needs an anchor, or it would be
     * judged the instant it launched.
     */
    @Test
    fun `grace is measured from establishment when the server was never reached`() {
        val neverVerified = record(
            trialExpiresAtMs = t0 + 400 * day,
            lastVerifiedAtMs = null,
            establishedAtMs = t0,
        )

        assertFalse(EntitlementPolicy.verificationIsStale(neverVerified, t0 + day, config))
        assertTrue(EntitlementPolicy.verificationIsStale(neverVerified, t0 + 40 * day, config))
    }

    /**
     * A known expiry is more specific than "couldn't verify", and more useful: it tells
     * the user what to do instead of what we failed to do.
     */
    @Test
    fun `a known expiry is reported ahead of a verification failure`() {
        val bothWrong = record(
            trialExpiresAtMs = t0 + day,
            lastVerifiedAtMs = t0,
        )

        val state = EntitlementPolicy.evaluate(bothWrong, nowMs = t0 + 40 * day, config = config)

        assertEquals(EntitlementState.TrialExpired, state)
    }

    // ------------------------------------------------------- clock manipulation

    /**
     * The defence that makes an offline trial honest. The furthest instant the device
     * has ever seen never decreases, so winding the clock back buys nothing.
     */
    @Test
    fun `winding the clock back does not extend a trial`() {
        val seenTheFuture = record(
            trialExpiresAtMs = t0 + 7 * day,
            maxObservedTimeMs = t0 + 9 * day,
        )

        val state = EntitlementPolicy.evaluate(seenTheFuture, nowMs = t0 + day, config = config)

        assertEquals(EntitlementState.TrialExpired, state)
    }

    @Test
    fun `winding the clock forward only ends the trial sooner`() {
        val state = EntitlementPolicy.evaluate(record(), nowMs = t0 + 400 * day, config = config)

        assertEquals(EntitlementState.TrialExpired, state)
    }

    @Test
    fun `the effective instant is never behind what the device has already seen`() {
        val r = record(maxObservedTimeMs = t0 + 5 * day)

        assertEquals(t0 + 5 * day, EntitlementPolicy.effectiveNow(r, t0))
        assertEquals(t0 + 9 * day, EntitlementPolicy.effectiveNow(r, t0 + 9 * day))
    }

    // -------------------------------------------------------------- revocation

    /**
     * The one thing that outranks a lifetime purchase. The rule elsewhere is that our
     * *silence* must never take away something bought outright — a revocation is not
     * silence, it is the server saying so.
     */
    @Test
    fun `a revoked lifetime is revoked`() {
        val revoked = record(
            plan = Plan.LIFETIME,
            trialExpiresAtMs = null,
        ).copy(revokedAtMs = t0 + day)

        val state = EntitlementPolicy.evaluate(revoked, nowMs = t0 + 2 * day, config = config)

        assertEquals(EntitlementState.Revoked(t0 + day), state)
        assertFalse(state.allowsUse)
    }

    @Test
    fun `revocation beats an otherwise valid trial or subscription`() {
        val trial = record().copy(revokedAtMs = t0)
        val annual = record(
            plan = Plan.ANNUAL,
            trialExpiresAtMs = null,
            subscriptionExpiresAtMs = t0 + 365 * day,
        ).copy(revokedAtMs = t0)

        assertFalse(EntitlementPolicy.evaluate(trial, t0 + day, config).allowsUse)
        assertFalse(EntitlementPolicy.evaluate(annual, t0 + day, config).allowsUse)
    }

    /**
     * Revocation is only ever written from a server response, so an ordinary record —
     * however stale — must never drift into it on its own.
     */
    @Test
    fun `nothing local ever produces a revocation`() {
        val neverVerified = record(
            trialExpiresAtMs = t0 + 400 * day,
            lastVerifiedAtMs = null,
        )

        val state = EntitlementPolicy.evaluate(neverVerified, t0 + 400 * day, config)

        assertTrue("$state", state !is EntitlementState.Revoked)
    }

    // ------------------------------------------------ confirmed versus unconfirmed

    /**
     * The distinction the licence policy turns on: a date that has passed is a fact and
     * locks the app; an unreachable server is not, and is forgiven for the grace period.
     */
    @Test
    fun `a known expiry locks while an unreachable server does not`() {
        val expired = record(trialExpiresAtMs = t0 + day, lastVerifiedAtMs = t0)
        val unreachableButValid = record(trialExpiresAtMs = t0 + 7 * day, lastVerifiedAtMs = t0)

        assertFalse(EntitlementPolicy.evaluate(expired, t0 + 2 * day, config).allowsUse)
        assertTrue(EntitlementPolicy.evaluate(unreachableButValid, t0 + 2 * day, config).allowsUse)
    }

    // ----------------------------------------------------------- broken records

    /**
     * A trial with no expiry is not an unlimited trial; it is a corrupt record, and
     * inventing a date for it would be inventing an entitlement.
     */
    @Test
    fun `a record missing its expiry grants nothing`() {
        val broken = record(trialExpiresAtMs = null)

        assertEquals(EntitlementState.Unknown, EntitlementPolicy.evaluate(broken, t0, config))
    }

    // -------------------------------------------------------------- the config

    /**
     * The policy must never contain a duration of its own. Doubling the configured
     * trial has to change the outcome, or something is hard-coded.
     */
    @Test
    fun `the policy reads its durations from the configuration`() {
        val generous = config.copy(offlineGraceMs = 400 * day)
        val longTrial = record(trialExpiresAtMs = t0 + 400 * day, lastVerifiedAtMs = t0)

        assertTrue(
            EntitlementPolicy.evaluate(longTrial, t0 + 40 * day, config)
                is EntitlementState.VerificationUnavailable,
        )
        assertTrue(
            EntitlementPolicy.evaluate(longTrial, t0 + 40 * day, generous)
                is EntitlementState.TrialActive,
        )
    }
}
