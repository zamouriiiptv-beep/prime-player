package com.castivio.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.castivio.data.database.entity.GroupEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {

    /** Hundreds of rows at most, so the whole list is safe to observe. */
    @Query("SELECT * FROM media_group WHERE kind = :kind ORDER BY provider_order, id")
    fun groupsOf(kind: String): Flow<List<GroupEntity>>

    @Query("SELECT * FROM media_group WHERE kind = :kind ORDER BY provider_order, id")
    suspend fun groupsNow(kind: String): List<GroupEntity>

    @Query("SELECT * FROM media_group WHERE id = :id")
    suspend fun byId(id: String): GroupEntity?

    /**
     * Records that a category's rows have been fetched.
     *
     * One column, written after the rows are committed, so a fetch interrupted halfway
     * leaves the category looking unloaded and is retried rather than half-trusted.
     */
    @Query("UPDATE media_group SET items_loaded_at = :atMs WHERE id = :id")
    suspend fun markItemsLoaded(id: String, atMs: Long)

    /**
     * Fills in the denormalised counts once, at the end of an import.
     *
     * One correlated update over a few hundred groups, instead of a
     * `COUNT(*) GROUP BY` over 400,000 rows every time the category rail is
     * observed.
     */
    @Query(
        """
        UPDATE media_group SET item_count =
            (SELECT COUNT(*) FROM media WHERE media.group_id = media_group.id)
        WHERE source_id = :sourceId
        """,
    )
    suspend fun refreshCounts(sourceId: String)

    @Query("DELETE FROM media_group WHERE source_id = :sourceId AND generation != :generation")
    suspend fun deleteOtherGenerations(sourceId: String, generation: Long): Int

    @Query("DELETE FROM media_group WHERE source_id = :sourceId")
    suspend fun deleteSource(sourceId: String): Int

    @Query("SELECT MAX(generation) FROM media_group WHERE source_id = :sourceId")
    suspend fun currentGeneration(sourceId: String): Long?
}
