package com.castivio.data.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import com.castivio.data.database.entity.MediaEntity
import kotlinx.coroutines.flow.Flow

/**
 * Reads over the catalogue.
 *
 * Every browse query is written twice — with and without a group filter —
 * rather than once with `(:groupId IS NULL OR group_id = :groupId)`. That
 * "clever" form defeats the index: SQLite cannot use
 * `idx_media_kind_group_order` when the group predicate is a runtime OR, so a
 * small category inside a 400,000 row library turns into a scan of the whole
 * kind. Eight explicit queries are worth an indexed lookup.
 *
 * Ordering always has `id` as the final tiebreak. Without it, rows with equal
 * `provider_order` can come back in a different order between pages and Paging
 * shows duplicates or gaps.
 *
 * Every browse, count, search and series read is scoped to the active subscription —
 * see [ACTIVE_SOURCE] — and the indices lead with `source_id` for that reason. The
 * reads by id are not: an id is already one subscription's, because it is derived from
 * the source id.
 */
@Dao
interface MediaDao {

    // ------------------------------------------------------------ browse, paged

    @Query(
        """
        SELECT * FROM media WHERE source_id = $ACTIVE_SOURCE AND $NOT_HIDDEN AND kind = :kind
        ORDER BY provider_order, id
        """,
    )
    fun pageByProvider(kind: String): PagingSource<Int, MediaEntity>

    @Query(
        """
        SELECT * FROM media WHERE source_id = $ACTIVE_SOURCE AND $NOT_HIDDEN AND kind = :kind AND group_id = :groupId
        ORDER BY provider_order, id
        """,
    )
    fun pageByProviderInGroup(kind: String, groupId: String): PagingSource<Int, MediaEntity>

    @Query(
        """
        SELECT * FROM media WHERE source_id = $ACTIVE_SOURCE AND $NOT_HIDDEN AND kind = :kind
        ORDER BY sort_title, id
        """,
    )
    fun pageByNameAsc(kind: String): PagingSource<Int, MediaEntity>

    @Query(
        """
        SELECT * FROM media WHERE source_id = $ACTIVE_SOURCE AND $NOT_HIDDEN AND kind = :kind AND group_id = :groupId
        ORDER BY sort_title, id
        """,
    )
    fun pageByNameAscInGroup(kind: String, groupId: String): PagingSource<Int, MediaEntity>

    @Query(
        """
        SELECT * FROM media WHERE source_id = $ACTIVE_SOURCE AND $NOT_HIDDEN AND kind = :kind
        ORDER BY sort_title DESC, id
        """,
    )
    fun pageByNameDesc(kind: String): PagingSource<Int, MediaEntity>

    @Query(
        """
        SELECT * FROM media WHERE source_id = $ACTIVE_SOURCE AND $NOT_HIDDEN AND kind = :kind AND group_id = :groupId
        ORDER BY sort_title DESC, id
        """,
    )
    fun pageByNameDescInGroup(kind: String, groupId: String): PagingSource<Int, MediaEntity>

    @Query(
        """
        SELECT * FROM media WHERE source_id = $ACTIVE_SOURCE AND $NOT_HIDDEN AND kind = :kind
        ORDER BY added_at DESC, provider_order, id
        """,
    )
    fun pageByRecent(kind: String): PagingSource<Int, MediaEntity>

    @Query(
        """
        SELECT * FROM media WHERE source_id = $ACTIVE_SOURCE AND $NOT_HIDDEN AND kind = :kind AND group_id = :groupId
        ORDER BY added_at DESC, provider_order, id
        """,
    )
    fun pageByRecentInGroup(kind: String, groupId: String): PagingSource<Int, MediaEntity>

    // ------------------------------------------------------- bounded direct reads

