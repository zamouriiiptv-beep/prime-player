package com.castivio.data.entitlement

import com.castivio.core.common.AppError
import com.castivio.core.common.Outcome
import com.castivio.domain.entitlement.Attestation
import com.castivio.domain.entitlement.EntitlementRecord
import com.castivio.domain.entitlement.EntitlementSource
import com.castivio.domain.entitlement.EntitlementState
import com.castivio.domain.entitlement.EntitlementStore
import com.castivio.domain.entitlement.Plan
import com.castivio.domain.entitlement.PricingDefaults
import com.castivio.domain.entitlement.RedemptionCredential
import com.castivio.domain.entitlement.RedemptionRequest
import com.castivio.domain.entitlement.VerificationRequest
import com.castivio.domain.identity.DeviceIdentity
import com.castivio.domain.identity.DeviceIdentityRecord
import com.castivio.domain.identity.IdentityProvenance
import com.castivio.domain.identity.MacAddress
import com.castivio.domain.time.ClockSignalSource
import com.castivio.domain.time.ClockSignals
import com.castivio.domain.time.ClockState
import com.castivio.domain.time.ClockStore
import com.castivio.domain.time.MonotonicClock
import com.castivio.domain.time.TimeAnchorSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The licence, assembled.
 *
 * Every rule about what a record *means* is proved in `:domain`; what is proved here is
 * that the pieces are wired the way those rules assume — that a read is a write, that a
 * failed refresh changes nothing, that a trial cannot be granted twice by asking twice,
 * and that the local source cannot say anything a local source has no business saying.
 */
class DefaultEntitlementRepositoryTest {

    private val day = 24L * 60 * 60 * 1000
    private val hour = 60L * 60 * 1000
    private val year = 365 * day
    private val t0 = 1_772_323_200_000L

    private val config = PricingDefaults.config

    // --------------------------------------------------------------- the harness

    private class MemoryStore(var record: EntitlementRecord? = null) : EntitlementStore {
        var writes = 0
        override suspend fun read(): EntitlementRecord? = record
        override suspend fun write(record: EntitlementRecord) {
            this.record = record
            writes++
        }

        override suspend fun clear() {
            record = null
        }
    }

    private class MemoryClockStore : ClockStore {
        var state = ClockState()
        override fun load(): ClockState = state
        override fun save(state: ClockState) {
            this.state = state
        }
    }

    private class Signals(
        var wallClockMs: Long,
        var elapsedRealtimeMs: Long = 30_000L,
        var bootId: String = "boot-a",
    ) : ClockSignalSource {
        override fun read() = ClockSignals(wallClockMs, elapsedRealtimeMs, bootId)
    }

    private val address = MacAddress.parse("2F:19:EB:20:44:7C")!!

    private class FixedIdentity(
        private val record: DeviceIdentityRecord,
        private val previous: List<DeviceIdentityRecord> = emptyList(),
    ) : DeviceIdentity {
        override fun current() = record
        override fun legacy() = previous
    }

    private fun identity(provenance: IdentityProvenance = IdentityProvenance.DEVICE) =
        FixedIdentity(DeviceIdentityRecord(address, 1, provenance))

    /** Records what it was asked and answers with whatever it was told to answer. */
    private class FakeServer(
        var verification: Outcome<Attestation>? = null,
        var redemption: Outcome<Attestation>? = null,
    ) : EntitlementSource {
        var lastVerification: VerificationRequest? = null
        var lastRedemption: RedemptionRequest? = null
        var verifyCalls = 0

        override suspend fun verify(request: VerificationRequest): Outcome<Attestation> {
            lastVerification = request
            verifyCalls++
            return verification ?: Outcome.Failure(AppError.NETWORK_UNAVAILABLE)
        }

        override suspend fun redeem(request: RedemptionRequest): Outcome<Attestation> {
            lastRedemption = request
            return redemption ?: Outcome.Failure(AppError.NETWORK_UNAVAILABLE)
        }
    }

    private fun repository(
        store: MemoryStore = MemoryStore(),
        signals: Signals = Signals(wallClockMs = t0),
        clockStore: MemoryClockStore = MemoryClockStore(),
        identity: DeviceIdentity = identity(),
        trialsEnabled: Boolean = true,
        source: EntitlementSource? = null,
    ) = DefaultEntitlementRepository(
        store = store,
        identity = identity,
        clock = MonotonicClock(signals, clockStore),
        trials = LocalEntitlementSource(config, enabled = trialsEnabled),
        config = config,
        source = source,
    )

