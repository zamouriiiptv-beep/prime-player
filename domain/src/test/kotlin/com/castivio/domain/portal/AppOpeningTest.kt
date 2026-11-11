package com.castivio.domain.portal

import com.castivio.core.common.Outcome
import com.castivio.domain.PlaylistSource
import com.castivio.domain.ProviderSource
import com.castivio.domain.SourceKind
import com.castivio.domain.SourceRepository
import com.castivio.domain.SyncState
import com.castivio.domain.entitlement.EntitlementRepository
import com.castivio.domain.entitlement.EntitlementState
import com.castivio.domain.entitlement.LicenceReason
import com.castivio.domain.entitlement.RedemptionCredential
import com.castivio.domain.entitlement.StartDestination
import com.castivio.domain.identity.DeviceIdentityRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How the app opens once the website is asked what was sent while it was closed.
 *
 * The cases that carry the weight are the ones that go wrong quietly: a device with a
 * subscription must not wait on a website; a device without one must not wait for ever; a
 * device that may not be used must not be told about subscriptions; and an ask that nobody is
 * waiting for any more must still end the way it would have.
 */
class AppOpeningTest {

    // --------------------------------------------------------------- the harness

    private val trial = EntitlementState.TrialActive(expiresAtMs = 1_800_000_000_000L, daysRemaining = 7)

    private class Entitlements(var granted: EntitlementState) : EntitlementRepository {
        var established = 0
        override val state: Flow<EntitlementState> = emptyFlow()
        override suspend fun current(): EntitlementState = granted
        override suspend fun establish(): EntitlementState {
            established++
            return granted
        }

        override suspend fun refresh(): Outcome<EntitlementState> = throw UnsupportedOperationException()
        override suspend fun redeem(credential: RedemptionCredential): Outcome<EntitlementState> =
            throw UnsupportedOperationException()

        override suspend fun identity(): DeviceIdentityRecord = throw UnsupportedOperationException()
    }

    /** Only what the opening asks of it: whether something is on the device. */
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
    private val existing = ProviderSource(id = "m:https://list.example.com/old.m3u8", kind = SourceKind.M3U_URL, label = "Old", url = null)

    /**
     * What `ReceiveSentSubscriptions` does, as far as an opening can tell: an `Added` answer
     * comes with the subscription on the device and active, and anything else leaves it alone.
     */
    private class Receiver(private val sources: Sources, private val leaves: ProviderSource) : SentSubscriptionReceiver {
        var asked = 0
        var answer: Reception = Reception.NothingSent
        var gate: CompletableDeferred<Unit>? = null
        var failure: Exception? = null

        override suspend fun announce() = Unit

        override suspend fun receive(): Reception {
            asked++
            gate?.await()
            failure?.let { throw it }
            if (answer is Reception.Added) sources.active = leaves
            return answer
        }
    }

    private val entitlements = Entitlements(trial)
    private val sources = Sources()
    private val receiver = Receiver(sources, sent)
    private var clockMs = 0L

    private fun TestScope.appOpening() =
        AppOpening(entitlements, sources, PullOnOpen(receiver, backgroundScope, { clockMs }))

    // ------------------------------------------------------- the licence comes first

    @Test
    fun `a device that may not be used goes to the licence screen and is not told about subscriptions`() = runTest {
        entitlements.granted = EntitlementState.TrialExpired

        val shown = appOpening().opening().toList()

        assertEquals(listOf<StartDestination>(StartDestination.Licence(LicenceReason.TRIAL_EXPIRED)), shown)
        runCurrent() // an ask that was started would run now
        assertEquals("nothing was asked", 0, receiver.asked)
    }

    // ----------------------------------------------- a subscription is already here

    @Test
    fun `a device with a subscription opens on Home at once and does not wait for the website`() = runTest {
        sources.active = existing
        receiver.gate = CompletableDeferred() // an answer that never comes

        val shown = appOpening().opening().toList()

        assertEquals(listOf<StartDestination>(StartDestination.Home), shown)
        assertEquals("no waiting: not a millisecond of the patience was spent", 0L, currentTime)
        runCurrent() // the ask runs behind Home, in its own scope
        assertEquals("asked, behind Home", 1, receiver.asked)
    }

