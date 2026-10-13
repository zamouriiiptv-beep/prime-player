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

    /**
     * Every name in use, for deciding what to call a subscription the user did not name.
     *
     * One column and not the rows: naming needs the labels and nothing else, and a
     * registration that read every source in full to look at one string each would be a
     * table scan hidden inside an insert.
     */
    @Query("SELECT label FROM source")
    suspend fun labels(): List<String>

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

    /**
     * A subscription and everything imported for it, in one transaction.
     *
     * Deleting the `source` row alone left its channels in the catalogue, where the
     * unscoped reads went on showing them under whichever subscription came next.
     * Every statement here is keyed on this one id, so no other subscription's rows can
     * be reached. User data is left alone on purpose: a favourite or a watch position
     * points at a media id, ids are derived from the subscription, and the same one added
     * again finds them where they were.
     *
     * The search rows first, while `media` can still say which rows were this
     * subscription's: the search table is standalone and nothing empties it on its own.
     */
    @Transaction
    suspend fun deleteWithCatalogue(id: String) {
        deleteSearchRowsOf(id)
        deleteMediaOf(id)
        deleteGroupsOf(id)
        deleteProgrammesOf(id)
        delete(id)
    }

    @Query("DELETE FROM media_fts WHERE media_id IN (SELECT id FROM media WHERE source_id = :id)")
    suspend fun deleteSearchRowsOf(id: String)

    @Query("DELETE FROM media WHERE source_id = :id")
    suspend fun deleteMediaOf(id: String)

    @Query("DELETE FROM media_group WHERE source_id = :id")
    suspend fun deleteGroupsOf(id: String)

    @Query("DELETE FROM programme WHERE source_id = :id")
    suspend fun deleteProgrammesOf(id: String)
}
