package com.castivio.domain.entitlement

import com.castivio.domain.identity.MacAddress
import com.castivio.domain.time.ClockSignalSource
import com.castivio.domain.time.ClockSignals
import com.castivio.domain.time.ClockState
import com.castivio.domain.time.ClockStore
import com.castivio.domain.time.MonotonicClock
import com.castivio.domain.time.TimeAnchorSource
import com.castivio.domain.time.TimeReading
import com.castivio.domain.time.TimeTrust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The support ticket, end to end.
 *
 * A television with a bad real-time clock boots two years into the future. The app
 * reads the time, believes it, and records it — because at that moment it has nothing
 * to disagree with. The subscription the customer paid for last week now reads as
 * having expired a year ago, and correcting the date by hand does not fix it: the
 * high-water mark that stops a *deliberate* rollback also stops an *honest* one.
 *
 * There is exactly one way out, and it is the reason `TimeTrust.NETWORK` exists.
 * Castivio's own licence infrastructure is allowed to say what time it is, and that
 * statement outranks everything the device has recorded about itself.
 *
 * The journey below is the whole mechanism in one test: poison, trap, repair, and the
 * proof that the repair sticks.
 */
class TrustedTimeRepairTest {

    private val day = 24L * 60 * 60 * 1000
    private val hour = 60L * 60 * 1000
    private val year = 365 * day

    /** 2026-03-01T00:00:00Z. */
    private val t0 = 1_772_323_200_000L

    private val config = PricingDefaults.config

    // ------------------------------------------------------------- the harness

    private class Store(var state: ClockState = ClockState()) : ClockStore {
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

    /** An annual subscription bought at [t0], with a year to run and just verified. */
    private fun annual() = EntitlementRecord(
        macAddress = MacAddress.parse("2F:19:EB:20:44:7C")!!,
        identityVersion = 1,
        plan = Plan.ANNUAL,
        subscriptionExpiresAtMs = t0 + year,
        establishedAtMs = t0,
        lastVerifiedAtMs = t0,
        maxObservedTimeMs = t0,
    )

    // --------------------------------------------------------------- the journey

    @Test
    fun `a fast clock ends a subscription and trusted time gives it back`() {
        val signals = Signals(wallClockMs = t0)
        val clock = MonotonicClock(signals, Store())
        var stored = annual()

        // 1. Everything is fine. The subscription has a year to run.
        val healthy = EntitlementPolicy.evaluate(stored, clock.now(), config)
        assertTrue("${healthy.state}", healthy.state.allowsUse)
        stored = healthy.record!!

        // 2. The real-time clock fails and the device wakes up in 2028.
        signals.wallClockMs = t0 + 2 * year
        val poisoned = EntitlementPolicy.evaluate(stored, clock.now(), config)

        assertEquals(EntitlementState.AnnualExpired(t0 + year), poisoned.state)
        stored = poisoned.record!!
        assertEquals(t0 + 2 * year, stored.maxObservedTimeMs)

        // 3. The trap. Setting the date back by hand changes nothing, because the mark
        //    does not come down for the device clock — which is exactly the behaviour
        //    that stops a second free trial, working against us here.
        signals.wallClockMs = t0 + 2 * hour
        val handCorrected = EntitlementPolicy.evaluate(stored, clock.now(), config)

        assertEquals(TimeTrust.FLOORED, clock.now().trust)
        assertEquals(EntitlementState.AnnualExpired(t0 + year), handCorrected.state)
        assertFalse(handCorrected.state.allowsUse)

        // 4. The device reaches Castivio's licence host, which states the time.
        val truth = clock.anchor(t0 + 2 * hour, TimeAnchorSource.LICENCE_SERVER)
        assertEquals(TimeTrust.NETWORK, truth.trust)

        val repaired = EntitlementPolicy.evaluate(handCorrected.record, truth, config)

        // 5. The poison is gone from the clock and from the record, and the customer
        //    has the subscription they paid for.
        assertEquals(t0 + 2 * hour, repaired.record!!.maxObservedTimeMs)
        assertTrue("${repaired.state}", repaired.state.allowsUse)
        assertEquals(
            EntitlementState.AnnualActive(t0 + year, 365),
            repaired.state,
        )
        stored = repaired.record!!

        // 6. And it sticks. A later ordinary read on the now-correct clock — after a
        //    reboot, so the anchor is gone and only the mark is left — does not put the
        //    two years back.
        signals.bootId = "boot-b"
        signals.elapsedRealtimeMs = 4_000L
        signals.wallClockMs = t0 + 3 * hour
        val afterwards = EntitlementPolicy.evaluate(stored, clock.now(), config)

        assertEquals(t0 + 3 * hour, afterwards.record!!.maxObservedTimeMs)
        assertTrue("${afterwards.state}", afterwards.state.allowsUse)
    }

