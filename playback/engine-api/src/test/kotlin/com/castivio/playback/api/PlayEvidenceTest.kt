package com.castivio.playback.api

import com.castivio.core.common.PlayOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whether a listen proved the stream works: the engine's mark, then a steady window, one answer for good. */
class PlayEvidenceTest {

    private val playing = PlaybackState.Playing(positionMs = 0, durationMs = null, bitrateBps = 0)
    private val buffering = PlaybackState.Buffering(bufferedMs = 0, bitrateBps = 0)

    /** A listen that opened at [at] and is read every second after, with the byte counter at [bytes] (null: the engine cannot count). */
    private fun opened(evidence: PlayEvidence, at: Long = 0L) {
        evidence.state(at, PlaybackState.Opening, frameShown = false)
        evidence.state(at, playing, frameShown = true)
    }

    private fun reading(bytes: Long?, faults: Long? = null) = QualityReading(bytesReceived = bytes, dataFaults = faults)

    @Test
    fun `nothing is decided before the engine says the stream is open`() {
        val evidence = PlayEvidence()

        evidence.state(0, PlaybackState.Opening, frameShown = false)
        evidence.tick(1_000, reading(100))
        evidence.tick(9_000, reading(900))

        assertNull(evidence.outcome)
        assertFalse(evidence.started)
    }

    @Test
    fun `playing without the engine's mark does not start the window`() {
        val evidence = PlayEvidence()

        evidence.state(0, playing, frameShown = false)

        assertFalse(evidence.started)
    }

    @Test
    fun `five clean seconds from the mark confirm it, and not before`() {
        val evidence = PlayEvidence()
        opened(evidence)

        for (s in 1..4) evidence.tick(s * 1_000L, reading(s * 1_000L))
        assertNull("at four seconds it is still being watched", evidence.outcome)

        evidence.tick(5_000, reading(5_000))
        assertEquals(PlayOutcome.CONFIRMED, evidence.outcome)
        assertEquals(PlayEvidence.Witness.GREW, evidence.witness)
    }

    @Test
    fun `the window is counted from the mark and not from the start of the run`() {
        val evidence = PlayEvidence()
        evidence.state(0, PlaybackState.Opening, frameShown = false)
        evidence.tick(1_000, null)
        evidence.state(3_000, playing, frameShown = true)

        for (s in 4..7) evidence.tick(s * 1_000L, null)
        assertNull(evidence.outcome)
        evidence.tick(8_000, null)
        assertEquals(PlayOutcome.CONFIRMED, evidence.outcome)
    }

    @Test
    fun `a stall inside the window is unstable, and coming back does not confirm it`() {
        val evidence = PlayEvidence()
        opened(evidence)
        evidence.tick(1_000, reading(1_000))
        evidence.tick(2_000, reading(2_000))

        evidence.state(2_500, buffering, frameShown = true)
        assertEquals(PlayOutcome.UNSTABLE, evidence.outcome)

        evidence.state(3_500, playing, frameShown = true)
        for (s in 4..12) evidence.tick(s * 1_000L, reading(s * 1_000L))
        assertEquals("it came back and is still not confirmed", PlayOutcome.UNSTABLE, evidence.outcome)
    }

    @Test
    fun `a broken packet inside the window is unstable`() {
        val evidence = PlayEvidence()
        opened(evidence)
        evidence.tick(1_000, reading(1_000, faults = 0))

        evidence.tick(2_000, reading(2_000, faults = 1))

        assertEquals(PlayOutcome.UNSTABLE, evidence.outcome)
    }

    @Test
    fun `readings that stop for longer than two seconds make the window unreliable, and unstable`() {
        val evidence = PlayEvidence()
        opened(evidence)
        evidence.tick(1_000, reading(1_000))

        evidence.tick(3_500, reading(3_500))

        assertEquals(PlayOutcome.UNSTABLE, evidence.outcome)
    }

    @Test
    fun `an engine that fails or ends the stream has failed it`() {
        val failed = PlayEvidence()
        opened(failed)
        failed.state(2_000, PlaybackState.Failed(PlaybackError.NETWORK), frameShown = true)
        assertEquals(PlayOutcome.FAILED, failed.outcome)

        val ended = PlayEvidence()
        opened(ended)
        ended.state(2_000, PlaybackState.Ended, frameShown = true)
        assertEquals(PlayOutcome.FAILED, ended.outcome)

        val neverOpened = PlayEvidence()
        neverOpened.state(0, PlaybackState.Opening, frameShown = false)
        neverOpened.state(10_000, PlaybackState.Failed(PlaybackError.TIMEOUT), frameShown = false)
        assertEquals(PlayOutcome.FAILED, neverOpened.outcome)
    }

    @Test
    fun `the viewer's hold, and the sound being let go of, say nothing about the stream`() {
        val held = PlayEvidence()
        opened(held)
        held.state(2_000, PlaybackState.Paused(0), frameShown = true)
        assertEquals(PlayOutcome.INCONCLUSIVE, held.outcome)

        val released = PlayEvidence()
        opened(released)
        released.state(2_000, PlaybackState.Idle, frameShown = true)
        assertEquals(PlayOutcome.INCONCLUSIVE, released.outcome)

        val left = PlayEvidence()
        opened(left)
        left.inconclusive()
        assertEquals(PlayOutcome.INCONCLUSIVE, left.outcome)
    }

    @Test
    fun `an idle player before anything opened decides nothing`() {
        val evidence = PlayEvidence()

        evidence.state(0, PlaybackState.Idle, frameShown = false)

        assertNull(evidence.outcome)
    }

    @Test
    fun `the first answer is final`() {
        val evidence = PlayEvidence()
        opened(evidence)
        evidence.state(1_000, PlaybackState.Failed(PlaybackError.SOURCE), frameShown = true)

        evidence.tick(5_000, reading(5_000))
        evidence.state(5_000, playing, frameShown = true)
        evidence.inconclusive()

        assertEquals(PlayOutcome.FAILED, evidence.outcome)
    }

    @Test
    fun `bytes are a witness and not a condition`() {
        val silent = PlayEvidence()
        opened(silent)
        for (s in 1..5) silent.tick(s * 1_000L, reading(null))
        assertEquals(PlayOutcome.CONFIRMED, silent.outcome)
        assertEquals(PlayEvidence.Witness.UNKNOWN, silent.witness)

        val flat = PlayEvidence()
        opened(flat)
        for (s in 1..5) flat.tick(s * 1_000L, reading(4_096))
        assertEquals("a burst-fed station still confirms on the clock", PlayOutcome.CONFIRMED, flat.outcome)
        assertEquals(PlayEvidence.Witness.DID_NOT_GROW, flat.witness)
    }

    @Test
    fun `a new listen starts with nothing carried over`() {
        val evidence = PlayEvidence()
        opened(evidence)
        evidence.state(1_000, buffering, frameShown = true)
        assertTrue(evidence.outcome == PlayOutcome.UNSTABLE)

        evidence.reset()

        assertNull(evidence.outcome)
        assertFalse(evidence.started)
        opened(evidence, at = 20_000)
        for (s in 21..25) evidence.tick(s * 1_000L, reading(s * 1_000L))
        assertEquals(PlayOutcome.CONFIRMED, evidence.outcome)
    }
}
