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
    /**
     * The film's number at TMDB, when the provider matched it to one: a positive whole number, or null when it sent none or
     * sent something that is not one. **Kept, and nothing is done with it yet** -- it is what a page told in another language
     * would ask by, and is read now so that the provider's coverage of it can be counted before anything is built on it.
     */
    val tmdbId: Long? = null,
    /** The film's IMDb id (`tt0133093`), when the provider sent one. Kept as [tmdbId] is. */
    val imdbId: String? = null,
    /**
     * Whether these details come from a reply that was **read for the ids** above. False only for details kept before they were:
     * such details hold none, and say nothing about whether the provider has one. True for every reply read now.
     *
     * A cache keeps what it kept for as long as the catalogue does, so the details of every film whose page was opened before the
     * ids were read would hold none for good. This is how that is told from a film whose provider gave none, and what makes the
     * film be asked of its provider once more -- behind its page, which is drawn from what is kept.
     */
    val idsRead: Boolean = true,
)

/** The details a film's page has been given before, so opening it again asks the provider nothing. */
interface MovieDetailsCache {
    suspend fun get(sourceId: String, providerRef: String): MovieDetails?

    /** Keeps [details], which are a reply read now: whatever [MovieDetails.idsRead] says of them, the cache keeps them as read. */
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
