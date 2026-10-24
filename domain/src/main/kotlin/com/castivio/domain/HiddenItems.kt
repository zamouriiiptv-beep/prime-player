package com.castivio.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the viewer can hide from the channel board: a whole bouquet, or one channel. */
enum class HiddenKind { BOUQUET, CHANNEL }

/**
 * What has been hidden while the app is open, from the moment the viewer asked -- see
 * [HiddenItems.recent]. A screen leaves these out at once; the store's own reads leave out everything,
 * this included, once it has been written.
 */
data class HiddenNow(val bouquets: Set<String> = emptySet(), val channels: Set<String> = emptySet()) {

    /** [ids] of [kind] added. */
    fun with(kind: HiddenKind, ids: Collection<String>): HiddenNow = when (kind) {
        HiddenKind.BOUQUET -> copy(bouquets = bouquets + ids)
        HiddenKind.CHANNEL -> copy(channels = channels + ids)
    }

    /** [ids] of [kind] taken back out: a write that failed. */
    fun without(kind: HiddenKind, ids: Collection<String>): HiddenNow = when (kind) {
        HiddenKind.BOUQUET -> copy(bouquets = bouquets - ids.toSet())
        HiddenKind.CHANNEL -> copy(channels = channels - ids.toSet())
    }

    /** Every one of [kind] taken out: they were all brought back. */
    fun cleared(kind: HiddenKind): HiddenNow = when (kind) {
        HiddenKind.BOUQUET -> copy(bouquets = emptySet())
        HiddenKind.CHANNEL -> copy(channels = emptySet())
    }

    /** A channel this hides: itself, or the bouquet it is in. */
    fun hidesChannel(id: String, groupId: String?): Boolean =
        id in channels || (groupId != null && groupId in bouquets)
}

/** Nothing hidden while the app is open: what an implementation with no [HiddenItems.recent] of its own says. */
private val NOTHING_RECENT: StateFlow<HiddenNow> = MutableStateFlow(HiddenNow())

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

    /**
     * What has been hidden while the app is open, **from the moment [hide] is called** -- before the
     * store has been written, and before any list has been read again. A screen leaves these out at
     * once, so a press is answered on the press; an item whose write fails comes back out of this, and
     * a restore takes its kind out. What was hidden before is left out by the store's reads already.
     */
    val recent: StateFlow<HiddenNow> get() = NOTHING_RECENT

    companion object {
        /** Nothing hidden and nothing to hide: for a screen that is not told about hiding. */
        val None: HiddenItems = object : HiddenItems {
            override fun count(kind: HiddenKind): Flow<Int> = MutableStateFlow(0)
            override suspend fun hide(kind: HiddenKind, ids: Collection<String>) = Unit
            override suspend fun restoreAll(kind: HiddenKind) = Unit
        }
    }
}
