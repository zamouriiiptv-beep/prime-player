package com.castivio.data.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.castivio.data.database.entity.CustomOrderEntity
import kotlinx.coroutines.flow.Flow

/** An item and its place, as a list's order is read back. */
data class PositionRow(
    @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "position") val position: Long,
)

/**
 * The viewer's own order -- see [CustomOrderEntity] for what a scope is.
 *
 * Nothing here touches `media` or `media_group` except to read them, so no statement in this
 * file can change the provider's order. Every read of a channel list scopes itself to the
 * active subscription through [ACTIVE_SOURCE], like the reads it sits beside.
 */
@Dao
interface OrderDao {

    @Query("SELECT EXISTS(SELECT 1 FROM custom_order WHERE scope = :scope)")
    suspend fun has(scope: String): Boolean

    /** The place [scope]'s order gives [itemId], or null when it gave it none. */
    @Query("SELECT position FROM custom_order WHERE scope = :scope AND item_id = :itemId")
    suspend fun positionOf(scope: String, itemId: String): Long?

    /** A bouquet list's saved order. Hundreds of rows at most: it is read whole, like the list. */
    @Query("SELECT item_id, position FROM custom_order WHERE scope = :scope")
    fun positions(scope: String): Flow<List<PositionRow>>

    @Query("DELETE FROM custom_order WHERE scope = :scope")
    suspend fun clear(scope: String)

    /**
     * Every draft, of every bouquet, and nothing else: no saved order is a draft scope, so none
     * is touched. What this clears is what an arrangement that never reached Save or Cancel left
     * behind -- the app closed, or the screen went away, half way through -- which nothing reads,
     * because a list reads a draft only while its session is running.
     */
    @Query("DELETE FROM custom_order WHERE scope GLOB 'c:*#draft'")
    suspend fun clearDrafts()

    // ------------------------------------------------------------------ the channels of one bouquet

    /**
     * Start arranging a bouquet: its draft is the order on screen now, complete.
     *
     * One arrangement runs at a time, so every draft there is -- this bouquet's from before, or
     * another's that was abandoned -- is cleared first, and the only draft afterwards is this one.
     *
     * One set-based statement, so no channel is read into memory: each channel of the bouquet
     * gets the place the saved order gives it, or -- one the provider added since, which has
     * none -- a place after every saved one, in the provider's order among themselves. Either
     * way no two share a place, which is what lets a move be an exchange. A bouquet never
     * arranged has no saved rows, and its draft is the provider's order.
     */
    @Transaction
    suspend fun begin(group: String, saved: String, draft: String) {
        clearDrafts()
        draftFrom(draft = draft, saved = saved, group = group, after = after(saved))
    }

    @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM custom_order WHERE scope = :scope")
    suspend fun after(scope: String): Long

    @Query(
        """
        INSERT INTO custom_order (scope, item_id, position)
        SELECT :draft, m.id, COALESCE(o.position, :after + m.provider_order)
        FROM media m
        LEFT JOIN custom_order o ON o.scope = :saved AND o.item_id = m.id
        WHERE m.source_id = $ACTIVE_SOURCE AND m.group_id = :group
        """,
    )
    suspend fun draftFrom(draft: String, saved: String, group: String, after: Long)

    /**
     * Move one channel one place, in the draft: exchange its place with its neighbour's.
     *
     * Atomic, and decided from the database rather than from what the screen last drew -- a
     * key pressed before the list has caught up with the last one still moves the channel one
     * place from where it is. A channel already first, or last, does not move: false.
     */
    @Transaction
    suspend fun move(group: String, draft: String, channelId: String, up: Boolean): Boolean {
        val here = positionOf(draft, channelId) ?: return false
        val neighbour = (if (up) before(draft, group, here) else behind(draft, group, here)) ?: return false
        place(draft, channelId, neighbour.position)
        place(draft, neighbour.itemId, here)
        return true
    }

    /**
     * Move several channels one place each, in the draft, **together**: the one nearest the edge they are
     * heading for first, and each exchanges its place with its neighbour's unless that neighbour is one
     * of the group -- so none of them passes another, the group keeps its order among itself, and it
     * bunches at an edge. With one channel this is [move].
     *
     * Atomic, and decided from the database like [move]: every exchange is of two places that exist, so no
     * two channels ever share one. False when none moved.
     */
    @Transaction
    suspend fun moveAll(group: String, draft: String, channelIds: List<String>, up: Boolean): Boolean {
        val members = channelIds.toSet()
        val placed = channelIds.mapNotNull { id -> positionOf(draft, id)?.let { id to it } }.sortedBy { it.second }
        var moved = false
        for ((id, _) in if (up) placed else placed.asReversed()) {
            val here = positionOf(draft, id) ?: continue
            val neighbour = (if (up) before(draft, group, here) else behind(draft, group, here)) ?: continue
            if (neighbour.itemId in members) continue
            place(draft, id, neighbour.position)
            place(draft, neighbour.itemId, here)
            moved = true
        }
        return moved
    }

    @Query("SELECT position FROM custom_order WHERE scope = :scope AND item_id = :id")
    suspend fun positionOf(scope: String, id: String): Long?

    /**
     * The nearest place below [position] in this list. Only rows of channels the bouquet still
     * has: a channel the provider took away is not a place anyone can see.
     */
    @Query(
        """
        SELECT o.item_id, o.position FROM custom_order o
        JOIN media m ON m.id = o.item_id AND m.source_id = $ACTIVE_SOURCE AND m.group_id = :group
        WHERE o.scope = :scope AND o.position < :position
        ORDER BY o.position DESC LIMIT 1
        """,
    )
    suspend fun before(scope: String, group: String, position: Long): PositionRow?

    @Query(
        """
        SELECT o.item_id, o.position FROM custom_order o
        JOIN media m ON m.id = o.item_id AND m.source_id = $ACTIVE_SOURCE AND m.group_id = :group
        WHERE o.scope = :scope AND o.position > :position
        ORDER BY o.position ASC LIMIT 1
        """,
    )
    suspend fun behind(scope: String, group: String, position: Long): PositionRow?

    @Query("UPDATE custom_order SET position = :position WHERE scope = :scope AND item_id = :id")
    suspend fun place(scope: String, id: String, position: Long)

    /** The draft becomes the saved order, and is gone. One transaction: never half of each. */
    @Transaction
    suspend fun save(saved: String, draft: String) {
        // Nothing to save is not an order to replace the saved one with.
        if (!has(draft)) return
        clear(saved)
        promote(saved = saved, draft = draft)
        clear(draft)
    }

    @Query("INSERT INTO custom_order (scope, item_id, position) SELECT :saved, item_id, position FROM custom_order WHERE scope = :draft")
    suspend fun promote(saved: String, draft: String)

    // ------------------------------------------------------------------ the bouquets of a section

    /**
     * Save the order of a section's bouquets, first to last.
     *
     * Replaces this subscription's rows of the scope and no other's -- [scope] holds every
     * subscription's bouquets, and a save must not reach another's. A bouquet left out of
     * [ids] has no row afterwards and follows the others.
     */
    @Transaction
    suspend fun saveBouquets(scope: String, kind: String, ids: List<String>) {
        forgetBouquets(scope, kind)
        ids.forEachIndexed { index, id -> put(CustomOrderEntity(scope, id, index.toLong())) }
    }

    @Query(
        """
        DELETE FROM custom_order WHERE scope = :scope AND item_id IN
        (SELECT id FROM media_group WHERE source_id = $ACTIVE_SOURCE AND kind = :kind)
        """,
    )
    suspend fun forgetBouquets(scope: String, kind: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: CustomOrderEntity)
}
