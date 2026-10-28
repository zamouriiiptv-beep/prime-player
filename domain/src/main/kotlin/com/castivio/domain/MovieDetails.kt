package com.castivio.domain

/**
 * What a provider tells about one film beyond its list row: the rating, the story, the people in it, when
 * it was added. Every field is absent when the provider did not send it, and a screen draws only what is here.
 *
 * Kept apart from [Movie] on purpose: the wall holds hundreds of thousands of rows, and these are asked for
 * one film at a time, when its page opens.
 */
data class MovieDetails(
    val rating: Double? = null,
    val plot: String? = null,
    val director: String? = null,
    val cast: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val releaseYear: Int? = null,
    val durationSeconds: Int? = null,
    val addedEpochSeconds: Long? = null,
)

/** The details a film's page has been given before, so opening it again asks the provider nothing. */
interface MovieDetailsCache {
    suspend fun get(sourceId: String, providerRef: String): MovieDetails?
    suspend fun put(sourceId: String, providerRef: String, details: MovieDetails, now: Long)
}

/**
 * The details of one film, from the cache or from its provider.
 *
 * Null when the provider gives none, cannot be reached, or the film has no provider id to ask by (an M3U
 * row). A null is never an error the page must show: the film plays and the page draws what it has.
 *
 * A provider that has not yet built a film's details answers empty the first time and fully a moment later,
 * so an empty answer is asked for again before it is believed, and [onTimes] is told what each asking cost.
 */
interface MovieDetailsRepository {
    suspend fun details(movie: Movie, onTimes: (RequestTimes) -> Unit = {}): MovieDetails?
}
