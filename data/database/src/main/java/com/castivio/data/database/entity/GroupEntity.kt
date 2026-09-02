package com.castivio.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A provider category. Hundreds of rows, so this one table is safe to read whole. */
@Entity(
    tableName = "media_group",
    indices = [
        Index(name = "idx_group_kind_order", value = ["kind", "provider_order"]),
        Index(name = "idx_group_source_generation", value = ["source_id", "generation"]),
    ],
)
data class GroupEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "source_id")
    val sourceId: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "kind")
    val kind: String,

    @ColumnInfo(name = "provider_order")
    val providerOrder: Int,

    /**
     * Denormalised on purpose. The category rail shows a count next to every
     * name; computing it with `COUNT(*) GROUP BY group_id` over 400,000 rows on
     * every observation is a visible stall on a weak box, so the importer writes
     * it once at the end instead.
     */
    @ColumnInfo(name = "item_count")
    val itemCount: Int = 0,

    @ColumnInfo(name = "generation")
    val generation: Long,
)

/**
 * A favourite.
 *
 * Deliberately *not* a foreign key onto `media`. A provider dropping a channel
 * for a week must not silently erase the user's favourite — when the channel
 * comes back under the same id, the favourite is still there. Orphans are
 * filtered by the join instead of deleted by a cascade.
 */
@Entity(tableName = "favorite")
data class FavoriteEntity(
    @PrimaryKey
    @ColumnInfo(name = "media_id")
    val mediaId: String,

    @ColumnInfo(name = "added_at")
    val addedAt: Long,
)

/** Watch position. Drives Continue Watching and History; same no-cascade reasoning. */
@Entity(
    tableName = "playback_progress",
    indices = [Index(name = "idx_progress_updated", value = ["updated_at"])],
)
data class ProgressEntity(
    @PrimaryKey
    @ColumnInfo(name = "media_id")
    val mediaId: String,

    @ColumnInfo(name = "position_ms")
    val positionMs: Long,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long?,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
