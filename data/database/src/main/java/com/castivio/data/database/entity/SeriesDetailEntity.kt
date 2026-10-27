package com.castivio.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A show's details as its provider gave them with its episodes, kept so the page has them at once the next time.
 *
 * **A cache, like the catalogue, and not user data:** every row can be fetched again from the provider. The lists
 * are joined with a newline, which a name cannot contain, as `vod_detail`'s are.
 */
@Entity(tableName = "series_detail")
data class SeriesDetailEntity(
    @PrimaryKey
    @ColumnInfo(name = "series_id")
    val seriesId: String,

    @ColumnInfo(name = "rating")
    val rating: Double?,

    @ColumnInfo(name = "plot")
    val plot: String?,

    @ColumnInfo(name = "cast_names")
    val castNames: String?,

    @ColumnInfo(name = "genres")
    val genres: String?,

    @ColumnInfo(name = "release_year")
    val releaseYear: Int?,

    @ColumnInfo(name = "backdrop_url")
    val backdropUrl: String?,

    @ColumnInfo(name = "fetched_at")
    val fetchedAt: Long,
)

/** One episode's plot, by the id of the catalogue row the episode was written as. */
@Entity(tableName = "episode_plot")
data class EpisodePlotEntity(
    @PrimaryKey
    @ColumnInfo(name = "media_id")
    val mediaId: String,

    @ColumnInfo(name = "plot")
    val plot: String,
)
