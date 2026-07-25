package com.castivio.data.database

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.castivio.data.database.dao.GroupDao
import com.castivio.data.database.dao.MediaDao
import com.castivio.domain.CatalogPager
import com.castivio.domain.CatalogQuery
import com.castivio.domain.CatalogRepository
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
import com.castivio.data.database.dao.FavoriteDao
import com.castivio.data.database.dao.ProgressDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
) : CatalogRepository, CatalogPager {

    // ------------------------------------------------------------- CatalogRepository

    override fun groups(kind: MediaKind): Flow<List<MediaGroup>> =
        groupDao.groupsOf(kind.name).map { rows -> rows.map { it.toDomain() } }

    override suspend fun page(request: PageRequest): Page<MediaItem> {
        val kind = request.kind.name
        val rows = if (request.groupId == null) {
            mediaDao.window(kind, request.offset, request.limit)
        } else {
            mediaDao.windowInGroup(kind, request.groupId, request.offset, request.limit)
        }
        val total = if (request.groupId == null) {
            mediaDao.countNow(kind)
        } else {
            mediaDao.countNowInGroup(kind, request.groupId)
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

    suspend fun search(query: String, kind: MediaKind, limit: Int): List<MediaItem> {
        val match = FtsQuery.build(query) ?: return emptyList()
        return mediaDao.searchKind(match, kind.name, limit).map { it.toDomain() }
    }

    override fun count(kind: MediaKind, groupId: String?): Flow<Int> =
        if (groupId == null) mediaDao.countOf(kind.name) else mediaDao.countOfGroup(kind.name, groupId)

    // -------------------------------------------------------------------- CatalogPager

    override fun items(query: CatalogQuery): Flow<PagingData<MediaItem>> {
        val kind = query.kind.name
        val group = query.groupId
        return pager {
            when (query.sort) {
                SortOrder.PROVIDER ->
                    if (group == null) mediaDao.pageByProvider(kind)
                    else mediaDao.pageByProviderInGroup(kind, group)

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

    override fun series(query: CatalogQuery): Flow<PagingData<SeriesSummary>> {
        val group = query.groupId
        return pager {
            if (group == null) mediaDao.pageSeries() else mediaDao.pageSeriesInGroup(group)
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

    private fun <T : Any> pager(source: () -> androidx.paging.PagingSource<Int, T>) =
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
    }
}
