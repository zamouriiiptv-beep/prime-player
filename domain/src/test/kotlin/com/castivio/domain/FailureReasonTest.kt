package com.castivio.domain

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A failure as the one line a debuggable build prints: what it was, what it said -- and never the account. */
class FailureReasonTest {

    private class StatusFailure(status: Int) : IOException("HTTP $status")

    @Test
    fun `a failure is its kind and what it said`() {
        assertEquals("StatusFailure: HTTP 404", failureReason(StatusFailure(404)))
    }

    @Test
    fun `a failure that said nothing is its kind`() {
        assertEquals("IOException", failureReason(IOException()))
    }

    @Test
    fun `what lay under a failure is named after it`() {
        val reason = failureReason(RuntimeException("write failed", IllegalStateException("database is locked")))

        assertTrue(reason, reason.startsWith("RuntimeException: write failed"))
        assertTrue(reason, reason.endsWith("IllegalStateException: database is locked"))
    }

    @Test
    fun `an address's credentials are not in the line`() {
        val reason = failureReason(
            IOException("failed to connect to http://panel:8080/player_api.php?username=sam&password=hunter2&action=get_series_info"),
        )

        assertFalse(reason, reason.contains("sam"))
        assertFalse(reason, reason.contains("hunter2"))
        assertTrue(reason, reason.contains("action=get_series_info"))
    }

    @Test
    fun `an address's user part is not in the line`() {
        val reason = failureReason(IOException("unexpected end of stream on http://sam:hunter2@panel:8080/x"))

        assertFalse(reason, reason.contains("hunter2"))
        assertFalse(reason, reason.contains("sam:"))
    }

    @Test
    fun `a long message is cut short`() {
        val reason = failureReason(IOException("x".repeat(1_000)))

        assertTrue(reason.length <= 160)
        assertTrue(reason.endsWith("…"))
    }

    @Test
    fun `a failed load carries the same line`() {
        assertEquals("IOException: down", EpisodesLoad.Failed(IOException("down")).reason)
    }
}
