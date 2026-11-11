package com.castivio.tv.gate

import com.castivio.core.common.Outcome
import com.castivio.domain.PlaylistSource
import com.castivio.domain.ProviderSource
import com.castivio.domain.SourceKind
import com.castivio.domain.SourceRepository
import com.castivio.domain.SyncState
import com.castivio.domain.entitlement.EntitlementRepository
import com.castivio.domain.entitlement.EntitlementState
import com.castivio.domain.entitlement.RedemptionCredential
import com.castivio.domain.entitlement.StartDestination
import com.castivio.domain.identity.DeviceIdentityRecord
import com.castivio.domain.portal.AppOpening
import com.castivio.domain.portal.PullOnOpen
import com.castivio.domain.portal.Reception
import com.castivio.domain.portal.SentSubscriptionReceiver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The gate's view model: the join between [AppOpening] and the screen.
 *
 * What the opening *decides* — the licence first, no wait where something is already on the
 * device, a bounded wait where nothing is, the rules about asking the website — is
 * `AppOpeningTest`'s and `PullOnOpenTest`'s. What is here is what the view model itself does
 * with the answers: it shows nothing until there is one, it follows a second one, it ignores a
 * return to the front while it is still deciding, and it moves on when told an activation
 * finished.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SplashViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // --------------------------------------------------------------- the harness

    private val trial = EntitlementState.TrialActive(expiresAtMs = 0L, daysRemaining = 5)

    private class Entitlements(private val granted: EntitlementState) : EntitlementRepository {
        override val state: Flow<EntitlementState> = emptyFlow()
        override suspend fun current(): EntitlementState = granted
        override suspend fun establish(): EntitlementState = granted
        override suspend fun refresh(): Outcome<EntitlementState> = throw UnsupportedOperationException()
        override suspend fun redeem(credential: RedemptionCredential): Outcome<EntitlementState> =
            throw UnsupportedOperationException()

        override suspend fun identity(): DeviceIdentityRecord = throw UnsupportedOperationException()
    }

    private class Sources(var active: ProviderSource? = null) : SourceRepository {
        override suspend fun activeNow(): ProviderSource? = active
        override suspend fun register(source: PlaylistSource, label: String?): ProviderSource =
            throw UnsupportedOperationException()

        override fun sources(): Flow<List<ProviderSource>> = throw UnsupportedOperationException()
        override fun active(): Flow<ProviderSource?> = throw UnsupportedOperationException()
        override suspend fun get(id: String): ProviderSource? = throw UnsupportedOperationException()
        override suspend fun save(source: ProviderSource) = throw UnsupportedOperationException()
        override suspend fun setActive(id: String) = throw UnsupportedOperationException()
        override suspend fun recordCatalogueImport(id: String, sync: SyncState) = throw UnsupportedOperationException()
        override suspend fun recordEpgImport(id: String, atMs: Long) = throw UnsupportedOperationException()
        override suspend fun delete(id: String) = throw UnsupportedOperationException()
    }

    private val sent = ProviderSource(id = "x:line.example.com:8080:bob", kind = SourceKind.XTREAM, label = "Sent", url = null)

    private class Receiver(private val sources: Sources, private val leaves: ProviderSource) : SentSubscriptionReceiver {
        var asked = 0
        var answer: Reception = Reception.NothingSent
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun announce() = Unit

        override suspend fun receive(): Reception {
            asked++
            gate?.await()
            if (answer is Reception.Added) sources.active = leaves
            return answer
        }
    }

    private val sources = Sources()
    private val receiver = Receiver(sources, sent)
    private var clockMs = 0L

    private fun viewModel(): SplashViewModel {
        val pull = PullOnOpen(receiver, CoroutineScope(SupervisorJob() + dispatcher), { clockMs })
        return SplashViewModel(AppOpening(Entitlements(trial), sources, pull))
    }

    // ------------------------------------------------------------------ the opening

    @Test
    fun `nothing is shown until the gates have answered`() = runTest(dispatcher) {
        val model = viewModel()

        assertNull(model.state.value.destination)

        advanceUntilIdle()

        assertEquals(StartDestination.Activation, model.state.value.destination)
    }

    @Test
    fun `a device with a subscription opens on Home`() = runTest(dispatcher) {
        sources.active = sent

        val model = viewModel()
        advanceUntilIdle()

        assertEquals(StartDestination.Home, model.state.value.destination)
    }

    /** The case this exists for: the activation screen is never drawn, so it cannot flash. */
    @Test
    fun `a subscription that arrives in time opens Home and the activation screen is never shown`() = runTest(dispatcher) {
        receiver.answer = Reception.Added(added = 1, rejected = 0)
        val seen = mutableListOf<StartDestination?>()
        val model = viewModel()
        backgroundScope.launch { model.state.collect { seen += it.destination } }

        advanceUntilIdle()
        runCurrent() // `advanceUntilIdle` does not wait for work in the background scope, which is where the collector is

        assertEquals(listOf<StartDestination?>(null, StartDestination.Home), seen)
    }

    @Test
    fun `a slow website shows the activation screen and then Home when the subscription lands`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        receiver.gate = gate
        receiver.answer = Reception.Added(added = 1, rejected = 0)
        val model = viewModel()

        advanceTimeBy(AppOpening.PATIENCE_MS)
        runCurrent()
        assertEquals(StartDestination.Activation, model.state.value.destination)

        gate.complete(Unit)
        runCurrent()

        assertEquals(StartDestination.Home, model.state.value.destination)
    }

    // ----------------------------------------------------------- coming forward

    @Test
    fun `coming forward while the opening is still deciding asks nobody and changes nothing`() = runTest(dispatcher) {
        receiver.gate = CompletableDeferred() // the opening's own ask is still out
        val model = viewModel()
        runCurrent()

        model.cameForward()
        runCurrent()

        assertNull(model.state.value.destination)
        assertEquals("only the opening asked", 1, receiver.asked)
    }

    @Test
    fun `coming forward on the activation screen moves to Home when something was sent`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()
        assertEquals(StartDestination.Activation, model.state.value.destination)

        clockMs = 30_000 // the owner sent it, and came back half a minute later
        receiver.answer = Reception.Added(added = 1, rejected = 0)
        model.cameForward()
        advanceUntilIdle()

        assertEquals(StartDestination.Home, model.state.value.destination)
    }

    @Test
    fun `coming forward with nothing sent leaves the screen where it is`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        clockMs = 30_000
        model.cameForward()
        advanceUntilIdle()

        assertEquals(StartDestination.Activation, model.state.value.destination)
        assertEquals(2, receiver.asked)
    }

    // ------------------------------------------------------------------ refresh

    @Test
    fun `an activation that finished moves the app on without asking the website again`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()
        assertEquals(StartDestination.Activation, model.state.value.destination)

        sources.active = sent // added by hand, on this device
        model.refresh()
        advanceUntilIdle()

        assertEquals(StartDestination.Home, model.state.value.destination)
        assertEquals("the opening's ask, and no other", 1, receiver.asked)
    }
}
