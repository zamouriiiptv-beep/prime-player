package com.castivio.playback.api

import com.castivio.core.common.StreamQuality

/**
 * Turns what an engine says about a stream into the one word the indicator shows, whichever engine it is.
 *
 * ## One monitor, two engines
 *
 * The monitor reads only the contract: the engine's playback state, which both engines keep to the same meaning, and a
 * [QualityReading], which each fills with what it has and leaves null for what it cannot say. It does not know which engine
 * is behind it, and the rule for what a stall, a silence or a thin buffer means is written here once and not in each.
 *
 * ## What drives it
 *
 * Two kinds of call, from one thread (the player's, and nothing here locks). [state] is told every change of the engine's
 * state as it happens, which is what makes a stall's start and end exact rather than as late as the next tick. [tick] is
 * called once a second or so while a stream plays and not otherwise: it takes a reading, sets it against what came
 * before, and returns the rating to show -- or null while there is none to show. Nothing here starts a timer, makes a
 * request or asks an engine for anything; it only reads what it is handed.
 *
 * ## Not flickering
 *
 * A rating that followed every second's figures would change colour with every hiccup. So the figure the policy gives is
 * a verdict and not yet the rating shown: what a viewer can see -- a stall, a stopped stream, a failure -- is shown at
 * once, and anything softer has to hold for [DOWN_CONFIRM_MS] before the indicator gets worse, and a better verdict has to
 * hold for [UP_HOLD_MS] before it gets better, by one step at a time. A stream that is fine does not jitter, and one that
 * is going wrong does not take a minute to say so.
 *
 * ## Starting again
 *
 * [reset] is for a new source and a new engine: a channel changed, a film opened, the backup taking over. Nothing of
 * the old stream's record is carried to the new one -- its stalls, its best buffer, its counters -- because the viewer
 * would be told how the last channel was doing about this one.
 *
 * ## Written down, in a debug build
 *
 * Given a [traceSink], the monitor hands it one [QualityTraceLine] for every [tick] that reads a stream, so that the
 * thresholds can be tuned on a real provider's playback. Given none -- which is what a release build does -- it builds no
 * line, and the rating is made exactly as it is with one: the line is a copy of the second's working and never an input to it.
 */
class StreamQualityMonitor(private val traceSink: ((QualityTraceLine) -> Unit)? = null) {

    private enum class Phase { NONE, OPENING, PLAYING, STALLED, PAUSED, FAILED }

    private class Mark(val atMs: Long, val value: Long)

    private class Sample(val atMs: Long, val value: Double)

    private var phase = Phase.NONE

    /** Whether the stream has played at all since [reset]: an opening after that is a drop, and counts as one. */
    private var everPlayed = false
    private var playingSinceMs = NOT_YET

    /**
     * When the stall in progress began, or [NOT_YET]. A stall that began inside a seek's grace begins, for the record, where
     * the grace ends: until then it is in the future, and has not yet cost the stream anything.
     */
    private var stallStartMs = NOT_YET
    private var stallCounted = false
    private val stalls = ArrayDeque<Long>()

    /** Until when a seek's refilling is excused, or the least a clock can read while there is none. */
    private var excusedUntilMs = Long.MIN_VALUE

    private var lastBytes = NOT_YET
    private var lastGrowthMs = NOT_YET
    private val byteMarks = ArrayDeque<Mark>()
    private val rates = ArrayDeque<Sample>()
    private val buffers = ArrayDeque<Sample>()

    private var lastFaults = NOT_YET
    private val faults = ArrayDeque<Mark>()

    /** What the latest reading added to the trace: the last second's rate, the last few seconds', and the faults since the one before. */
    private var tickRateBps: Double? = null
    private var tickRecentRateBps: Double? = null
    private var tickNewFaults = 0L

    private var shown: StreamQuality? = null
    private var worseSinceMs = NOT_YET
    private var betterSinceMs = NOT_YET

