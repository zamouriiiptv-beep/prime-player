package com.castivio.playback.api

import com.castivio.core.common.PlayOutcome

/**
 * Whether one listen proved the stream works: the engine opened it, and it ran steadily for a few seconds.
 *
 * ## A watcher, not a gate
 *
 * It is told what the player already knows -- every change of the engine's state, and the reading of its counters that is taken every
 * second -- and decides nothing the viewer can see. The sound is announced the moment the engine opens it, as it always was; this says,
 * some seconds later, whether that opening was worth believing, and the answer goes to a record on the device and to nothing on the screen.
 *
 * ## One answer per listen, and it is final
 *
 * The window begins at the engine's own mark that the stream is open and playing, and lasts [windowMs]:
 *
 *  - it ends clean -- no stall, no fault, no gap in what was read -- and the listen is [PlayOutcome.CONFIRMED];
 *  - the engine goes into a stall, a packet comes in broken, or the readings stop coming for longer than [maxGapMs], and it is
 *    [PlayOutcome.UNSTABLE] **for good**: a stream that stopped in the third second and came back in the fourth did not run steadily
 *    for five, and is not confirmed by having come back;
 *  - the engine fails it, or ends it, and it is [PlayOutcome.FAILED];
 *  - the viewer holds it, or leaves it, or changes it, and it is [PlayOutcome.INCONCLUSIVE]: the stream was not given the chance to say.
 *
 * Bytes are a witness and not a condition: a station whose server sends in bursts, or an engine that cannot count them, still confirms on
 * the clock and the absence of stalls. What the counter said is kept in [witness] beside the outcome.
 *
 * Pure, over the monotonic millisecond clock the player already keeps, so it is tested with times and without a decoder.
 */
class PlayEvidence(
    private val windowMs: Long = WINDOW_MS,
    private val maxGapMs: Long = MAX_GAP_MS,
) {
    /** What the engine's byte counter said over the window. */
    enum class Witness { GREW, DID_NOT_GROW, UNKNOWN }

    /** The answer, or null while the listen is still being watched. */
    var outcome: PlayOutcome? = null
        private set

    var witness: Witness = Witness.UNKNOWN
        private set

    private var startedAtMs = NOT_YET
    private var lastTickMs = NOT_YET
    private var firstBytes: Long? = null
    private var lastBytes: Long? = null
    private var growths = 0
    private var firstFaults: Long? = null

    /** A new listen: nothing carried from the last. */
    fun reset() {
        outcome = null
        witness = Witness.UNKNOWN
        startedAtMs = NOT_YET
        lastTickMs = NOT_YET
        firstBytes = null
        lastBytes = null
        growths = 0
        firstFaults = null
    }

    /** Whether the window has begun: the engine has opened the stream and not yet stopped. */
    val started: Boolean get() = startedAtMs != NOT_YET

    /**
     * Every change of the engine's state, as it happens.
     *
     * @param frameShown the engine's own mark that the source is open: the first frame, or for a sound the moment it is ready.
     */
    fun state(nowMs: Long, playback: PlaybackState, frameShown: Boolean) {
        if (outcome != null) return
        when (playback) {
            is PlaybackState.Failed, PlaybackState.Ended -> outcome = PlayOutcome.FAILED
            is PlaybackState.Playing -> if (frameShown && !started) {
                startedAtMs = nowMs
                lastTickMs = nowMs
            }
            // A stall after the stream opened, inside the window: it did not run steadily.
            is PlaybackState.Buffering -> if (started) outcome = PlayOutcome.UNSTABLE
            // The viewer's hold, or the stream let go of: not the stream's word.
            is PlaybackState.Paused, PlaybackState.Idle -> if (started) outcome = PlayOutcome.INCONCLUSIVE
            PlaybackState.Opening -> Unit
        }
    }

    /** The reading taken every second while the stream plays; [reading] is null where the engine has none. */
    fun tick(nowMs: Long, reading: QualityReading?) {
        if (outcome != null || !started) return

        if (nowMs - lastTickMs > maxGapMs) {
            outcome = PlayOutcome.UNSTABLE
            return
        }
        lastTickMs = nowMs

        reading?.bytesReceived?.let { bytes ->
            if (firstBytes == null) firstBytes = bytes
            val before = lastBytes
            if (before != null && bytes > before) growths++
            lastBytes = bytes
        }
        reading?.dataFaults?.let { faults ->
            val first = firstFaults
            if (first == null) {
                firstFaults = faults
            } else if (faults > first) {
                outcome = PlayOutcome.UNSTABLE
                return
            }
        }

        if (nowMs - startedAtMs >= windowMs) {
            witness = when {
                firstBytes == null -> Witness.UNKNOWN
                growths >= MIN_GROWTHS -> Witness.GREW
                else -> Witness.DID_NOT_GROW
            }
            outcome = PlayOutcome.CONFIRMED
        }
    }

    /** The viewer held, left or changed the sound: the stream was not given the chance to say. */
    fun inconclusive() {
        if (outcome == null) outcome = PlayOutcome.INCONCLUSIVE
    }

    companion object {
        /** How long the stream has to run steadily, from the engine's mark that it is open. */
        const val WINDOW_MS = 5_000L

        /** The longest the readings may stop coming before the window is not to be trusted as steady. */
        const val MAX_GAP_MS = 2_000L

        /** Readings in which the byte counter moved, for it to be called growing. */
        const val MIN_GROWTHS = 2

        private const val NOT_YET = -1L
    }
}
