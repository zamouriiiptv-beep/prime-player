package com.castivio.data.database

import com.castivio.domain.MediaKind

/**
 * The names of the lists `custom_order` keeps -- see `CustomOrderEntity` for what each holds.
 *
 * Strings, and written in one place, because the repository that reads an order and the DAO
 * that writes it must mean the same list by them.
 */
internal object OrderScopes {

    /** A bouquet's channels, as saved. */
    fun channels(groupId: String) = "c:$groupId"

    /** A bouquet's channels while they are being arranged. */
    fun draft(groupId: String) = "c:$groupId#draft"

    /** A section's bouquets. */
    fun bouquets(kind: MediaKind) = "g:${kind.name}"
}
