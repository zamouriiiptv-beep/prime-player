package com.castivio.domain

import kotlinx.coroutines.flow.Flow

/** What the viewer can hide from the channel board: a whole bouquet, or one channel. */
enum class HiddenKind { BOUQUET, CHANNEL }

/**
 * Bouquets and channels the viewer has hidden, between launches.
 *
 * **Hiding is the viewer's, on this device, and the subscription is not told.** Nothing is deleted
 * from the catalogue and nothing is written to the provider: a hidden item is a row here that the
 * lists leave out, so restoring it is deleting that row and the item is where it always was -- in its
 * place in the provider's order, and in the viewer's own.
 *
 * ## Stored by id, and kept through a refresh
 *
 * The catalogue's own ids, the key the favourites and the viewer's orders use, and for the same
 * reason: a re-import replaces the catalogue's rows and keeps these, so what was hidden stays hidden
 * after the subscription is refreshed or the app restarts. A bouquet that is hidden hides its
 * channels with it, without a row for each; a channel can be hidden by itself inside a bouquet that
 * is not.
 */
interface HiddenItems {

    /** How many items of [kind] are hidden, as it changes. */
    fun count(kind: HiddenKind): Flow<Int>

    /**
     * Hides every one of [ids], which are [kind]s, in one act. Idempotent: an item that already is
     * hidden stays as it was.
     */
    suspend fun hide(kind: HiddenKind, ids: Collection<String>)

    /** Brings back every hidden item of [kind]. */
    suspend fun restoreAll(kind: HiddenKind)
}
