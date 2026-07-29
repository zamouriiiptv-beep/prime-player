package com.castivio.domain.time

import com.castivio.domain.entitlement.EntitlementPolicy
import com.castivio.domain.entitlement.EntitlementRecord
import com.castivio.domain.entitlement.EntitlementState
import com.castivio.domain.entitlement.Plan
import com.castivio.domain.entitlement.PricingDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clock, under every way a device can lie about the time.
 *
 * The scenarios are not hypothetical. A television that has been unplugged for a month
 * boots at the epoch; a phone in aeroplane mode drifts; a user who wants a second free
 * trial sets the date back deliberately. All three arrive at the same code, and the
 * distinction the clock has to keep is between "wrong" and "moved" — the first is
 * forgiven, the second is not paid for.
 */
class MonotonicClockTest {

    private val day = 24L * 60 * 60 * 1000
    private val year = 365 * day

    /** 2026-03-01T00:00:00Z, comfortably inside the credible window. */
    private val t0 = 1_772_323_200_000L

    // --------------------------------------------------------------- the harness

    private class RecordingStore(initial: ClockState = ClockState()) : ClockStore {
        var state: ClockState = initial
        var writes: Int = 0
        override fun load(): ClockState = state
        override fun save(state: ClockState) {
            this.state = state
            writes++
        }
    }

    private class Signals(
        var wallClockMs: Long,
        var elapsedRealtimeMs: Long = 60_000L,
        var bootId: String = "boot-a",
    ) : ClockSignalSource {
        override fun read() = ClockSignals(wallClockMs, elapsedRealtimeMs, bootId)

        /** Time actually passing: both clocks advance together. */
        fun pass(ms: Long) {
            wallClockMs += ms
            elapsedRealtimeMs += ms
        }

        /** The user changing the date: only the wall clock moves. */
        fun setDateTo(epochMs: Long) {
            wallClockMs = epochMs
        }

        /** A reboot: elapsed-realtime restarts and the boot identifier changes. */
        fun reboot(offMs: Long, newBootId: String) {
            wallClockMs += offMs
            elapsedRealtimeMs = 4_000L
            bootId = newBootId
        }
    }

    // ------------------------------------------------------- the ordinary device

    @Test
    fun `a clock nobody has touched is simply reported`() {
        val signals = Signals(wallClockMs = t0)
        val clock = MonotonicClock(signals, RecordingStore())

        assertEquals(TimeReading(t0, TimeTrust.DEVICE), clock.now())
    }

    @Test
    fun `time passing normally is trusted the whole way`() {
        val signals = Signals(wallClockMs = t0)
        val clock = MonotonicClock(signals, RecordingStore())

        repeat(10) {
            signals.pass(6L * 60 * 60 * 1000)
            assertEquals(TimeTrust.DEVICE, clock.now().trust)
        }
        assertEquals(t0 + 60 * 60 * 60 * 1000, clock.nowMs())
    }

    @Test
    fun `reading the time records the furthest instant seen`() {
        val store = RecordingStore()
        val signals = Signals(wallClockMs = t0)
        val clock = MonotonicClock(signals, store)

        clock.now()

        assertEquals(t0, store.state.highWaterMarkMs)
    }

    @Test
    fun `a reading that changes nothing is not written`() {
        val store = RecordingStore()
        val clock = MonotonicClock(Signals(wallClockMs = t0), store)

        clock.now()
        val afterFirst = store.writes
        repeat(5) { clock.now() }

        assertEquals(afterFirst, store.writes)
    }

    // ------------------------------------------------------------- the rollback

    @Test
    fun `a clock moved backwards reports the furthest instant already seen`() {
        val signals = Signals(wallClockMs = t0 + 30 * day)
        val clock = MonotonicClock(signals, RecordingStore())
        clock.now()

        signals.setDateTo(t0)

        assertEquals(TimeReading(t0 + 30 * day, TimeTrust.FLOORED), clock.now())
    }

