package com.castivio.data.entitlement

import com.castivio.core.common.AppError
import com.castivio.core.common.Outcome
import com.castivio.domain.entitlement.Attestation
import com.castivio.domain.entitlement.EntitlementPolicy
import com.castivio.domain.entitlement.EntitlementRecord
import com.castivio.domain.entitlement.EntitlementRepository
import com.castivio.domain.entitlement.EntitlementSource
import com.castivio.domain.entitlement.EntitlementState
import com.castivio.domain.entitlement.EntitlementStore
import com.castivio.domain.entitlement.Plan
import com.castivio.domain.entitlement.PricingConfig
import com.castivio.domain.entitlement.RedemptionCredential
import com.castivio.domain.entitlement.RedemptionRequest
import com.castivio.domain.entitlement.TrialGrantor
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
 * Three rules it enforces on top of them:
 *
 *  1. **Silence revokes nothing.** A failed [refresh] leaves the stored record exactly
 *     as it was. Only an [Attestation] changes a plan, and only an attestation carrying
 *     `revokedAtMs` takes one away.
 *  2. **The server replaces, it does not merge.** When an attestation arrives, what it
 *     says is what is stored — except the identity, which is ours, and the high-water
 *     mark, which is set to the server's own clock because that is the most trustworthy
 *     instant this device has ever had.
 *  3. **A read is a write.** Every evaluation goes through the [com.castivio.domain.time.TimeReading]
 *     overload, so the mark advances with an honest clock and is repaired by a trusted
 *     one, and the corrected record is persisted rather than recomputed next launch.
 */
internal class DefaultEntitlementRepository(
    private val store: EntitlementStore,
    private val identity: DeviceIdentity,
    private val clock: TrustedTime,
    private val trials: TrialGrantor,
    private val config: PricingConfig,
    /**
     * Castivio's licence server, when this build has one.
     *
     * Null today. Everything that needs it says so plainly rather than pretending to
     * succeed, and binding a real one is the whole of the change — no signature above
     * this line moves, because the domain was written for the server that does not
     * exist yet rather than for the local storage that does.
     */
    private val source: EntitlementSource? = null,
) : EntitlementRepository {

    private val published = MutableStateFlow<EntitlementState>(EntitlementState.Unknown)

    /** One writer at a time: two launches racing to grant a trial must grant one. */
    private val lock = Mutex()

    override val state: Flow<EntitlementState> = published.asStateFlow()

    override suspend fun current(): EntitlementState = lock.withLock { evaluate() }

    override suspend fun establish(): EntitlementState = lock.withLock {
        // Idempotent, and deliberately so: this is called on every launch, and a second
        // free week must never fall out of asking twice.
        if (store.read() != null) return@withLock evaluate()

        val device = identity.current()
        val reading = clock.now()

        when (val granted = trials.grant(device, reading.epochMs)) {
            is Outcome.Failure -> {
                // No trial to be had -- a release build with no licence server. Nothing
                // is stored, so the app is unentitled and says so, which is the honest
                // state rather than a silent free pass.
                publish(EntitlementState.Unknown)
            }

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

    override suspend fun refresh(): Outcome<EntitlementState> {
        val server = source ?: return Outcome.Failure(AppError.NOT_CONFIGURED)

        val device = identity.current()
        val request = lock.withLock {
            VerificationRequest(
                macAddress = device.macAddress,
                identityVersion = device.algorithmVersion,
                provenance = device.provenance,
                legacyAddresses = identity.legacy().map { it.macAddress },
                cached = store.read(),
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
        val server = source ?: return Outcome.Failure(AppError.NOT_CONFIGURED)

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
        val decision = EntitlementPolicy.evaluate(stored, clock.now(), config)

        val corrected = decision.record
        if (corrected != null && corrected != stored) store.write(corrected)

        return publish(decision.state)
    }

    private fun publish(state: EntitlementState): EntitlementState {
        published.value = state
        return state
    }
}
