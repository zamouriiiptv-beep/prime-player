package com.castivio.data.entitlement

import com.castivio.core.common.AppError
import com.castivio.core.common.Outcome
import com.castivio.domain.entitlement.Attestation
import com.castivio.domain.entitlement.EntitlementPolicy
import com.castivio.domain.entitlement.EntitlementRecord
import com.castivio.domain.entitlement.EntitlementRepository
import com.castivio.domain.entitlement.EntitlementState
import com.castivio.domain.entitlement.EntitlementStore
import com.castivio.domain.entitlement.Licensing
import com.castivio.domain.entitlement.Plan
import com.castivio.domain.entitlement.PricingConfig
import com.castivio.domain.entitlement.RedemptionCredential
import com.castivio.domain.entitlement.RedemptionRequest
import com.castivio.domain.entitlement.ServiceFault
import com.castivio.domain.entitlement.StoredEntitlement
import com.castivio.domain.entitlement.VerificationRequest
import com.castivio.domain.identity.DeviceIdentity
import com.castivio.domain.identity.DeviceIdentityRecord
import com.castivio.domain.time.TimeAnchorSource
import com.castivio.domain.time.TrustedTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one place the licence is decided, and the only place that writes it down.
 *
 * It composes rather than decides. [EntitlementPolicy] says what a record means,
 * [TrustedTime] says when now is, [DeviceIdentity] says who is asking, and this class
 * puts those three together and stores the result. Every rule that could lock a paying
 * customer out lives in the pure code and is tested there.
 *
 * Four rules it enforces on top of them:
 *
 *  1. **Silence revokes nothing.** A failed [refresh] leaves the stored record exactly
 *     as it was. Only an [Attestation] changes a plan, and only an attestation carrying
 *     `revokedAtMs` takes one away.
 *  2. **The server replaces, it does not merge.** When an attestation arrives, what it
 *     says is what is stored — except the identity, which is ours, and the high-water
 *     mark, which is set to the server's own clock because that is the most trustworthy
 *     instant this device has ever had.
 *  3. **A read is a write.** Every evaluation goes through the
 *     [com.castivio.domain.time.TimeReading] overload, so the mark advances with an
 *     honest clock and is repaired by a trusted one, and the corrected record is
 *     persisted rather than recomputed next launch.
 *  4. **An unreadable licence is not an absent one.** A record that will not open means
 *     this device *had* something. In production that is a reason to ask the server
 *     again with the same unchanged identity — never to hand out a fresh trial, and
 *     never to tell the customer they have no licence.
 */
