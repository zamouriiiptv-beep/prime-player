package com.castivio.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.paging.PagingData
import com.castivio.domain.CatalogOrder
import com.castivio.domain.CatalogPager
import com.castivio.domain.CatalogQuery
import com.castivio.domain.CatalogRepository
import com.castivio.domain.ChannelRef
import com.castivio.domain.InProgressItem
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaItem
import com.castivio.domain.MediaKind
import com.castivio.domain.Page
import com.castivio.domain.PageRequest
import com.castivio.domain.PlaylistSource
import com.castivio.domain.ProviderSource
import com.castivio.domain.Season
import com.castivio.domain.SectionLoads
import com.castivio.domain.SeriesSummary
import com.castivio.domain.SourceRepository
import com.castivio.domain.SyncState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent

/**
 * The holder's collaborators, as test doubles: enough of each to run [BrowseViewModel] and see
 * what it asked of them.
 */
internal class FakePager : CatalogPager {
    val asked = mutableListOf<CatalogQuery>()
    override fun items(query: CatalogQuery): Flow<PagingData<MediaItem>> {
        asked += query
        return emptyFlow()
    }
    override fun series(query: CatalogQuery): Flow<PagingData<SeriesSummary>> = emptyFlow()
    override fun seasons(seriesId: String): Flow<List<Season>> = emptyFlow()
    override fun favorites(): Flow<PagingData<MediaItem>> = emptyFlow()
    override fun continueWatching(): Flow<PagingData<InProgressItem>> = emptyFlow()
    override fun history(): Flow<PagingData<InProgressItem>> = emptyFlow()
}

internal class FakeCatalogue(groups: List<MediaGroup>) : CatalogRepository {
    val held = MutableStateFlow(groups)
    override fun groups(kind: MediaKind): Flow<List<MediaGroup>> = held
    override suspend fun page(request: PageRequest): Page<MediaItem> = error("not asked")
    override suspend fun item(id: String): MediaItem? = error("not asked")
    override suspend fun search(query: String, limit: Int): List<MediaItem> = error("not asked")
    override suspend fun search(query: String, kind: MediaKind, limit: Int): List<MediaItem> = error("not asked")
    override fun count(kind: MediaKind, groupId: String?): Flow<Int> = flowOf(5)
    override suspend fun channelRefs(mediaIds: List<String>): List<ChannelRef> = error("not asked")
}

internal class FakeSources : SourceRepository {
    override suspend fun register(source: PlaylistSource, label: String?): ProviderSource = error("not asked")
    override fun sources(): Flow<List<ProviderSource>> = flowOf(emptyList())
    override fun active(): Flow<ProviderSource?> = flowOf(null)
    override suspend fun activeNow(): ProviderSource? = null
    override suspend fun get(id: String): ProviderSource? = null
    override suspend fun save(source: ProviderSource) = Unit
    override suspend fun recordCatalogueImport(id: String, sync: SyncState) = Unit
    override suspend fun recordEpgImport(id: String, atMs: Long) = Unit
    override suspend fun setActive(id: String) = Unit
    override suspend fun delete(id: String) = Unit
}

/** Records what the holder asked of the stored order, in the order it asked. */
internal class FakeOrder : CatalogOrder {
    val calls = mutableListOf<String>()
    var failSave = false
    var failMove = false
    var failBegin = false

    /** What a move answers: false is "it did not move", as at either end of the list. */
    var moves = true

    override suspend fun begin(groupId: String) {
        if (failBegin) error("disk full")
        calls += "begin $groupId"
    }

    override suspend fun move(groupId: String, channelId: String, up: Boolean): Boolean {
        if (failMove) error("disk full")
        calls += "move $groupId $channelId ${if (up) "up" else "down"}"
        return moves
    }

    /** Recorded as one call, ids sorted so a test does not depend on the order a set was walked in. */
    override suspend fun moveAll(groupId: String, channelIds: Collection<String>, up: Boolean): Boolean {
        if (failMove) error("disk full")
        calls += "moveAll $groupId ${channelIds.sorted().joinToString(",")} ${if (up) "up" else "down"}"
        return moves
    }

    override suspend fun save(groupId: String) {
        if (failSave) error("disk full")
        calls += "save $groupId"
    }

    override suspend fun discard(groupId: String) {
        calls += "discard $groupId"
    }

    override suspend fun saveBouquets(kind: MediaKind, orderedIds: List<String>) {
        if (failSave) error("disk full")
        calls += "bouquets ${kind.name} ${orderedIds.joinToString(",")}"
    }

    var sweeps = 0
    var abandoned = 0

    override suspend fun sweepDrafts() {
        sweeps++
    }

    override fun draftAbandoned() {
        abandoned++
    }
}

/** The holder on the Live section, with its rows and its state being read, as the board does. */
internal class Board(
    val model: BrowseViewModel,
    val pager: FakePager,
    val catalogue: FakeCatalogue,
    val order: FakeOrder,
) {
    val states = mutableListOf<BrowseState>()
    val state: BrowseState get() = states.last()
    val last: CatalogQuery get() = pager.asked.last()
    val bouquets: List<String> get() = state.shownGroups.map { it.id }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.browseBoard(
    groups: List<MediaGroup> = listOf(SPORTS, NEWS, KIDS),
    order: FakeOrder = FakeOrder(),
    /** Held in this store when given, so a test can end the holder as the app does. */
    store: ViewModelStore? = null,
): Board {
    val pager = FakePager()
    val catalogue = FakeCatalogue(groups)
    val loads = SectionLoads(backgroundScope, flowOf(null), { _, _ -> emptyFlow() }, monotonicMs = { 0L })
    val build = { BrowseViewModel(catalogue, pager, loads, FakeSources(), order) }
    val model = if (store == null) {
        build()
    } else {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = build() as T
        }
        ViewModelProvider(store, factory)["live", BrowseViewModel::class.java]
    }
    val board = Board(model, pager, catalogue, order)
    model.show(CatalogSection.Live)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.items.collect { } }
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect { board.states += it } }
    runCurrent()
    return board
}

internal val SPORTS = MediaGroup(id = "sports", name = "Sports", kind = MediaKind.LIVE, itemCount = 3)
internal val NEWS = MediaGroup(id = "news", name = "News", kind = MediaKind.LIVE, itemCount = 2)
internal val KIDS = MediaGroup(id = "kids", name = "Kids", kind = MediaKind.LIVE, itemCount = 4)
