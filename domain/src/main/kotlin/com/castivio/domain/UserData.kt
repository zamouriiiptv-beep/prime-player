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

    /** Removing an item from Continue Watching is a user action, not a side effect. */
    suspend fun clear(mediaId: String)

    suspend fun clearAll()
}
