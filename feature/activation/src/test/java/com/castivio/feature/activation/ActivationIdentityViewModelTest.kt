package com.castivio.feature.activation

import com.castivio.core.common.AppDispatchers
import com.castivio.core.common.Outcome
import com.castivio.domain.entitlement.EntitlementRepository
import com.castivio.domain.entitlement.EntitlementState
import com.castivio.domain.entitlement.RedemptionCredential
import com.castivio.domain.identity.DeviceIdentity
import com.castivio.domain.identity.DeviceIdentityRecord
import com.castivio.domain.identity.DeviceKey
import com.castivio.domain.identity.IdentityProvenance
import com.castivio.domain.identity.MacAddress
import com.castivio.domain.portal.Reception
import com.castivio.domain.portal.SentSubscriptionReceiver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Refresh button, from the press to the line under it.
 *
 * What the screen decides is small and is all here: it says it is checking, says what it
 * found, ignores a second press while one is running, never answers faster than a person can
 * read, and reports an arrival exactly once. What the *sequence* does — adding the
 * subscription, acknowledging it — is `ReceiveSentSubscriptionsTest`'s, and what goes over
 * the wire is `HttpSubscriptionInboxTest`'s; this is the join between them and the screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivationIdentityViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // --------------------------------------------------------------- the harness

    private val record = DeviceIdentityRecord(MacAddress.parse("AA:BB:CC:00:11:22")!!, 2, IdentityProvenance.DEVICE)

    private inner class Identity : DeviceIdentity {
        override fun current() = record
        override fun legacy(): List<DeviceIdentityRecord> = emptyList()
        override fun key() = DeviceKey("482731")
    }

    private inner class Entitlement : EntitlementRepository {
        private val trial = EntitlementState.TrialActive(expiresAtMs = 0L, daysRemaining = 5)
        override val state: Flow<EntitlementState> = flowOf(trial)
        override suspend fun current(): EntitlementState = trial
        override suspend fun establish(): EntitlementState = trial
        override suspend fun refresh(): Outcome<EntitlementState> = Outcome.Success(trial)
        override suspend fun redeem(credential: RedemptionCredential): Outcome<EntitlementState> = Outcome.Success(trial)
        override suspend fun identity(): DeviceIdentityRecord = record
    }

    private class Receiver : SentSubscriptionReceiver {
        var answer: Reception = Reception.NothingSent
        var announces = 0
        var receives = 0

        /** When set, an answer waits for it: the portal is slow. */
        var hold: CompletableDeferred<Unit>? = null

        override suspend fun announce() {
            announces++
        }

        override suspend fun receive(): Reception {
            receives++
            hold?.await()
            return answer
        }
    }

    private val receiver = Receiver()

    private fun viewModel() = ActivationIdentityViewModel(
        identity = Identity(),
        entitlement = Entitlement(),
        dispatchers = object : AppDispatchers {
            override val main: CoroutineDispatcher = dispatcher
            override val io: CoroutineDispatcher = dispatcher
            override val default: CoroutineDispatcher = dispatcher
        },
        portal = receiver,
    )

    // ------------------------------------------------------------------ the answers

    @Test
    fun `a subscription that was added is found, and the arrival is reported once`() = runTest {
        receiver.answer = Reception.Added(added = 1, rejected = 0)
        val model = viewModel()
        advanceUntilIdle()

        model.refresh()
        runCurrent()
        assertEquals(RefreshState.Checking, model.state.value.refresh)

        advanceUntilIdle()
        assertEquals(RefreshState.Found, model.state.value.refresh)
        assertTrue(model.state.value.arrived)

        model.arrivalHandled()
        assertFalse("taken off the state, so a later composition cannot report it again", model.state.value.arrived)
        assertEquals("what happened is still what the line says", RefreshState.Found, model.state.value.refresh)

        model.arrivalHandled()
        assertFalse(model.state.value.arrived)
    }

    @Test
    fun `nothing sent says so and reports no arrival`() = runTest {
        receiver.answer = Reception.NothingSent
        val model = viewModel()
        advanceUntilIdle()

        model.refresh()
        advanceUntilIdle()

        assertEquals(RefreshState.None, model.state.value.refresh)
        assertFalse(model.state.value.arrived)
    }

    @Test
    fun `every way it can fail is the one line that says try again, and reports no arrival`() = runTest {
        for (failure in listOf(Reception.Unreachable, Reception.NotRecognised, Reception.Rejected(2))) {
            receiver.answer = failure
            val model = viewModel()
            advanceUntilIdle()

            model.refresh()
            advanceUntilIdle()

            assertEquals("$failure", RefreshState.Error, model.state.value.refresh)
            assertFalse("$failure", model.state.value.arrived)
        }
    }

    @Test
    fun `a failed press can be pressed again, and the second one can find it`() = runTest {
        receiver.answer = Reception.Unreachable
        val model = viewModel()
        advanceUntilIdle()
        model.refresh()
        advanceUntilIdle()
        assertEquals(RefreshState.Error, model.state.value.refresh)

        receiver.answer = Reception.Added(1, 0)
        model.refresh()
        runCurrent()
        assertEquals(RefreshState.Checking, model.state.value.refresh)
        advanceUntilIdle()

        assertEquals(RefreshState.Found, model.state.value.refresh)
        assertTrue(model.state.value.arrived)
    }

    @Test
    fun `a press after an arrival that finds nothing does not report another`() = runTest {
        receiver.answer = Reception.Added(1, 0)
        val model = viewModel()
        advanceUntilIdle()
        model.refresh()
        advanceUntilIdle()
        model.arrivalHandled()

        receiver.answer = Reception.NothingSent
        model.refresh()
        advanceUntilIdle()

        assertEquals(RefreshState.None, model.state.value.refresh)
        assertFalse(model.state.value.arrived)
    }

    // ------------------------------------------------------------------- the control

    @Test
    fun `a second press while one is running is ignored`() = runTest {
        receiver.hold = CompletableDeferred()
        val model = viewModel()
        advanceUntilIdle()

        model.refresh()
        runCurrent()
        model.refresh()
        model.refresh()
        runCurrent()

        assertEquals(1, receiver.receives)
        assertEquals(RefreshState.Checking, model.state.value.refresh)

        receiver.hold!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(RefreshState.None, model.state.value.refresh)
    }

    @Test
    fun `an answer that comes back at once is still shown as checking long enough to read`() = runTest {
        receiver.answer = Reception.NothingSent
        val model = viewModel()
        advanceUntilIdle()

        model.refresh()
        advanceTimeBy(100)
        runCurrent()

        assertEquals(RefreshState.Checking, model.state.value.refresh)
        assertEquals("the portal was asked straight away; only the answer waits", 1, receiver.receives)

        advanceUntilIdle()
        assertEquals(RefreshState.None, model.state.value.refresh)
    }

    @Test
    fun `a slow portal is waited for and not cut short by the floor`() = runTest {
        receiver.hold = CompletableDeferred()
        receiver.answer = Reception.Added(1, 0)
        val model = viewModel()
        advanceUntilIdle()

        model.refresh()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("still waiting on the portal after a minute", RefreshState.Checking, model.state.value.refresh)

        receiver.hold!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(RefreshState.Found, model.state.value.refresh)
    }

    // ---------------------------------------------------------------- announcing

    @Test
    fun `the portal is told the device exists as soon as the key is on screen`() = runTest {
        val model = viewModel()
        advanceUntilIdle()

        assertEquals("482731", model.state.value.deviceKey)
        assertEquals(1, receiver.announces)
        assertEquals("announcing is not asking", 0, receiver.receives)
        assertEquals("and it does not touch the refresh line", RefreshState.Idle, model.state.value.refresh)
    }
}
