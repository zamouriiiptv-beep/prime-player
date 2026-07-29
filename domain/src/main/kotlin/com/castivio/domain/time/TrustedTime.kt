package com.castivio.domain.time

/**
 * What time it is, and how much that answer can be trusted.
 *
 * The device clock is user-settable, which makes it unfit to be the only source for a
 * decision that costs money — a trial that ends in seven days ends whenever the owner
 * of the clock says so. Everything in Castivio that measures a licence asks this
 * instead, and gets both a number and an honest label for where the number came from.
 *
 * Three facts, in decreasing order of authority:
 *
 *  1. **An anchor taken from our licence host, projected with elapsed-realtime.** Once
 *     we have been told the time by a server, the amount of time that has passed since
 *     is measurable without the wall clock at all, and elapsed-realtime cannot be set
 *     by anyone. Within one boot this is as good as a signed timestamp.
 *  2. **The device clock, floored at everything we have already seen.** Moving it
 *     forward is a self-inflicted wound; moving it backwards buys nothing, because the
 *     floor does not move down.
 *  3. **The floor alone**, when the clock reads earlier than an instant this device has
 *     demonstrably already lived through.
 *
 * The implementation is [MonotonicClock], which is pure and holds no clock of its own.
 */
interface TrustedTime {

    /** The current instant, with the reason it should be believed. */
    fun now(): TimeReading

    /**
     * Records an authoritative epoch, and returns the corrected reading.
     *
     * A trusted anchor is the only thing allowed to move the clock *backwards*, which
     * is what repairs a device whose fast clock has already been absorbed into the
     * high-water mark. Nothing else can, so nothing else can hand back a spent trial.
     */
    fun anchor(epochMs: Long, source: TimeAnchorSource): TimeReading

    /** [now] for the many callers that only want the number. */
    fun nowMs(): Long = now().epochMs
}

/** An instant, and why it should be believed. */
data class TimeReading(
    val epochMs: Long,
    val trust: TimeTrust,
)

enum class TimeTrust {
    /**
     * Projected from an anchor taken during this boot. The device clock was not
     * consulted and cannot affect this reading.
     */
    NETWORK,

    /** The device clock, at or ahead of every instant this device has seen. */
    DEVICE,

    /**
     * The device clock reads earlier than an instant already observed, so the
     * high-water mark is being reported instead. Not proof of tampering — a dead
     * coin cell does this — but it is proof the clock cannot be relied on.
     */
    FLOORED,
}

/**
 * Where an authoritative epoch came from.
 *
 * Both entries name *our* host, and that is the whole rule: **an anchor is never
 * harvested from a provider's server.** A provider URL is typed in by the user, so
 * a hostile or merely misconfigured portal could otherwise hand us any `Date` it
 * liked — and since an anchor may move the clock backwards, that would be a way to
 * revive an expired licence. Trust flows only from the host whose certificate we
 * pin our own business to.
 */
enum class TimeAnchorSource {

    /** The `Date` header of a TLS response from Castivio's licence host. */
    LICENCE_HOST_HEADER,

    /** A timestamp inside a signed licence-server response. */
    LICENCE_SERVER,
    ;

    /** True when this source should not be displaced by [other] within one session. */
    internal fun outranks(other: TimeAnchorSource): Boolean = ordinal > other.ordinal
}

/**
 * The window a clock reading has to fall inside to be worth recording.
 *
 * Not a security boundary — a wrong-by-a-week clock passes this easily. It exists to
 * keep the obviously broken readings out of a high-water mark that never comes back
 * down: a device that boots at the epoch with a dead battery, or a `Date` header from
 * a proxy with a corrupt clock, must not be able to strand a licence in the year 2107.
 */
object TrustedTimeBounds {

    /** 2025-01-01T00:00:00Z — before Castivio existed, so before any real reading. */
    const val EARLIEST_CREDIBLE_MS: Long = 1_735_689_600_000L

    /** 2100-01-01T00:00:00Z. */
    const val LATEST_CREDIBLE_MS: Long = 4_102_444_800_000L

    fun isCredible(epochMs: Long): Boolean =
        epochMs in EARLIEST_CREDIBLE_MS..LATEST_CREDIBLE_MS
}
