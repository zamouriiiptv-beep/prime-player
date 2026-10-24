package com.castivio.data.database

import com.castivio.data.database.dao.VodDetailDao
import com.castivio.data.database.entity.VodDetailEntity
import com.castivio.domain.MovieDetails
import com.castivio.domain.MovieDetailsCache

/** A film's details kept in Room, one row per film of one source. */
class RoomMovieDetailsCache(private val dao: VodDetailDao) : MovieDetailsCache {

    override suspend fun get(sourceId: String, providerRef: String): MovieDetails? =
        dao.get(sourceId, providerRef)?.toDomain()

    override suspend fun put(sourceId: String, providerRef: String, details: MovieDetails, now: Long) {
        dao.put(
            VodDetailEntity(
                sourceId = sourceId,
                providerRef = providerRef,
                rating = details.rating,
                plot = details.plot,
                director = details.director,
                castNames = details.cast.joinToString(LIST_SEPARATOR).ifEmpty { null },
                genres = details.genres.joinToString(LIST_SEPARATOR).ifEmpty { null },
                releaseYear = details.releaseYear,
                durationSeconds = details.durationSeconds,
                addedEpochSeconds = details.addedEpochSeconds,
                fetchedAt = now,
            ),
        )
    }

    private fun VodDetailEntity.toDomain(): MovieDetails = MovieDetails(
        rating = rating,
        plot = plot,
        director = director,
        cast = castNames?.split(LIST_SEPARATOR).orEmpty(),
        genres = genres?.split(LIST_SEPARATOR).orEmpty(),
        releaseYear = releaseYear,
        durationSeconds = durationSeconds,
        addedEpochSeconds = addedEpochSeconds,
    )

    private companion object {
        /** A name cannot hold a newline, so one separates a list that is stored as a single column. */
        const val LIST_SEPARATOR = "\n"
    }
}
