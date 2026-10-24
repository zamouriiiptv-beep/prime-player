package com.castivio.domain

import kotlinx.coroutines.flow.Flow

/**
 * Favourites.
 *
 * Stored by media id and independent of the catalogue: a provider that drops a
 * channel for a week must not silently erase the user's list. When the channel
 * returns under the same id, the favourite is still there.
 */
interface FavoritesRepository {
    fun isFavorite(mediaId: String): Flow<Boolean>

    /** @return the new state, so a remote button can toggle without re-reading. */
    suspend fun toggle(mediaId: String): Boolean

    fun count(): Flow<Int>

    /**
     * Every favourite's id, as the list changes -- the viewer's own handful of channels, held whole
     * so a row asks whether it is one by a set lookup and not by a query. Not the catalogue: this is
     * only ever as long as what somebody chose to mark.
     */
    fun ids(): Flow<Set<String>>

    /**
     * Marks every one of [mediaIds] as a favourite, or none of them, in one act.
     *
     * Idempotent: a channel that already is one stays as it was, with the time it was marked, and
     * one that is not is not made to be. The ids are the catalogue's own, so the list survives a
     * refresh of the subscription -- see the note on [FavoritesRepository].
     */
    suspend fun set(mediaIds: Collection<String>, favorite: Boolean)
}

/**
 * Watch positions.
 *
 * Written while playing, so the write path has to be cheap: one row, one upsert,
 * off the main thread, and never more often than the player's progress tick.
 */
interface ProgressRepository {
    suspend fun save(mediaId: String, positionMs: Long, durationMs: Long?)

    suspend fun progress(mediaId: String): PlaybackProgress?

    /**
     * A live channel was watched: its picture showed, now. It goes to the top of the channels the rail's
     * Recently watched entry lists, and a channel watched before only moves there. Not a position -- a
     * live channel has none to resume -- so it is never in Continue Watching.
     */
    suspend fun watched(mediaId: String)

    /** Removing an item from Continue Watching is a user action, not a side effect. */
    suspend fun clear(mediaId: String)

    suspend fun clearAll()
}
