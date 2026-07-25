package com.castivio.domain

/**
 * The write side of the catalogue.
 *
 * Import is the one operation in this app that touches hundreds of thousands of
 * rows, so the contract is shaped around the two things that decide whether a
 * Fire Stick survives it:
 *
 *  - **Batches, not lists.** [writeItems] receives a bounded window (~1,000
 *    rows) and must not retain it. Nothing in the pipeline ever holds the
 *    catalogue, which is why import memory is flat regardless of library size.
 *  - **Commit as you go.** [commit] is called after every batch so the UI —
 *    which observes the tables — shows content while the rest is still
 *    importing. Time-to-first-content is what a user feels; a single
 *    transaction around 400,000 rows would show nothing for 20 seconds.
 *
 * Implementations are blocking on purpose: SQLite is blocking, and pretending
 * otherwise with `suspend` would only hide which dispatcher the work belongs
 * on. The engine documents that it must run off the main thread.
 */
interface CatalogWriter {

    /**
     * Starts an import for [sourceId]. Implementations typically open a
     * transaction and switch to fast-import pragmas here.
     *
     * The [mode] is not a hint. An [ImportMode.APPEND] write that was treated as
     * a replacement would prune every row that is not in it — which, for a lazily
     * loaded season of one series, means deleting the entire library.
     */
    fun begin(sourceId: String, mode: ImportMode = ImportMode.REPLACE)

    /**
     * Groups discovered so far, first-seen order preserved. Called before the
     * items that reference them, so a group id is never a dangling reference.
     */
    fun writeGroups(groups: List<MediaGroup>)

    /** One batch of rows. Must not be retained after the call returns. */
    fun writeItems(items: List<CatalogItem>)

    /** Makes everything written so far visible to readers. */
    fun commit()

    /**
     * Import completed. Implementations restore normal pragmas, build indices
     * and prune rows that belong to an older generation of the same source.
     */
    fun finish(summary: ImportSummary)

    /** Import failed or was cancelled; roll back to the last [commit]. */
    fun abort(cause: Throwable?)
}

/** How a write relates to what is already stored. */
enum class ImportMode {
    /**
     * The source's catalogue as a whole. Rows are written under a new generation
     * and the previous one is dropped at the end, so a refresh removes what the
     * provider no longer lists without the library ever being empty.
     */
    REPLACE,

    /**
     * An addition to what is already there: a lazily loaded season, one category.
     * Nothing is pruned, and the search index is updated per row instead of
     * rebuilt — a full rebuild would be seconds of work to add twelve episodes.
     */
    APPEND,
}

/**
 * A catalogue row, flattened for bulk insert.
 *
 * This is deliberately not [MediaItem]. The sealed model is what features read;
 * this is what the importer writes — one shape, no polymorphism, so a batch
 * insert is a single prepared statement rather than a `when` per row.
 *
 * Series are stored as their episodes. There is no parent row and no in-memory
 * series index: the series list is a `GROUP BY series_id` over this table,
 * which costs nothing on the heap and stays correct while an import is still
 * running.
 */
data class CatalogItem(
    /** Stable across re-imports, so favourites and progress survive a refresh. */
    val id: String,
    val sourceId: String,
    val kind: MediaKind,
    val title: String,
    val streamUrl: String,
    val artworkUrl: String? = null,
    val groupId: String? = null,
    /** `tvg-id`, used to join the EPG. Null when the provider omits it. */
    val epgChannelId: String? = null,
    /**
     * The provider's own id for this row — an Xtream stream, series or episode id.
     *
     * Kept because some provider APIs are addressed by it and nothing else:
     * `get_short_epg` needs a stream id, and so does a catch-up URL. The row id is a
     * hash and deliberately not reversible, so without this column those calls are
     * impossible. Null for M3U, which has no such id.
     */
    val providerRef: String? = null,
    /** Position in the provider's own ordering — the default sort users expect. */
    val providerOrder: Int = 0,
    /** Null for live and radio; set for VOD. */
    val durationSeconds: Int? = null,
    val seriesId: String? = null,
    val seriesTitle: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
)

/** What an import actually did. Reported to the writer and to diagnostics. */
data class ImportSummary(
    val sourceId: String,
    val items: Int,
    val groups: Int,
    /** Entries the parser could not use — malformed lines are normal in the wild. */
    val skipped: Int,
    val byKind: Map<MediaKind, Int>,
    val durationMs: Long,
    val cancelled: Boolean = false,
) {
    fun count(kind: MediaKind): Int = byKind[kind] ?: 0
}
