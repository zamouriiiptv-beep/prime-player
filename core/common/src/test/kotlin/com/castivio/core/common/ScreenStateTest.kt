package com.castivio.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenStateTest {

    @Test
    fun `content carries its value`() {
        val state: ScreenState<List<String>> = ScreenState.Content(listOf("a", "b"))

        assertEquals(listOf("a", "b"), state.valueOrNull())
    }

    @Test
    fun `the other states carry no value`() {
        assertNull(ScreenState.Loading.valueOrNull())
        assertNull(ScreenState.Empty(EmptyReason.NO_FAVORITES).valueOrNull())
        assertNull(ScreenState.Failed(AppError.TIMEOUT).valueOrNull())
    }

    /**
     * The rule this type exists to enforce. A refresh happening behind content is not
     * loading, because rendering it as loading is the spinner-over-a-populated-list
     * that the state design forbids.
     */
    @Test
    fun `a refresh behind content is not loading`() {
        val refreshing: ScreenState<String> = ScreenState.Content("live", refreshing = true)

        assertTrue(refreshing.isBusy)
        assertFalse(refreshing is ScreenState.Loading)
        assertEquals("live", refreshing.valueOrNull())
    }

    @Test
    fun `a first load is busy and settled content is not`() {
        assertTrue(ScreenState.Loading.isBusy)
        assertFalse(ScreenState.Content("live").isBusy)
        assertFalse(ScreenState.Empty(EmptyReason.NO_HISTORY).isBusy)
        assertFalse(ScreenState.Failed(AppError.SERVER_ERROR).isBusy)
    }

    /**
     * Empty is its own state rather than content with an empty list, because an empty
     * result needs a reason and an action. A list-shaped state would render an empty
     * grid, which is the dead end the design forbids.
     */
    @Test
    fun `empty names its reason and can name the provider`() {
        val state = ScreenState.Empty(
            reason = EmptyReason.PROVIDER_HAS_NO_CONTENT,
            providerLabel = "Nova IPTV",
        )

        assertEquals(EmptyReason.PROVIDER_HAS_NO_CONTENT, state.reason)
        assertEquals("Nova IPTV", state.providerLabel)
    }

    @Test
    fun `a failure says whether retrying is worth offering`() {
        assertTrue(ScreenState.Failed(AppError.TIMEOUT).retryable)
        assertFalse(ScreenState.Failed(AppError.UNAUTHORIZED, retryable = false).retryable)
    }

    // ---------------------------------------------------------------------- map

    @Test
    fun `mapping transforms content and preserves the refresh flag`() {
        val mapped = ScreenState.Content(listOf(1, 2, 3), refreshing = true)
            .map { items -> items.size }

        assertEquals(ScreenState.Content(3, refreshing = true), mapped)
    }

    @Test
    fun `mapping leaves every other state untouched`() {
        val empty = ScreenState.Empty(EmptyReason.NO_SEARCH_RESULTS)
        val failed = ScreenState.Failed(AppError.NETWORK_UNAVAILABLE)

        assertEquals(ScreenState.Loading, ScreenState.Loading.map { "never called" })
        assertEquals(empty, empty.map { "never called" })
        assertEquals(failed, failed.map { "never called" })
    }

    @Test
    fun `mapping does not run the transform when there is no content`() {
        var calls = 0
        ScreenState.Loading.map { calls++ }
        ScreenState.Empty(EmptyReason.NO_PROVIDER).map { calls++ }

        assertEquals(0, calls)
    }
}