    /**
     * The attack this whole class exists for: run out the trial, set the date back,
     * expect a fresh week. The mark does not move down, so nothing is bought — and
     * setting the date forward again resumes from where the device really is, not from
     * the fake past it was just pretending to be in.
     */
    @Test
    fun `winding the clock back and forward again buys no time`() {
        val signals = Signals(wallClockMs = t0)
        val clock = MonotonicClock(signals, RecordingStore())
        clock.now()

        signals.pass(8 * day)
        val real = clock.nowMs()

        signals.setDateTo(t0 - year)
        assertEquals(real, clock.nowMs())

        signals.setDateTo(t0 + 3 * day)
        assertEquals(real, clock.nowMs())

        signals.setDateTo(real + 1)
        assertEquals(real + 1, clock.nowMs())
    }

    @Test
    fun `the furthest instant survives the process being killed`() {
        val store = RecordingStore()
        val signals = Signals(wallClockMs = t0 + 30 * day)
        MonotonicClock(signals, store).now()

        // Process dies; a new one starts against the same store and a wound-back clock.
        signals.setDateTo(t0)
        val restarted = MonotonicClock(signals, store)

        assertEquals(TimeReading(t0 + 30 * day, TimeTrust.FLOORED), restarted.now())
    }

    // ---------------------------------------------------------- the broken clock

    @Test
    fun `a clock at the epoch does not become the reported time`() {
        val store = RecordingStore()
        val signals = Signals(wallClockMs = t0)
        val clock = MonotonicClock(signals, store)
        clock.now()

        // The coin cell died: the device boots in 1970.
        signals.setDateTo(0L)

        assertEquals(TimeReading(t0, TimeTrust.FLOORED), clock.now())
        assertEquals(t0, store.state.highWaterMarkMs)
    }

    @Test
    fun `a clock in the far future does not poison the mark`() {
        val store = RecordingStore()
        val signals = Signals(wallClockMs = t0)
        val clock = MonotonicClock(signals, store)
        clock.now()

        signals.setDateTo(TrustedTimeBounds.LATEST_CREDIBLE_MS + year)

        assertEquals(TimeReading(t0, TimeTrust.FLOORED), clock.now())
        assertEquals(t0, store.state.highWaterMarkMs)
    }

    /**
     * A device with a broken clock and no history has nothing to be checked against.
     * Reporting the number it has is the honest answer; refusing to answer is not.
     */
    @Test
    fun `a first launch with an incredible clock still answers`() {
        val store = RecordingStore()
        val clock = MonotonicClock(Signals(wallClockMs = 0L), store)

        assertEquals(TimeReading(0L, TimeTrust.DEVICE), clock.now())
        assertEquals(ClockState(), store.state)
    }

    // ---------------------------------------------------------------- the anchor

    @Test
    fun `an anchor is reported as network time`() {
        val clock = MonotonicClock(Signals(wallClockMs = t0 - 3 * day), RecordingStore())

        assertEquals(
            TimeReading(t0, TimeTrust.NETWORK),
            clock.anchor(t0, TimeAnchorSource.LICENCE_SERVER),
        )
    }

    /**
     * Once anchored, the wall clock is not consulted at all — elapsed-realtime carries
     * the reading forward, and nothing on the device can set elapsed-realtime.
     */
    @Test
    fun `an anchored clock ignores the device clock entirely`() {
        val signals = Signals(wallClockMs = t0)
        val clock = MonotonicClock(signals, RecordingStore())
        clock.anchor(t0, TimeAnchorSource.LICENCE_SERVER)

        signals.elapsedRealtimeMs += 90 * 60 * 1000
        signals.setDateTo(t0 + 5 * year)

        assertEquals(TimeReading(t0 + 90 * 60 * 1000, TimeTrust.NETWORK), clock.now())

        signals.setDateTo(t0 - 5 * year)
        assertEquals(TimeReading(t0 + 90 * 60 * 1000, TimeTrust.NETWORK), clock.now())
    }

    /**
     * The repair. A device whose clock ran two years fast has already recorded that in
     * its mark, and without this it would stay two years in the future forever — with
     * a trial that ended before it started. A trusted source is allowed to correct it,
     * which is exactly why nothing but our own host may anchor.
     */
    @Test
    fun `a trusted anchor repairs a mark poisoned by a fast clock`() {
        val store = RecordingStore()
        val signals = Signals(wallClockMs = t0 + 2 * year)
        val clock = MonotonicClock(signals, store)
        clock.now()
        assertEquals(t0 + 2 * year, store.state.highWaterMarkMs)

        signals.setDateTo(t0)
        clock.anchor(t0, TimeAnchorSource.LICENCE_SERVER)

        assertEquals(t0, store.state.highWaterMarkMs)
        assertEquals(TimeReading(t0, TimeTrust.NETWORK), clock.now())
    }