    // ------------------------------------------------------------- the first launch

    @Test
    fun `a device with nothing has nothing until a trial is established`() = runTest {
        val store = MemoryStore()
        val repository = repository(store)

        assertEquals(EntitlementState.Unknown, repository.current())
        assertNull(store.record)
    }

    @Test
    fun `establishing grants exactly the configured trial`() = runTest {
        val store = MemoryStore()

        val state = repository(store).establish()

        assertEquals(EntitlementState.TrialActive(t0 + config.trialDurationMs, 7), state)
        assertEquals(Plan.TRIAL, store.record?.plan)
        assertEquals(t0, store.record?.trialStartedAtMs)
        assertEquals(t0 + config.trialDurationMs, store.record?.trialExpiresAtMs)
        assertEquals(7 * day, config.trialDurationMs)
    }

    @Test
    fun `the trial is bound to this device's address and identity version`() = runTest {
        val store = MemoryStore()

        repository(store).establish()

        assertEquals(address, store.record?.macAddress)
        assertEquals(1, store.record?.identityVersion)
    }

    /**
     * `establish` runs on every launch, so a second free week must not fall out of
     * asking twice — nor out of two launches racing.
     */
    @Test
    fun `establishing twice does not grant a second trial`() = runTest {
        val store = MemoryStore()
        val repository = repository(store)

        repository.establish()
        val first = store.record

        repeat(5) { repository.establish() }

        assertEquals(first?.trialStartedAtMs, store.record?.trialStartedAtMs)
        assertEquals(first?.trialExpiresAtMs, store.record?.trialExpiresAtMs)
    }

    @Test
    fun `an expired trial is not re-granted by establishing again`() = runTest {
        val signals = Signals(wallClockMs = t0)
        val store = MemoryStore()
        val repository = repository(store, signals)
        repository.establish()

        signals.wallClockMs = t0 + 8 * day

        assertEquals(EntitlementState.TrialExpired, repository.establish())
        assertEquals(t0 + config.trialDurationMs, store.record?.trialExpiresAtMs)
    }

    /**
     * A release build has no licence server, so it has nothing that can honestly grant a
     * free week. The result is an unentitled device that says so — not a silent free
     * pass, which is what a locally granted trial in a shipped APK would be.
     */
    @Test
    fun `a build with no trial grantor establishes nothing`() = runTest {
        val store = MemoryStore()

        val state = repository(store, trialsEnabled = false).establish()

        assertEquals(EntitlementState.Unknown, state)
        assertNull(store.record)
    }

    // ------------------------------------------------------- what the local source is

    /**
     * The structural rule, stated as a test because the compiler already states it
     * better: [LocalEntitlementSource] implements `TrialGrantor`, whose return type has
     * no plan, no price and no revocation. It could not sell a lifetime licence if it
     * tried, and nothing that goes through it produces anything but a trial.
     */
    @Test
    fun `the local source can only ever produce a trial`() = runTest {
        val store = MemoryStore()

        repository(store).establish()

        assertEquals(Plan.TRIAL, store.record?.plan)
        assertNull(store.record?.subscriptionExpiresAtMs)
        assertNull(store.record?.revokedAtMs)
        assertNull(store.record?.serverSignature)
        assertNull(store.record?.lastVerifiedAtMs)
    }

    @Test
    fun `without a licence server there is nothing to verify against`() = runTest {
        val repository = repository()
        repository.establish()

        val refreshed = repository.refresh()
        val redeemed = repository.redeem(RedemptionCredential.RecoveryCode("ABCD-EFGH"))

        assertEquals(AppError.NOT_CONFIGURED, (refreshed as Outcome.Failure).error)
        assertEquals(AppError.NOT_CONFIGURED, (redeemed as Outcome.Failure).error)
    }

    // ---------------------------------------------------------------- the clock

