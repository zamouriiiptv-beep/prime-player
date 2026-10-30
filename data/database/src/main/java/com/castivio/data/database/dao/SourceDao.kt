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

    /**
     * The subscription's own row, and nothing else: one primary-key lookup.
     *
     * This is what makes a subscription disappear. Every catalogue read is scoped to the
     * active subscription (see `ACTIVE_SOURCE`) and one without a row cannot be active, so
     * from this statement on nothing it imported is on any screen. What it imported is
     * removed after it, by [purgeStep].
     */
    @Query("DELETE FROM source WHERE id = :id")
    suspend fun delete(id: String)

    /** The oldest subscription, active or not: the one that takes over when the active one is deleted. */
    @Query("SELECT * FROM source ORDER BY created_at LIMIT 1")
    suspend fun firstNow(): SourceEntity?

    /**
     * [delete], and the active role handed to the oldest subscription left when the one deleted
     * had it, in the same transaction.
     *
     * A moment with no active subscription while others exist is a moment the app opens on the
     * activation screen, so there is never one: the row goes and its successor is active together.
     * Deleting one that was not active changes nothing about which is.
     */
    @Transaction
    suspend fun deleteHandingOver(id: String) {
        val wasActive = byId(id)?.isActive == true
        delete(id)
        if (wasActive) firstNow()?.let { activate(it.id) }
    }

    /**
     * Subscriptions whose row is gone and whose catalogue is not: the one just deleted, and
     * any whose removal was cut short -- the process ended between two [purgeStep]s.
     *
     * Read from the categories, a few hundred rows, and not from the catalogue: [purgeStep]
     * takes a subscription's categories last, so one with anything left still has them.
     */
    @Query("SELECT DISTINCT source_id FROM media_group WHERE source_id NOT IN (SELECT id FROM source)")
    suspend fun orphaned(): List<String>

    /**
     * One short step of removing what was imported for a deleted subscription: up to [limit]
     * of its catalogue rows with their search entries, or else up to [limit] programmes of
     * its guide, or else up to [limit] of its categories. False when nothing is left -- and
     * at once when the subscription has been added again, whose rows are its own from then
     * on and not this removal's to take.
     *
     * Every statement is keyed on this one id, so no other subscription's rows can be
     * reached. User data is left alone on purpose: a favourite or a watch position points at
     * a media id, ids are derived from the subscription, and the same one added again finds
     * them where they were.
     *
     * ## Why in steps, when it was one transaction
     *
     * The one transaction is what made a deletion slow. It held SQLite's one write
     * connection until the whole catalogue was gone, and every other write waited behind it,
     * Room's notices included. And it deleted the search entries by `media_id`, a column the
     * search table does not index, so it visited every entry of every subscription: a
     * subscription with nothing imported took as long as everything the device held.
     *
     * A step holds the connection for a few hundred rows and gives it back. The search
     * entries go by `docid`, which is their row's rowid (see `MediaFtsEntity`), one lookup
     * each, and in the same step as their rows, so a step cut short is rolled back whole and
     * never leaves an entry without its row.
     */
    @Transaction
    suspend fun purgeStep(id: String, limit: Int): Boolean {
        if (byId(id) != null) return false
        val rows = mediaRowsOf(id, limit)
        if (rows.isNotEmpty()) {
            deleteSearchRows(rows)
            deleteMediaRows(rows)
            return true
        }
        return deleteProgrammesOf(id, limit) > 0 || deleteGroupsOf(id, limit) > 0
    }

    @Query("SELECT rowid FROM media WHERE source_id = :id LIMIT :limit")
    suspend fun mediaRowsOf(id: String, limit: Int): List<Long>

    @Query("DELETE FROM media_fts WHERE docid IN (:rows)")
    suspend fun deleteSearchRows(rows: List<Long>)

    @Query("DELETE FROM media WHERE rowid IN (:rows)")
    suspend fun deleteMediaRows(rows: List<Long>)

    @Query("DELETE FROM programme WHERE rowid IN (SELECT rowid FROM programme WHERE source_id = :id LIMIT :limit)")
    suspend fun deleteProgrammesOf(id: String, limit: Int): Int

    @Query("DELETE FROM media_group WHERE rowid IN (SELECT rowid FROM media_group WHERE source_id = :id LIMIT :limit)")
    suspend fun deleteGroupsOf(id: String, limit: Int): Int
}
