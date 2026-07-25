package com.castivio.data.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.castivio.data.database.entity.FavoriteEntity
import com.castivio.data.database.entity.MediaEntity
import com.castivio.data.database.entity.ProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(favorite: FavoriteEntity)

    @Query("DELETE FROM favorite WHERE media_id = :mediaId")
    suspend fun remove(mediaId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorite WHERE media_id = :mediaId)")
    fun isFavorite(mediaId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite WHERE media_id = :mediaId)")
    suspend fun isFavoriteNow(mediaId: String): Boolean

    @Query("SELECT COUNT(*) FROM favorite")
    fun count(): Flow<Int>

    /**
     * `INNER JOIN` on purpose: a favourite whose channel is gone from the current
     * playlist is kept in the table but not shown. It reappears by itself when
     * the provider restores the channel.
     */
    @Query(
        """
        SELECT m.* FROM favorite f
        INNER JOIN media m ON m.id = f.media_id
        ORDER BY f.added_at DESC, m.id
        """,
    )
    fun page(): PagingSource<Int, MediaEntity>
}

@Dao
interface ProgressDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: ProgressEntity)

    @Query("SELECT * FROM playback_progress WHERE media_id = :mediaId")
    suspend fun byId(mediaId: String): ProgressEntity?

    @Query("DELETE FROM playback_progress WHERE media_id = :mediaId")
    suspend fun clear(mediaId: String)

    @Query("DELETE FROM playback_progress")
    suspend fun clearAll()

    /**
     * Continue Watching: started, not finished.
     *
     * The 95% rule lives in [com.castivio.domain.PlaybackProgress]; it is
     * expressed here as integer arithmetic rather than a float ratio so SQLite
     * evaluates it exactly, and rows with an unknown duration (live, or a VOD the
     * provider gave no length for) are kept rather than guessed at.
     */
    @Query(
        """
        SELECT m.*, p.media_id AS p_media_id, p.position_ms AS p_position_ms,
               p.duration_ms AS p_duration_ms, p.updated_at AS p_updated_at
        FROM playback_progress p
        INNER JOIN media m ON m.id = p.media_id
        WHERE p.position_ms > 0
          AND (p.duration_ms IS NULL OR p.position_ms * 100 < p.duration_ms * 95)
        ORDER BY p.updated_at DESC, m.id
        """,
    )
    fun pageInProgress(): PagingSource<Int, MediaWithProgress>

    /** History keeps finished items too — that is the point of a history. */
    @Query(
        """
        SELECT m.*, p.media_id AS p_media_id, p.position_ms AS p_position_ms,
               p.duration_ms AS p_duration_ms, p.updated_at AS p_updated_at
        FROM playback_progress p
        INNER JOIN media m ON m.id = p.media_id
        ORDER BY p.updated_at DESC, m.id
        """,
    )
    fun pageHistory(): PagingSource<Int, MediaWithProgress>
}

/**
 * A media row and its progress in one read.
 *
 * Prefixed columns because `media_id` and `updated_at` would otherwise collide
 * with the media table's own names once more columns are added there.
 */
data class MediaWithProgress(
    @Embedded val media: MediaEntity,
    @Embedded(prefix = "p_") val progress: ProgressEntity,
)