    @Test
    fun `an anchor does not survive a reboot`() {
        val signals = Signals(wallClockMs = t0)
        val clock = MonotonicClock(signals, RecordingStore())
        clock.anchor(t0, TimeAnchorSource.LICENCE_SERVER)

        signals.reboot(offMs = 2 * day, newBootId = "boot-b")

        // Back to the device clock, floored — the projection basis is gone.
        assertEquals(TimeReading(t0 + 2 * day, TimeTrust.DEVICE), clock.now())
    }

    /**
     * The reason [ClockSignals.bootId] exists. Without it, a short first session
     * followed by a longer second one would look like elapsed-realtime moving forward
     * within one boot, and the projection would silently add the hours the device
     * spent switched off twice over.
     */
    @Test
    fun `a reboot that runs longer than the anchored session is still a reboot`() {
        val signals = Signals(wallClockMs = t0, elapsedRealtimeMs = 5_000L)
        val clock = MonotonicClock(signals, RecordingStore())
        clock.anchor(t0, TimeAnchorSource.LICENCE_SERVER)

        // Off for a week, then up for an hour: elapsed-realtime is larger than it was
        // at the anchor, but it is counting a different boot.
        signals.wallClockMs = t0 + 7 * day
        signals.elapsedRealtimeMs = 60L * 60 * 1000
        signals.bootId = "boot-b"

        assertEquals(TimeReading(t0 + 7 * day, TimeTrust.DEVICE), clock.now())
    }

    @Test
    fun `elapsed realtime running backwards discards the anchor`() {
        val signals = Signals(wallClockMs = t0, elapsedRealtimeMs = 10 * 60 * 1000)
        val clock = MonotonicClock(signals, RecordingStore())
        clock.anchor(t0, TimeAnchorSource.LICENCE_SERVER)

        // Cannot happen on a healthy device; if it does, the counter is not the one we
        // measured against and the anchor means nothing.
        signals.elapsedRealtimeMs = 60L * 1000
        signals.setDateTo(t0 - day)

        assertEquals(TimeReading(t0, TimeTrust.FLOORED), clock.now())
    }

    // -------------------------------------------------------- anchor precedence

    @Test
    fun `a date header does not displace a signed timestamp in the same session`() {
        val store = RecordingStore()
        val clock = MonotonicClock(Signals(wallClockMs = t0), store)
        clock.anchor(t0, TimeAnchorSource.LICENCE_SERVER)

        clock.anchor(t0 + 5 * day, TimeAnchorSource.LICENCE_HOST_HEADER)

        assertEquals(TimeAnchorSource.LICENCE_SERVER, store.state.anchor?.source)
        assertEquals(t0, store.state.anchor?.epochMs)
    }

    @Test
    fun `a signed timestamp displaces a date header`() {
        val store = RecordingStore()
        val clock = MonotonicClock(Signals(wallClockMs = t0), store)
        clock.anchor(t0 + 5 * day, TimeAnchorSource.LICENCE_HOST_HEADER)

        clock.anchor(t0, TimeAnchorSource.LICENCE_SERVER)

        assertEquals(TimeAnchorSource.LICENCE_SERVER, store.state.anchor?.source)
        assertEquals(t0, store.state.anchor?.epochMs)
    }

    /** After a reboot the stronger anchor cannot be projected, so it holds nothing back. */
    @Test
    fun `a date header is accepted after a reboot`() {
        val store = RecordingStore()
        val signals = Signals(wallClockMs = t0)
        val clock = MonotonicClock(signals, store)
        clock.anchor(t0, TimeAnchorSource.LICENCE_SERVER)

        signals.reboot(offMs = day, newBootId = "boot-b")
        clock.anchor(t0 + day, TimeAnchorSource.LICENCE_HOST_HEADER)

        assertEquals(TimeAnchorSource.LICENCE_HOST_HEADER, store.state.anchor?.source)
        assertEquals("boot-b", store.state.anchor?.bootId)
    }

