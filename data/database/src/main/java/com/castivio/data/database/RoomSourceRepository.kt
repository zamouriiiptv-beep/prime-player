package com.castivio.data.database

import com.castivio.data.database.dao.SourceDao
import com.castivio.data.database.entity.SourceEntity
import com.castivio.domain.ProviderSource
import com.castivio.domain.SourceKind
import com.castivio.domain.SourceRepository
import com.castivio.domain.SyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomSourceRepository(
    private val dao: SourceDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : SourceRepository {

    override fun sources(): Flow<List<ProviderSource>> =
        dao.all().map { rows -> rows.map { it.toDomain() } }

    override fun active(): Flow<ProviderSource?> = dao.active().map { it?.toDomain() }

    override suspend fun activeNow(): ProviderSource? = dao.activeNow()?.toDomain()

    override suspend fun get(id: String): ProviderSource? = dao.byId(id)?.toDomain()

    /**
     * Saves without clobbering sync state.
     *
     * Re-entering the same provider — after an expiry, or to fix a typo in a
     * password — must not reset `etag` and `last_import_at`, or the next launch
     * re-downloads a catalogue that is already on disk.
     */
    override suspend fun save(source: ProviderSource) {
        val existing = dao.byId(source.id)
        dao.upsert(
            source.toEntity(
                createdAt = existing?.createdAt ?: source.createdAtMs.takeIf { it > 0 } ?: clock(),
                sync = if (source.sync == SyncState()) existing?.syncState() ?: source.sync else source.sync,
            ),
        )
        if (source.isActive) dao.activate(source.id)
    }

    override suspend fun setActive(id: String) = dao.activate(id)

    override suspend fun recordCatalogueImport(id: String, sync: SyncState) {
        dao.recordImport(
            id = id,
            etag = sync.etag,
            lastModified = sync.lastModified,
            contentHash = sync.contentHash,
            importedAt = sync.lastImportAtMs ?: clock(),
            itemCount = sync.itemCount,
        )
    }

    override suspend fun recordEpgImport(id: String, atMs: Long) = dao.recordEpgImport(id, atMs)

    override suspend fun delete(id: String) = dao.delete(id)
}

private fun SourceEntity.syncState() = SyncState(
    etag = etag,
    lastModified = lastModified,
    contentHash = contentHash,
    lastImportAtMs = lastImportAt,
    lastEpgImportAtMs = lastEpgImportAt,
    itemCount = itemCount,
)

internal fun SourceEntity.toDomain() = ProviderSource(
    id = id,
    // An unknown kind reads as a playlist URL rather than crashing: a row written
    // by a newer version must not brick an older one.
    kind = runCatching { SourceKind.valueOf(kind) }.getOrDefault(SourceKind.M3U_URL),
    label = label,
    url = url,
    username = username,
    password = password,
    epgUrl = epgUrl,
    userAgent = userAgent,
    sync = syncState(),
    createdAtMs = createdAt,
    isActive = isActive,
)

internal fun ProviderSource.toEntity(createdAt: Long, sync: SyncState = this.sync) = SourceEntity(
    id = id,
    kind = kind.name,
    label = label,
    url = url,
    username = username,
    password = password,
    epgUrl = epgUrl,
    userAgent = userAgent,
    etag = sync.etag,
    lastModified = sync.lastModified,
    contentHash = sync.contentHash,
    lastImportAt = sync.lastImportAtMs,
    lastEpgImportAt = sync.lastEpgImportAtMs,
    itemCount = sync.itemCount,
    createdAt = createdAt,
    isActive = isActive,
)
