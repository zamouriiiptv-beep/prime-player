package com.castivio.domain.time

/**
 * The three readings a platform can give us about time.
 *
 * @param wallClockMs the device clock, in epoch milliseconds. Settable by the user,
 *   by the network operator, and by a flat battery.
 * @param elapsedRealtimeMs milliseconds since boot, including sleep. Nothing on the
 *   device can set it and it never runs backwards — but it resets to zero on every
 *   boot, so it measures durations rather than instants.
 * @param bootId an identifier for the current boot. It is what makes
 *   [elapsedRealtimeMs] comparable to a value stored earlier: without it, an
 *   elapsed reading of two hours could mean "two hours after the anchor" or "two
 *   hours into a boot that happened after the anchor", and those differ by however
 *   long the device was switched off.
 */
data class ClockSignals(
    val wallClockMs: Long,
    val elapsedRealtimeMs: Long,
    val bootId: String,
)

/**
 * An epoch we were told by a server, paired with the elapsed-realtime reading at the
 * moment we were told, so the pair can be projected forward without the wall clock.
 */
data class TimeAnchor(
    val epochMs: Long,
    val elapsedRealtimeMs: Long,
    val bootId: String,
    val source: TimeAnchorSource,
)

/**
 * Everything the clock remembers between launches. Small on purpose — it is written
 * on a hot path and read before anything else the app does.
 *
 * @param highWaterMarkMs the furthest instant this device has ever observed. It only
 *   moves down when a trusted anchor says so.
 */
data class ClockState(
    val highWaterMarkMs: Long = 0L,
    val anchor: TimeAnchor? = null,
)

/** A reading and the state it produced. Every clock operation returns both. */
data class ClockTick(
    val reading: TimeReading,
    val state: ClockState,
)

/**
 * Where [ClockState] survives a restart.
 *
 * Synchronous by necessity: a clock that has to be awaited is not a clock, and the
 * entitlement gate reads one before the first frame. Implementations must be safe to
 * call from any thread and must make [save] durable enough that killing the process
 * immediately afterwards does not lose the high-water mark — that mark is the only
 * thing standing between a user and an infinite trial.
 */
interface ClockStore {
    fun load(): ClockState
    fun save(state: ClockState)
}

/**
 * A clock that does not go backwards, assembled from the three signals that do.
 *
 * Holds no state of its own: every operation loads, computes purely, and writes back
 * only when something changed. That leaves [ClockStore] as the single owner of the
 * memory, which is also what makes concurrent readers harmless — two readers racing
 * both compute a value at or above the mark, and whichever write lands last is still
 * at or above it.
 *
 * The reconciliation itself is [ClockState.read] and [ClockState.anchoring], which are
 * pure functions of their arguments and are where the behaviour is tested.
 */
class MonotonicClock(
    private val signals: ClockSignalSource,
    private val store: ClockStore,
) : TrustedTime {

    override fun now(): TimeReading {
        val state = store.load()
        return commit(state, state.read(signals.read()))
    }

    override fun anchor(epochMs: Long, source: TimeAnchorSource): TimeReading {
        val state = store.load()
        return commit(state, state.anchoring(signals.read(), epochMs, source))
    }

    private fun commit(before: ClockState, tick: ClockTick): TimeReading {
        if (tick.state != before) store.save(tick.state)
        return tick.reading
    }
}

/** Supplies [ClockSignals]. Implemented per platform; there is nothing to test in it. */
fun interface ClockSignalSource {
    fun read(): ClockSignals
}

/**
 * The current instant, and the state that should be remembered afterwards.
 *
 * Reading the time is a write: it is how the high-water mark learns that this device
 * has lived through a given instant, which is what makes the next rollback detectable.
 */
fun ClockState.read(signals: ClockSignals): ClockTick {
    project(signals)?.let { projected ->
        // An anchor from this boot outranks the wall clock in both directions. Letting
        // it correct downwards is deliberate: a device whose clock ran two years fast
        // has already poisoned its own mark, and this is the only path that cleans it.
        return ClockTick(TimeReading(projected, TimeTrust.NETWORK), copy(highWaterMarkMs = projected))
    }

    val wall = signals.wallClockMs
    val credible = TrustedTimeBounds.isCredible(wall)

    // A reading we do not believe is not evidence of anything, so it neither raises the
    // mark nor displaces it.
    if (!credible || wall < highWaterMarkMs) {
        return if (highWaterMarkMs > 0L) {
            ClockTick(TimeReading(highWaterMarkMs, TimeTrust.FLOORED), this)
        } else {
            // Nothing has ever been observed, so there is nothing to check the clock
            // against. Reporting a number we half believe beats reporting zero.
            ClockTick(TimeReading(wall, TimeTrust.DEVICE), this)
        }
    }

    return ClockTick(TimeReading(wall, TimeTrust.DEVICE), copy(highWaterMarkMs = wall))
}

/**
 * Records an authoritative epoch and returns the corrected reading.
 *
 * The anchor replaces the high-water mark rather than being folded into it, because
 * the point of a trusted source is that it is right and the mark might not be.
 */
fun ClockState.anchoring(
    signals: ClockSignals,
    epochMs: Long,
    source: TimeAnchorSource,
): ClockTick {
    // Garbage from a broken proxy is not an anchor. Fall through to an ordinary read
    // rather than failing: the caller asked what time it is, not for a verdict on the
    // header it happened to see.
    if (!TrustedTimeBounds.isCredible(epochMs)) return read(signals)

    // A `Date` header does not displace a signed timestamp taken in the same session;
    // it does displace one from a previous boot, whose projection is unusable anyway.
    val held = anchor
    if (held != null && held.bootId == signals.bootId && held.source.outranks(source)) {
        return read(signals)
    }

    val taken = TimeAnchor(
        epochMs = epochMs,
        elapsedRealtimeMs = signals.elapsedRealtimeMs,
        bootId = signals.bootId,
        source = source,
    )
    return ClockTick(
        reading = TimeReading(epochMs, TimeTrust.NETWORK),
        state = ClockState(highWaterMarkMs = epochMs, anchor = taken),
    )
}

/**
 * The anchor projected to now, or null when it cannot be projected.
 *
 * Null in two cases, both meaning "the elapsed-realtime clock this anchor was measured
 * against no longer exists": a different boot, or a reading earlier than the anchor's
 * own, which a monotonic counter cannot produce within one boot.
 */
private fun ClockState.project(signals: ClockSignals): Long? {
    val held = anchor ?: return null
    if (held.bootId != signals.bootId) return null
    val sinceAnchor = signals.elapsedRealtimeMs - held.elapsedRealtimeMs
    if (sinceAnchor < 0L) return null
    return held.epochMs + sinceAnchor
}
