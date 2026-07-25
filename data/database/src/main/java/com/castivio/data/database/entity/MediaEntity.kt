package com.castivio.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One catalogue row.
 *
 * The indices are the design. At 400,000 rows every screen in the app is
 * `WHERE kind = ? [AND group_id = ?] ORDER BY provider_order LIMIT 60`, and
 * without a covering index that is a full scan per page — the difference between
 * a list that appears instantly and one that stutters on every scroll.
 *
 * [generation] is how a re-import stays atomic without a giant transaction:
 * rows are written with a new generation, then the previous generation's rows
 * are deleted at the end. Readers always see a complete catalogue — the old one
 * or the new one, never half of each.
 */
@Entity(
    tableName = "media",
    indices = [
        // The default browse query, and the one used most.
        Index(name = "idx_media_kind_order", value = ["kind", "provider_order"]),
        // A category page.
        Index(name = "idx_media_kind_group_order", value = ["kind", "group_id", "provider_order"]),
        // Name sort, and the alphabet jump-bar.
        Index(name = "idx_media_kind_sort_title", value = ["kind", "sort_title"]),
        // The Series screen groups by this; season lists filter on it.
        Index(name = "idx_media_series", value = ["series_id", "season_number", "episode_number"]),
        // Pruning the previous import generation.
        Index(name = "idx_media_source_generation", value = ["source_id", "generation"]),
        // EPG joins.
        Index(name = "idx_media_epg", value = ["epg_channel_id"]),
    ],
)
data class MediaEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "source_id")
    val sourceId: String,

    /** Stored as the enum name: an ordinal would break if the enum is reordered. */
    @ColumnInfo(name = "kind")
    val kind: String,

    @ColumnInfo(name = "title")
    val title: String,

    /**
     * Lowercased title for sorting and prefix jumps.
     *
     * SQLite's `COLLATE NOCASE` only folds ASCII, so an Arabic or accented
     * catalogue would sort wrongly. Normalising once at import is also faster
     * than applying a function per row per query.
     */
    @ColumnInfo(name = "sort_title")
    val sortTitle: String,

    /**
     * What search matches against: the title and the show name, case-folded.
     *
     * Folded here rather than in SQL because SQLite's `lower()` only handles
     * ASCII. Without this, a Russian user typing `новости` would not find
     * `Новости` — the FTS tokenizer does not case-fold non-Latin scripts either,
     * so both sides have to be folded in Kotlin.
     */
    @ColumnInfo(name = "search_text")
    val searchText: String,

    @ColumnInfo(name = "stream_url")
    val streamUrl: String,

    @ColumnInfo(name = "artwork_url")
    val artworkUrl: String?,

    @ColumnInfo(name = "group_id")
    val groupId: String?,

    @ColumnInfo(name = "epg_channel_id")
    val epgChannelId: String?,

    /**
     * The provider's own id for this row.
     *
     * `get_short_epg` and catch-up URLs are addressed by it and by nothing else,
     * and [id] is a hash that cannot be reversed. Null for M3U rows.
     */
    @ColumnInfo(name = "provider_ref")
    val providerRef: String?,

    @ColumnInfo(name = "provider_order")
    val providerOrder: Int,

    @ColumnInfo(name = "duration_seconds")
    val durationSeconds: Int?,

    @ColumnInfo(name = "series_id")
    val seriesId: String?,

    @ColumnInfo(name = "series_title")
    val seriesTitle: String?,

    @ColumnInfo(name = "season_number")
    val seasonNumber: Int?,

    @ColumnInfo(name = "episode_number")
    val episodeNumber: Int?,

    @ColumnInfo(name = "generation")
    val generation: Long,

    @ColumnInfo(name = "added_at")
    val addedAt: Long,
)

/**
 * The search index.
 *
 * **FTS4, not FTS5** — FTS5 is not guaranteed on the SQLite that ships with
 * API 21, and this app runs on boxes that old.
 *
 * The default tokenizer is deliberate too. `unicode61` is not guaranteed to be
 * compiled in on every device's SQLite, and its main benefit is case folding for
 * non-ASCII scripts — which Arabic, the largest non-English audience here, does
 * not have. So the default tokenizer costs nothing real and works everywhere.
 *
 * Not an external-content table: Room would then maintain it with triggers that
 * fire per inserted row, and this app inserts 400,000 rows in a burst. The
 * importer writes both tables in the same batch instead, which is one prepared
 * statement rather than a trigger program per row.
 */
@Entity(tableName = "media_fts")
@Fts4(notIndexed = ["media_id"])
data class MediaFtsEntity(
    @ColumnInfo(name = "media_id")
    val mediaId: String,

    /**
     * The single indexed column: [MediaEntity.searchText], which already folds
     * case and includes the show name — so "breaking" finds an episode whose own
     * title is "Pilot".
     */
    @ColumnInfo(name = "search_text")
    val searchText: String,
)
