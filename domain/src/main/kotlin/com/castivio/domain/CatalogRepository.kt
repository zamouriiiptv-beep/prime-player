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

    /**
     * Provider and guide ids for specific rows.
     *
     * Bounded by the ids passed in — the visible window — because its caller is a
     * per-channel guide request, and asking for more channels than are on screen
     * would mean issuing requests nobody is waiting for.
     */
    suspend fun channelRefs(mediaIds: List<String>): List<ChannelRef>
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

    /**
     * A playlist the user picked from storage.
     *
     * Held as a URI string rather than a path: on modern Android the picker
     * returns a content URI that a path cannot be derived from.
     */
    data class LocalFile(val uri: String, val label: String? = null) : PlaylistSource

    data class Xtream(val host: String, val username: String, val password: String) : PlaylistSource

    /** Resolved from the activation portal by device MAC. */
    data class Portal(val mac: String, val deviceKey: String) : PlaylistSource
}

/**
 * Whether this provider can be read a section at a time.
 *
 * The distinction is the provider's, not a preference. An Xtream panel is addressable
 * by kind and by category — `get_live_categories`, then `get_live_streams` for the one
 * category the user opened — so there is never a reason to download a 400,000-row
 * catalogue to show somebody a list of five categories. An M3U playlist is a single
 * file with no index: the only way to know what is in it is to read it, so its import
 * happens when the provider is added and there is no later moment that would be
 * cheaper.
 *
 * Everything downstream keys off this: [com.castivio.domain.activation.ActivateProvider]
 * imports nothing at sign-in for an on-demand source, and [CatalogSections] is what
 * fills it in afterwards, one screen at a time.
 */
val PlaylistSource.isOnDemand: Boolean
    get() = this is PlaylistSource.Xtream

/**
 * Fetching one part of a provider's catalogue, at the moment the user asks for it.
 *
 * This is the contract that makes "sign in" cheap. Nothing here loads a catalogue;
 * every method is one screen's worth of data:
 *
 *  - [categories] is one request per kind. Opening Live TV fetches live categories and
 *    touches neither films nor series.
 *  - [items] is one request per category. Opening "UK · General" fetches that category
 *    and no other.
 *  - [episodes] is one request per show, and only when the show is opened.
 *
 * Each returns how many rows it wrote, so a caller can tell "the provider has nothing
 * here" from "the request failed" — two states that a bare success would flatten into
 * an empty screen.
 *
 * Results are written to the same tables the readers observe, so a section fetched once
 * is on the device: coming back to it is a database read and not a second request. The
 * implementation decides when a stored section is too old to trust; the screens never
 * ask twice on their own.
 */
interface CatalogSections {

    /** The categories of one kind. One request; does not touch the other kinds. */
    suspend fun categories(kind: MediaKind): com.castivio.core.common.Outcome<Int>

    /**
     * The rows of one category.
     *
     * @param groupId a group this app already stored — so the category has to have been
     *   listed by [categories] first, which is exactly the order the screens go in.
     */
    suspend fun items(groupId: String): com.castivio.core.common.Outcome<Int>

    /** One show's episodes, fetched when the show is opened and not before. */
    suspend fun episodes(seriesId: String): com.castivio.core.common.Outcome<Int>
}

/**
 * What [CatalogSections] needs to know before it fetches anything.
 *
 * Separate from [CatalogRepository] because it is the *loader's* view of the store,
 * not a screen's: one-shot reads and one write, all of them about whether a request is
 * needed at all. A screen has no business asking any of these.
 */
interface CatalogSectionStore {

    /**
     * The categories of one kind as they stand, without observing them.
     *
     * `Now` rather than sharing [CatalogRepository.groups]'s name: that one is a flow a
     * screen watches, this is a single answer a loader acts on, and reading one where
     * the other was meant is the kind of mistake a name should prevent.
     */
    suspend fun groupsNow(kind: MediaKind): List<MediaGroup>

    suspend fun group(id: String): MediaGroup?

    /** Records that a category's rows are on the device, so the next visit is free. */
    suspend fun markItemsLoaded(groupId: String, atMs: Long)

    /** True once a show has episodes stored, which is what makes opening it free. */
    suspend fun hasEpisodes(seriesId: String): Boolean

    /** The show row as the provider addresses it, or null if this app has never seen it. */
    suspend fun show(seriesId: String): ShowRef?
}

/**
 * A show, as much of it as fetching its episodes requires.
 *
 * [providerRef] is the provider's own series id, which `get_series_info` is addressed
 * by; the row id is a hash and cannot be turned back into one.
 */
data class ShowRef(val providerRef: String, val title: String, val groupId: String?)

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
