package com.castivio.core.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the collapsed card says, and when the card goes.
 *
 * Pure, as the thing it tests is: a summary is plain values and the linger is arithmetic on a clock the test gives it.
 */
class StripSummaryTest {

    private fun summary(
        running: Boolean = false,
        endedAtNanos: Long? = 0L,
        categories: String? = "462/462",
        status: String = "done",
        tone: StripTone = StripTone.DONE,
    ) = StripSummary(
        sessionId = 7,
        name = "CHANNELS · COLD",
        seconds = "4.97 s",
        status = status,
        categories = categories,
        tone = tone,
        running = running,
        endedAtNanos = endedAtNanos,
    )

    private fun millis(ms: Long) = ms * 1_000_000L

    @Test
    fun `the detail line is the total, where it stands, and the categories`() {
        assertEquals("4.97 s · done · categories 462/462", summary().detail)
    }

    @Test
    fun `a measurement with no categories says none`() {
        assertEquals("4.97 s · done", summary(categories = null).detail)
    }

    @Test
    fun `a failure keeps its stage in the detail`() {
        val failed = summary(status = "FAILED at streams", tone = StripTone.FAILED, categories = "12/462")

        assertEquals("4.97 s · FAILED at streams · categories 12/462", failed.detail)
    }

    @Test
    fun `a measurement that runs is not going`() {
        assertNull(StripLinger.hideInMs(summary(running = true, endedAtNanos = null), millis(60_000), false, null, 0L))
    }

    @Test
    fun `a finished one stays for the linger and then goes`() {
        val ended = summary(endedAtNanos = millis(1_000))

        assertEquals(StripLinger.LINGER_MS, StripLinger.hideInMs(ended, millis(1_000), false, null, 0L))
        assertEquals(5_000L, StripLinger.hideInMs(ended, millis(1_000 + StripLinger.LINGER_MS - 5_000), false, null, 0L))
        assertEquals(0L, StripLinger.hideInMs(ended, millis(1_000 + StripLinger.LINGER_MS), false, null, 0L))
        assertEquals(0L, StripLinger.hideInMs(ended, millis(1_000 + StripLinger.LINGER_MS + 60_000), false, null, 0L))
    }

    @Test
    fun `a card that is being read is not going, and counts the linger again from when it is let go`() {
        val ended = summary(endedAtNanos = millis(1_000))

        // Opened to the full report, a long time after it ended.
        assertNull(StripLinger.hideInMs(ended, millis(120_000), true, null, 0L))

        // Closed again at 120 s: a whole linger from there, not the little that is left of the first.
        assertEquals(StripLinger.LINGER_MS, StripLinger.hideInMs(ended, millis(120_000), false, millis(120_000), 0L))
        assertEquals(0L, StripLinger.hideInMs(ended, millis(120_000 + StripLinger.LINGER_MS), false, millis(120_000), 0L))
    }

    @Test
    fun `a touch from before the end does not shorten or lengthen the linger`() {
        val ended = summary(endedAtNanos = millis(10_000))

        assertEquals(StripLinger.LINGER_MS, StripLinger.hideInMs(ended, millis(10_000), false, millis(2_000), 0L))
    }

    @Test
    fun `a measurement that does not say when it ended is counted from when the card was first drawn`() {
        val replaced = summary(endedAtNanos = null, status = "replaced", tone = StripTone.MUTED)

        assertEquals(StripLinger.LINGER_MS, StripLinger.hideInMs(replaced, millis(50_000), false, null, millis(50_000)))
        assertEquals(0L, StripLinger.hideInMs(replaced, millis(50_000 + StripLinger.LINGER_MS), false, null, millis(50_000)))
    }
}
