package com.castivio.playback.api

import com.castivio.core.common.StreamQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a stream is called, from the evidence alone: no engine, no clock.
 *
 * The thresholds are a first reading of what a viewer notices, and these say what each one decides, so that tuning one
 * against real playback shows in a test which thing moved.
 */
class StreamQualityPolicyTest {

    private fun verdict(evidence: QualityEvidence) = StreamQualityPolicy.verdict(evidence)

    @Test
    fun `a stream with nothing wrong is excellent, and that is not a firm finding`() {
        val v = verdict(QualityEvidence())

        assertEquals(StreamQuality.EXCELLENT, v.quality)
        assertFalse(v.firm)
    }

    @Test
    fun `a failure that is the connection's is bad at once`() {
        val v = verdict(QualityEvidence(failed = true))

        assertEquals(StreamQuality.BAD, v.quality)
        assertTrue(v.firm)
    }

    @Test
    fun `a stall in progress is poor from a second and bad from four`() {
        assertEquals(StreamQuality.EXCELLENT, verdict(QualityEvidence(stalledNowMs = 999)).quality)
        assertEquals(StreamQuality.POOR, verdict(QualityEvidence(stalledNowMs = 1_000)).quality)
        assertEquals(StreamQuality.POOR, verdict(QualityEvidence(stalledNowMs = 3_999)).quality)
        assertEquals(StreamQuality.BAD, verdict(QualityEvidence(stalledNowMs = 4_000)).quality)
        assertTrue(verdict(QualityEvidence(stalledNowMs = 1_000)).firm)
    }

    @Test
    fun `stalls in the last minute are what the viewer is being put through`() {
        assertEquals(StreamQuality.GOOD, verdict(QualityEvidence(stallsInWindow = 1, stallsInLongWindow = 1)).quality)
        assertEquals(StreamQuality.POOR, verdict(QualityEvidence(stallsInWindow = 2, stallsInLongWindow = 2)).quality)
        assertEquals(StreamQuality.BAD, verdict(QualityEvidence(stallsInWindow = 3, stallsInLongWindow = 3)).quality)
    }

    @Test
    fun `a stall some minutes ago is a blemish and nothing worse`() {
        val v = verdict(QualityEvidence(stallsInWindow = 0, stallsInLongWindow = 1))

        assertEquals(StreamQuality.GOOD, v.quality)
        assertFalse(v.firm)
    }

    @Test
    fun `no bytes with nothing to carry the picture is a stream that has stopped`() {
        // The buffer is unknown (the backup engine) and so is not carrying anything.
        assertEquals(StreamQuality.BAD, verdict(QualityEvidence(silentMs = 3_000)).quality)
        assertEquals(StreamQuality.EXCELLENT, verdict(QualityEvidence(silentMs = 2_999)).quality)
        // A thin buffer does not carry it either.
        assertEquals(StreamQuality.BAD, verdict(QualityEvidence(silentMs = 5_000, bufferRatio = 0.3)).quality)
    }

    @Test
    fun `silence while the buffer is healthy is a film's player not reading, which is health`() {
        val v = verdict(QualityEvidence(silentMs = 30_000, bufferRatio = 0.8))

        assertEquals(StreamQuality.EXCELLENT, v.quality)
    }

    @Test
    fun `a buffer that has drained is poor, a little drained is good, and neither is firm`() {
        val thin = verdict(QualityEvidence(bufferRatio = 0.1))
        val lower = verdict(QualityEvidence(bufferRatio = 0.4))

        assertEquals(StreamQuality.POOR, thin.quality)
        assertFalse(thin.firm)
        assertEquals(StreamQuality.GOOD, lower.quality)
        assertEquals(StreamQuality.EXCELLENT, verdict(QualityEvidence(bufferRatio = 0.5)).quality)
        // No usual to compare with is no evidence: a channel sent at exactly its own speed holds almost nothing.
        assertEquals(StreamQuality.EXCELLENT, verdict(QualityEvidence(bufferRatio = null)).quality)
    }

    @Test
    fun `a rate that drops counts only when no buffer is carrying it`() {
        assertEquals(StreamQuality.EXCELLENT, verdict(QualityEvidence(rateDrop = true, bufferRatio = 0.9)).quality)
        assertEquals(StreamQuality.POOR, verdict(QualityEvidence(rateDrop = true, bufferRatio = null)).quality)
        assertEquals(StreamQuality.POOR, verdict(QualityEvidence(rateDrop = true, bufferRatio = 0.4)).quality)
    }

    @Test
    fun `lost data is a blemish at one and a degrading stream at three`() {
        assertEquals(StreamQuality.GOOD, verdict(QualityEvidence(faultsInWindow = 1)).quality)
        assertEquals(StreamQuality.GOOD, verdict(QualityEvidence(faultsInWindow = 2)).quality)
        assertEquals(StreamQuality.POOR, verdict(QualityEvidence(faultsInWindow = 3)).quality)
    }

    @Test
    fun `what the viewer can see outranks what is merely coming`() {
        // A full buffer does not make a stream that has stalled three times in a minute any better.
        val v = verdict(QualityEvidence(stallsInWindow = 3, stallsInLongWindow = 3, bufferRatio = 1.0))

        assertEquals(StreamQuality.BAD, v.quality)
        assertTrue(v.firm)
    }
}