    @Test
    fun `an incredible epoch is not accepted as an anchor`() {
        val store = RecordingStore()
        val signals = Signals(wallClockMs = t0)
        val clock = MonotonicClock(signals, store)
        clock.now()

        val reading = clock.anchor(0L, TimeAnchorSource.LICENCE_HOST_HEADER)

        assertNull(store.state.anchor)
        assertEquals(TimeReading(t0, TimeTrust.DEVICE), reading)
    }

    @Test
    fun `an anchor beyond the credible window is not accepted`() {
        val store = RecordingStore()
        val clock = MonotonicClock(Signals(wallClockMs = t0), store)

        clock.anchor(TrustedTimeBounds.LATEST_CREDIBLE_MS + 1, TimeAnchorSource.LICENCE_SERVER)

        assertNull(store.state.anchor)
    }

    @Test
    fun `an anchor survives the process being killed within the same boot`() {
        val store = RecordingStore()
        val signals = Signals(wallClockMs = t0)
        MonotonicClock(signals, store).anchor(t0, TimeAnchorSource.LICENCE_SERVER)

        signals.elapsedRealtimeMs += 30 * 60 * 1000
        signals.setDateTo(t0 + year)
        val restarted = MonotonicClock(signals, store)

        assertNotNull(store.state.anchor)
        assertEquals(TimeReading(t0 + 30 * 60 * 1000, TimeTrust.NETWORK), restarted.now())
    }

    // ------------------------------------------------------- against the policy

    /**
     * The clock and the entitlement policy, wired together as the app wires them. The
     * policy already floors against its own stored mark; the clock is what stops the
     * number ever reaching it wound back in the first place.
     */
    @Test
    fun `a rolled back clock cannot revive an expired trial`() {
        val store = RecordingStore()
        val signals = Signals(wallClockMs = t0)
        val clock = MonotonicClock(signals, store)

        val record = EntitlementRecord(
            macAddress = "2F:19:EB:20:44:7C",
            identityVersion = 1,
            plan = Plan.TRIAL,
            trialStartedAtMs = t0,
            trialExpiresAtMs = t0 + 7 * day,
            establishedAtMs = t0,
            lastVerifiedAtMs = t0,
            maxObservedTimeMs = t0,
        )

        signals.pass(8 * day)
        val expiredAt = clock.nowMs()
        assertEquals(
            EntitlementState.TrialExpired,
            EntitlementPolicy.evaluate(record, expiredAt, PricingDefaults.config),
        )

        // Two days back on the calendar: still expired, because the clock will not go
        // back and the policy would not have listened if it had.
        signals.setDateTo(t0 + 6 * day)
        val state = EntitlementPolicy.evaluate(record, clock.nowMs(), PricingDefaults.config)

        assertEquals(EntitlementState.TrialExpired, state)
    }

    /**
     * The other direction, which is the one a support ticket arrives about: a device
     * whose clock was wrong, corrected by our own server, gets its trial back.
     */
    @Test
    fun `a trusted anchor restores a trial a fast clock had ended`() {
        val store = RecordingStore()
        val signals = Signals(wallClockMs = t0 + 2 * year)
        val clock = MonotonicClock(signals, store)

        val record = EntitlementRecord(
            macAddress = "2F:19:EB:20:44:7C",
            identityVersion = 1,
            plan = Plan.TRIAL,
            trialStartedAtMs = t0,
            trialExpiresAtMs = t0 + 7 * day,
            establishedAtMs = t0,
            lastVerifiedAtMs = t0,
            maxObservedTimeMs = t0,
        )

        assertEquals(
            EntitlementState.TrialExpired,
            EntitlementPolicy.evaluate(record, clock.nowMs(), PricingDefaults.config),
        )

        signals.setDateTo(t0 + day)
        clock.anchor(t0 + day, TimeAnchorSource.LICENCE_SERVER)

        val repaired = EntitlementPolicy.evaluate(record, clock.nowMs(), PricingDefaults.config)

        assertTrue("$repaired", repaired.allowsUse)
        assertEquals(EntitlementState.TrialActive(t0 + 7 * day, 6), repaired)
    }
}
