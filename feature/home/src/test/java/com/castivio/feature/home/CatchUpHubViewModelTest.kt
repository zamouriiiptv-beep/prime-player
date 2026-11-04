package com.castivio.feature.home

import com.castivio.core.common.Outcome
import com.castivio.domain.ArchiveGroup
import com.castivio.domain.CatchUpChannel
import com.castivio.domain.CatchUpGateway
import com.castivio.domain.CatchUpListing
import com.castivio.domain.CatchUpProgramme
import com.castivio.domain.Channel
import com.castivio.domain.MediaGroup
import com.castivio.domain.time.TimeAnchorSource
import com.castivio.domain.time.TimeReading
import com.castivio.domain.time.TimeTrust
import com.castivio.domain.time.TrustedTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Where the viewer is inside Catch-up, and what the player's report does to the list: the three places and the order Back
 * leaves them in, the first page being the only one Back does not handle, and a channel the provider has no recording of
 * being remembered as such, at the moment the player said so.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatchUpHubViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Clock(var epochMs: Long) : TrustedTime {
        override fun now(): TimeReading = TimeReading(epochMs, TimeTrust.DEVICE)
        override fun anchor(epochMs: Long, source: TimeAnchorSource): TimeReading {
            this.epochMs = epochMs
            return now()
        }
    }

    /** Remembers what it is told is unavailable, and answers [knownEmpty] from it. */
    private class Gateway : CatchUpGateway {
        val marked = mutableListOf<Pair<String, Long>>()
        override suspend fun listing(channel: CatchUpChannel, nowMs: Long): CatchUpListing = error("not asked")
        override suspend fun playbackUrl(channel: CatchUpChannel, programme: CatchUpProgramme): Outcome<String> =
            error("not asked")
        override fun knownEmpty(mediaId: String, nowMs: Long): Boolean = marked.any { it.first == mediaId }
        override fun markUnavailable(mediaId: String, nowMs: Long) {
            marked += mediaId to nowMs
        }
    }

    private val clock = Clock(1_000L)
    private val gateway = Gateway()
    private val pager = FakePager()

    private fun hub() = CatchUpHubViewModel(
        catalog = FakeCatalogue(emptyList<MediaGroup>()),
        pager = pager,
        gateway = gateway,
        clock = clock,
    )

    private val france = ArchiveGroup(id = "g1", name = "France", channels = 12)
    private val channel = Channel(
        id = "c1",
        title = "One",
        artworkUrl = null,
        number = 1,
        groupId = "g1",
        streamUrl = "http://host/live/u/p/1.ts",
        epgChannelId = null,
        catchUpHours = 72,
    )

    // ------------------------------------------------------------------------------- the stages

    @Test
    fun `it opens on the categories`() {
        assertEquals(HubStage.Groups, hub().stage.value)
    }

    @Test
    fun `a category opens its channels and a channel opens its programmes, over the same category`() {
        val hub = hub()
        hub.openGroup(france)
        assertEquals(HubStage.Channels(france), hub.stage.value)
        hub.openChannel(channel)
        assertEquals(HubStage.Programmes(france, channel), hub.stage.value)
    }

    @Test
    fun `all is a category of none, and a channel opened from it keeps that`() {
        val hub = hub()
        hub.openGroup(null)
        assertEquals(HubStage.Channels(null), hub.stage.value)
        hub.openChannel(channel)
        assertEquals(HubStage.Programmes(null, channel), hub.stage.value)
    }

    @Test
    fun `back leaves the places in the opposite order and says so`() {
        val hub = hub()
        hub.openGroup(france)
        hub.openChannel(channel)

        assertTrue(hub.back())
        assertEquals(HubStage.Channels(france), hub.stage.value)
        assertTrue(hub.back())
        assertEquals(HubStage.Groups, hub.stage.value)
    }

    @Test
    fun `back on the first page is not handled, so the caller leaves Catch-up`() {
        val hub = hub()
        assertFalse(hub.back())
        assertEquals(HubStage.Groups, hub.stage.value)
    }

    @Test
    fun `leaving Catch-up from any place makes the next visit begin at the categories`() {
        val hub = hub()
        hub.openGroup(france)
        hub.openChannel(channel)
        hub.reset()
        assertEquals(HubStage.Groups, hub.stage.value)
    }

    // -------------------------------------------------------------------- a channel with no recording

    @Test
    fun `a channel the provider has no recording of is remembered at the time the player said so`() {
        clock.epochMs = 42_000L
        val hub = hub()

        hub.unavailable("c1")

        assertEquals(listOf("c1" to 42_000L), gateway.marked)
        assertTrue(gateway.knownEmpty("c1", 42_000L))
    }

    // ---------------------------------------------------------------------------------- the counts

    @Test
    fun `the first page counts every channel that keeps an archive, whichever category holds it`() {
        val groups = HubGroups(listOf(france, ArchiveGroup("g2", "Sport", 5), ArchiveGroup("g3", "News", 0)))
        assertEquals(17, groups.total)
        assertEquals(0, HubGroups(emptyList()).total)
    }
}
