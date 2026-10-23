package com.castivio.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.castivio.data.database.entity.HiddenEntity
import kotlinx.coroutines.flow.Flow

/** How many channels of a bouquet the viewer has hidden one by one. */
data class HiddenInGroup(val groupId: String?, val hidden: Int)

@Dao
interface HiddenDao {

    /**
     * `IGNORE`: an item that already is hidden keeps the time it was hidden. A list insert is one
     * transaction, so a batch lands whole.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addAll(items: List<HiddenEntity>)

    @Query("DELETE FROM hidden_item WHERE type = :type")
    suspend fun clear(type: String)

    @Query("SELECT COUNT(*) FROM hidden_item WHERE type = :type")
    fun count(type: String): Flow<Int>

    /**
     * Per bouquet of the active subscription, the channels hidden by themselves -- what the rail takes
     * off a bouquet's stored count. A small answer: one row per bouquet that has any.
     */
    @Query(
        """
        SELECT m.group_id AS groupId, COUNT(*) AS hidden
        FROM hidden_item h
        INNER JOIN media m ON m.id = h.item_id
        WHERE h.type = 'CHANNEL' AND m.source_id = $ACTIVE_SOURCE
        GROUP BY m.group_id
        """,
    )
    fun channelsByGroup(): Flow<List<HiddenInGroup>>
}