    // ------------------------------------------------- the asymmetry, stated alone

    /**
     * The device may raise the mark and may not lower it. This is the half that keeps
     * a second free trial out of reach, and it has to keep working after the repair
     * path exists.
     */
    @Test
    fun `a device reading never lowers the mark`() {
        val record = annual().copy(maxObservedTimeMs = t0 + 30 * day)

        val back = record.observing(TimeReading(t0, TimeTrust.DEVICE))
        val floored = record.observing(TimeReading(t0, TimeTrust.FLOORED))
        val forward = record.observing(TimeReading(t0 + 60 * day, TimeTrust.DEVICE))

        assertEquals(t0 + 30 * day, back.maxObservedTimeMs)
        assertEquals(t0 + 30 * day, floored.maxObservedTimeMs)
        assertEquals(t0 + 60 * day, forward.maxObservedTimeMs)
    }

    /** And the half that repairs it: only a trusted reading may move the mark down. */
    @Test
    fun `only a network reading lowers the mark`() {
        val record = annual().copy(maxObservedTimeMs = t0 + 2 * year)

        val repaired = record.observing(TimeReading(t0, TimeTrust.NETWORK))

        assertEquals(t0, repaired.maxObservedTimeMs)
    }

    /**
     * A rolled-back clock still buys nothing once the decision goes through the same
     * path the repair does — the reading it produces is `FLOORED`, not `NETWORK`.
     */
    @Test
    fun `winding the clock back does not revive an expired trial through the repair path`() {
        val signals = Signals(wallClockMs = t0)
        val clock = MonotonicClock(signals, Store())
        var stored = EntitlementRecord(
            macAddress = MacAddress.parse("2F:19:EB:20:44:7C")!!,
            identityVersion = 1,
            plan = Plan.TRIAL,
            trialStartedAtMs = t0,
            trialExpiresAtMs = t0 + 7 * day,
            establishedAtMs = t0,
            lastVerifiedAtMs = t0,
            maxObservedTimeMs = t0,
        )

        signals.wallClockMs = t0 + 8 * day
        stored = EntitlementPolicy.evaluate(stored, clock.now(), config).record!!

        signals.wallClockMs = t0 - year
        val attempted = EntitlementPolicy.evaluate(stored, clock.now(), config)

        assertEquals(EntitlementState.TrialExpired, attempted.state)
        assertEquals(t0 + 8 * day, attempted.record!!.maxObservedTimeMs)
    }

    /**
     * The repair is not reachable from a provider's server, because a provider can
     * never produce a `NETWORK` reading — only the two Castivio sources anchor, and the
     * clock is what enforces that. Stated here as well because it is the security
     * property the whole asymmetry rests on.
     */
    @Test
    fun `every anchor source names castivio`() {
        assertEquals(
            listOf(TimeAnchorSource.LICENCE_HOST_HEADER, TimeAnchorSource.LICENCE_SERVER),
            TimeAnchorSource.values().toList(),
        )
    }

    // --------------------------------------------------------- nothing is stored

    /**
     * The policy still only reports. It hands back the record the caller should store
     * rather than storing it, so a read can never grant, revoke or persist anything on
     * its own.
     */
    @Test
    fun `a decision on no record produces no record`() {
        val clock = MonotonicClock(Signals(wallClockMs = t0), Store())

        val decision = EntitlementPolicy.evaluate(record = null, reading = clock.now(), config = config)

        assertEquals(EntitlementState.Unknown, decision.state)
        assertEquals(null, decision.record)
    }
}