    // ------------------------------------------------------------ nothing is here

    @Test
    fun `nothing here and nothing sent shows the activation screen once the website has said so`() = runTest {
        receiver.answer = Reception.NothingSent

        val shown = appOpening().opening().toList()

        assertEquals(listOf<StartDestination>(StartDestination.Activation), shown)
        assertEquals(1, receiver.asked)
    }

    /** The case this exists for: sent from the website, the app closed and opened again. */
    @Test
    fun `nothing here and a subscription arrives in time opens on Home and never shows activation`() = runTest {
        receiver.answer = Reception.Added(added = 1, rejected = 0)

        val shown = appOpening().opening().toList()

        assertEquals(listOf<StartDestination>(StartDestination.Home), shown)
    }

    @Test
    fun `an unreachable website shows the activation screen`() = runTest {
        receiver.answer = Reception.Unreachable

        assertEquals(listOf<StartDestination>(StartDestination.Activation), appOpening().opening().toList())
    }

    @Test
    fun `a subscription the device refused leaves the activation screen where it was`() = runTest {
        receiver.answer = Reception.Rejected(count = 1)

        assertEquals(listOf<StartDestination>(StartDestination.Activation), appOpening().opening().toList())
    }

    @Test
    fun `an unknown key shows the activation screen`() = runTest {
        receiver.answer = Reception.NotRecognised

        assertEquals(listOf<StartDestination>(StartDestination.Activation), appOpening().opening().toList())
    }

    @Test
    fun `an exception in the ask is not the reason the app does not open`() = runTest {
        receiver.failure = IllegalStateException("database")

        assertEquals(listOf<StartDestination>(StartDestination.Activation), appOpening().opening().toList())
    }

    // ------------------------------------------------------------------- slow

