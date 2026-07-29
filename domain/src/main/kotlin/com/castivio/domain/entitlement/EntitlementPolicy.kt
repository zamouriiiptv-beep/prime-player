package com.castivio.domain.entitlement

import kotlin.math.max

/**
 * Turns what is stored into what the app is allowed to do.
 *
 * Pure, and deliberately so: this is the one decision that can lock a paying customer
 * out of a product they bought, and a decision like that should be provable by a test
 * rather than reasoned about at a call site. It takes the time and the configuration
 * as arguments — it reads no clock and no defaults of its own.
 *
 * The order of the rules is the design:
 *
 *  1. **A rolled-back clock buys nothing.** Every comparison runs against the furthest
 *     instant this device has ever seen, so moving the clock back cannot extend a
 *     trial. Moving it forward ends the trial sooner, which is a self-inflicted wound
 *     rather than an exploit.
 *  2. **A revocation outranks everything.** It is the server speaking, and the server
 *     is the source of truth — so it takes precedence even over lifetime.
 *  3. **Lifetime is never revoked *by silence*.** It was bought outright, so an outage
 *     of our licence server must not take it away. The server can still correct a
 *     lifetime record when it is reachable; what it cannot do is punish silence.
 *  4. **A known expiry beats an unknown one.** If the cached dates already say the
 *     trial or subscription ended, that is reported as expired — it is the more
 *     specific and more useful truth than "couldn't verify".
 *  5. **Silence is forgiven for a while.** Only after the grace period in
 *     [PricingConfig] does an unconfirmed entitlement stop counting, and even then it
 *     is reported as unverified rather than as expired.
 */
object EntitlementPolicy {

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * @param record what is stored for this device, or null on a genuine first launch.
     * @param nowMs the current instant, from the app's trusted clock.
     * @param config the pricing and grace configuration in force.
     */
    fun evaluate(
        record: EntitlementRecord?,
        nowMs: Long,
        config: PricingConfig,
    ): EntitlementState {
        if (record == null) return EntitlementState.Unknown

        // Rule 1. A clock that moved backwards must not rewind an expiry.
        val now = effectiveNow(record, nowMs)

        // Rule 2. A revocation is the server speaking, so it outranks everything —
        // including lifetime. Rule 3 protects the customer from our *silence*; this is
        // not silence, and the client never sets it on its own authority.
        record.revokedAtMs?.let { return EntitlementState.Revoked(it) }

        // Rule 3. Bought outright; our silence is not their problem.
        if (record.plan == Plan.LIFETIME) return EntitlementState.Lifetime

        // Rule 4. A cached expiry we already know about is the better answer.
        val settled = settled(record, now)
        if (!settled.allowsUse) return settled

        // Rule 5. Otherwise the entitlement stands until the grace runs out.
        val graceEndsAt = graceEndsAt(record, config)
        if (now >= graceEndsAt) {
            return EntitlementState.VerificationUnavailable(
                lastKnownPlan = record.plan,
                lastKnownExpiresAtMs = record.expiresAtMs,
                graceEndedAtMs = graceEndsAt,
            )
        }
        return settled
    }

    /**
     * The instant every comparison uses.
     *
     * Exposed because the same reconciliation has to happen wherever a time is written
     * back, and two implementations of one rule is one implementation too many.
     */
    fun effectiveNow(record: EntitlementRecord, nowMs: Long): Long =
        max(nowMs, record.maxObservedTimeMs)

    /**
     * When an unconfirmed entitlement stops counting.
     *
     * Measured from the last successful verification, or from when the record was
     * established if the server has never been reached — otherwise a device that has
     * never been online would have no anchor and would be judged instantly.
     */
    fun graceEndsAt(record: EntitlementRecord, config: PricingConfig): Long {
        val anchor = record.lastVerifiedAtMs ?: record.establishedAtMs
        return anchor + config.verifyIntervalMs + config.offlineGraceMs
    }

    /** True when the server has not confirmed this record recently enough to matter. */
    fun verificationIsStale(
        record: EntitlementRecord,
        nowMs: Long,
        config: PricingConfig,
    ): Boolean = effectiveNow(record, nowMs) >= graceEndsAt(record, config)

    /** The state implied by the stored dates alone, before verification is considered. */
    private fun settled(record: EntitlementRecord, now: Long): EntitlementState =
        when (record.plan) {
            Plan.TRIAL -> {
                val expires = record.trialExpiresAtMs
                when {
                    // A trial record with no expiry is not a trial; it is a broken
                    // record, and guessing an expiry for it would be inventing an
                    // entitlement. Treat it as nothing known.
                    expires == null -> EntitlementState.Unknown
                    now < expires -> EntitlementState.TrialActive(expires, daysRemaining(now, expires))
                    else -> EntitlementState.TrialExpired
                }
            }

            Plan.ANNUAL -> {
                val expires = record.subscriptionExpiresAtMs
                when {
                    expires == null -> EntitlementState.Unknown
                    now < expires -> EntitlementState.AnnualActive(expires, daysRemaining(now, expires))
                    else -> EntitlementState.AnnualExpired
                }
            }

            Plan.LIFETIME -> EntitlementState.Lifetime
        }

    /**
     * Whole days left, rounded up.
     *
     * Rounded up because a subscription with thirty hours left has two days on it in
     * the only sense the user cares about: it will still be working tomorrow. Rounding
     * down would show "1 day" and then keep working, which reads as a bug.
     */
    private fun daysRemaining(now: Long, expiresAtMs: Long): Int {
        val remaining = expiresAtMs - now
        if (remaining <= 0) return 0
        return ((remaining + DAY_MS - 1) / DAY_MS).toInt()
    }
}
