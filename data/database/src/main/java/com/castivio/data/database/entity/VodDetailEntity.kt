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
)
