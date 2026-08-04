package com.castivio.feature.licence

import com.castivio.core.common.AppDispatchers
import com.castivio.core.common.Outcome
import com.castivio.core.common.config.ActivationDestination
import com.castivio.domain.entitlement.EntitlementRepository
import com.castivio.domain.entitlement.EntitlementState
import com.castivio.domain.entitlement.PricingDefaults
import com.castivio.domain.entitlement.RedemptionCredential
import com.castivio.domain.identity.DeviceIdentity
import com.castivio.domain.identity.DeviceIdentityRecord
import com.castivio.domain.identity.IdentityProvenance
import com.castivio.domain.identity.MacAddress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The state holder, on the JVM, with no device and no Compose.
 *
 * Everything asserted here is behaviour a user can observe and a screenshot
 * cannot: which link a plan opens, whether two copy confirmations interfere,
 * whether a handoff that goes nowhere leaves a card spinning forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LicenceViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val licence = MutableStateFlow<EntitlementState>(EntitlementState.TrialExpired)
    private var refreshResult: Outcome<EntitlementState> = Outcome.Success(EntitlementState.Lifetime)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun model() = LicenceViewModel(
        entitlement = FakeEntitlement(licence) { refreshResult },
        identity = FakeIdentity,
        dispatchers = object : AppDispatchers {
            override val main: CoroutineDispatcher = dispatcher
            override val io: CoroutineDispatcher = dispatcher
            override val default: CoroutineDispatcher = dispatcher
        },
    )

    /**
     * The plans are configuration and are known before anything is read.
     *
     * Not a hardcoded pair: whatever `purchasable` says, in its order.
     */
    @Test
    fun `the plans come from the config, before the record is read`() = runTest(dispatcher) {
        val model = model()
        assertEquals(PricingDefaults.config.purchasable, model.state.value.plans)
        assertNull("a licence was reported before it was read", model.state.value.licence)
    }

    @Test
    fun `the entitlement is collected, not read once`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()
        assertEquals(EntitlementState.TrialExpired, model.state.value.licence)

        // A device left on this screen past midnight sees the count fall rather
        // than showing yesterday's number.
        licence.value = EntitlementState.Lifetime
        advanceUntilIdle()
        assertEquals(EntitlementState.Lifetime, model.state.value.licence)
    }

    /**
     * The link a plan opens carries the plan and this device, and no price.
     *
     * The MAC is deliberately present: this is a link opened on the user's own
     * handset, not a QR, and asking somebody to retype an address they can see
     * two centimetres away would be a worse product for no security gain.
     */
    @Test
    fun `a plan opens the portal for that plan and this device`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()

        val annual = PricingDefaults.config.purchasable.first()
        val url = model.portalFor(annual)

        assertTrue("the plan is missing from $url", url.contains("plan=annual"))
        assertTrue("this device is missing from $url", url.contains("mac=2F%3A19"))
        assertTrue("the link left the one destination constant", url.startsWith(ActivationDestination.URL))
        assertFalse("a price reached the link: $url", url.contains("6") && url.contains("EUR"))
        assertEquals("annual", model.state.value.opening)
    }

    /**
     * The working state clears itself.
     *
     * The portal is a browser away and a user who never completes the purchase
     * must not come back to a card that has been spinning since they left.
     */
    @Test
    fun `a handoff stops looking busy on its own`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()
        model.portalFor(PricingDefaults.config.purchasable.first())
        assertEquals("annual", model.state.value.opening)

        advanceTimeBy(5_000)
        advanceUntilIdle()
        assertNull("the card is still busy five seconds later", model.state.value.opening)
    }

    /** No browser, no portal. Said plainly rather than left spinning. */
    @Test
    fun `a handoff that cannot start says so`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()
        model.portalFor(PricingDefaults.config.purchasable.first())
        model.handoffFailed()

        assertNull(model.state.value.opening)
        assertTrue("the failure was not reported", model.state.value.failed)
    }

    /** Back, mid-handoff: stop waiting, and do not call it an error. */
    @Test
    fun `cancelling a handoff is not a failure`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()
        model.portalFor(PricingDefaults.config.purchasable.first())
        model.cancelHandoff()

        assertNull(model.state.value.opening)
        assertFalse("cancelling was reported as a failure", model.state.value.failed)
    }

    @Test
    fun `a refresh that fails is reported and a refresh that works is not`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()

        refreshResult = Outcome.Failure(com.castivio.core.common.AppError.NOT_CONFIGURED)
        model.refresh()
        advanceUntilIdle()
        assertTrue("a failed refresh was reported as fine", model.state.value.failed)
        assertNull(model.state.value.opening)

        refreshResult = Outcome.Success(EntitlementState.Lifetime)
        model.refresh()
        advanceUntilIdle()
        assertFalse("the earlier failure was never cleared", model.state.value.failed)
    }

    /**
     * The two copy confirmations are independent.
     *
     * A user who copies the address and then the key within a second should see
     * both ticks. This is stated as a test because the sibling screen's file
     * claimed independence in a comment while modelling a shared flag underneath
     * it — a disagreement that survives review because both halves read fine.
     */
    @Test
    fun `copying one identifier does not clear the other`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()

        model.copied(Copied.Address)
        advanceTimeBy(500)
        model.copied(Copied.Key)

        assertTrue("the address tick went out early", model.state.value.addressCopied)
        assertTrue("the key tick never lit", model.state.value.keyCopied)

        // The address was copied first, so its tick clears first, and the line
        // follows whichever is still lit rather than going blank.
        //
        // `advanceTimeBy` and **not** `advanceUntilIdle`. The second runs every
        // pending task whatever it is scheduled for, so it fires the key's timer
        // as well and the test then asserts that a tick which has legitimately
        // expired is still lit. That is the whole failure this test had on its
        // first run: the harness was asked to skip to the end and then asked
        // about the middle.
        advanceTimeBy(1_200)
        assertFalse(
            "the address tick is still lit 1700ms after it was set, and it lasts 1500",
            model.state.value.addressCopied,
        )
        assertTrue(
            "the key tick went out with the address tick -- the two timers are " +
                "sharing state again",
            model.state.value.keyCopied,
        )
        assertEquals(
            "the status line went blank instead of following the tick still lit",
            Copied.Key,
            model.state.value.lastCopied,
        )
    }

    /** And both go out in the end. A tick that never clears stops being feedback. */
    @Test
    fun `a copy confirmation is taken back`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()
        model.copied(Copied.Address)
        advanceTimeBy(3_000)

        assertFalse("the tick never cleared", model.state.value.addressCopied)
        assertEquals(
            "the status line still names a copy that is over",
            Copied.None,
            model.state.value.lastCopied,
        )
    }

    /** Support is told which device is asking, so the call does not start with hex. */
    @Test
    fun `the support link carries the device`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()
        val url = model.supportUrl()
        assertTrue("the support link is not the one constant: $url",
            url.startsWith(ActivationDestination.SUPPORT_URL))
        assertTrue("support is not told which device: $url", url.contains("mac=2F%3A19"))
    }
}

private object FakeIdentity : DeviceIdentity {
    override fun current() = DeviceIdentityRecord(
        macAddress = MacAddress.parse("2F:19:EB:20:44:7C")!!,
        algorithmVersion = 1,
        provenance = IdentityProvenance.DEVICE,
    )

    override fun legacy(): List<DeviceIdentityRecord> = emptyList()
}

private class FakeEntitlement(
    override val state: Flow<EntitlementState>,
    private val onRefresh: () -> Outcome<EntitlementState>,
) : EntitlementRepository {
    override suspend fun current(): EntitlementState = EntitlementState.Unknown
    override suspend fun establish(): EntitlementState = EntitlementState.Unknown
    override suspend fun refresh(): Outcome<EntitlementState> = onRefresh()
    override suspend fun redeem(credential: RedemptionCredential): Outcome<EntitlementState> =
        error("this screen never redeems: the portal does, and the server answers")

    override suspend fun identity() = FakeIdentity.current()
}
