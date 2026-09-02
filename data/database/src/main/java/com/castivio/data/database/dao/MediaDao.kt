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
 */
@Dao
interface MediaDao {

    // ------------------------------------------------------------ browse, paged

    @Query(
        """
        SELECT * FROM media WHERE kind = :kind
        ORDER BY provider_order, id
        """,
    )
    fun pageByProvider(kind: String): PagingSource<Int, MediaEntity>

    @Query(
        """
        SELECT * FROM media WHERE kind = :kind AND group_id = :groupId
        ORDER BY provider_order, id
        """,
    )
    fun pageByProviderInGroup(kind: String, groupId: String): PagingSource<Int, MediaEntity>

    @Query(
        """
        SELECT * FROM media WHERE kind = :kind
        ORDER BY sort_title, id
        """,
    )
    fun pageByNameAsc(kind: String): PagingSource<Int, MediaEntity>

    @Query(
        """
        SELECT * FROM media WHERE kind = :kind AND group_id = :groupId
        ORDER BY sort_title, id
        """,
    )
    fun pageByNameAscInGroup(kind: String, groupId: String): PagingSource<Int, MediaEntity>

    @Query(
        """
        SELECT * FROM media WHERE kind = :kind
        ORDER BY sort_title DESC, id
        """,
    )
    fun pageByNameDesc(kind: String): PagingSource<Int, MediaEntity>

    @Query(
        """
        SELECT * FROM media WHERE kind = :kind AND group_id = :groupId
        ORDER BY sort_title DESC, id
        """,
    )
    fun pageByNameDescInGroup(kind: String, groupId: String): PagingSource<Int, MediaEntity>

    @Query(
        """
        SELECT * FROM media WHERE kind = :kind
        ORDER BY added_at DESC, provider_order, id
        """,
    )
    fun pageByRecent(kind: String): PagingSource<Int, MediaEntity>

    @Query(
        """
        SELECT * FROM media WHERE kind = :kind AND group_id = :groupId
        ORDER BY added_at DESC, provider_order, id
        """,
    )
    fun pageByRecentInGroup(kind: String, groupId: String): PagingSource<Int, MediaEntity>

    // ------------------------------------------------------- bounded direct reads

    @Query(
        """
        SELECT * FROM media WHERE kind = :kind
        ORDER BY provider_order, id LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun window(kind: String, offset: Int, limit: Int): List<MediaEntity>

    @Query(
        """
        SELECT * FROM media WHERE kind = :kind AND group_id = :groupId
        ORDER BY provider_order, id LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun windowInGroup(kind: String, groupId: String, offset: Int, limit: Int): List<MediaEntity>

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

    @Query("SELECT COUNT(*) FROM media WHERE kind = :kind")
    fun countOf(kind: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM media WHERE kind = :kind AND group_id = :groupId")
    fun countOfGroup(kind: String, groupId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM media WHERE kind = :kind")
    suspend fun countNow(kind: String): Int

    @Query("SELECT COUNT(*) FROM media WHERE kind = :kind AND group_id = :groupId")
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
        WHERE f.search_text MATCH :match
        ORDER BY m.kind, m.provider_order
        LIMIT :limit
        """,
    )
    suspend fun search(match: String, limit: Int): List<MediaEntity>

    @Query(
        """
        SELECT m.* FROM media_fts f
        INNER JOIN media m ON m.id = f.media_id
        WHERE f.search_text MATCH :match AND m.kind = :kind
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
        WHERE kind = 'SERIES' AND series_id IS NOT NULL
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
        WHERE kind = 'SERIES' AND series_id IS NOT NULL AND group_id = :groupId
        GROUP BY series_id
        ORDER BY providerOrder, seriesId
        """,
    )
    fun pageSeriesInGroup(groupId: String): PagingSource<Int, SeriesRow>

    @Query(
        """
        SELECT * FROM media WHERE series_id = :seriesId AND episode_number IS NOT NULL
        ORDER BY season_number, episode_number, id
        """,
    )
    fun episodesOf(seriesId: String): Flow<List<MediaEntity>>

    /**
     * Whether a show's episodes are already stored.
     *
     * `EXISTS` rather than a count: the question is whether a request is needed, and
     * stopping at the first row answers it without scanning a long-running series.
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM media WHERE series_id = :seriesId AND episode_number IS NOT NULL
        )
        """,
    )
    suspend fun hasEpisodes(seriesId: String): Boolean

    /**
     * The show itself, as opposed to its episodes.
     *
     * A catalogue listing writes one shell row per show — no episode number, and the
     * provider's series id in `provider_ref`. That id is what `get_series_info` is
     * addressed by, so this is the row an episode fetch starts from.
     */
    @Query(
        """
        SELECT * FROM media WHERE series_id = :seriesId AND episode_number IS NULL
        ORDER BY provider_order LIMIT 1
        """,
    )
    suspend fun showShell(seriesId: String): MediaEntity?

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
