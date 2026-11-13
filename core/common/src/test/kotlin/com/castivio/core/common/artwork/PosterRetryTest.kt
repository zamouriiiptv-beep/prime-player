package com.castivio.core.common.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a picture that failed is asked for again, and when asking would be wasted, proved without waiting. */
class PosterRetryTest {

    @Test
    fun `the first failure is asked again after two seconds, and the waits grow to thirty`() {
        assertEquals(2_000L, PosterRetry.waitBeforeRetry(1))
        assertEquals(5_000L, PosterRetry.waitBeforeRetry(2))
        assertEquals(10_000L, PosterRetry.waitBeforeRetry(3))
        assertEquals(20_000L, PosterRetry.waitBeforeRetry(4))
        assertEquals(30_000L, PosterRetry.waitBeforeRetry(5))
    }

    @Test
    fun `a picture tried as often as it is on its own waits to be kicked`() {
        assertEquals(5, PosterRetry.RETRIES)
        assertNull(PosterRetry.waitBeforeRetry(PosterRetry.RETRIES + 1))
        assertNull(PosterRetry.waitBeforeRetry(40))
    }

    @Test
    fun `a picture that has not failed has no wait`() {
        assertNull(PosterRetry.waitBeforeRetry(0))
        assertNull(PosterRetry.waitBeforeRetry(-1))
    }

    @Test
    fun `no answer at all is worth asking again, which is what a timeout and a dropped connection are`() {
        assertTrue(PosterRetry.retryable(null))
    }

    @Test
    fun `a host that is busy or broken may answer differently a few seconds later`() {
        listOf(408, 425, 429, 500, 502, 503, 504, 599).forEach { assertTrue("$it", PosterRetry.retryable(it)) }
    }

    @Test
    fun `a host that said the picture is not there or not for us will say it again`() {
        listOf(400, 401, 403, 404, 410, 451).forEach { assertFalse("$it", PosterRetry.retryable(it)) }
    }

    @Test
    fun `a redirect that was not followed and a status past the range are not worth a retry either`() {
        listOf(301, 304, 600, 0).forEach { assertFalse("$it", PosterRetry.retryable(it)) }
    }

    @Test
    fun `the status of a refusal is read from the loader's message`() {
        assertEquals(404, PosterRetry.statusOf("HTTP 404: Not Found"))
        assertEquals(503, PosterRetry.statusOf("HTTP 503: "))
        assertEquals(429, PosterRetry.statusOf("HTTP 429"))
    }

    @Test
    fun `a failure of the road has no status, and a message that only mentions one is not a refusal`() {
        assertNull(PosterRetry.statusOf(null))
        assertNull(PosterRetry.statusOf("timeout"))
        assertNull(PosterRetry.statusOf("Unable to resolve host \"host\": No address associated with hostname"))
        assertNull(PosterRetry.statusOf("the answer was HTTP 404"))
        assertNull(PosterRetry.statusOf("HTTP 4040: not a status"))
    }

    @Test
    fun `a refusal read from a message is retried or not by the same rule as one read from a status`() {
        assertFalse(PosterRetry.retryable(PosterRetry.statusOf("HTTP 404: Not Found")))
        assertTrue(PosterRetry.retryable(PosterRetry.statusOf("HTTP 503: Service Unavailable")))
        assertTrue(PosterRetry.retryable(PosterRetry.statusOf("timeout")))
    }
}
