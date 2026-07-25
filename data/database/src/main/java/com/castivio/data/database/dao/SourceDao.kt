package com.castivio.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.castivio.data.database.entity.SourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {

    @Query("SELECT * FROM source ORDER BY created_at")
    fun all(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM source WHERE is_active = 1 ORDER BY created_at LIMIT 1")
    fun active(): Flow<SourceEntity?>

    @Query("SELECT * FROM source WHERE is_active = 1 ORDER BY created_at LIMIT 1")
    suspend fun activeNow(): SourceEntity?

    @Query("SELECT * FROM source WHERE id = :id")
    suspend fun byId(id: String): SourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: SourceEntity)

    /**
     * Exactly one source is active.
     *
     * Done in one transaction because a moment with two active sources — or none —
     * is a moment where the app shows the wrong catalogue or an empty one.
     */
    @Transaction
    suspend fun activate(id: String) {
        clearActive()
        setActive(id)
    }

    @Query("UPDATE source SET is_active = 0 WHERE is_active = 1")
    suspend fun clearActive()

    @Query("UPDATE source SET is_active = 1 WHERE id = :id")
    suspend fun setActive(id: String)

    /** Records what a completed catalogue import learned about the provider. */
    @Query(
        """
        UPDATE source SET etag = :etag, last_modified = :lastModified, content_hash = :contentHash,
            last_import_at = :importedAt, item_count = :itemCount
        WHERE id = :id
        """,
    )
    suspend fun recordImport(
        id: String,
        etag: String?,
        lastModified: String?,
        contentHash: String?,
        importedAt: Long,
        itemCount: Int,
    )

    @Query("UPDATE source SET last_epg_import_at = :importedAt WHERE id = :id")
    suspend fun recordEpgImport(id: String, importedAt: Long)

    @Query("DELETE FROM source WHERE id = :id")
    suspend fun delete(id: String)
}
