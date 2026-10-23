package com.castivio.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A bouquet or a channel the viewer has hidden.
 *
 * **User data, not catalogue**, and a table of its own with no foreign keys, for the reason `favorite`
 * and `custom_order` are: a re-import replaces the catalogue's rows, and a hidden item that went with
 * them would be back on screen every time the provider was refreshed. Nothing here deletes or edits the
 * catalogue -- the lists read around these rows, and restoring one is deleting its row.
 *
 * [itemId] is a bouquet's id or a channel's, which are the catalogue's own and cannot meet. A channel
 * is hidden if its own id is here or the id of the bouquet it is in is: see `NOT_HIDDEN`. [type] says
 * which it is, so Settings can count and restore the two apart.
 */
@Entity(tableName = "hidden_item")
data class HiddenEntity(
    @PrimaryKey
    @ColumnInfo(name = "item_id")
    val itemId: String,

    /** `BOUQUET` or `CHANNEL` -- a `HiddenKind`'s name. */
    @ColumnInfo(name = "type")
    val type: String,

    @ColumnInfo(name = "hidden_at")
    val hiddenAt: Long,
)
