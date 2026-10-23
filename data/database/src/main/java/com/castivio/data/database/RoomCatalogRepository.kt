package com.castivio.data.database

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import com.castivio.core.platform.CastivioTrace
import com.castivio.data.database.dao.FavoriteDao
import com.castivio.data.database.dao.GroupDao
import com.castivio.data.database.dao.MediaDao
import com.castivio.data.database.dao.OrderDao
import com.castivio.data.database.dao.ProgressDao
import com.castivio.domain.CatalogOrder
import com.castivio.domain.CatalogPager
import com.castivio.domain.CatalogQuery
import com.castivio.domain.CatalogRepository
import com.castivio.domain.ChannelRef
import com.castivio.domain.Episode
import com.castivio.domain.InProgressItem
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaItem
import com.castivio.domain.MediaKind
import com.castivio.domain.Page
import com.castivio.domain.PageRequest
import com.castivio.domain.Season
import com.castivio.domain.SeriesSummary
import com.castivio.domain.SortOrder
import com.castivio.domain.arrangedBy
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The catalogue, read from SQLite.
 *
 * Every read here is bounded. There is no method that can return the library,
 * because at 400,000 rows that is an OOM rather than a slow query — the
 * interface in `:domain` is shaped to make the memory-safe path the only path.
 */
