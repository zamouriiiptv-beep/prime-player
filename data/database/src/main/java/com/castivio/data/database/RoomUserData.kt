package com.castivio.data.database

import com.castivio.data.database.dao.FavoriteDao
import com.castivio.data.database.dao.ProgressDao
import com.castivio.data.database.entity.FavoriteEntity
import com.castivio.data.database.entity.ProgressEntity
import com.castivio.domain.FavoritesRepository
import com.castivio.domain.PlaybackProgress
import com.castivio.domain.ProgressRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomFavoritesRepository(
    private val dao: FavoriteDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : FavoritesRepository {

    override fun isFavorite(mediaId: String): Flow<Boolean> = dao.isFavorite(mediaId)

    override suspend fun toggle(mediaId: String): Boolean {
        val wasFavorite = dao.isFavoriteNow(mediaId)
        if (wasFavorite) dao.remove(mediaId) else dao.add(FavoriteEntity(mediaId, clock()))
        return !wasFavorite
    }

    override fun count(): Flow<Int> = dao.count()

    override fun ids(): Flow<Set<String>> = dao.ids().map { it.toSet() }

    override suspend fun set(mediaIds: Collection<String>, favorite: Boolean) {
        val distinct = mediaIds.distinct()
        if (distinct.isEmpty()) return
        val now = clock()
        distinct.chunked(MAX_IDS_PER_STATEMENT).forEach { chunk ->
            if (favorite) dao.addAll(chunk.map { FavoriteEntity(it, now) }) else dao.removeAll(chunk)
        }
    }

    private companion object {
        /** SQLite's bind-variable limit is 999 on older Android; stay clear of it. */
        const val MAX_IDS_PER_STATEMENT = 400
    }
}

class RoomProgressRepository(
    private val dao: ProgressDao,
    /**
     * Told after a channel was recorded as watched, so the list of recent channels is read again by the
     * write itself -- Room's notice did not reach the lists on a device; see `RoomCatalogRepository`.
     * Before [clock], so a test's trailing lambda is still the clock.
     */
    private val onWatched: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) : ProgressRepository {

    /**
     * Position 0 is not progress.
     *
     * The player reports a position on every tick, including the first one before
     * anything has played. Storing that would fill Continue Watching with rows
     * for channels the user tuned past in half a second.
     */
    override suspend fun save(mediaId: String, positionMs: Long, durationMs: Long?) {
        if (positionMs < MIN_POSITION_MS) return
        dao.upsert(
            ProgressEntity(
                mediaId = mediaId,
                positionMs = positionMs,
                durationMs = durationMs?.takeIf { it > 0 },
                updatedAt = clock(),
            ),
        )
    }

    override suspend fun progress(mediaId: String): PlaybackProgress? = dao.byId(mediaId)?.toDomain()

    override suspend fun watched(mediaId: String) {
        dao.watched(mediaId, clock())
        onWatched()
    }

    override suspend fun clear(mediaId: String) = dao.clear(mediaId)

    override suspend fun clearAll() = dao.clearAll()

    private companion object {
        /** Below ten seconds there is nothing worth resuming. */
        const val MIN_POSITION_MS = 10_000L
    }
}
