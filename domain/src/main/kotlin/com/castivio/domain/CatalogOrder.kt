package com.castivio.domain

/**
 * The viewer's own order for what a provider listed: a bouquet's channels, and a section's
 * bouquets.
 *
 * **It never changes the provider's order.** The provider's order is the catalogue's -- it is
 * how the playlist arrived, and a refresh writes it again -- and an arrangement is a second
 * thing kept beside it: user data, like a favourite, that survives a refresh and is read
 * instead of the provider's order only where the viewer has made one. A channel the provider
 * adds after a bouquet was arranged has no place in the arrangement and follows the others.
 *
 * ## Arranging is a session
 *
 * [begin] makes a draft of a bouquet's channels, and every [move] changes the draft; a list
 * read with [CatalogQuery.arranging] shows it as it is, so what the viewer sees is what is
 * stored. [save] makes the draft the order, [discard] throws it away -- and until one of them
 * is called the order that was saved is exactly as it was.
 *
 * Bouquets are a short list that is already held whole, so they are arranged where they are
 * held and written once: [saveBouquets].
 */
interface CatalogOrder {

    /** Start arranging [groupId]'s channels: a draft equal to the order on screen now. */
    suspend fun begin(groupId: String)

    /**
     * Move [channelId] one place up or down in the draft of [groupId].
     *
     * False when it did not move: it is already first, or last.
     */
    suspend fun move(groupId: String, channelId: String, up: Boolean): Boolean

    /** The draft becomes [groupId]'s order. */
    suspend fun save(groupId: String)

    /** The draft is dropped; the order that was saved, or the provider's, is what remains. */
    suspend fun discard(groupId: String)

    /** The order of [kind]'s bouquets, first to last. A bouquet left out follows the others. */
    suspend fun saveBouquets(kind: MediaKind, orderedIds: List<String>)

    /**
     * Throw away a draft no arrangement is using: one left by an app that closed, or a screen
     * that went away, before Save or Cancel. Saved orders are never touched.
     *
     * Asked for whenever a board is opened, and done once a process -- the first time, which is
     * before any arrangement can have begun in it -- and once more after [draftAbandoned]. Never
     * while an arrangement runs: [begin] clears every other draft itself and marks the sweep done.
     */
    suspend fun sweepDrafts()

    /**
     * An arrangement ended with neither Save nor Cancel: its screen went away. Nothing is written
     * now -- the screen is going -- and the next [sweepDrafts] throws the draft away.
     */
    fun draftAbandoned()
}

/**
 * These bouquets in the viewer's order: those they placed first, in the places they gave, then
 * the rest as they came.
 *
 * A bouquet nothing has placed -- one the provider added since -- keeps the provider's order
 * among the others like it, after every placed one. Stable, and the list is returned as it is
 * when nothing was placed at all.
 */
fun List<MediaGroup>.arrangedBy(positions: Map<String, Long>): List<MediaGroup> {
    if (positions.isEmpty()) return this
    val (placed, rest) = partition { it.id in positions }
    return placed.sortedBy { positions.getValue(it.id) } + rest
}
