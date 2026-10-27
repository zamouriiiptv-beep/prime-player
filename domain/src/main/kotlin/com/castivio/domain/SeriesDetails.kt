package com.castivio.domain

import kotlinx.coroutines.flow.Flow

/**
 * What a provider tells about one show beyond its list row: the rating, the story, the people in it, the year,
 * and its wide picture. Every field is absent when the provider did not send it, and a page draws only what is
 * here. A provider that sent none of it leaves no [SeriesDetails] at all.
 *
 * Kept apart from the show's row on purpose: the wall holds hundreds of thousands of rows, and these arrive with
 * the episodes, when the show's page is opened.
 */
data class SeriesDetails(
    val rating: Double? = null,
    val plot: String? = null,
    val cast: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val releaseYear: Int? = null,
    /** The first of the provider's wide pictures of the show, drawn behind the page's top. */
    val backdropUrl: String? = null,
)

/**
 * The details of the shows whose pages have been opened, and the plot of each of their episodes.
 *
 * Read as flows, because the details arrive with the episodes, after the page is already up: the page draws what
 * the catalogue holds at once, and what the provider said joins it when it is written. Both are keyed as the
 * catalogue keys them -- a show by its series id, an episode by its row's id -- and neither is the viewer's own:
 * every row can be fetched again.
 */
interface SeriesDetailsStore {

    /** The show's details; null while there are none, and again if the provider sent none. */
    fun details(seriesId: String): Flow<SeriesDetails?>

    /** The plot of each of the show's episodes that has one, by the episode's row id. */
    fun episodePlots(seriesId: String): Flow<Map<String, String>>

    /** Keeps what the provider just said of the show: [details] when it said anything, and the episodes' [plots]. */
    suspend fun put(seriesId: String, details: SeriesDetails?, plots: Map<String, String>, now: Long)
}
