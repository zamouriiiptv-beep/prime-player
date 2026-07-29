package com.castivio.domain.entitlement

import com.castivio.domain.identity.MacAddress
import com.castivio.domain.time.TimeReading
import com.castivio.domain.time.TimeTrust
import kotlin.math.max

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
     * The licence server has withdrawn this entitlement — a refund, a chargeback, a
     * duplicate, an abuse finding.
     *
     * This is the one thing that outranks [Lifetime]. The rule elsewhere is that our
     * *silence* must never take away something bought outright; a revocation is not
     * silence, it is the server saying so, and the server is the source of truth.
     * Nothing is inferred locally: this state exists only because a response set it.
     */
    data class Revoked(val revokedAtMs: Long?) : EntitlementState {
        override val allowsUse: Boolean get() = false
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
     * The app could not work out what this device is entitled to, and the reason is on
     * our side rather than the user's.
     *
     * Separate from [Unknown], which means "nothing has been established yet and that is
     * a true statement about this device". This one means "we cannot tell", and the two
     * deserve different sentences: telling somebody who paid last week that they have no
     * licence, because a keystore reset made their record unreadable, is a support call
     * and a bad review.
     *
     * It is also the state a production build reaches when it has no licence server to
     * ask. **Failing closed is the point.** A shipped build with no authority behind it
     * must say so rather than quietly granting itself a free week, which is what a local
     * trial in a release APK would be.
     */
    data class ServiceUnavailable(val fault: ServiceFault) : EntitlementState {
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
 * Why the app cannot say what this device is entitled to.
 *
 * Both are ours to fix, and neither is the user's doing — which is why they are one
 * state on screen and two values in a diagnostic.
 */
enum class ServiceFault {
    /**
     * This build has no licence server bound. It cannot establish anything, and it must
     * not pretend to.
     */
    NOT_CONFIGURED,

    /**
     * Something is stored and will not open — an edited blob, or a key that no longer
     * exists after a keystore reset or a restore onto another device.
     *
     * Deliberately *not* reported as "no licence". A device in this state had one, and
     * with a licence server bound the way out is to ask again with the same unchanged
     * device identity, not to hand out a fresh trial.
     */
    STORAGE_UNREADABLE,
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
    /**
     * The device identity this entitlement is bound to.
     *
     * The typed value rather than its text, because "the string that identifies this
     * device" and "the string a provider's panel wants" are different questions with
     * different answers — [MacAddress] carries the canonical form and the spellings,
     * and nothing downstream has to remember which one it is holding.
     */
    val macAddress: MacAddress,

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
     * When the licence server withdrew this entitlement, if it has.
     *
     * Only ever written from a server response. The client never revokes on its own
     * authority — an unreachable server is silence, and silence revokes nothing.
     */
    val revokedAtMs: Long? = null,

    /**
     * The furthest point in time this device has ever observed, from the device
     * clock or from a network response.
     *
     * Never decreases on the strength of the device clock, which is what stops a
     * rolled-back clock from extending a trial. It *does* come down when a trusted
     * source says so — see [observing] — because the alternative is a device whose
     * fast clock ended its own subscription and can never take it back.
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

    /**
     * The record after seeing [reading], for the caller to store.
     *
     * The asymmetry is the point, and it is the same asymmetry the clock itself makes:
     *
     *  - A reading the device produced only ever raises the mark. Winding the date
     *    back buys nothing, which is the whole defence against a second free trial.
     *  - A reading from Castivio's own licence infrastructure **replaces** it. A device
     *    whose clock ran two years fast has already recorded those two years, and
     *    without this the subscription it just paid for would read as expired forever —
     *    a permanent injury caused by a wrong clock and nothing else.
     *
     * Only [TimeTrust.NETWORK] can lower the mark, and only Castivio's host can produce
     * a `NETWORK` reading (see [com.castivio.domain.time.TimeAnchorSource]), so the
     * repair path is not reachable from a provider's server.
     */
    fun observing(reading: TimeReading): EntitlementRecord = when (reading.trust) {
        TimeTrust.NETWORK -> copy(maxObservedTimeMs = reading.epochMs)
        TimeTrust.DEVICE, TimeTrust.FLOORED ->
            copy(maxObservedTimeMs = max(maxObservedTimeMs, reading.epochMs))
    }
}
