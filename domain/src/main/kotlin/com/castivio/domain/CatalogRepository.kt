package com.castivio.domain

import kotlinx.coroutines.flow.Flow

/**
 * The catalogue boundary.
 *
 * Note what is missing: there is no `getAllChannels(): List<Channel>`. At 100k
 * channels that call is an OOM on a low-end box, so the interface simply does
 * not offer it. Every read is either a bounded [Page] or a single item, which
 * makes the memory-safe path the only path a feature can take.
 */
interface CatalogRepository {

    /** Groups are small (hundreds), so this one list is safe to hold. */
    fun groups(kind: MediaKind): Flow<List<MediaGroup>>

    /** Paged read. Backed by SQLite; only the requested window is materialised. */
    suspend fun page(request: PageRequest): Page<MediaItem>

    suspend fun item(id: String): MediaItem?

    /** Full-text search over an FTS index — never a LIKE scan. */
    suspend fun search(query: String, limit: Int = 60): List<MediaItem>

    /** Row counts for headers, answered by SQL COUNT rather than by loading. */
    fun count(kind: MediaKind, groupId: String? = null): Flow<Int>
}

data class PageRequest(
    val kind: MediaKind,
    val groupId: String? = null,
    val offset: Int = 0,
    val limit: Int = DEFAULT_PAGE,
    val sort: SortOrder = SortOrder.PROVIDER,
) {
    init {
        require(limit in 1..MAX_PAGE) { "page limit $limit outside 1..$MAX_PAGE" }
    }

    companion object {
        const val DEFAULT_PAGE = 60
        /** A hard ceiling: no caller can accidentally ask for the whole library. */
        const val MAX_PAGE = 300
    }
}

data class Page<out T>(val items: List<T>, val offset: Int, val totalCount: Int) {
    val hasMore: Boolean get() = offset + items.size < totalCount
}

enum class SortOrder { PROVIDER, NAME_ASC, NAME_DESC, RECENTLY_ADDED }

/**
 * Importing a provider's catalogue.
 *
 * [import] emits progress as it goes because the UI shows content while the
 * import is still running — time-to-first-content is what users feel, not
 * time-to-complete.
 */
interface CatalogImporter {
    fun import(source: PlaylistSource): Flow<ImportProgress>

    /** True when the provider's content is unchanged and import can be skipped. */
    suspend fun isUpToDate(source: PlaylistSource): Boolean
}

sealed interface PlaylistSource {
    data class M3u(val url: String, val userAgent: String? = null) : PlaylistSource
    data class Xtream(val host: String, val username: String, val password: String) : PlaylistSource
    /** Resolved from the activation portal by device MAC. */
    data class Portal(val mac: String, val deviceKey: String) : PlaylistSource
}

sealed interface ImportProgress {
    data object CheckingForChanges : ImportProgress
    data object UpToDate : ImportProgress
    /** [groupsReady] lets the UI show finished groups while the rest imports. */
    data class Importing(
        val itemsImported: Int,
        val groupsReady: Int,
        val kind: MediaKind,
    ) : ImportProgress
    data class Done(val totalItems: Int, val durationMs: Long) : ImportProgress
    data class Failed(val error: com.castivio.core.common.AppError) : ImportProgress
}