class RoomCatalogRepository(
    private val mediaDao: MediaDao,
    private val groupDao: GroupDao,
    private val favoriteDao: FavoriteDao,
    private val progressDao: ProgressDao,
    /** For the channel list's own pages -- see [WindowPagingSource]. */
    private val database: CastivioDatabase,
) : CatalogRepository, CatalogPager, CatalogOrder {

    private val orderDao: OrderDao get() = database.orderDao()

    /**
     * Drafts are swept and begun one at a time, so a sweep can never land after a begin and
     * take the draft it just made.
     */
    private val drafts = Mutex()

    /**
     * Whether this process has already cleared what an earlier one left -- or begun an
     * arrangement, which clears it too. Set back by [draftAbandoned], so a session whose screen
     * went away is cleared by the next sweep.
     */
    private val draftsSwept = AtomicBoolean(false)

    /**
     * **The viewer's orders are told to their readers by the writes that change them.**
     *
     * Room was relied on for it, and on a device it did not tell them: a channel moved with
     * the arrows stayed where it was on screen although the draft had it in its new place --
     * the saved list showed every move once Save was pressed -- and a saved order of bouquets
     * left the rail in the old one until the screen was opened again. Every write to
     * `custom_order` goes through this class, so this class says so: [orderWrites] for the
     * bouquets' order, and [orderReaders] -- the windows of a bouquet being read -- to read
     * again. Room's own notice, where it comes, only reads once more what this already read.
     */
    private val orderWrites = MutableStateFlow(0L)
    private val orderReaders = CopyOnWriteArraySet<PagingSource<*, *>>()

    private fun ordersChanged() {
        orderWrites.update { it + 1 }
        orderReaders.forEach { it.invalidate() }
    }

    /** [source] reads a bouquet in an order the viewer may be changing: told of every change. */
    private fun <S : PagingSource<*, *>> readingOrders(source: S): S {
        orderReaders += source
        source.registerInvalidatedCallback { orderReaders -= source }
        return source
    }

    // ------------------------------------------------------------- CatalogRepository

    /**
     * The section's bouquets, in the viewer's order where they have made one and the provider's
     * where they have not -- see [CatalogOrder]. Hundreds of rows, read whole, as before.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun groups(kind: MediaKind): Flow<List<MediaGroup>> =
        combine(
            groupDao.groupsOf(kind.name),
            // Read again after every write to an order -- see [orderWrites].
            orderWrites.flatMapLatest { orderDao.positions(OrderScopes.bouquets(kind)) },
        ) { rows, saved ->
            rows.map { it.toDomain() }.arrangedBy(saved.associate { it.itemId to it.position })
        }

    override suspend fun page(request: PageRequest): Page<MediaItem> {
        val kind = request.kind.name
        // Held in a local: a property from another module cannot be smart cast,
        // and `:domain` is a separate module.
        val group = request.groupId
        val rows = if (group == null) {
            mediaDao.window(kind, request.offset, request.limit)
        } else {
            mediaDao.windowInGroup(kind, group, request.offset, request.limit)
        }
        val total = if (group == null) {
            mediaDao.countNow(kind)
        } else {
            mediaDao.countNowInGroup(kind, group)
        }
        return Page(items = rows.map { it.toDomain() }, offset = request.offset, totalCount = total)
    }

    override suspend fun item(id: String): MediaItem? = mediaDao.byId(id)?.toDomain()

    /**
     * Search, bounded by [limit] and answered by the FTS index.
     *
     * Returns empty for input with nothing searchable in it — that is not the
     * same as "no results", and the caller distinguishes the two by whether the
     * user has typed anything at all.
     */
    override suspend fun search(query: String, limit: Int): List<MediaItem> {
        val match = FtsQuery.build(query) ?: return emptyList()
        return mediaDao.search(match, limit).map { it.toDomain() }
    }

    override suspend fun search(query: String, kind: MediaKind, limit: Int): List<MediaItem> {
        val match = FtsQuery.build(query) ?: return emptyList()
        return mediaDao.searchKind(match, kind.name, limit).map { it.toDomain() }
    }

    override suspend fun channelRefs(mediaIds: List<String>): List<ChannelRef> {
        if (mediaIds.isEmpty()) return emptyList()
        // Chunked below SQLite's variable limit, like every other IN query here.
        return mediaIds.chunked(MAX_IDS_PER_QUERY).flatMap { chunk ->
            mediaDao.refsFor(chunk).map { row ->
                ChannelRef(
                    mediaId = row.mediaId,
                    providerRef = row.providerRef,
                    epgChannelId = row.epgChannelId,
                )
            }
        }
    }

    override fun count(kind: MediaKind, groupId: String?): Flow<Int> =
        if (groupId == null) mediaDao.countOf(kind.name) else mediaDao.countOfGroup(kind.name, groupId)

    // -------------------------------------------------------------------- CatalogPager

    override fun items(query: CatalogQuery): Flow<PagingData<MediaItem>> {
        val kind = query.kind.name
        val group = query.groupId
        // Nothing searchable is no search, as for [search]: the list, not an empty one.
        val match = FtsQuery.build(query.search)
        if (match != null) {
            // The same windows as the provider's order, read the same way, narrowed by the index.
            return pager {
                WindowPagingSource(database.invalidationTracker, database.queryExecutor) { offset, limit ->
                    if (group == null) mediaDao.windowMatching(match, kind, offset, limit)
                    else mediaDao.windowMatchingInGroup(match, kind, group, offset, limit)
                }
            }.map { data -> data.map { it.toDomain() } }
        }
        return pager {
            when (query.sort) {
                // Read outside a transaction, so a page lands while a section is being
                // written -- see [WindowPagingSource]. The same rows, in the same order.
                SortOrder.PROVIDER -> WindowPagingSource(database.invalidationTracker, database.queryExecutor) { offset, limit ->
                    if (group == null) {
                        mediaDao.window(kind, offset, limit)
                    } else {
                        // The viewer's order where this bouquet has one -- or its draft, while
                        // they are arranging it -- and the indexed provider's order where it has
                        // not, which is almost every bouquet and costs nothing extra.
                        val scope = if (query.arranging) OrderScopes.draft(group) else OrderScopes.channels(group)
                        if (orderDao.has(scope)) mediaDao.windowArranged(kind, group, scope, offset, limit)
                        else mediaDao.windowInGroup(kind, group, offset, limit)
                    }
                }.let { source -> if (group == null) source else readingOrders(source) }

                SortOrder.NAME_ASC ->
                    if (group == null) mediaDao.pageByNameAsc(kind)
                    else mediaDao.pageByNameAscInGroup(kind, group)

                SortOrder.NAME_DESC ->
                    if (group == null) mediaDao.pageByNameDesc(kind)
                    else mediaDao.pageByNameDescInGroup(kind, group)

                SortOrder.RECENTLY_ADDED ->
                    if (group == null) mediaDao.pageByRecent(kind)
                    else mediaDao.pageByRecentInGroup(kind, group)
            }
        }.map { data -> data.map { it.toDomain() } }
    }

    // --------------------------------------------------------------------------- CatalogOrder

    override suspend fun begin(groupId: String) {
        drafts.withLock {
            draftsSwept.set(true)
            orderDao.begin(groupId, OrderScopes.channels(groupId), OrderScopes.draft(groupId))
        }
        ordersChanged()
    }

    override suspend fun sweepDrafts() {
        val swept = drafts.withLock {
            draftsSwept.compareAndSet(false, true).also { first -> if (first) orderDao.clearDrafts() }
        }
        if (swept) ordersChanged()
    }

    override fun draftAbandoned() {
        draftsSwept.set(false)
    }

    override suspend fun move(groupId: String, channelId: String, up: Boolean): Boolean =
        orderDao.move(groupId, OrderScopes.draft(groupId), channelId, up).also { moved ->
            if (moved) ordersChanged()
        }

    override suspend fun save(groupId: String) {
        orderDao.save(OrderScopes.channels(groupId), OrderScopes.draft(groupId))
        ordersChanged()
    }

    override suspend fun discard(groupId: String) {
        orderDao.clear(OrderScopes.draft(groupId))
        ordersChanged()
    }

    override suspend fun saveBouquets(kind: MediaKind, orderedIds: List<String>) {
        orderDao.saveBouquets(OrderScopes.bouquets(kind), kind.name, orderedIds)
        ordersChanged()
    }

    // -------------------------------------------------------------------------- the series

    override fun series(query: CatalogQuery): Flow<PagingData<SeriesSummary>> {
        val group = query.groupId
        return pager {
            WindowPagingSource(database.invalidationTracker, database.queryExecutor) { offset, limit ->
                if (group == null) mediaDao.windowSeries(offset, limit)
                else mediaDao.windowSeriesInGroup(group, offset, limit)
            }
        }.map { data -> data.map { it.toDomain() } }
    }

    /**
     * Seasons of one show.
     *
     * A season list is tens of rows, so this reads the show's episodes and groups
     * them — the one place in the app where holding a list is correct, because it
     * is bounded by a single series rather than by the library.
     */
    override fun seasons(seriesId: String): Flow<List<Season>> =
        mediaDao.episodesOf(seriesId).map { rows ->
            rows.map { it.toDomain() }
                .filterIsInstance<Episode>()
                .groupBy { it.seasonNumber }
                .toSortedMap()
                .map { (number, episodes) ->
                    Season(number = number, episodes = episodes.sortedBy { it.episodeNumber })
                }
        }

    override fun favorites(): Flow<PagingData<MediaItem>> =
        pager { favoriteDao.page() }.map { data -> data.map { it.toDomain() } }

    override fun continueWatching(): Flow<PagingData<InProgressItem>> =
        pager { progressDao.pageInProgress() }.map { data -> data.map { it.toDomain() } }

    override fun history(): Flow<PagingData<InProgressItem>> =
        pager { progressDao.pageHistory() }.map { data -> data.map { it.toDomain() } }

    /**
     * The pager, with a marker on the first page of every new stream.
     *
     * `Castivio.FirstPage` is emitted once per query -- the load a screen is waiting on
     * -- and never for the prefetches behind it, which nobody is waiting for and which
     * would turn an opening measurement into a scrolling one. The flag is per call to
     * this function, so a new query gets a new flag exactly as it gets a new stream.
     *
     * The `onEach` observes; it changes no page, no size and no ordering. `PagingConfig`
     * below is untouched.
     */
    private fun <T : Any> pager(source: () -> androidx.paging.PagingSource<Int, T>) =
        rawPager(source).onEach {
            if (firstPageSeen.compareAndSet(false, true)) {
                CastivioTrace.instant(CastivioTrace.FIRST_PAGE)
            }
        }

    private val firstPageSeen = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun <T : Any> rawPager(source: () -> androidx.paging.PagingSource<Int, T>) =
        Pager(config = PagingConfig(
            pageSize = PAGE_SIZE,
            prefetchDistance = PREFETCH,
            initialLoadSize = INITIAL_LOAD,
            // Dropping pages keeps memory flat while a user holds the down key
            // through 100,000 channels: only a window stays materialised.
            maxSize = MAX_IN_MEMORY,
            enablePlaceholders = false,
        ), pagingSourceFactory = source).flow

    private companion object {
        /**
         * Sized for a TV grid: roughly two screens of a 6-across poster wall.
         * Small pages make scrolling stutter (a query per row of posters); large
         * ones make the first paint late.
         */
        const val PAGE_SIZE = 60
        const val INITIAL_LOAD = 120

        /** Load ahead by half a page so a fast D-pad scroll never hits an empty row. */
        const val PREFETCH = 30

        /**
         * Hard ceiling on materialised rows. Must be at least
         * `PAGE_SIZE + 2 * PREFETCH`; at 300 it holds five pages, roughly 60 KB of
         * objects, and never grows with library size.
         */
        const val MAX_IN_MEMORY = 300

        /** SQLite's bind-variable limit is 999 on older Android; stay clear of it. */
        const val MAX_IDS_PER_QUERY = 400
    }
}