    @Test
    fun `a rolled back clock does not extend a trial`() = runTest {
        val signals = Signals(wallClockMs = t0)
        val store = MemoryStore()
        val repository = repository(store, signals)
        repository.establish()

        signals.wallClockMs = t0 + 8 * day
        assertEquals(EntitlementState.TrialExpired, repository.current())

        signals.wallClockMs = t0 + day

        assertEquals(EntitlementState.TrialExpired, repository.current())
    }

    /** Reading the time is a write: the mark advances and is stored, not recomputed. */
    @Test
    fun `evaluating stores the advanced mark`() = runTest {
        val signals = Signals(wallClockMs = t0)
        val store = MemoryStore()
        val repository = repository(store, signals)
        repository.establish()

        signals.wallClockMs = t0 + 3 * day
        repository.current()

        assertEquals(t0 + 3 * day, store.record?.maxObservedTimeMs)
    }

    @Test
    fun `an unchanged evaluation does not rewrite the record`() = runTest {
        val store = MemoryStore()
        val repository = repository(store)
        repository.establish()
        val after = store.writes

        repeat(5) { repository.current() }

        assertEquals(after, store.writes)
    }

    // ------------------------------------------------------------- with a server

    private fun attestation(
        plan: Plan = Plan.ANNUAL,
        expiresAtMs: Long? = t0 + year,
        revokedAtMs: Long? = null,
        serverTimeMs: Long = t0,
    ) = Attestation(
        record = EntitlementRecord(
            macAddress = address,
            identityVersion = 1,
            plan = plan,
            subscriptionExpiresAtMs = expiresAtMs.takeIf { plan == Plan.ANNUAL },
            trialExpiresAtMs = expiresAtMs.takeIf { plan == Plan.TRIAL },
            establishedAtMs = t0 - 30 * day,
            revokedAtMs = revokedAtMs,
            maxObservedTimeMs = 0,
        ),
        serverTimeMs = serverTimeMs,
        signature = "MEUCIQD-signed",
    )

    @Test
    fun `a verified subscription replaces the trial`() = runTest {
        val store = MemoryStore()
        val server = FakeServer(verification = Outcome.Success(attestation()))
        val repository = repository(store, source = server)
        repository.establish()

        val state = repository.refresh()

        assertEquals(EntitlementState.AnnualActive(t0 + year, 365), (state as Outcome.Success).value)
        assertEquals(Plan.ANNUAL, store.record?.plan)
        assertEquals("MEUCIQD-signed", store.record?.serverSignature)
        assertEquals(t0, store.record?.lastVerifiedAtMs)
    }

    @Test
    fun `verification sends this device's identity and whatever is cached`() = runTest {
        val store = MemoryStore()
        val server = FakeServer(verification = Outcome.Success(attestation()))
        val repository = repository(store, identity = identity(IdentityProvenance.INSTALLATION), source = server)
        repository.establish()

        repository.refresh()

        val sent = server.lastVerification!!
        assertEquals(address, sent.macAddress)
        assertEquals(1, sent.identityVersion)
        assertEquals(IdentityProvenance.INSTALLATION, sent.provenance)
        assertEquals(Plan.TRIAL, sent.cached?.plan)
    }

    /**
     * The identity is ours to state. Trusting the server's echo would let one bad answer
     * rebind this device's licence to somebody else's address.
     */
    @Test
    fun `an attestation cannot move the licence to another address`() = runTest {
        val store = MemoryStore()
        val elsewhere = attestation().let {
            it.copy(record = it.record.copy(macAddress = MacAddress.parse("AA:BB:CC:DD:EE:FF")!!, identityVersion = 9))
        }
        val repository = repository(store, source = FakeServer(verification = Outcome.Success(elsewhere)))
        repository.establish()

        repository.refresh()

        assertEquals(address, store.record?.macAddress)
        assertEquals(1, store.record?.identityVersion)
    }

    /** Silence revokes nothing — not the plan, and not the dates. */
    @Test
    fun `a failed refresh leaves the record exactly as it was`() = runTest {
        val store = MemoryStore()
        val server = FakeServer(verification = Outcome.Failure(AppError.NETWORK_UNAVAILABLE))
        val repository = repository(store, source = server)
        repository.establish()
        val before = store.record

        val refreshed = repository.refresh()

        assertEquals(AppError.NETWORK_UNAVAILABLE, (refreshed as Outcome.Failure).error)
        assertEquals(before, store.record)
    }

