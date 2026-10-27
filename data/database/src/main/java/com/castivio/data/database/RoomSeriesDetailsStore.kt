package com.castivio.data.database

import com.castivio.data.database.dao.SeriesDetailDao
import com.castivio.data.database.entity.EpisodePlotEntity
import com.castivio.data.database.entity.SeriesDetailEntity
import com.castivio.domain.SeriesDetails
import com.castivio.domain.SeriesDetailsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The details of the shows whose pages were opened, kept in Room. */
class RoomSeriesDetailsStore(private val dao: SeriesDetailDao) : SeriesDetailsStore {

    override fun details(seriesId: String): Flow<SeriesDetails?> =
        dao.details(seriesId).map { it?.toDomain() }

    override fun episodePlots(seriesId: String): Flow<Map<String, String>> =
        dao.plots(seriesId).map { rows -> rows.associate { it.mediaId to it.plot } }

    override suspend fun put(seriesId: String, details: SeriesDetails?, plots: Map<String, String>, now: Long) {
        if (details == null) {
            dao.clear(seriesId)
        } else {
            dao.put(
                SeriesDetailEntity(
                    seriesId = seriesId,
                    rating = details.rating,
                    plot = details.plot,
                    castNames = details.cast.joinToString(DETAIL_LIST_SEPARATOR).ifEmpty { null },
                    genres = details.genres.joinToString(DETAIL_LIST_SEPARATOR).ifEmpty { null },
                    releaseYear = details.releaseYear,
                    backdropUrl = details.backdropUrl,
                    fetchedAt = now,
                ),
            )
        }
        if (plots.isNotEmpty()) dao.putPlots(plots.map { (id, plot) -> EpisodePlotEntity(id, plot) })
    }

    private fun SeriesDetailEntity.toDomain(): SeriesDetails = SeriesDetails(
        rating = rating,
        plot = plot,
        cast = castNames?.split(DETAIL_LIST_SEPARATOR).orEmpty(),
        genres = genres?.split(DETAIL_LIST_SEPARATOR).orEmpty(),
        releaseYear = releaseYear,
        backdropUrl = backdropUrl,
    )
}
