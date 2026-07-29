package com.castivio.domain.entitlement

/**
 * Whether this device is allowed to use Castivio, which has nothing to do with
 * whether a provider is willing to serve it channels.
 *
 * The two are separate systems on purpose. Castivio's licence is per device, sold
 * by us, and answered by our own server; a provider subscription is bought from a
 * third party and answered by theirs. Sharing a type between them is how a product
 * ends up showing "expired" without the user being able to tell which one — so
 * nothing here is reused by [com.castivio.domain.ProviderSource], and nothing there
 * is reused here.
 *
 * One device, one identifier, one entitlement. There is no account in phase one.
 */
enum class Plan {
    /** Time-limited, granted once per device identity. */
    TRIAL,

    /** Renews; carries an expiry the server owns. */
    ANNUAL,

    /** Bought once, bound to the device identity, no expiry. */
    LIFETIME,
}

/**
 * The state the app acts on. Every screen asks [allowsUse]; only the licence screen
 * looks at which case it is, so a new plan does not ripple through the app.
 */
sealed interface EntitlementState {

    /** May the app be used right now? The single question the shell asks. */
    val allowsUse: Boolean

    data class TrialActive(
        val expiresAtMs: Long,
        val daysRemaining: Int,
    ) : EntitlementState {
        override val allowsUse: Boolean get() = true
    }

    data object TrialExpired : EntitlementState {
        override val allowsUse: Boolean get() = false
    }

    data class AnnualActive(
        val expiresAtMs: Long,
        val daysRemaining: Int,
    ) : EntitlementState {
        override val allowsUse: Boolean get() = true
    }

    data object AnnualExpired : EntitlementState {
        override val allowsUse: Boolean get() = false
    }

    /** Bought outright. Never expires, and is never revoked by an outage of ours. */
    data object Lifetime : EntitlementState {
        override val allowsUse: Boolean get() = true
    }

    /**
     * Nothing has been established for this device yet — the state on a genuine
     * first launch, before a trial has been granted.
     *
     * Granting the trial is an action, not a reading, so the policy never invents
     * one: it reports what is known and the repository decides what to do about it.
     */
    data object Unknown : EntitlementState {
        override val allowsUse: Boolean get() = false
    }

    /**
     * There is a cached entitlement, but it has gone too long without confirmation
     * from the licence server.
     *
     * This is deliberately *not* an accusation. It is reached only after the grace
     * period in [PricingConfig] has passed, so an outage of ours or a fortnight in a
     * hotel does not lock anyone out — and the licence screen says "couldn't verify",
     * never "expired", because those are different facts.
     *
     * Carries the last known facts rather than the last known state, so this case can
     * never nest inside itself.
     */
    data class VerificationUnavailable(
        val lastKnownPlan: Plan,
        val lastKnownExpiresAtMs: Long?,
        /** When the offline grace ran out. */
        val graceEndedAtMs: Long,
    ) : EntitlementState {
        override val allowsUse: Boolean get() = false
    }
}

/**
 * What is stored for this device between launches.
 *
 * The licence server is the source of truth; this is the replay of what it last
 * said, plus the two local defences that make an offline reading honest —
 * [maxObservedTimeMs] against a clock that moves backwards, and [establishedAtMs]
 * so a device that has never reached the server still has an anchor to measure
 * grace from.
 */
data class EntitlementRecord(
    /** The device identity this entitlement is bound to, in MAC form. */
    val macAddress: String,

    /**
     * Which derivation produced [macAddress]. Stored so a future algorithm can look
     * up the previous identity instead of silently minting a new one and stranding
     * a paid licence. See `DeviceIdentity` for the versioning rule.
     */
    val identityVersion: Int,

    val plan: Plan,

    val trialStartedAtMs: Long? = null,
    val trialExpiresAtMs: Long? = null,

    /** For [Plan.ANNUAL]. Null for trial and lifetime. */
    val subscriptionExpiresAtMs: Long? = null,

    /** When the record was first written on this device. */
    val establishedAtMs: Long,

    /** Last time the licence server confirmed this record. Null before it ever has. */
    val lastVerifiedAtMs: Long? = null,

    /**
     * The furthest point in time this device has ever observed, from the device
     * clock or from a network response. Never decreases, which is what stops a
     * rolled-back clock from extending a trial.
     */
    val maxObservedTimeMs: Long,

    /**
     * Opaque proof from the server, stored and replayed rather than interpreted.
     * The client never validates entitlement on its own authority.
     */
    val serverSignature: String? = null,
) {
    /** The expiry that applies to this record's plan, if it has one. */
    val expiresAtMs: Long?
        get() = when (plan) {
            Plan.TRIAL -> trialExpiresAtMs
            Plan.ANNUAL -> subscriptionExpiresAtMs
            Plan.LIFETIME -> null
        }
}