    @Query(
        """
        SELECT * FROM media WHERE source_id = $ACTIVE_SOURCE AND $NOT_HIDDEN AND kind = :kind
        ORDER BY provider_order, id LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun window(kind: String, offset: Int, limit: Int): List<MediaEntity>

    @Query(
        """
        SELECT * FROM media WHERE source_id = $ACTIVE_SOURCE AND $NOT_HIDDEN AND kind = :kind AND group_id = :groupId
        ORDER BY provider_order, id LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun windowInGroup(kind: String, groupId: String, offset: Int, limit: Int): List<MediaEntity>

    /**
     * One window of a bouquet's channels in the viewer's own order -- [scope] names whose: see
     * `CustomOrderEntity` -- and the provider's for any channel that order has no place for.
     *
     * Such a channel (one the provider added after the order was made) follows the others,
     * in the provider's order among themselves, which is what keeps a refresh from putting a
     * new arrival in the middle of a list the viewer set. The fallback is a constant far above
     * any place the order gives, so it cannot meet one.
     *
     * Read only for a bouquet that has an order -- the repository asks first -- because this
     * cannot use the index [windowInGroup] does, and sorts the bouquet for every window.
     */
    @Query(
        """
        SELECT m.* FROM media m
        LEFT JOIN custom_order o ON o.scope = :scope AND o.item_id = m.id
        WHERE m.source_id = $ACTIVE_SOURCE AND $NOT_HIDDEN_M AND m.kind = :kind AND m.group_id = :groupId
        ORDER BY COALESCE(o.position, 1000000000 + m.provider_order), m.id
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun windowArranged(kind: String, groupId: String, scope: String, offset: Int, limit: Int): List<MediaEntity>

    /**
     * One window of the rows whose name matches, in provider order -- the channel list's own
     * pages, narrowed by the index rather than by a scan. Joined by `media_id` for the reason
     * [search] is. The `id` tie-break is [window]'s, so two reads of one window agree.
     */
    @Query(
        """
        SELECT m.* FROM media_fts f
        INNER JOIN media m ON m.id = f.media_id
        WHERE f.search_text MATCH :match AND m.source_id = $ACTIVE_SOURCE AND $NOT_HIDDEN_M AND m.kind = :kind
        ORDER BY m.provider_order, m.id LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun windowMatching(match: String, kind: String, offset: Int, limit: Int): List<MediaEntity>

    @Query(
        """
        SELECT m.* FROM media_fts f
        INNER JOIN media m ON m.id = f.media_id
        WHERE f.search_text MATCH :match AND m.source_id = $ACTIVE_SOURCE AND $NOT_HIDDEN_M AND m.kind = :kind
          AND m.group_id = :groupId
        ORDER BY m.provider_order, m.id LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun windowMatchingInGroup(
        match: String,
        kind: String,
        groupId: String,
        offset: Int,
        limit: Int,
    ): List<MediaEntity>

    /**
     * One window of the channels the viewer has marked, in the provider's own order -- the list the
     * rail's Favourites entry opens. `INNER JOIN`, as `FavoriteDao.page` is: a favourite whose
     * channel is not in the active subscription's catalogue is kept in its table and not shown, and
     * is back when the provider restores it. The order is [window]'s, so a favourite carries the
     * number it has everywhere else and the channel after it is the one after it in the list.
     */
    @Query(
        """
        SELECT m.* FROM favorite f
        INNER JOIN media m ON m.id = f.media_id
        WHERE m.source_id = $ACTIVE_SOURCE AND $NOT_HIDDEN_M AND m.kind = :kind
        ORDER BY m.provider_order, m.id LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun windowFavorites(kind: String, offset: Int, limit: Int): List<MediaEntity>

    /**
     * One window of the channels the viewer has watched, the one watched last first -- the list the rail's
     * Recently watched entry opens. A channel's row in `playback_progress` is written each time its picture
     * shows (see `ProgressDao.watched`), so its `updated_at` is when it was last watched. `INNER JOIN` and the
     * active subscription's, as [windowFavorites] is; a channel the viewer hid is in no list of channels.
     */
    @Query(
        """
        SELECT m.* FROM playback_progress p
        INNER JOIN media m ON m.id = p.media_id
        WHERE m.source_id = $ACTIVE_SOURCE AND $NOT_HIDDEN_M AND m.kind = :kind
        ORDER BY p.updated_at DESC, m.id LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun windowRecent(kind: String, offset: Int, limit: Int): List<MediaEntity>

    // ------------------------------------------- where a channel stands in a list, and how long it is
    //
    // Each counts the rows that come before one in the order its window reads -- `provider_order`,
    // then `id`, as the windows above -- so a channel's place plus one is the next window to read.
    // Written beside them and not apart: change an order here and the count must change with it.

    @Query(
        """
        SELECT COUNT(*) FROM media WHERE source_id = $ACTIVE_SOURCE AND $NOT_HIDDEN AND kind = :kind
        AND (provider_order < :providerOrder OR (provider_order = :providerOrder AND id < :id))
        """,
    )
    suspend fun rankBefore(kind: String, providerOrder: Int, id: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM media WHERE source_id = $ACTIVE_SOURCE AND $NOT_HIDDEN AND kind = :kind AND group_id = :groupId
        AND (provider_order < :providerOrder OR (provider_order = :providerOrder AND id < :id))
        """,
    )
    suspend fun rankBeforeInGroup(kind: String, groupId: String, providerOrder: Int, id: String): Int

    /** [windowArranged]'s order: [place] is the place the viewer's order gives the channel, or the one after it. */
    @Query(
        """
        SELECT COUNT(*) FROM media m
        LEFT JOIN custom_order o ON o.scope = :scope AND o.item_id = m.id
        WHERE m.source_id = $ACTIVE_SOURCE AND $NOT_HIDDEN_M AND m.kind = :kind AND m.group_id = :groupId
        AND (COALESCE(o.position, 1000000000 + m.provider_order) < :place
             OR (COALESCE(o.position, 1000000000 + m.provider_order) = :place AND m.id < :id))
        """,
    )
    suspend fun rankBeforeArranged(kind: String, groupId: String, scope: String, place: Long, id: String): Int

    /** [windowFavorites]'s order. */
    @Query(
        """
        SELECT COUNT(*) FROM favorite f
        INNER JOIN media m ON m.id = f.media_id
        WHERE m.source_id = $ACTIVE_SOURCE AND $NOT_HIDDEN_M AND m.kind = :kind
        AND (m.provider_order < :providerOrder OR (m.provider_order = :providerOrder AND m.id < :id))
        """,
    )
    suspend fun rankBeforeFavorites(kind: String, providerOrder: Int, id: String): Int

    /** How long [windowFavorites]' list is. */
    @Query(
        """
        SELECT COUNT(*) FROM favorite f
        INNER JOIN media m ON m.id = f.media_id
        WHERE m.source_id = $ACTIVE_SOURCE AND $NOT_HIDDEN_M AND m.kind = :kind
        """,
    )
    suspend fun countNowFavorites(kind: String): Int

    /** How long [windowRecent]'s list is. */
    @Query(
        """
        SELECT COUNT(*) FROM playback_progress p
        INNER JOIN media m ON m.id = p.media_id
        WHERE m.source_id = $ACTIVE_SOURCE AND $NOT_HIDDEN_M AND m.kind = :kind
        """,
    )
    suspend fun countNowRecent(kind: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM media_fts f
        INNER JOIN media m ON m.id = f.media_id
        WHERE f.search_text MATCH :match AND m.source_id = $ACTIVE_SOURCE AND $NOT_HIDDEN_M AND m.kind = :kind
        AND (m.provider_order < :providerOrder OR (m.provider_order = :providerOrder AND m.id < :id))
        """,
    )
    suspend fun rankBeforeMatching(match: String, kind: String, providerOrder: Int, id: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM media_fts f
        INNER JOIN media m ON m.id = f.media_id
        WHERE f.search_text MATCH :match AND m.source_id = $ACTIVE_SOURCE AND $NOT_HIDDEN_M AND m.kind = :kind
        AND m.group_id = :groupId
        AND (m.provider_order < :providerOrder OR (m.provider_order = :providerOrder AND m.id < :id))
        """,
    )
    suspend fun rankBeforeMatchingInGroup(match: String, kind: String, groupId: String, providerOrder: Int, id: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM media_fts f
        INNER JOIN media m ON m.id = f.media_id
        WHERE f.search_text MATCH :match AND m.source_id = $ACTIVE_SOURCE AND $NOT_HIDDEN_M AND m.kind = :kind
        """,
    )
    suspend fun countMatching(match: String, kind: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM media_fts f
        INNER JOIN media m ON m.id = f.media_id
        WHERE f.search_text MATCH :match AND m.source_id = $ACTIVE_SOURCE AND $NOT_HIDDEN_M AND m.kind = :kind
        AND m.group_id = :groupId
        """,
    )
    suspend fun countMatchingInGroup(match: String, kind: String, groupId: String): Int

    @Query("SELECT * FROM media WHERE id = :id")
    suspend fun byId(id: String): MediaEntity?

    @Query("SELECT * FROM media WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<MediaEntity>

    /**
     * Provider and guide ids for specific rows.
     *
     * A projection rather than whole rows: its caller issues one network request per
     * result, so it wants three columns for twenty channels, not twenty full rows.
     */
    @Query(
        """
        SELECT id AS mediaId, provider_ref AS providerRef, epg_channel_id AS epgChannelId
        FROM media WHERE id IN (:ids)
        """,
    )
    suspend fun refsFor(ids: List<String>): List<ChannelRefRow>

    // -------------------------------------------------------------------- counts
    //
    // **What is left to the viewer, not what the provider filed.** A section's count is the indexed
    // `COUNT` less what the viewer hid -- see [HIDDEN_IN_KIND] -- so the figures Home and the board draw
    // are the channels they can open. The subtraction is read from the small table of hidden items, never
    // by asking each row of the catalogue whether it is hidden: that would turn an index-only count into a
    // visit to every row, on every commit of an import that is running behind the screen. Room observes
    // `hidden_item` through the subquery, so a count re-reads itself when something is hidden or restored.

    @Query("SELECT (SELECT COUNT(*) FROM media WHERE source_id = $ACTIVE_SOURCE AND kind = :kind) - $HIDDEN_IN_KIND")
    fun countOf(kind: String): Flow<Int>

    /** A bouquet that is hidden itself has nothing left to count. */
    @Query(
        """
        SELECT CASE WHEN EXISTS (SELECT 1 FROM hidden_item WHERE item_id = :groupId) THEN 0
        ELSE (SELECT COUNT(*) FROM media WHERE source_id = $ACTIVE_SOURCE AND kind = :kind AND group_id = :groupId)
             - $HIDDEN_IN_GROUP END
        """,
    )
    fun countOfGroup(kind: String, groupId: String): Flow<Int>

    @Query("SELECT (SELECT COUNT(*) FROM media WHERE source_id = $ACTIVE_SOURCE AND kind = :kind) - $HIDDEN_IN_KIND")
    suspend fun countNow(kind: String): Int

    @Query(
        """
        SELECT CASE WHEN EXISTS (SELECT 1 FROM hidden_item WHERE item_id = :groupId) THEN 0
        ELSE (SELECT COUNT(*) FROM media WHERE source_id = $ACTIVE_SOURCE AND kind = :kind AND group_id = :groupId)
             - $HIDDEN_IN_GROUP END
        """,
    )
    suspend fun countNowInGroup(kind: String, groupId: String): Int

    // -------------------------------------------------------------------- search

    /**
     * Full-text search, never `LIKE '%q%'` — that is a full scan of 400,000 rows
     * on a device with a slow CPU and no index to help it.
     *
     * The join is by `media_id` rather than rowid because the FTS table is
     * standalone: see [com.castivio.data.database.entity.MediaFtsEntity] for why
     * an external-content table would cost a trigger program per inserted row.
     */
    @Query(
        """
        SELECT m.* FROM media_fts f
        INNER JOIN media m ON m.id = f.media_id
        WHERE f.search_text MATCH :match AND m.source_id = $ACTIVE_SOURCE AND $NOT_HIDDEN_M
        ORDER BY m.kind, m.provider_order
        LIMIT :limit
        """,
    )
    suspend fun search(match: String, limit: Int): List<MediaEntity>

    @Query(
        """
        SELECT m.* FROM media_fts f
        INNER JOIN media m ON m.id = f.media_id
        WHERE f.search_text MATCH :match AND m.source_id = $ACTIVE_SOURCE AND $NOT_HIDDEN_M AND m.kind = :kind
        ORDER BY m.provider_order
        LIMIT :limit
        """,
    )
    suspend fun searchKind(match: String, kind: String, limit: Int): List<MediaEntity>

    // -------------------------------------------------------------------- series

    /**
     * Shows, aggregated from episodes.
     *
     * `COUNT(episode_number)` rather than `COUNT(*)`: an Xtream import writes one
     * shell row per show before any episode is fetched, and that shell must read
     * as "no episodes loaded yet" rather than as a one-episode series.
     *
     * `artwork_url` and `series_title` are bare columns beside `min(...)`, which
     * in SQLite means "the value from the row that produced the minimum" — so the
     * poster is the first episode's, not an arbitrary one. Documented SQLite
     * behaviour for min/max aggregates, and the reason this needs no subquery.
     */
    @Query(
        """
        SELECT series_id AS seriesId,
               series_title AS title,
               artwork_url AS artworkUrl,
               COUNT(episode_number) AS episodeCount,
               COUNT(DISTINCT season_number) AS seasonCount,
               min(provider_order) AS providerOrder
        FROM media
        WHERE source_id = $ACTIVE_SOURCE AND kind = 'SERIES' AND series_id IS NOT NULL
        GROUP BY series_id
        ORDER BY providerOrder, seriesId
        """,
    )
    fun pageSeries(): PagingSource<Int, SeriesRow>

    @Query(
        """
        SELECT series_id AS seriesId,
               series_title AS title,
               artwork_url AS artworkUrl,
               COUNT(episode_number) AS episodeCount,
               COUNT(DISTINCT season_number) AS seasonCount,
               min(provider_order) AS providerOrder
        FROM media
        WHERE source_id = $ACTIVE_SOURCE AND kind = 'SERIES' AND series_id IS NOT NULL AND group_id = :groupId
        GROUP BY series_id
        ORDER BY providerOrder, seriesId
        """,
    )
    fun pageSeriesInGroup(groupId: String): PagingSource<Int, SeriesRow>

    /**
     * One window of the shows, narrowed by a search and in the order asked for -- read outside a transaction,
     * see `WindowPagingSource`.
     *
     * **A search is of the shows, found through their episodes.** The index carries an episode under its show's
     * name as well as its own, so a match on "breaking" is three episodes of one show; the show is what the list
     * draws, and its figures are the show's -- every episode it has, not the ones that matched -- which is why
     * the search is a `series_id IN (...)` over the whole show and not a filter of the rows being counted. With
     * no search, [match] is null and the subquery is never run: SQLite stops at the first true side of an `OR`.
     *
     * **The order is a parameter**, and a series list is the one place that is right: it is grouped, so there is
     * no index for an order by to use whichever way it is written, and the rows are shows -- hundreds, a few
     * thousand at the most -- and not the library. A name is the show's own row's sort title where the provider
     * wrote one (a shell) and its name where it did not -- see [SHOW_SORT_TITLE]; recently added is the newest of its
     * rows; and every order ends where the provider's does, so two reads of one window agree.
     */
    @Query(
        """
        SELECT series_id AS seriesId,
               series_title AS title,
               artwork_url AS artworkUrl,
               COUNT(episode_number) AS episodeCount,
               COUNT(DISTINCT season_number) AS seasonCount,
               min(provider_order) AS providerOrder
        FROM media
        WHERE source_id = $ACTIVE_SOURCE AND kind = 'SERIES' AND series_id IS NOT NULL
          AND (:match IS NULL OR series_id IN (
              SELECT m.series_id FROM media_fts f
              INNER JOIN media m ON m.id = f.media_id
              WHERE f.search_text MATCH :match AND m.source_id = $ACTIVE_SOURCE AND m.kind = 'SERIES'
                AND m.series_id IS NOT NULL))
        GROUP BY series_id
        ORDER BY CASE :sort WHEN 'NAME_ASC' THEN $SHOW_SORT_TITLE END,
                 CASE :sort WHEN 'NAME_DESC' THEN $SHOW_SORT_TITLE END DESC,
                 CASE :sort WHEN 'RECENTLY_ADDED' THEN max(added_at) END DESC,
                 providerOrder, seriesId
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun windowSeries(match: String?, sort: String, offset: Int, limit: Int): List<SeriesRow>

    /** [windowSeries] in one category. Written twice, as every browse query is -- see the note on this interface. */
    @Query(
        """
        SELECT series_id AS seriesId,
               series_title AS title,
               artwork_url AS artworkUrl,
               COUNT(episode_number) AS episodeCount,
               COUNT(DISTINCT season_number) AS seasonCount,
               min(provider_order) AS providerOrder
        FROM media
        WHERE source_id = $ACTIVE_SOURCE AND kind = 'SERIES' AND series_id IS NOT NULL AND group_id = :groupId
          AND (:match IS NULL OR series_id IN (
              SELECT m.series_id FROM media_fts f
              INNER JOIN media m ON m.id = f.media_id
              WHERE f.search_text MATCH :match AND m.source_id = $ACTIVE_SOURCE AND m.kind = 'SERIES'
                AND m.series_id IS NOT NULL))
        GROUP BY series_id
        ORDER BY CASE :sort WHEN 'NAME_ASC' THEN $SHOW_SORT_TITLE END,
                 CASE :sort WHEN 'NAME_DESC' THEN $SHOW_SORT_TITLE END DESC,
                 CASE :sort WHEN 'RECENTLY_ADDED' THEN max(added_at) END DESC,
                 providerOrder, seriesId
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun windowSeriesInGroup(groupId: String, match: String?, sort: String, offset: Int, limit: Int): List<SeriesRow>

    /**
     * One window of the shows the viewer has marked, in the provider's order -- the list the series rail's
     * Favourites entry opens. A show is marked by its own row, the one with no episode number, so it is that row
     * that joins `favorite`; the counts are the show's whole, as [windowSeries] gives, whatever the join matched.
     * `INNER JOIN`, as [windowFavorites] is: a favourite whose show is not in the active subscription's catalogue
     * is kept in its table and not shown, and is back when the provider restores it.
     */
    @Query(
        """
        SELECT m.series_id AS seriesId,
               COALESCE(m.series_title, m.title) AS title,
               m.artwork_url AS artworkUrl,
               (SELECT COUNT(e.episode_number) FROM media e WHERE e.series_id = m.series_id AND e.source_id = m.source_id) AS episodeCount,
               (SELECT COUNT(DISTINCT e.season_number) FROM media e WHERE e.series_id = m.series_id AND e.source_id = m.source_id) AS seasonCount,
               m.provider_order AS providerOrder
        FROM favorite f
        INNER JOIN media m ON m.id = f.media_id
        WHERE m.source_id = $ACTIVE_SOURCE AND m.kind = 'SERIES' AND m.series_id IS NOT NULL AND m.episode_number IS NULL
        ORDER BY m.provider_order, m.id
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun windowFavoriteSeries(offset: Int, limit: Int): List<SeriesRow>

    @Query(
        """
        SELECT * FROM media WHERE series_id = :seriesId AND episode_number IS NOT NULL
        ORDER BY season_number, episode_number, id
        """,
    )
    fun episodesOf(seriesId: String): Flow<List<MediaEntity>>

    /**
     * A show's own row -- the one the catalogue import wrote with no episode number -- which says which provider it
     * is from and by what id that provider knows it: what its episodes are asked for by.
     */
    @Query(
        """
        SELECT * FROM media
        WHERE series_id = :seriesId AND kind = 'SERIES' AND episode_number IS NULL
        LIMIT 1
        """,
    )
    suspend fun seriesShell(seriesId: String): MediaEntity?

    // --------------------------------------------------------------- maintenance

    /**
     * Drops the previous import's rows.
     *
     * This is what makes a re-import atomic without one enormous transaction:
     * the new generation is written and committed in batches, then the old one
     * disappears in a single statement. A reader is always looking at one
     * complete catalogue.
     */
    @Query("DELETE FROM media WHERE source_id = :sourceId AND generation != :generation")
    suspend fun deleteOtherGenerations(sourceId: String, generation: Long): Int

    @Query("DELETE FROM media WHERE source_id = :sourceId")
    suspend fun deleteSource(sourceId: String): Int

    @Query("SELECT MAX(generation) FROM media WHERE source_id = :sourceId")
    suspend fun currentGeneration(sourceId: String): Long?
}

/** Projection for a per-channel guide request. */
data class ChannelRefRow(
    val mediaId: String,
    val providerRef: String?,
    val epgChannelId: String?,
)

/** Projection for the series aggregation. Not an entity — there is no series table. */
data class SeriesRow(
    val seriesId: String,
    val title: String?,
    val artworkUrl: String?,
    val episodeCount: Int,
    val seasonCount: Int,
    val providerOrder: Int,
)
