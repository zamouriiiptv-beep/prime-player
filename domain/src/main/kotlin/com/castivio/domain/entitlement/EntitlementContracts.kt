package com.castivio.domain.entitlement

import com.castivio.core.common.Outcome
import com.castivio.domain.identity.DeviceIdentityRecord
import com.castivio.domain.identity.MacAddress
import kotlinx.coroutines.flow.Flow

/**
 * The three roles in Castivio's licensing, kept apart on purpose.
 *
 * ```
 *              can establish a purchase?   can revoke?   can grant a trial?
 *  EntitlementSource   yes                     yes             yes
 *  TrialGrantor        no                      no              yes
 *  EntitlementStore    no                      no              no
 * ```
 *
 * The separation is structural rather than documented. [TrialGrantor.grant] returns a
 * [TrialGrant], which has no plan, no price and no revocation field — so an
 * implementation of it *cannot express* a lifetime purchase or a withdrawal however
 * much it might want to. Only [EntitlementSource] returns an [Attestation], and only a
 * server signs one.
 *
 * That matters because the implementation shipping today is local. A local trial is a
 * convenience for development; a local *purchase* would be a licensing system anyone
 * could forge with a text editor, and the way to stop that being written by accident is
 * to make it not compile.
 */

// ---------------------------------------------------------------- the authority

/**
 * Castivio's licence server: the only thing that can say what a device has bought.
 *
 * There is no implementation of this in the app today, and there must never be a local
 * one. Establishing an annual subscription, a lifetime purchase, a revocation or a
 * recovery are facts about money that changed hands, and a device cannot know them —
 * it can only be told.
 *
 * When the server arrives it is bound here and nothing above this line changes: the
 * repository already asks for it, already handles its absence, and already treats its
 * answer as replacing whatever was cached.
 */
interface EntitlementSource {

    /** Asks what this device is entitled to. */
    suspend fun verify(request: VerificationRequest): Outcome<Attestation>

    /**
     * Presents proof of a purchase or a recovery and asks for the entitlement it buys.
     *
     * One method for both because the difference is in the credential, not in what
     * happens: the server checks the proof and states a new entitlement, and the client
     * stores whatever it is told.
     */
    suspend fun redeem(request: RedemptionRequest): Outcome<Attestation>
}

/**
 * @param legacyAddresses addresses this device minted under earlier identity algorithms,
 *   so that raising `DeviceIdentityAlgorithm.CURRENT` moves an entitlement across rather
 *   than stranding it. Empty on every device today.
 * @param cached what is stored locally, sent so the server can reconcile rather than
 *   guess. It is evidence, never authority — the server's answer replaces it either way.
 */
data class VerificationRequest(
    val macAddress: MacAddress,
    val identityVersion: Int,
    val provenance: com.castivio.domain.identity.IdentityProvenance,
    val legacyAddresses: List<MacAddress> = emptyList(),
    val cached: EntitlementRecord? = null,
)

data class RedemptionRequest(
    val macAddress: MacAddress,
    val identityVersion: Int,
    val credential: RedemptionCredential,
)

/** Proof that something was paid for, or that an entitlement already exists elsewhere. */
sealed interface RedemptionCredential {

    /**
     * A random, high-entropy code the server issued when the entitlement was
     * established, for moving it to a device that lost its identity — after a factory
     * reset, or after clearing the data of an installation-scoped identity.
     *
     * Not derived from the MAC address, and never stored on the server in the clear:
     * the client holds the code, the server holds only a hash of it. The wording is
     * deliberate — this contract says what the client sends, and says nothing about
     * what the server keeps beyond the fact that it cannot be the code itself.
     */
    data class RecoveryCode(val code: String) : RedemptionCredential

    /**
     * A receipt from whatever store the purchase was made in.
     *
     * Deliberately opaque and store-agnostic. Play Billing is one possible producer of
     * this string and must not become the shape of it — the licence server is the
     * authority, and it is the thing that knows how to check a given store's receipt.
     */
    data class PurchaseReceipt(val token: String, val productId: String) : RedemptionCredential
}

/**
 * A signed statement from the licence server.
 *
 * [serverTimeMs] is as valuable as the record: it is a trusted instant, and feeding it
 * to `TrustedTime` is what repairs a device whose own clock has run away.
 */
data class Attestation(
    val record: EntitlementRecord,
    val serverTimeMs: Long,
    val signature: String,
)

// -------------------------------------------------------------------- the trial

/**
 * Grants a trial, and grants nothing else.
 *
 * The narrow return type is the point: there is no plan to set, no expiry to invent
 * beyond the configured duration, and no way to say "revoked" or "lifetime". An
 * implementation of this can be as untrusted as it likes and still cannot manufacture
 * a purchase.
 *
 * In production the licence server implements this too, because only the server can
 * remember that a device has already had its free week. The local implementation is
 * for development, and it cannot remember anything that survives clearing app data —
 * which is exactly why it is not the production answer.
 */
