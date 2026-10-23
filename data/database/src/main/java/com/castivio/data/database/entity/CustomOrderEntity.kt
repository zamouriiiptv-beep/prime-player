package com.castivio.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * One place in the viewer's own order: an item, where they put it.
 *
 * **User data, not catalogue.** The provider's order is the catalogue's -- `provider_order` on
 * `media` and `media_group` -- and nothing here writes it. This is what the viewer did to it:
 * rows that say "in this list, this goes here", read beside the provider's order and never
 * instead of it. A table of its own with no foreign keys, for the reason `favorite` is one: a
 * re-import replaces the catalogue's rows, and an arrangement that went with them would be
 * lost every time the provider was refreshed. It is kept, like favourites, when a subscription
 * is deleted, so adding the same one again finds its lists as they were left.
 *
 * ## Scopes
 *
 * A [scope] names one list, and [itemId] is a channel or a bouquet in it:
 *
 *  - `c:<bouquet id>`: the channels of one bouquet. Every channel the viewer has had on screen
 *    while arranging it has a row, so the saved order is a complete one, and a channel the
 *    provider adds afterwards has none and follows the others.
 *  - `c:<bouquet id>#draft`: the same list while it is being arranged. Moves are written here,
 *    one at a time, and the list on screen is read from here until the viewer saves -- which
 *    replaces the saved scope with it -- or cancels, which deletes it. The saved order is
 *    never touched before then.
 *  - `g:<kind>`: the bouquets of a section. Bouquet ids carry their subscription, so one scope
 *    holds every subscription's without them meeting.
 *
 * [position] is only ever compared with the others of its scope. Integers, so a move is an
 * exchange of two values and there is nothing to run out of.
 */
@Entity(
    tableName = "custom_order",
    primaryKeys = ["scope", "item_id"],
    indices = [
        // The neighbour of an item: "the nearest position below this one, in this list".
        Index(name = "idx_custom_order_position", value = ["scope", "position"]),
    ],
)
data class CustomOrderEntity(
    @ColumnInfo(name = "scope")
    val scope: String,

    @ColumnInfo(name = "item_id")
    val itemId: String,

    @ColumnInfo(name = "position")
    val position: Long,
)
