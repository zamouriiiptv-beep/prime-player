package com.castivio.data.database

import com.castivio.data.database.dao.SourceDao
import com.castivio.data.database.entity.SourceEntity
import com.castivio.data.parsing.SourceIds
import com.castivio.domain.PlaylistSource
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

    /**
     * Registers a provider and makes it the active one.
     *
     * The id comes from [SourceIds], so the same credentials entered twice — or
     * re-entered after a reinstall — resolve to the same source, and every catalogue
     * id built on top of it stays valid.
     *
     * ## Why an unnamed source is stored unnamed
     *
     * [label] is what the user typed, and null when they typed nothing. It used to be
     * replaced here with `SourceIds.labelOf(source)` — the host, or `host · username`
     * for Xtream — which made the column non-empty and made "the user named this" and
     * "we invented a name" indistinguishable from that moment on. A screen that wants
     * to offer its own placeholder could then only guess, and guessing by comparing
     * the stored label against a re-derived one is a second implementation of the
     * derivation that drifts the first time either changes.
     *
     * So the fact is kept rather than overwritten: no name is stored as no name, and a
     * *display* fallback belongs to whichever screen is drawing it. That is a
     * presentation decision — Home numbers them `Playlist 1`, `Playlist 2` — and
     * presentation decisions do not belong in a table.
     *
     * Rows written before this change keep whatever label they were given, so a
     * subscription the user did name still shows that name. Nothing is rewritten and
     * no migration is needed: the column was already `TEXT NOT NULL`, and empty is a
     * value it could always hold.
     */
    override suspend fun register(source: PlaylistSource, label: String?): ProviderSource {
        val id = SourceIds.of(source)
        val existing = dao.byId(id)
        val registered = ProviderSource(
            id = id,
            kind = SourceIds.kindOf(source),
            label = label.orEmpty(),
            url = when (source) {
                is PlaylistSource.M3u -> source.url
                is PlaylistSource.LocalFile -> source.uri
                is PlaylistSource.Xtream -> source.host
                is PlaylistSource.Portal -> null
            },
            username = (source as? PlaylistSource.Xtream)?.username,
            password = (source as? PlaylistSource.Xtream)?.password,
            userAgent = (source as? PlaylistSource.M3u)?.userAgent ?: existing?.userAgent,
            // Credentials may have changed; what the last import learned has not.
            sync = existing?.syncState() ?: SyncState(),
            createdAtMs = existing?.createdAt ?: clock(),
            isActive = true,
        )
        save(registered)
        return registered
    }

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