    /** The rating as it stands, or null while there is none to show. */
    val rating: StreamQuality? get() = shown

    /**
     * Whether a [tick] would use a reading: a stream is playing, stalled, or dropped after it played. Not while it is
     * opening for the first time, paused, failed or stopped, so the player asks the engine for nothing it will not use.
     */
    val wantsReading: Boolean
        get() = when (phase) {
            Phase.PLAYING, Phase.STALLED -> true
            Phase.OPENING -> everPlayed
            Phase.NONE, Phase.PAUSED, Phase.FAILED -> false
        }

    /** A new source or a new engine: nothing of the last is carried. */
    fun reset() {
        phase = Phase.NONE
        everPlayed = false
        playingSinceMs = NOT_YET
        stallStartMs = NOT_YET
        stallCounted = false
        excusedUntilMs = Long.MIN_VALUE
        stalls.clear()
        lastBytes = NOT_YET
        lastGrowthMs = NOT_YET
        byteMarks.clear()
        rates.clear()
        buffers.clear()
        lastFaults = NOT_YET
        faults.clear()
        shown = null
        worseSinceMs = NOT_YET
        betterSinceMs = NOT_YET
    }

    /**
     * The engine's state changed to [playback], with or without a picture on the screen ([frameShown]).
     *
     * A stall begins when a stream that was playing starts buffering, or is opened again after it played: a radio that
     * reconnects has dropped. It ends when the stream plays again. A pause is not a stall and takes none of the stream's
     * time with it: nothing is read while it lasts, and what follows is read as if the silence had not happened.
     */
    fun state(nowMs: Long, playback: PlaybackState, frameShown: Boolean) {
        val next = phaseOf(playback, frameShown)
        val before = phase
        if (next == before) return
        phase = next
        when (next) {
            Phase.PLAYING -> {
                everPlayed = true
                if (playingSinceMs == NOT_YET) playingSinceMs = nowMs
                endStall()
                if (before == Phase.PAUSED) {
                    // The silence of a pause is not the stream's: its bytes and its rates begin again from here.
                    lastGrowthMs = nowMs
                    byteMarks.clear()
                }
            }
            Phase.STALLED -> beginStall(nowMs)
            Phase.OPENING -> if (everPlayed) beginStall(nowMs)
            Phase.PAUSED -> endStall()
            Phase.FAILED, Phase.NONE -> Unit
        }
    }

    /**
     * One reading of the stream at [nowMs] (a monotonic clock, in milliseconds): the rating to show, or null.
     *
     * Null before the stream has played, after it has stopped for a reason that is not the connection's, and for the
     * first [WARMUP_MS] of playing unless something the viewer can see has already gone wrong. A paused stream keeps the
     * rating it had.
     */
    fun tick(nowMs: Long, reading: QualityReading?): StreamQuality? {
        when (phase) {
            Phase.NONE -> return null
            Phase.PAUSED -> return shown
            Phase.OPENING -> if (!everPlayed) return null
            Phase.PLAYING, Phase.STALLED, Phase.FAILED -> Unit
        }
        tickRateBps = null
        tickRecentRateBps = null
        tickNewFaults = 0L
        if (reading != null) record(nowMs, reading)
        // A stall excused when it began is still going when the excuse runs out: from here it is a stall like any other.
        if (stallStartMs != NOT_YET && !stallCounted && nowMs >= stallStartMs) countStall()
        val evidence = evidence(nowMs)
        val verdict = StreamQualityPolicy.verdict(evidence)
        val rating = settle(nowMs, verdict)
        traceSink?.invoke(traceLine(nowMs, reading, evidence, verdict, rating))
        return rating
    }