    /** An offline television must reach the one screen that works offline, and not later than the patience. */
    @Test
    fun `a slow website is waited for until the patience runs out, and then the activation screen shows`() = runTest {
        receiver.gate = CompletableDeferred()
        val shown = mutableListOf<StartDestination>()
        val opening = appOpening()

        backgroundScope.launch { opening.opening(patienceMs = 5_000).toList(shown) }
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals("still deciding at 4.999 s", emptyList<StartDestination>(), shown)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf<StartDestination>(StartDestination.Activation), shown)
    }

    @Test
    fun `a slow answer that adds a subscription moves the app on to Home after the first screen`() = runTest {
        val gate = CompletableDeferred<Unit>()
        receiver.gate = gate
        receiver.answer = Reception.Added(added = 1, rejected = 0)
        val shown = mutableListOf<StartDestination>()
        val opening = appOpening()

        backgroundScope.launch { opening.opening(patienceMs = 5_000).toList(shown) }
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf<StartDestination>(StartDestination.Activation), shown)

        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf(StartDestination.Activation, StartDestination.Home), shown)
    }

    @Test
    fun `a slow answer with nothing in it leaves the activation screen alone`() = runTest {
        val gate = CompletableDeferred<Unit>()
        receiver.gate = gate
        receiver.answer = Reception.NothingSent
        val shown = mutableListOf<StartDestination>()
        val opening = appOpening()

        backgroundScope.launch { opening.opening(patienceMs = 5_000).toList(shown) }
        advanceTimeBy(5_000)
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf<StartDestination>(StartDestination.Activation), shown)
    }

    /**
     * The screen is gone before the answer is in. The ask belongs to the process, so the
     * subscription is still added, and the next launch finds it there.
     */
    @Test
    fun `an ask that nobody is waiting for any more still ends with the subscription added`() = runTest {
        val gate = CompletableDeferred<Unit>()
        receiver.gate = gate
        receiver.answer = Reception.Added(added = 1, rejected = 0)
        val opening = appOpening()

        val collecting = backgroundScope.launch { opening.opening(patienceMs = 5_000).toList() }
        runCurrent()
        collecting.cancel()
        gate.complete(Unit)
        runCurrent()

        assertEquals(sent, sources.active)
    }

    // ----------------------------------------------------------- asked once

    @Test
    fun `a launch that finds an ask already out joins it instead of making another`() = runTest {
        val gate = CompletableDeferred<Unit>()
        receiver.gate = gate
        receiver.answer = Reception.Added(added = 1, rejected = 0)
        val opening = appOpening()
        val first = mutableListOf<StartDestination>()
        val second = mutableListOf<StartDestination>()

        backgroundScope.launch { opening.opening(patienceMs = 5_000).toList(first) }
        backgroundScope.launch { opening.opening(patienceMs = 5_000).toList(second) }
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf<StartDestination>(StartDestination.Home), first)
        assertEquals(listOf<StartDestination>(StartDestination.Home), second)
        assertEquals(1, receiver.asked)
    }

    @Test
    fun `an ask made a moment ago is not repeated, and the activation screen shows without a wait`() = runTest {
        val opening = appOpening()
        opening.opening().toList()
        receiver.gate = CompletableDeferred() // a second ask would hang

        val shown = opening.opening(patienceMs = 5_000).toList()

        assertEquals(listOf<StartDestination>(StartDestination.Activation), shown)
        assertEquals("no waiting: there was nothing to wait for", 0L, currentTime)
        runCurrent()
        assertEquals(1, receiver.asked)
    }

    // ------------------------------------------------------------------ settled

    @Test
    fun `settled answers from what the device holds and asks nobody`() = runTest {
        val opening = appOpening()

        assertEquals(StartDestination.Activation, opening.settled())
        sources.active = sent
        assertEquals(StartDestination.Home, opening.settled())

        runCurrent()
        assertEquals(0, receiver.asked)
        assertEquals("the licence is established, as it always was", 2, entitlements.established)
    }

    // ------------------------------------------------------------ coming forward

    @Test
    fun `coming forward on the licence screen asks nobody`() = runTest {
        val moved = appOpening().cameForward(StartDestination.Licence(LicenceReason.TRIAL_EXPIRED))

        assertNull(moved)
        runCurrent()
        assertEquals(0, receiver.asked)
    }

    @Test
    fun `coming forward on the activation screen with a subscription waiting moves to Home`() = runTest {
        receiver.answer = Reception.Added(added = 1, rejected = 0)

        assertEquals(StartDestination.Home, appOpening().cameForward(StartDestination.Activation))
    }

    @Test
    fun `coming forward on the activation screen with nothing waiting stays`() = runTest {
        receiver.answer = Reception.NothingSent

        assertNull(appOpening().cameForward(StartDestination.Activation))
        assertEquals(1, receiver.asked)
    }

    @Test
    fun `coming forward on Home adds what was sent and stays, because Home shows it by itself`() = runTest {
        sources.active = existing
        receiver.answer = Reception.Added(added = 1, rejected = 0)

        assertNull(appOpening().cameForward(StartDestination.Home))
        assertEquals("the new subscription is the one shown", sent, sources.active)
    }

    /**
     * Only an arrival moves the app. Whatever else changed while the ask was out — here the last
     * subscription was deleted, so nothing is on the device any more — is for the screen that
     * deleted it to act on, not for a path that was asked to bring in what the website sent.
     */
    @Test
    fun `coming forward moves the app only when something arrived`() = runTest {
        receiver.answer = Reception.NothingSent

        assertNull(appOpening().cameForward(StartDestination.Home))
    }

    @Test
    fun `coming forward twice in a moment asks once`() = runTest {
        val opening = appOpening()
        opening.cameForward(StartDestination.Activation)

        assertNull(opening.cameForward(StartDestination.Activation))
        assertEquals(1, receiver.asked)
    }

    /** The launch judges the licence. A lapse while the app was away is neither a reason to ask nor to move the user. */
    @Test
    fun `coming forward after the licence lapsed meanwhile asks nobody and stays`() = runTest {
        receiver.answer = Reception.Added(added = 1, rejected = 0)
        entitlements.granted = EntitlementState.TrialExpired

        assertNull(appOpening().cameForward(StartDestination.Activation))
        runCurrent()
        assertEquals(0, receiver.asked)
        assertEquals("nothing is established, nothing is granted", 0, entitlements.established)
    }
}
