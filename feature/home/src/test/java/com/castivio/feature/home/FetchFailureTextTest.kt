package com.castivio.feature.home

import com.castivio.core.common.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * A failed section says what went wrong. It said "your provider did not answer" over a
 * playlist the provider had refused, which sent the user waiting instead of checking it.
 */
class FetchFailureTextTest {

    @Test
    fun `a refusal reads as a refusal, whatever form it took`() {
        val refused = AppError.UNAUTHORIZED.fetchFailureDetail()

        assertEquals(R.string.browse_fetch_failed_final, refused)
        assertEquals(refused, AppError.NOT_FOUND.fetchFailureDetail())
        assertEquals(refused, AppError.NOT_CONFIGURED.fetchFailureDetail())
    }

    @Test
    fun `the different causes read differently`() {
        val sentences = listOf(
            AppError.UNAUTHORIZED,
            AppError.TIMEOUT,
            AppError.NETWORK_UNAVAILABLE,
            AppError.SERVER_ERROR,
            AppError.MALFORMED_PLAYLIST,
            AppError.UNKNOWN,
        ).map { it.fetchFailureDetail() }

        assertEquals(sentences.size, sentences.toSet().size)
    }

    @Test
    fun `only a timeout is said not to have answered in time`() {
        for (error in AppError.entries.filter { it != AppError.TIMEOUT }) {
            assertNotEquals(error.name, R.string.browse_fetch_failed_retryable, error.fetchFailureDetail())
        }
    }
}
