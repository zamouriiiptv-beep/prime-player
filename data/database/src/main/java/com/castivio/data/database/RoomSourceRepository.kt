package com.castivio.data.database

import com.castivio.data.database.dao.SourceDao
import com.castivio.data.database.entity.SourceEntity
import com.castivio.data.parsing.SourceIds
import com.castivio.domain.PlaylistSource
import com.castivio.domain.ProviderSource
import com.castivio.domain.SourceKind
import com.castivio.domain.SourceRepository
import com.castivio.domain.SyncState
import com.castivio.domain.nextPlaylistName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * @param onActiveChanged told when another subscription becomes active, or the active one is deleted:
 *   every catalogue read finds the active subscription through `source`, and all of them read again --
 *   see `RoomCatalogRepository.activeChanged`. Before [clock], which stays last so a trailing lambda is its.
 */
class RoomSourceRepository(
    private val dao: SourceDao,
    private val onActiveChanged: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) : SourceRepository {

    /**
     * Every write through this repository, counted, so [sources] and [active] read again
     * after each.
     *
     * Room's notice was all that moved them, and on a device the screens did not move: a
     * subscription added from Home was saved and made active, and Home still said there was
     * none when the app was closed seconds later; a deleted one stayed in the list. Every
     * write to `source` at run time goes through this class, so this class says so -- as
     * `RoomCatalogRepository` does for the orders and the hidden, whose readers Room's notice
     * did not reach either. Room's notice, when it comes, reads once more what this already
     * read, and `distinctUntilChanged` lets the second reading through only if it differs.
     */
    private val writes = MutableStateFlow(0L)

    private fun changed() = writes.update { it + 1 }

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
     * **A name is now decided here, and it is deliberately not the host.** Leaving the
     * column blank kept the data honest and left the product without an answer: three
     * unnamed subscriptions are three blanks nobody can choose between, and a
     * placeholder each screen worked out from a row's position renumbered itself the
     * moment a row above it was deleted. So [nextPlaylistName] gives it the first
     * `Playlist n` no other subscription is using, once, and that is stored.
     *
     * The distinction the old comment was protecting — "the user named this" against
     * "we invented a name" — is not worth what it cost, because after this the two are
     * the same thing: a generated name is an ordinary name, shown like any other and
     * renamed from the subscriptions screen exactly as a typed one is.
     *
     * Still not the host. A name derived from the address is one that changes when the
     * address does, cannot be told from something the user typed, and puts part of a
     * server URL on every screen that lists subscriptions.
     *
     * Rows written before this change keep whatever label they were given, including
     * the blank ones, so nothing is rewritten and no migration is needed. What draws a
     * blank row is the screen's business, as it was.
     */
    override suspend fun register(source: PlaylistSource, label: String?): ProviderSource {
        val id = SourceIds.of(source)
        val existing = dao.byId(id)
        val named = label?.trim().orEmpty()
        val registered = ProviderSource(
            id = id,
            kind = SourceIds.kindOf(source),
            // Re-registering a subscription that already exists keeps the name it has:
            // the user may have renamed it since, and an import must not undo that.
            label = named.ifEmpty { existing?.label?.ifBlank { null } ?: nextPlaylistName(dao.labels()) },
            url = when (source) {
                is PlaylistSource.M3u -> source.url
                is PlaylistSource.LocalFile -> source.uri
                is PlaylistSource.Xtream -> source.host
                is PlaylistSource.Portal -> source.url
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

    /** Read again after every write -- see [writes]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun sources(): Flow<List<ProviderSource>> =
        writes.flatMapLatest { dao.all() }.map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged()

    /** Read again after every write -- see [writes]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun active(): Flow<ProviderSource?> =
        writes.flatMapLatest { dao.active() }.map { it?.toDomain() }.distinctUntilChanged()

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
        changed()
        if ((existing?.isActive ?: false) != source.isActive) onActiveChanged()
    }

    override suspend fun setActive(id: String) {
        val before = dao.activeNow()?.id
        dao.activate(id)
        changed()
        if (before != id) onActiveChanged()
    }

    override suspend fun recordCatalogueImport(id: String, sync: SyncState) {
        dao.recordImport(
            id = id,
            etag = sync.etag,
            lastModified = sync.lastModified,
            contentHash = sync.contentHash,
            importedAt = sync.lastImportAtMs ?: clock(),
            itemCount = sync.itemCount,
        )
        changed()
    }

    override suspend fun recordEpgImport(id: String, atMs: Long) {
        dao.recordEpgImport(id, atMs)
        changed()
    }

    /**
     * The subscription, and then its catalogue, never another's.
     *
     * The row goes first, alone, and the readers are told: from that moment it is on no
     * screen, because every catalogue read is scoped to the active subscription and one
     * without a row cannot be it. The catalogue then goes behind it, a step at a time -- see
     * [SourceDao.purgeStep] -- with what an earlier removal left when it was cut short. It
     * used to be one transaction that removed the row last, and the row stayed on screen
     * for as long as the device took to delete everything else.
     */
    override suspend fun delete(id: String) {
        val wasActive = dao.byId(id)?.isActive == true
        dao.delete(id)
        changed()
        if (wasActive) onActiveChanged()
        for (gone in (listOf(id) + dao.orphaned()).distinct()) {
            while (dao.purgeStep(gone, PURGE_STEP)) {
                // Each step is its own transaction; between two, any other write has the connection.
            }
        }
    }

    private companion object {
        /** Rows a purge step binds by id: clear of SQLite's 999 bind-variable limit on older Android. */
        const val PURGE_STEP = 400
    }
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
