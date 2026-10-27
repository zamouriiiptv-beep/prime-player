package com.castivio.data.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.castivio.data.database.entity.EpisodePlotEntity
import com.castivio.data.database.entity.SeriesDetailEntity
import kotlinx.coroutines.flow.Flow

/** One episode's plot as the join reads it. */
data class EpisodePlotRow(
    @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "plot") val plot: String,
)

@Dao
interface SeriesDetailDao {

    @Query("SELECT * FROM series_detail WHERE series_id = :seriesId")
    fun details(seriesId: String): Flow<SeriesDetailEntity?>

    /**
     * The plots of one show's episodes: the rows of `episode_plot` that name a row of this show.
     * Joined on the primary key of `media`, over the show's few hundred rows at most.
     */
    @Query(
        "SELECT p.media_id AS media_id, p.plot AS plot FROM episode_plot p " +
            "INNER JOIN media m ON m.id = p.media_id WHERE m.series_id = :seriesId",
    )
    fun plots(seriesId: String): Flow<List<EpisodePlotRow>>

    /** `REPLACE`: a show asked for again takes the fresh reply, and its time of fetching with it. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: SeriesDetailEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPlots(entities: List<EpisodePlotEntity>)

    /** A provider that now says nothing of a show takes back what an earlier reply said. */
    @Query("DELETE FROM series_detail WHERE series_id = :seriesId")
    suspend fun clear(seriesId: String)
}