    private fun traceLine(
        nowMs: Long,
        reading: QualityReading?,
        evidence: QualityEvidence,
        verdict: QualityVerdict,
        rating: StreamQuality?,
    ) = QualityTraceLine(
        sinceStartMs = if (playingSinceMs == NOT_YET) null else (nowMs - playingSinceMs).coerceAtLeast(0L),
        phase = phase.name,
        rateBps = tickRateBps,
        recentRateBps = tickRecentRateBps,
        usualRateBps = usualRate(),
        silentMs = evidence.silentMs,
        bufferedMs = reading?.bufferedAheadMs,
        bufferRatio = evidence.bufferRatio,
        stalledNowMs = evidence.stalledNowMs,
        stallsInWindow = evidence.stallsInWindow,
        newFaults = tickNewFaults,
        faultsInWindow = evidence.faultsInWindow,
        graceMs = if (excusedUntilMs > nowMs) excusedUntilMs - nowMs else 0L,
        verdict = verdict.quality,
        firm = verdict.firm,
        shown = rating,
    )

    /**
     * The viewer moved within the stream -- a seek, a jump to the live edge, a replay -- and what the stream usually holds
     * and usually delivers is about to change: a buffer refilling from empty is not a connection that got worse. The
     * references begin again; the stalls already counted and the rating stay as they are.
     *
     * **The refilling is excused for [SEEK_GRACE_MS].** A seek asks the source for another part, and until it answers the
     * player buffers and no bytes arrive: the viewer caused that and is not being badly served by it. A stall that begins
     * inside the grace does not count while the grace lasts and does not count at all if it ends inside it, and silence is
     * counted from where the grace ends. A source that is still not answering then is what the indicator is for: from there
     * the stall and the silence are judged as any other, so a jump that never lands is no longer excused.
     */
    fun moved(nowMs: Long) {
        byteMarks.clear()
        rates.clear()
        buffers.clear()
        excusedUntilMs = nowMs + SEEK_GRACE_MS
        lastGrowthMs = excusedUntilMs
        // A stall already waiting out an earlier jump's grace waits out this one's instead.
        if (stallStartMs != NOT_YET && !stallCounted) stallStartMs = excusedUntilMs
    }

    /* ----------------------------------------------------------------- the record */

    private fun beginStall(nowMs: Long) {
        if (stallStartMs != NOT_YET) return
        if (nowMs < excusedUntilMs) {
            stallStartMs = excusedUntilMs
            stallCounted = false
        } else {
            stallStartMs = nowMs
            countStall()
        }
    }

    private fun countStall() {
        stalls.addLast(stallStartMs)
        stallCounted = true
    }

    private fun endStall() {
        stallStartMs = NOT_YET
        stallCounted = false
    }

    private fun record(nowMs: Long, reading: QualityReading) {
        reading.bytesReceived?.let { bytes ->
            if (lastBytes == NOT_YET || bytes < lastBytes) {
                // The first count, or a count that began again: its own baseline, and a rate from it.
                byteMarks.clear()
                lastGrowthMs = nowMs
            } else if (bytes > lastBytes) {
                lastGrowthMs = nowMs
            }
            lastBytes = bytes
            byteMarks.addLast(Mark(nowMs, bytes))
            if (byteMarks.size >= 2) {
                val before = byteMarks[byteMarks.size - 2]
                val seconds = (nowMs - before.atMs) / MS_PER_SECOND
                if (seconds > 0.0) tickRateBps = (bytes - before.value) * BITS_PER_BYTE / seconds
            }
            while (byteMarks.size > 1 && nowMs - byteMarks.first().atMs > RATE_SPAN_MS * 2) byteMarks.removeFirst()
            sampleRate(nowMs, bytes)
        }
        reading.bufferedAheadMs?.let { buffers.addLast(Sample(nowMs, it.toDouble())) }
        reading.dataFaults?.let { count ->
            val before = lastFaults
            lastFaults = count
            if (before != NOT_YET && count > before) {
                faults.addLast(Mark(nowMs, count - before))
                tickNewFaults = count - before
            }
        }
        prune(nowMs)
    }

