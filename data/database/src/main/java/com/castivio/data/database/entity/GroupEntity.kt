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

    /**
     * The provider's own category id, which is how its contents are asked for later.
     *
     * The row id above is a hash of the category name and is not reversible, so this
     * column is what makes it possible to list categories now and fetch one of them
     * when the user opens it. Without it the only way to have a category's contents
     * would be to have downloaded everything already.
     *
     * Null for M3U, where a group-title is all there is and there is no endpoint to
     * address with it.
     */
    @ColumnInfo(name = "provider_ref")
    val providerRef: String? = null,

    /**
     * When this category row itself was last written by a category listing.
     *
     * The pair with [itemsLoadedAt] is the whole of on-demand loading: this says the
     * category is known to exist, that one says its contents are here. A section
     * re-lists its categories when this goes stale, which is a cheap request; it
     * re-fetches a category's rows only when that one does.
     */
    @ColumnInfo(name = "listed_at")
    val listedAt: Long = 0,

    /**
     * When this category's rows were last fetched, or null if they never were.
     *
     * Separate from the category existing, because listing a category and loading it
     * are separate requests: after `get_live_categories` there are hundreds of rows
     * here and not one channel behind any of them.
     */
    @ColumnInfo(name = "items_loaded_at")
    val itemsLoadedAt: Long? = null,

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
