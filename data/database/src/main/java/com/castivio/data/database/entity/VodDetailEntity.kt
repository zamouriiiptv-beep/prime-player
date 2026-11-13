package com.castivio.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * A film's details as its provider gave them on request, kept so the page does not ask again.
 *
 * **A cache, like the catalogue, and not user data:** every row can be fetched again from the provider, so
 * nothing here is a viewer's own. The lists are joined with a newline, which a name cannot contain.
 */
@Entity(tableName = "vod_detail", primaryKeys = ["source_id", "provider_ref"])
data class VodDetailEntity(
    @ColumnInfo(name = "source_id")
    val sourceId: String,

    @ColumnInfo(name = "provider_ref")
    val providerRef: String,

    @ColumnInfo(name = "rating")
    val rating: Double?,

    @ColumnInfo(name = "plot")
    val plot: String?,

    @ColumnInfo(name = "director")
    val director: String?,

    @ColumnInfo(name = "cast_names")
    val castNames: String?,

    @ColumnInfo(name = "genres")
    val genres: String?,

    @ColumnInfo(name = "release_year")
    val releaseYear: Int?,

    @ColumnInfo(name = "duration_seconds")
    val durationSeconds: Int?,

    @ColumnInfo(name = "added_epoch")
    val addedEpochSeconds: Long?,

    @ColumnInfo(name = "fetched_at")
    val fetchedAt: Long,

    /**
     * The film's number at TMDB, as the provider's reply gave it; null when it gave none. Kept and not used yet: see
     * `MovieDetails.tmdbId`. Null for every row written before the column existed -- `CastivioMigrations.ADD_PROVIDER_IDS`.
     */
    @ColumnInfo(name = "tmdb_id")
    val tmdbId: Long? = null,

    /** The film's IMDb id, as the provider's reply gave it; null when it gave none. */
    @ColumnInfo(name = "imdb_id")
    val imdbId: String? = null,

    /**
     * Whether the reply these details came from was **read for the ids** above: false for every row kept before they were, and
     * for a row a portal's list wrote, true for one written from a reply read now.
     *
     * Without it a row kept earlier could not be told from a film whose provider gave no id, and -- the table having no expiry --
     * it would hold none for good; with it the film is asked of its provider once more, behind its page.
     */
    @ColumnInfo(name = "ids_read", defaultValue = "0")
    val idsRead: Boolean = false,
)