    @Test
    fun `a revocation from the server locks the app`() = runTest {
        val store = MemoryStore()
        val revoked = attestation(plan = Plan.LIFETIME, expiresAtMs = null, revokedAtMs = t0 - day)
        val repository = repository(store, source = FakeServer(verification = Outcome.Success(revoked)))
        repository.establish()

        val state = (repository.refresh() as Outcome.Success).value

        assertEquals(EntitlementState.Revoked(t0 - day), state)
        assertFalse(state.allowsUse)
    }

    @Test
    fun `redeeming a recovery code carries the credential through untouched`() = runTest {
        val store = MemoryStore()
        val server = FakeServer(redemption = Outcome.Success(attestation(plan = Plan.LIFETIME, expiresAtMs = null)))
        val repository = repository(store, source = server)
        repository.establish()

        val state = repository.redeem(RedemptionCredential.RecoveryCode("CASTIVIO-1234-5678"))

        assertEquals(EntitlementState.Lifetime, (state as Outcome.Success).value)
        assertEquals(
            RedemptionCredential.RecoveryCode("CASTIVIO-1234-5678"),
            server.lastRedemption?.credential,
        )
        assertEquals(address, server.lastRedemption?.macAddress)
    }

    @Test
    fun `redeeming a store receipt carries the credential through untouched`() = runTest {
        val server = FakeServer(redemption = Outcome.Success(attestation()))
        val repository = repository(source = server)

        repository.redeem(RedemptionCredential.PurchaseReceipt("token-abc", "castivio.annual"))

        assertEquals(
            RedemptionCredential.PurchaseReceipt("token-abc", "castivio.annual"),
            server.lastRedemption?.credential,
        )
    }

    // ---------------------------------------------------- the server's clock

    /**
     * The end-to-end repair, through the repository rather than the pure code: a fast
     * clock ends a subscription, the server states the time, and the subscription comes
     * back — mark and all.
     */
    @Test
    fun `a verified answer repairs a clock that had ended the subscription`() = runTest {
        val store = MemoryStore()
        val signals = Signals(wallClockMs = t0)
        val clockStore = MemoryClockStore()
        val server = FakeServer(verification = Outcome.Success(attestation(serverTimeMs = t0 + hour)))
        val repository = repository(store, signals, clockStore, source = server)
        repository.establish()

        // The real-time clock fails and the device wakes up in 2028.
        signals.wallClockMs = t0 + 2 * year
        assertEquals(EntitlementState.TrialExpired, repository.current())
        assertEquals(t0 + 2 * year, store.record?.maxObservedTimeMs)

        // Correcting the date by hand does nothing; the mark does not come down for the
        // device clock. Only the server can.
        signals.wallClockMs = t0 + hour
        assertEquals(EntitlementState.TrialExpired, repository.current())

        val state = (repository.refresh() as Outcome.Success).value

        assertTrue("$state", state.allowsUse)
        assertEquals(t0 + hour, store.record?.maxObservedTimeMs)
        assertEquals(t0 + hour, clockStore.state.highWaterMarkMs)
    }

    @Test
    fun `a verified answer anchors the clock to the server`() = runTest {
        val clockStore = MemoryClockStore()
        val server = FakeServer(verification = Outcome.Success(attestation(serverTimeMs = t0 + hour)))
        val repository = repository(clockStore = clockStore, source = server)
        repository.establish()

        repository.refresh()

        assertNotNull(clockStore.state.anchor)
        assertEquals(TimeAnchorSource.LICENCE_SERVER, clockStore.state.anchor?.source)
        assertEquals(t0 + hour, clockStore.state.anchor?.epochMs)
    }

    // ------------------------------------------------------------------ publishing

    @Test
    fun `the published state follows every operation`() = runTest {
        val signals = Signals(wallClockMs = t0)
        val repository = repository(signals = signals)

        assertEquals(EntitlementState.Unknown, repository.state.first())

        repository.establish()
        assertTrue(repository.state.first().allowsUse)

        signals.wallClockMs = t0 + 8 * day
        repository.current()
        assertEquals(EntitlementState.TrialExpired, repository.state.first())
    }

    @Test
    fun `the identity is reported for the licence screen`() = runTest {
        assertEquals(address, repository().identity().macAddress)
    }
}