    /**
     * The rate over the last few seconds, kept as a sample once the first seconds are over.
     *
     * Not before: a provider sends the first seconds of a live stream faster than it plays, and a rate taken from that would
     * be what every later second is measured against.
     */
    private fun sampleRate(nowMs: Long, bytes: Long) {
        val since = playingSinceMs
        if (since == NOT_YET || nowMs - since < WARMUP_MS) return
        val then = byteMarks.lastOrNull { nowMs - it.atMs >= RATE_SPAN_MS } ?: return
        val seconds = (nowMs - then.atMs) / MS_PER_SECOND
        if (seconds <= 0.0) return
        val rate = (bytes - then.value) / seconds
        rates.addLast(Sample(nowMs, rate))
        tickRecentRateBps = rate * BITS_PER_BYTE
    }

    private fun prune(nowMs: Long) {
        while (stalls.isNotEmpty() && nowMs - stalls.first() > StreamQualityPolicy.LONG_WINDOW_MS) stalls.removeFirst()
        while (rates.isNotEmpty() && nowMs - rates.first().atMs > REFERENCE_WINDOW_MS) rates.removeFirst()
        while (buffers.isNotEmpty() && nowMs - buffers.first().atMs > REFERENCE_WINDOW_MS) buffers.removeFirst()
        while (faults.isNotEmpty() && nowMs - faults.first().atMs > StreamQualityPolicy.FAULT_WINDOW_MS) faults.removeFirst()
    }

    /* --------------------------------------------------------------- the evidence */

    private fun evidence(nowMs: Long): QualityEvidence {
        prune(nowMs)
        val stalledNow = if (stallStartMs == NOT_YET) 0L else (nowMs - stallStartMs).coerceAtLeast(0L)
        val bytesCounted = lastBytes != NOT_YET && lastGrowthMs != NOT_YET
        return QualityEvidence(
            failed = phase == Phase.FAILED,
            stalledNowMs = stalledNow,
            stallsInWindow = stalls.count { nowMs - it <= StreamQualityPolicy.STALL_WINDOW_MS },
            stallsInLongWindow = stalls.size,
            silentMs = if (bytesCounted) (nowMs - lastGrowthMs).coerceAtLeast(0L) else null,
            bufferRatio = bufferRatio(),
            rateDrop = rateDropped(),
            faultsInWindow = faults.sumOf { it.value }.toInt(),
        )
    }

    /**
     * The buffer now against what it has usually held in the last minute -- the median, so a provider's burst at the start
     * is not what the rest is measured against -- or null where there is no usual: too few readings, or a stream that has
     * never held a cushion, whose buffer says nothing either way.
     */
    private fun bufferRatio(): Double? {
        if (buffers.size < MIN_BUFFER_SAMPLES) return null
        val sorted = buffers.map { it.value }.sorted()
        val usual = sorted[sorted.size / 2]
        if (usual < MIN_CUSHION_MS) return null
        return (buffers.last().value / usual).coerceIn(0.0, 1.0)
    }

    /** The latest rate against the median of those kept: under half of it, once there are enough to have a median. */
    private fun rateDropped(): Boolean {
        val median = medianRate() ?: return false
        return median > 0.0 && rates.last().value < median * RATE_DROP_RATIO
    }

    /** The median of the rates kept, in bytes per second, or null before there are enough to have one. */
    private fun medianRate(): Double? {
        if (rates.size < MIN_RATE_SAMPLES) return null
        val sorted = rates.map { it.value }.sorted()
        return sorted[sorted.size / 2]
    }

    /** The same median in bits per second, for the trace. */
    private fun usualRate(): Double? = medianRate()?.times(BITS_PER_BYTE)

    /* ----------------------------------------------------------------- the rating */