interface TrialGrantor {
    suspend fun grant(identity: DeviceIdentityRecord, nowMs: Long): Outcome<TrialGrant>
}

data class TrialGrant(
    val startedAtMs: Long,
    val expiresAtMs: Long,
)

// -------------------------------------------------------------------- the store

/**
 * Where the record survives between launches. Remembers; never decides.
 *
 * Implementations are expected to be tamper-resistant — the stored expiry and
 * high-water mark are precisely what someone wanting a second free week would edit —
 * but tamper-resistance is not the defence. The defence is that the server is the
 * authority, and everything here is a cache of what it last said.
 */
interface EntitlementStore {

    suspend fun read(): StoredEntitlement

    suspend fun write(record: EntitlementRecord)

    suspend fun clear()
}

/**
 * What was found on disk — and the distinction that matters most is between the last
 * two cases.
 *
 * "Nothing is stored" and "something is stored and will not open" look identical to a
 * naive reader and are opposite facts. The first is a device that has never been
 * licensed. The second is a device that *was*, whose record was made unreadable by a
 * keystore reset, a restore onto another handset, or somebody editing the file — and
 * treating it as the first would tell a paying customer they have no licence, and hand
 * a tamperer a fresh trial for the price of one corrupted byte.
 */
sealed interface StoredEntitlement {

    /** The record, or null when there is not one to be had. */
    val record: EntitlementRecord? get() = (this as? Present)?.record

    /** Genuinely nothing: a device that has never been licensed. */
    data object None : StoredEntitlement

    data class Present(override val record: EntitlementRecord) : StoredEntitlement

    /** Something is there. It cannot be read, and it will not become readable. */
    data class Unreadable(val fault: StorageFault) : StoredEntitlement
}

enum class StorageFault {
    /** The sealed blob would not open: edited, or sealed with a key that is gone. */
    UNSEALABLE,

    /** It opened, and what came out is not a record this build can read. */
    UNDECODABLE,
}

// ------------------------------------------------------------------- the build

/**
 * Which licensing world this build lives in.
 *
 * A sealed type rather than a boolean, because a boolean is one wrong `!` away from
 * shipping a release that licenses itself. [Production] has **nowhere to put a
 * [TrialGrantor]** — it is not that it refuses one, it is that the shape does not admit
 * one — so a shipped build cannot grant itself anything however the wiring is edited.
 *
 * The trial still exists in production. It is granted by the licence server through
 * [EntitlementSource], which is the only party that can remember a device has already
 * had its free week and the only one that can refuse.
 */
sealed interface Licensing {

    /** Castivio's licence server, when this build has one bound. */
    val source: EntitlementSource?

    /**
     * Debug and test builds. A local trial may be granted so that the app can be used,
     * and an APK tested on a real television, before the licence server exists.
     */
    data class Development(
        val trials: TrialGrantor,
        override val source: EntitlementSource? = null,
    ) : Licensing

    /**
     * Shipped builds. Only the server establishes anything, including the trial.
     *
     * A null [source] is a build with no authority behind it, and it **fails closed**:
     * every device reads as [EntitlementState.ServiceUnavailable] and the app says so.
     * That is the intended behaviour, not a gap — a release APK that quietly granted
     * itself a licence would be a licensing system with no licences in it.
     */
    data class Production(override val source: EntitlementSource?) : Licensing
}

// --------------------------------------------------------------- what apps ask

/**
 * The single thing the app talks to about its own licence.
 *
 * Every method returns an [EntitlementState] rather than a record, because no caller
 * outside this package has any business reading a stored expiry: the question is always
 * "may this be used", and the answer comes from [EntitlementPolicy] against a trusted
 * clock.
 */
interface EntitlementRepository {

    /** The current state, republished whenever anything here changes it. */
    val state: Flow<EntitlementState>

    /** Re-evaluates against the clock now, repairing and storing the mark if needed. */
    suspend fun current(): EntitlementState

    /**
     * Makes sure this device has an entitlement of some kind, granting the trial if
     * nothing has ever been established. Idempotent: a device that already has a record
     * is left exactly as it was, so this cannot hand out a second free week.
     */
    suspend fun establish(): EntitlementState

    /** Asks the licence server. [com.castivio.core.common.AppError.NOT_CONFIGURED] when there is none. */
    suspend fun refresh(): Outcome<EntitlementState>

    /** Presents a purchase or a recovery code. */
    suspend fun redeem(credential: RedemptionCredential): Outcome<EntitlementState>

    /** This device's address, for the licence screen to show and for support to quote. */
    suspend fun identity(): DeviceIdentityRecord
}
