package com.castivio.data.worldradio

import androidx.room.withTransaction
import com.castivio.core.common.PlayOutcome
import com.castivio.data.worldradio.dao.DirectoryCacheDao
import com.castivio.data.worldradio.dao.SavedStationDao
import com.castivio.data.worldradio.dao.StationHealthDao
import com.castivio.data.worldradio.entity.DirectoryCacheEntity
import com.castivio.data.worldradio.entity.SavedStationEntity
import com.castivio.data.worldradio.entity.StationHealthEntity
import com.castivio.domain.CachedBody
import com.castivio.domain.DirectoryCache
import com.castivio.domain.StationHealth
import com.castivio.domain.StationRecord
import com.castivio.domain.WorldRadioLibrary
import com.castivio.domain.WorldStation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The world radio's library in its own database: favourites, listens, and what listening said about each station.
 *
 * Every write that touches more than one row is one transaction, so a favourite is never half-saved; nothing here reads or writes a
 * table of the subscription's, because this database does not have one.
 */
class RoomWorldRadioLibrary(
    private val database: WorldRadioDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : WorldRadioLibrary {

    private val saved: SavedStationDao = database.savedStationDao()
    private val health: StationHealthDao = database.stationHealthDao()

    override fun favorites(): Flow<List<WorldStation>> = saved.favorites().map { rows -> rows.map { it.toDomain() } }

    override fun favoriteIds(): Flow<Set<String>> = saved.favoriteIds().map { it.toSet() }

    override fun recents(limit: Int): Flow<List<WorldStation>> = saved.recents(limit).map { rows -> rows.map { it.toDomain() } }

    override suspend fun setFavorite(station: WorldStation, favorite: Boolean) {
        database.withTransaction {
            val existing = saved.byUuid(station.uuid)
            if (favorite) {
                saved.put(station.toEntity(favoriteAt = existing?.favoriteAt ?: clock(), playedAt = existing?.playedAt))
            } else if (existing != null) {
                saved.clearFavorite(station.uuid)
                saved.dropUnused()
            }
        }
    }

    override suspend fun recordListened(station: WorldStation, nowMs: Long) {
        database.withTransaction {
            val existing = saved.byUuid(station.uuid)
            saved.put(station.toEntity(favoriteAt = existing?.favoriteAt, playedAt = nowMs))
            saved.trimListens(keep = KEEP_LISTENS)
            saved.forgetOldListensOfFavorites(keep = KEEP_LISTENS)
        }
    }

    override suspend fun records(uuids: Collection<String>): Map<String, StationRecord> {
        if (uuids.isEmpty()) return emptyMap()
        val found = HashMap<String, StationRecord>()
        for (chunk in uuids.distinct().chunked(CHUNK)) {
            for (row in health.of(chunk)) found[row.uuid] = row.toDomain()
        }
        return found
    }

    override suspend fun recordOutcome(uuid: String, outcome: PlayOutcome, nowMs: Long) {
        if (outcome == PlayOutcome.INCONCLUSIVE) return
        database.withTransaction {
            val before = health.byUuid(uuid)?.toDomain()
            val after = StationHealth.after(before, uuid, outcome, nowMs) ?: return@withTransaction
            health.put(after.toEntity())
        }
    }

    override suspend fun refreshSaved(station: WorldStation) {
        database.withTransaction {
            val existing = saved.byUuid(station.uuid) ?: return@withTransaction
            saved.put(station.toEntity(favoriteAt = existing.favoriteAt, playedAt = existing.playedAt))
        }
    }

    private companion object {
        /** How many listens are kept: more than a list on a television shows. */
        const val KEEP_LISTENS = 100

        /** Below SQLite's bind-variable limit. */
        const val CHUNK = 500
    }
}

/** The lists the directory sent, kept in the world radio's database. */
class RoomDirectoryCache(private val dao: DirectoryCacheDao) : DirectoryCache {

    override suspend fun read(key: String): CachedBody? = dao.byKey(key)?.let { CachedBody(it.body, it.fetchedAt) }

    override suspend fun write(key: String, body: String, fetchedAtMs: Long) {
        dao.put(DirectoryCacheEntity(key = key, body = body, fetchedAt = fetchedAtMs))
    }
}

internal fun SavedStationEntity.toDomain() = WorldStation(
    uuid = uuid,
    name = name,
    url = url,
    urlResolved = urlResolved,
    favicon = favicon,
    countryCode = countryCode,
    country = country,
    tags = tags.split(',').filter { it.isNotEmpty() },
    codec = codec,
    bitrate = bitrate,
    hls = hls,
    serverCheckedOk = checkedOk,
    serverCheckedAtMs = checkedAt,
    votes = votes,
    clickCount = clickCount,
    sslError = sslError,
)

internal fun WorldStation.toEntity(favoriteAt: Long?, playedAt: Long?) = SavedStationEntity(
    uuid = uuid,
    name = name,
    url = url,
    urlResolved = urlResolved,
    favicon = favicon,
    countryCode = countryCode,
    country = country,
    tags = tags.joinToString(","),
    codec = codec,
    bitrate = bitrate,
    hls = hls,
    votes = votes,
    clickCount = clickCount,
    checkedOk = serverCheckedOk,
    checkedAt = serverCheckedAtMs,
    sslError = sslError,
    favoriteAt = favoriteAt,
    playedAt = playedAt,
)

internal fun StationHealthEntity.toDomain() = StationRecord(
    uuid = uuid,
    lastOkAtMs = lastOkAt,
    lastFailedAtMs = lastFailedAt,
    consecutiveFailures = consecutiveFailures,
    unstableCount = unstableCount,
    unstableSinceMs = unstableSince,
)

internal fun StationRecord.toEntity() = StationHealthEntity(
    uuid = uuid,
    lastOkAt = lastOkAtMs,
    lastFailedAt = lastFailedAtMs,
    consecutiveFailures = consecutiveFailures,
    unstableCount = unstableCount,
    unstableSince = unstableSinceMs,
)