    private fun settle(nowMs: Long, verdict: QualityVerdict): StreamQuality? {
        val warm = playingSinceMs != NOT_YET && nowMs - playingSinceMs >= WARMUP_MS
        val current = shown
        if (current == null) {
            // Before the first rating: held back until the first seconds are over, unless the viewer is already seeing it.
            if (!warm && !(verdict.firm && verdict.quality != StreamQuality.EXCELLENT)) return null
            shown = verdict.quality
            worseSinceMs = NOT_YET
            betterSinceMs = NOT_YET
            return shown
        }
        val worse = verdict.quality.ordinal > current.ordinal
        val better = verdict.quality.ordinal < current.ordinal
        when {
            worse -> {
                betterSinceMs = NOT_YET
                if (verdict.firm) {
                    shown = verdict.quality
                    worseSinceMs = NOT_YET
                } else {
                    if (worseSinceMs == NOT_YET) worseSinceMs = nowMs
                    if (nowMs - worseSinceMs >= DOWN_CONFIRM_MS) {
                        shown = verdict.quality
                        worseSinceMs = NOT_YET
                    }
                }
            }
            better -> {
                worseSinceMs = NOT_YET
                if (betterSinceMs == NOT_YET) betterSinceMs = nowMs
                if (nowMs - betterSinceMs >= UP_HOLD_MS) {
                    shown = StreamQuality.entries[current.ordinal - 1]
                    betterSinceMs = nowMs
                }
            }
            else -> {
                worseSinceMs = NOT_YET
                betterSinceMs = NOT_YET
            }
        }
        return shown
    }

    companion object {
        /** The first seconds of playing, in which nothing is rated unless it has already gone wrong where the viewer sees. */
        const val WARMUP_MS = 8_000L

        /** A worse verdict that is not a stall has to hold this long before the indicator gets worse. */
        const val DOWN_CONFIRM_MS = 3_000L

        /** A better verdict has to hold this long before the indicator gets better, and then by one step. */
        const val UP_HOLD_MS = 10_000L

        /**
         * How long after a seek its buffering and its silence are excused: as long as the opening of a stream is, since
         * asking a source for another part is a small opening. A first reading, not a measurement of any provider.
         */
        const val SEEK_GRACE_MS = 8_000L

        /** The stretch a rate is taken over. */
        const val RATE_SPAN_MS = 5_000L

        /** How far back the best buffer and the median rate are kept. */
        const val REFERENCE_WINDOW_MS = 60_000L

        /** Rates needed before there is a median to be under. */
        const val MIN_RATE_SAMPLES = 5

        /** The share of the median rate below which the rate has dropped. */
        const val RATE_DROP_RATIO = 0.5

        /** Buffer readings needed before there is a usual level to compare with. */
        const val MIN_BUFFER_SAMPLES = 8

        /** The least a usual buffer has to be to say anything: under it the stream has no cushion and its buffer is no evidence. */
        const val MIN_CUSHION_MS = 2_000.0

        private const val MS_PER_SECOND = 1_000.0
        private const val BITS_PER_BYTE = 8.0
        private const val NOT_YET = -1L

        /** The failures that are the connection's. A stream that failed to decode says nothing of how it was arriving. */
        private val CONNECTION_FAILURES = setOf(
            PlaybackError.NETWORK,
            PlaybackError.NOT_FOUND,
            PlaybackError.SOURCE,
            PlaybackError.TIMEOUT,
        )

        private fun phaseOf(playback: PlaybackState, frameShown: Boolean): Phase = when (playback) {
            PlaybackState.Idle, PlaybackState.Ended -> Phase.NONE
            PlaybackState.Opening -> Phase.OPENING
            is PlaybackState.Buffering -> if (frameShown) Phase.STALLED else Phase.OPENING
            is PlaybackState.Playing -> if (frameShown) Phase.PLAYING else Phase.OPENING
            is PlaybackState.Paused -> Phase.PAUSED
            is PlaybackState.Failed -> if (playback.reason in CONNECTION_FAILURES) Phase.FAILED else Phase.NONE
        }
    }
}
