package com.castivio.feature.home

import com.castivio.domain.MediaGroup
import com.castivio.domain.arrangedBy

/**
 * What the viewer has picked up in reorder mode.
 *
 * Picking up and putting down is the whole of the remote's part: a list the arrow keys are
 * meant to *move through* cannot also be a list they move *things* in, so a row is picked up
 * with OK, moved with the arrows while it is held, and put down with OK -- and until then the
 * arrows go where they always went.
 */
sealed interface Held {
    val id: String

    data class Bouquet(override val id: String) : Held

    data class Channel(override val id: String) : Held
}

/**
 * The board in reorder mode: what is being arranged, what is picked up, and where the bouquets
 * stand while they are being moved.
 *
 * A mode, and a visible one: the board is in it from the moment the viewer asks until they save
 * or cancel, and nothing moved in it is kept before the first or put back by the second -- see
 * `CatalogOrder`. Bouquets are a list held whole, so they are moved here, in memory, and written
 * once on [save][BrowseViewModel.save]; the channels of a bouquet are paged and are moved where
 * they are stored, as a draft.
 */
data class Arranging(
    /** The bouquets in the order being set, first to last, by id. What the rail lists while this lasts. */
    val bouquets: List<String>,
    /** Where they stood when the mode began. A save writes them only if they are not these. */
    val initial: List<String>,
    /**
     * The bouquet whose channels are being arranged -- the one that was open -- or null when "all"
     * was: that is the whole section, which has no order of its own, and its channels stay as
     * they are while the bouquets are moved.
     */
    val channelsOf: String?,
    val held: Held? = null,
    /**
     * A channel was moved. A save writes the bouquet's channels only if one was: an order is the
     * viewer's, and one they did not make is not stored in their name.
     */
    val channelsMoved: Boolean = false,
    /** Save is being written: nothing is pressed twice while it is. */
    val saving: Boolean = false,
    /** The last save did not reach the database. The mode stays open, and says so. */
    val failed: Boolean = false,
) {
    /** The bouquets have been moved from where they stood. */
    val bouquetsMoved: Boolean get() = bouquets != initial

    /**
     * Pressing a row that is already held puts it down; pressing another picks that one up
     * instead. One thing is held at a time.
     */
    fun pressed(row: Held): Arranging = copy(held = if (held == row) null else row)

    /** Put whatever is held down. */
    fun dropped(): Arranging = copy(held = null)

    /** The held bouquet one place up or down; anything else held, or nothing, is unchanged. */
    fun withHeldBouquetMoved(up: Boolean): Arranging {
        val bouquet = held as? Held.Bouquet ?: return this
        return copy(bouquets = bouquets.moved(bouquet.id, up))
    }
}

/**
 * [id] one place up or down in this list; the list is returned as it is when [id] is not in it,
 * or is already first, or last.
 */
internal fun List<String>.moved(id: String, up: Boolean): List<String> {
    val from = indexOf(id)
    val to = if (up) from - 1 else from + 1
    if (from < 0 || to !in indices) return this
    return toMutableList().apply {
        val neighbour = this[to]
        this[to] = this[from]
        this[from] = neighbour
    }
}

/**
 * These bouquets in the order of [ids]: the ones it names, as it names them, then any it does
 * not -- one that began to be listed after the mode began -- as they came.
 */
internal fun List<MediaGroup>.arrangedAs(ids: List<String>): List<MediaGroup> =
    arrangedBy(ids.withIndex().associate { (place, id) -> id to place.toLong() })
