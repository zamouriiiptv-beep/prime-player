package com.castivio.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a press on a channel does about its lock -- decided on the spot where it can be, and waited
 * for where it cannot.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelLocksTest {

    private class MemoryLocks(initial: Set<String>?) : ChannelLocks {
        private val state = MutableStateFlow(initial)
        override val locked: StateFlow<Set<String>?> = state
        override fun lock(channelId: String) { state.value = state.value.orEmpty() + channelId }
        override fun unlock(channelId: String) { state.value = state.value.orEmpty() - channelId }
        fun read(set: Set<String>) { state.value = set }
    }

    @Test
    fun `a locked live channel asks for the password`() {
        assertEquals(PressVerdict.Ask, pressVerdictFor(live = true, channelId = "c1", known = setOf("c1", "c2")))
    }

    @Test
    fun `a live channel that is not locked starts`() {
        assertEquals(PressVerdict.Start, pressVerdictFor(live = true, channelId = "c3", known = setOf("c1", "c2")))
        assertEquals(PressVerdict.Start, pressVerdictFor(live = true, channelId = "c3", known = emptySet()))
    }

    @Test
    fun `a press before the locked set has been read waits for it`() {
        assertEquals(PressVerdict.Wait, pressVerdictFor(live = true, channelId = "c1", known = null))
    }

    @Test
    fun `a film or an episode starts at once and never waits on the locked set`() {
        assertEquals(PressVerdict.Start, pressVerdictFor(live = false, channelId = "m1", known = null))
        assertEquals(PressVerdict.Start, pressVerdictFor(live = false, channelId = "m1", known = setOf("m1")))
    }

    @Test
    fun `a press that carries no channel starts`() {
        assertEquals(PressVerdict.Start, pressVerdictFor(live = true, channelId = null, known = setOf("c1")))
        assertEquals(PressVerdict.Start, pressVerdictFor(live = true, channelId = null, known = null))
    }

    @Test
    fun `a channel is locked once the set has been read and it is in it`() = runTest {
        val locks = MemoryLocks(setOf("c1"))

        assertTrue(locks.isLocked("c1"))
        assertFalse(locks.isLocked("c2"))
    }

    @Test
    fun `asking before the set has been read waits, and answers from what is read`() = runTest {
        val locks = MemoryLocks(null)
        val answer = CompletableDeferred<Boolean>()
        launch { answer.complete(locks.isLocked("c1")) }
        advanceUntilIdle()
        assertFalse("nothing is said while nothing is known", answer.isCompleted)

        locks.read(setOf("c1"))
        advanceUntilIdle()

        assertTrue(answer.await())
    }
}
