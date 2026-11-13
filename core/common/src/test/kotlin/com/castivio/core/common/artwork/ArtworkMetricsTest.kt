package com.castivio.core.common.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the load strip says about the pictures, from calls made up for the purpose. */
class ArtworkMetricsTest {

    private fun ms(count: Long) = count * 1_000_000L

    private val kilobyte = 1_024L

    @Test
    fun `a window in which no picture was asked for says nothing`() {
        assertTrue(ArtworkTally().figures().describe().isEmpty())
    }

    @Test
    fun `a mixed window is told in two lines`() {
        val tally = ArtworkTally()
        repeat(3) { tally.started(ArtworkHost.PROVIDER) }
        tally.started(ArtworkHost.TMDB)
        tally.started(ArtworkHost.OTHER)
        tally.ended(ArtworkOutcome.OK, bytes = 100 * kilobyte, callNanos = ms(1_500), waitNanos = ms(100))
        tally.ended(ArtworkOutcome.OK, bytes = 300 * kilobyte, callNanos = ms(2_500), waitNanos = ms(300))
        tally.ended(ArtworkOutcome.TIMEOUT, callNanos = ms(6_000), waitNanos = ms(200))
        tally.ended(ArtworkOutcome.HTTP, httpStatus = 404, callNanos = ms(400), waitNanos = 0L)
        tally.ended(ArtworkOutcome.CANCELLED, callNanos = ms(50))
        tally.retried()
        tally.retried()

        assertEquals(
            listOf(
                "artwork: 5 calls · ok 2 · timeout 1 · http 404×1 · network 0 · cancelled 1 · in flight 0 · asked again 2",
                "artwork: 0.4 MB (200 KB each) · call (s) mean 2.60 slowest 6.00 · before a connection (s) mean 0.15" +
                    " · hosts provider 3 · tmdb 1 · other 1",
            ),
            tally.figures().describe(),
        )
    }

    @Test
    fun `a call that has begun and not ended is in flight`() {
        val tally = ArtworkTally()
        repeat(3) { tally.started(ArtworkHost.OTHER) }
        tally.ended(ArtworkOutcome.OK, bytes = kilobyte, callNanos = ms(10))

        assertEquals(2L, tally.figures().inFlight)
    }

    @Test
    fun `a call cancelled because its card left the screen is not a failure and takes no part in the times`() {
        val tally = ArtworkTally()
        tally.started(ArtworkHost.OTHER)
        tally.ended(ArtworkOutcome.CANCELLED, callNanos = ms(30_000))

        val figures = tally.figures()
        assertEquals(1L, figures.cancelled)
        assertEquals(0L, figures.timeouts + figures.network + figures.httpFailures.values.sum())
        assertEquals(0L, figures.slowestMs)
        assertEquals(0L, figures.meanMs)
    }

    @Test
    fun `a call that never had a connection to wait for does not lower the mean wait`() {
        val tally = ArtworkTally()
        repeat(2) { tally.started(ArtworkHost.OTHER) }
        tally.ended(ArtworkOutcome.OK, bytes = kilobyte, callNanos = ms(100), waitNanos = ms(400))
        tally.ended(ArtworkOutcome.OK, bytes = kilobyte, callNanos = ms(100), waitNanos = -1L)

        assertEquals(400L, tally.figures().meanBeforeConnectionMs)
    }

    @Test
    fun `a window with no body yet says so rather than dividing by nothing`() {
        val tally = ArtworkTally()
        tally.started(ArtworkHost.PROVIDER)
        tally.ended(ArtworkOutcome.NETWORK, callNanos = ms(80))

        val second = tally.figures().describe()[1]
        assertTrue(second, second.startsWith("artwork: no body yet"))
    }

    @Test
    fun `a host that answers with a new status every time cannot grow the table without end`() {
        val tally = ArtworkTally()
        (400..408).forEach {
            tally.started(ArtworkHost.OTHER)
            tally.ended(ArtworkOutcome.HTTP, httpStatus = it, callNanos = ms(1))
        }

        val failures = tally.figures().httpFailures
        assertEquals(9, failures.size)
        assertEquals(1L, failures[0])
        assertEquals(9L, failures.values.sum())
    }

    @Test
    fun `an answer with no status is filed with the statuses past the eighth, as other`() {
        val tally = ArtworkTally()
        tally.started(ArtworkHost.OTHER)
        tally.ended(ArtworkOutcome.HTTP, httpStatus = null, callNanos = ms(1))

        assertEquals(1L, tally.figures().httpFailures[0])
        assertTrue(tally.figures().describe()[0].contains("http other×1"))
    }

    @Test
    fun `the figures are Western digits and the same whatever the locale`() {
        val tally = ArtworkTally()
        tally.started(ArtworkHost.OTHER)
        tally.ended(ArtworkOutcome.OK, bytes = 1_500 * kilobyte, callNanos = ms(1_234), waitNanos = ms(56))
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale("ar", "SA"))
            val second = tally.figures().describe()[1]
            assertTrue(second, second.startsWith("artwork: 1.5 MB (1500 KB each) · call (s) mean 1.23 slowest 1.23"))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun `starting again forgets everything the window counted`() {
        val tally = ArtworkTally()
        tally.started(ArtworkHost.PROVIDER)
        tally.ended(ArtworkOutcome.HTTP, httpStatus = 503, bytes = kilobyte, callNanos = ms(5), waitNanos = ms(1))
        tally.retried()

        tally.reset()

        assertTrue(tally.figures().describe().isEmpty())
        assertEquals(0L, tally.figures().retried)
        assertTrue(tally.figures().httpFailures.isEmpty())
    }
}
