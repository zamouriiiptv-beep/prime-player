package com.castivio.feature.home

import com.castivio.core.common.FrameSize
import com.castivio.domain.CatalogRepository
import com.castivio.domain.ChannelGuideFetcher
import com.castivio.domain.ChannelRef
import com.castivio.domain.EpgCoverage
import com.castivio.domain.EpgRepository
import com.castivio.domain.FavoritesRepository
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaItem
import com.castivio.domain.MediaKind
import com.castivio.domain.NowNext
import com.castivio.domain.NowNextRefresher
import com.castivio.domain.Page
import com.castivio.domain.PageRequest
import com.castivio.domain.Programme
import com.castivio.domain.StreamQuality
import com.castivio.domain.time.TimeAnchorSource
import com.castivio.domain.time.TimeReading
import com.castivio.domain.time.TimeTrust
import com.castivio.domain.time.TrustedTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The board's quality badge is measured, written by the channel that was measured, and read
 * back by id -- never taken from a name.
 */
class ChannelQualityBoardTest {

    private fun holder(store: MemoryQualities) = ChannelsViewModel(
        epg = NoEpg,
        catalog = NoCatalog,
        nowNext = NoNowNext,
        guideFetcher = NoFetcher,
        favorites = NoFavorites,
        clock = NoClock,
        measurements = store,
    )

    @Test
    fun `a channel with a picture is recorded at the class of its measured size`() {
        val store = MemoryQualities()
        val model = holder(store)

        model.measured("a", FrameSize(1920, 1080))
        model.measured("b", FrameSize(1280, 720))
        model.measured("c", FrameSize(3840, 2160))
        model.measured("d", FrameSize(720, 576))

        assertEquals(
            mapOf("a" to StreamQuality.FHD, "b" to StreamQuality.HD, "c" to StreamQuality.UHD, "d" to StreamQuality.SD),
            model.quality.value,
        )
    }

    @Test
    fun `a channel that was never measured has no entry, whatever it is called`() {
        val model = holder(MemoryQualities(mapOf("seen" to StreamQuality.HD)))

        assertEquals(StreamQuality.HD, model.quality.value["seen"])
        assertTrue("an unwatched '4K UHD' channel has no badge", "4k-uhd-never-opened" !in model.quality.value)
    }

    @Test
    fun `the latest measurement wins`() {
        val store = MemoryQualities()
        val model = holder(store)

        model.measured("a", FrameSize(1280, 720))
        model.measured("a", FrameSize(1920, 1080))

        assertEquals(StreamQuality.FHD, model.quality.value["a"])
        assertEquals(2, store.writes.size)
    }

    @Test
    fun `what was measured on an earlier launch is there from the start`() {
        val model = holder(MemoryQualities(mapOf("a" to StreamQuality.UHD)))
        assertEquals(StreamQuality.UHD, model.quality.value["a"])
    }

    /* ------------------------------------------------------------------ doubles */

    private object NoEpg : EpgRepository {
        override suspend fun nowNext(channelIds: List<String>, atMs: Long): Map<String, NowNext> = emptyMap()
        override suspend fun window(channelIds: List<String>, fromMs: Long, toMs: Long): Map<String, List<Programme>> = emptyMap()
        override suspend fun programmes(channelId: String, fromMs: Long, toMs: Long): List<Programme> = emptyList()
        override fun coverage(): Flow<EpgCoverage> = flowOf(EpgCoverage(0, 0, null, null, null))
        override suspend fun hasFreshGuide(atMs: Long, minimumHorizonMs: Long): Boolean = false
    }

    private object NoCatalog : CatalogRepository {
        override fun groups(kind: MediaKind): Flow<List<MediaGroup>> = flowOf(emptyList())
        override suspend fun page(request: PageRequest): Page<MediaItem> = Page(emptyList(), 0, 0)
        override suspend fun item(id: String): MediaItem? = null
        override suspend fun search(query: String, limit: Int): List<MediaItem> = emptyList()
        override suspend fun search(query: String, kind: MediaKind, limit: Int): List<MediaItem> = emptyList()
        override fun count(kind: MediaKind, groupId: String?): Flow<Int> = flowOf(0)
        override suspend fun channelRefs(mediaIds: List<String>): List<ChannelRef> = emptyList()
    }

    private object NoNowNext : NowNextRefresher {
        override suspend fun refresh(channels: List<ChannelRef>): Int = 0
    }

    private object NoFetcher : ChannelGuideFetcher {
        override suspend fun fetch(channel: ChannelRef): Int = 0
    }

    private object NoFavorites : FavoritesRepository {
        override fun isFavorite(mediaId: String): Flow<Boolean> = flowOf(false)
        override suspend fun toggle(mediaId: String): Boolean = false
        override fun count(): Flow<Int> = flowOf(0)
    }

    private object NoClock : TrustedTime {
        override fun now(): TimeReading = TimeReading(0L, TimeTrust.DEVICE)
        override fun anchor(epochMs: Long, source: TimeAnchorSource): TimeReading = now()
    }
}
