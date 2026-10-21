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

    /**
     * The same search, restricted to one kind.
     *
     * Not a filter applied to the result of the unrestricted search: the limit is part
     * of the query, so searching everything and discarding the films afterwards returns
     * whatever handful of channels happened to be in the first sixty rows. The
     * restriction belongs in the index, and `media_fts` is already joined against
     * `media.kind` for exactly this.
     *
     * It exists because the Channels board's field says **Search channels** and has to
     * mean it: a viewer who types "sport" there is looking for a channel, and answering
     * with films is answering a question they did not ask.
     */
    suspend fun search(query: String, kind: MediaKind, limit: Int = 60): List<MediaItem>

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

    /**
     * One kind of the catalogue, for a screen that was just opened.
     *
     * Defaulted to [import] rather than left abstract: a source that can be read only
     * whole has nothing better to offer. An implementation that can do better overrides
     * it -- Xtream addresses each kind on its own endpoint, and a playlist is downloaded
     * once, kept on the device, and read one section at a time from the copy (see
     * [PlaylistCopies]).
     *
     * An implementation that does override it must not prune another section: this is
     * called once per section, and a write that replaced the whole catalogue would delete
     * the sections fetched before it. It appends, or replaces only [kind]. See
     * [ImportMode] and [CatalogWriter.begin].
     *
     * @param fresh the provider's latest is wanted -- a refresh -- rather than whatever of
     *   it this device already holds: a playlist's kept copy is read only when it is false.
     */
    fun importKind(source: PlaylistSource, kind: MediaKind, fresh: Boolean = false): Flow<ImportProgress> =
        import(source)

    /** True when the provider's content is unchanged and import can be skipped. */
    suspend fun isUpToDate(source: PlaylistSource): Boolean
}

sealed interface PlaylistSource {
    data class M3u(
        val url: String,
        val userAgent: String? = null,
        /**
         * The panel account this playlist is read through, when it is read through one.
         *
         * Set by [ProviderSource.asPlaylistSource] for everything that reads the catalogue,
         * so the section loader and the guide cannot read one subscription two ways; and by
         * [ProviderSource.asAccountSource] for the question about the account, which reads
         * no rows and so goes to the panel whenever the link names one. Null from the form:
         * what the user typed is a playlist and is saved as one, with its link and its id.
         * Only the reading changes, and nothing on screen says so.
         *
         * Not part of the identity: `SourceIds` keys an M3U on its link whichever way it is
         * read, so the rows, the favourites and the history stay with the subscription.
         */
        val xtream: Xtream? = null,
    ) : PlaylistSource

    /**
     * A playlist the user picked from storage.
     *
     * Held as a URI string rather than a path: on modern Android the picker
     * returns a content URI that a path cannot be derived from.
     */
    data class LocalFile(val uri: String, val label: String? = null) : PlaylistSource

    data class Xtream(val host: String, val username: String, val password: String) : PlaylistSource

    /**
     * A Stalker or Ministra portal, identified by the address the provider sent.
     *
     * ## One field, and it is the URL
     *
     * This used to be `(mac, deviceKey)`, from a time when a portal was going to be
     * resolved through Castivio's own activation service rather than talked to
     * directly. Nothing ever constructed one — the validator and the importer both
     * answered it with `NOT_FOUND`, and the repository stored `url = null` for it — so
     * the shape was a placeholder for a feature rather than a description of one.
     *
     * A portal *is* its address, in the same way an Xtream account is its host and a
     * playlist is its link: it is what the user was given, what they re-enter on a new
     * box, and the only thing that tells two of them apart. So the URL is the field,
     * and [com.castivio.data.parsing.SourceIds] keys the subscription on it.
     *
     * ## The MAC is not here, and is not asked for
     *
     * A portal handshake does carry an STB MAC, and Castivio has one already: the
     * device address on the activation screen, which is what a user registers with
     * their provider. It belongs to the device rather than to the subscription — the
     * same portal on two boxes is two MACs and one address — so it is supplied by the
     * identity at the moment of the call, not stored in the source.
     */
    data class Portal(val url: String) : PlaylistSource
}

sealed interface ImportProgress {
    data object CheckingForChanges : ImportProgress
    data object UpToDate : ImportProgress
    /** [groupsReady] lets the UI show finished groups while the rest imports. */
    data class Importing(
        val itemsImported: Int,
        /**
         * How many categories this source has, in total.
         *
         * Known from the first event onward: the Xtream engine writes every group before
         * a single worker starts, so this is the denominator rather than a running
         * count. [categoriesDone] is the numerator.
         */
        val groupsReady: Int,
        val kind: MediaKind,
        /**
         * Categories that have finished — succeeded or failed — out of [groupsReady].
         *
         * The pair is what a determinate progress bar needs, and what the loading screen
         * shows as `120 / 834`.
         *
         * **Null, not zero, for an importer that cannot say.** An M3U playlist is a
         * single file: its groups are discovered as the parser walks it, and not one of
         * them is ever "finished" before the whole file is. Reporting zero there would be
         * indistinguishable from an Xtream import that has not finished its first
         * category yet, and the loading screen would draw a bar that never moved for the
         * entire download. Null says *ask a different question*, and the screen does —
         * it counts rows instead.
         */
        val categoriesDone: Int? = null,
        /**
         * Bytes the provider has actually sent for this import so far.
         *
         * Filled in by the layer that owns the HTTP counters, not by the parser — see
         * `DefaultCatalogImporter`. Zero where nothing counts them.
         */
        val bytes: Long = 0,
        /**
         * Categories that are complete, counted and committed with rows in them: the ones a
         * screen opened now lists. Rising, and zero until the first category is complete.
         *
         * Not [categoriesDone], which counts a category that failed or turned out empty as
         * finished too, and which the importers that read a whole section in one stream
         * cannot answer at all. Every importer that writes categories answers this one: a
         * category read on its own is complete when its request ends, and one inside a
         * stream when the stream moves on to the next -- see [CatalogWriter.countGroups].
         */
        val categoriesListed: Int = 0,
    ) : ImportProgress
    data class Done(val totalItems: Int, val durationMs: Long) : ImportProgress

    /**
     * @param diagnosis how far the download got, from an importer that can say. Null from
     *   one that cannot, and the screen then has only [error]'s sentence to give.
     */
    data class Failed(
        val error: com.castivio.core.common.AppError,
        val diagnosis: FetchDiagnosis? = null,
    ) : ImportProgress
}
