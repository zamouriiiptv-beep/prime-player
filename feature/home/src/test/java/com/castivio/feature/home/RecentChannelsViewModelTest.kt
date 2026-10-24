package com.castivio.feature.home

import com.castivio.domain.PlaybackProgress
import com.castivio.domain.PlayerPulse
import com.castivio.domain.ProgressRepository
import com.castivio.domain.PulsePhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * What is recorded as watched for the rail's Recently watched entry: a channel once its picture shows --
 * not on the press, not while it opens, not after it stopped -- written through the progress store, and a
 * write that fails costs that one record and nothing else.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecentChannelsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Records the channels it is told were watched; fails while [failing]. */
    private class FakeProgress : ProgressRepository {
        val watched = mutableListOf<String>()
        var failing = false
        override suspend fun save(mediaId: String, positionMs: Long, durationMs: Long?) = Unit
        override suspend fun progress(mediaId: String): PlaybackProgress? = null
        override suspend fun clear(mediaId: String) = Unit
        override suspend fun clearAll() = Unit
        override suspend fun watched(mediaId: String) {
            if (failing) error("disk full")
            watched += mediaId
        }
    }

    // ---------------------------------------------------------------------------- what is watched

    private val url = "http://host/live/u/p/1.ts"

    @Test
    fun `a channel is watched once the player has a picture of its stream`() {
        assertEquals("c1", watchedChannel("c1", url, PlayerPulse(url, PulsePhase.SHOWING)))
    }

    @Test
    fun `a channel still opening, or stopped, is not watched`() {
        assertNull(watchedChannel("c1", url, PlayerPulse(url, PulsePhase.OPENING)))
        assertNull(watchedChannel("c1", url, PlayerPulse(url, PulsePhase.STOPPED)))
        assertNull(watchedChannel("c1", url, null))
    }

    @Test
    fun `a picture of another stream is not this channel watched`() {
        assertNull(watchedChannel("c1", url, PlayerPulse("http://host/live/u/p/2.ts", PulsePhase.SHOWING)))
    }

    @Test
    fun `nothing is watched when no channel is on`() {
        assertNull(watchedChannel(null, url, PlayerPulse(url, PulsePhase.SHOWING)))
        assertNull(watchedChannel("c1", null, PlayerPulse(url, PulsePhase.SHOWING)))
    }

    // ----------------------------------------------------------------------------- the record

    @Test
    fun `each channel watched is written, in the order it was watched`() = runTest(dispatcher) {
        val progress = FakeProgress()
        val model = RecentChannelsViewModel(progress)

        model.watched("c1")
        model.watched("c2")
        model.watched("c1")
        runCurrent()

        assertEquals(listOf("c1", "c2", "c1"), progress.watched)
    }

    @Test
    fun `a write that fails costs that record, and the next is written`() = runTest(dispatcher) {
        val progress = FakeProgress().apply { failing = true }
        val model = RecentChannelsViewModel(progress)

        model.watched("c1")
        runCurrent()
        progress.failing = false
        model.watched("c2")
        runCurrent()

        assertEquals(listOf("c2"), progress.watched)
    }
}
