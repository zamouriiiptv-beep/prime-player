package com.castivio.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.castivio.data.database.entity.GroupEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {

    /**
     * Hundreds of rows at most, so the whole list is safe to observe.
     *
     * The active subscription's categories only — see [ACTIVE_SOURCE].
     */
    @Query(
        "SELECT * FROM media_group WHERE source_id = $ACTIVE_SOURCE AND kind = :kind ORDER BY provider_order, id",
    )
    fun groupsOf(kind: String): Flow<List<GroupEntity>>

    @Query(
        "SELECT * FROM media_group WHERE source_id = $ACTIVE_SOURCE AND kind = :kind ORDER BY provider_order, id",
    )
    suspend fun groupsNow(kind: String): List<GroupEntity>

    @Query("SELECT * FROM media_group WHERE id = :id")
    suspend fun byId(id: String): GroupEntity?

    /**
     * Fills in the denormalised counts once, at the end of an import.
     *
     * One correlated update over a few hundred groups, instead of a
     * `COUNT(*) GROUP BY` over 400,000 rows every time the category rail is
     * observed. Each count names the subscription and the kind as well as the
     * category, so it is a range of `idx_media_source_kind_group_order` rather
     * than a scan of every row once per category -- the writer's own count, for
     * the reason `RoomCatalogWriter.COUNT_GROUPS` gives.
     */
    @Query(
        """
        UPDATE media_group SET item_count = (
            SELECT COUNT(*) FROM media
            WHERE media.source_id = media_group.source_id
              AND media.kind = media_group.kind
              AND media.group_id = media_group.id
        )
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