internal class DefaultEntitlementRepository(
    private val store: EntitlementStore,
    private val identity: DeviceIdentity,
    private val clock: TrustedTime,
    private val config: PricingConfig,
    /**
     * Which licensing world this build lives in.
     *
     * [Licensing.Production] has nowhere to put a `TrialGrantor`, so a shipped build
     * cannot grant itself anything however this file is later edited. With no server
     * bound it fails closed, which is the intended behaviour of a release APK that has
     * no authority behind it yet.
     */
    private val licensing: Licensing,
) : EntitlementRepository {

    private val published = MutableStateFlow<EntitlementState>(EntitlementState.Unknown)

    /** One writer at a time: two launches racing to grant a trial must grant one. */
    private val lock = Mutex()

    override val state: Flow<EntitlementState> = published.asStateFlow()

    override suspend fun current(): EntitlementState = lock.withLock { evaluate() }

    override suspend fun establish(): EntitlementState {
        val stored = lock.withLock { store.read() }

        return when (stored) {
            // Already licensed. Idempotent on purpose: this runs on every launch, and a
            // second free week must never fall out of asking twice.
            is StoredEntitlement.Present -> current()

            is StoredEntitlement.None -> when (licensing) {
                is Licensing.Development -> grantLocalTrial(licensing)
                is Licensing.Production -> askTheServer(ServiceFault.NOT_CONFIGURED)
            }

            // Rule 4. Something was here.
            is StoredEntitlement.Unreadable -> when (licensing) {
                // Development is allowed to move on: there is no server to recover from,
                // and a developer with a corrupted file wants a working app.
                is Licensing.Development -> {
                    store.clear()
                    grantLocalTrial(licensing)
                }

                // Production asks again with the same unchanged device identity, which is
                // exactly what recovery looks like: the address did not change, so the
                // server still knows what this device bought.
                is Licensing.Production -> askTheServer(ServiceFault.STORAGE_UNREADABLE)
            }
        }
    }

    override suspend fun refresh(): Outcome<EntitlementState> {
        val server = licensing.source ?: return Outcome.Failure(AppError.NOT_CONFIGURED)

        val device = identity.current()
        val request = lock.withLock {
            VerificationRequest(
                macAddress = device.macAddress,
                identityVersion = device.algorithmVersion,
                provenance = device.provenance,
                legacyAddresses = identity.legacy().map { it.macAddress },
                cached = store.read().record,
            )
        }

        return when (val answered = server.verify(request)) {
            // Rule 1. An unreachable server is silence, and silence changes nothing --
            // not the record, and not the state the app is already showing.
            is Outcome.Failure -> Outcome.Failure(answered.error, answered.cause)
            is Outcome.Success -> Outcome.Success(accept(answered.value, device))
        }
    }

    override suspend fun redeem(credential: RedemptionCredential): Outcome<EntitlementState> {
        val server = licensing.source ?: return Outcome.Failure(AppError.NOT_CONFIGURED)

        val device = identity.current()
        val request = RedemptionRequest(
            macAddress = device.macAddress,
            identityVersion = device.algorithmVersion,
            credential = credential,
        )

        return when (val answered = server.redeem(request)) {
            is Outcome.Failure -> Outcome.Failure(answered.error, answered.cause)
            is Outcome.Success -> Outcome.Success(accept(answered.value, device))
        }
    }

    override suspend fun identity(): DeviceIdentityRecord = identity.current()

    // ------------------------------------------------------------- establishing

    private suspend fun grantLocalTrial(licensing: Licensing.Development): EntitlementState =
        lock.withLock {
            val device = identity.current()
            val reading = clock.now()

            when (val granted = licensing.trials.grant(device, reading.epochMs)) {
                is Outcome.Failure -> publish(EntitlementState.Unknown)

                is Outcome.Success -> {
                    store.write(
                        EntitlementRecord(
                            macAddress = device.macAddress,
                            identityVersion = device.algorithmVersion,
                            plan = Plan.TRIAL,
                            trialStartedAtMs = granted.value.startedAtMs,
                            trialExpiresAtMs = granted.value.expiresAtMs,
                            establishedAtMs = reading.epochMs,
                            maxObservedTimeMs = reading.epochMs,
                        ),
                    )
                    evaluate()
                }
            }
        }

    /**
     * The production path to a first entitlement, which is also the production path out
     * of a corrupted one: ask the server, with the identity this device has always had.
     *
     * @param fault what to report if there is nobody to ask, or asking fails. Two very
     *   different sentences — "this build has no licence service" and "we could not read
     *   your licence" — and the caller is the only one that knows which applies.
     */
    private suspend fun askTheServer(fault: ServiceFault): EntitlementState {
        if (licensing.source == null) {
            return lock.withLock { publish(EntitlementState.ServiceUnavailable(fault)) }
        }

        return when (val answered = refresh()) {
            is Outcome.Success -> answered.value

            is Outcome.Failure -> lock.withLock {
                publish(
                    when (fault) {
                        // Nothing was ever stored and the server could not be reached.
                        // "Nothing has been established" is the true statement, and it
                        // is a state the licence screen already words correctly.
                        ServiceFault.NOT_CONFIGURED -> EntitlementState.Unknown

                        // A licence is on this device and cannot be read. Saying "you
                        // have none" would be false and would read as an accusation.
                        ServiceFault.STORAGE_UNREADABLE ->
                            EntitlementState.ServiceUnavailable(ServiceFault.STORAGE_UNREADABLE)
                    },
                )
            }
        }
    }

    // -------------------------------------------------------------- evaluating

    /**
     * Takes the server at its word, and takes its clock while it is there.
     *
     * The anchor happens first so the evaluation that follows runs against network time
     * rather than against whatever the device believes — which is what lets a
     * subscription that a fast clock had already ended come back.
     */
    private suspend fun accept(attestation: Attestation, device: DeviceIdentityRecord): EntitlementState =
        lock.withLock {
            clock.anchor(attestation.serverTimeMs, TimeAnchorSource.LICENCE_SERVER)

            store.write(
                attestation.record.copy(
                    // The identity is ours to state, not the server's to change: it
                    // echoes what we sent, and trusting the echo would let a bad answer
                    // rebind this device's licence to another address.
                    macAddress = device.macAddress,
                    identityVersion = device.algorithmVersion,
                    lastVerifiedAtMs = attestation.serverTimeMs,
                    maxObservedTimeMs = attestation.serverTimeMs,
                    serverSignature = attestation.signature,
                ),
            )
            evaluate()
        }

    /** Reads the clock, judges, stores the correction, publishes. Always in that order. */
    private suspend fun evaluate(): EntitlementState {
        val stored = store.read()

        // A record that will not open is not a record. In production it means the device
        // was licensed and we cannot prove it, which is our problem to say out loud
        // rather than the user's to be blamed for; in development it is a file to
        // replace, and `establish` will do that on the next launch.
        if (stored is StoredEntitlement.Unreadable && licensing is Licensing.Production) {
            return publish(EntitlementState.ServiceUnavailable(ServiceFault.STORAGE_UNREADABLE))
        }

        val record = stored.record
        val decision = EntitlementPolicy.evaluate(record, clock.now(), config)

        val corrected = decision.record
        if (corrected != null && corrected != record) store.write(corrected)

        // A production build with no licence server can never establish anything, so it
        // says so rather than reporting a bare "nothing here" that reads like the user's
        // fault. Development is left alone: there, nothing established is exactly true.
        if (decision.state is EntitlementState.Unknown &&
            licensing is Licensing.Production &&
            licensing.source == null
        ) {
            return publish(EntitlementState.ServiceUnavailable(ServiceFault.NOT_CONFIGURED))
        }

        return publish(decision.state)
    }

    private fun publish(state: EntitlementState): EntitlementState {
        published.value = state
        return state
    }
}
