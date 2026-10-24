package com.castivio.feature.home

import com.castivio.domain.Movie

/**
 * What the films screen says about a film, from what the provider actually sent and nothing else.
 *
 * A playlist carries a name, a poster, a stream and -- for some films -- a running time. It carries no
 * rating, no synopsis and no cast, and the screens that draw a film are written so that a fact that is
 * not here is not drawn: no empty chip, no "N/A", and certainly no number that was not in the data.
 * Pure, so what is said and what is left unsaid are tested without a screen.
 */

/**
 * `2h 46m`, `46m` or `3h`: a running time in the form the reference draws, or null when the provider gave
 * none -- zero and a negative are "no running time", not a film of nought minutes.
 *
 * Western digits and Latin unit letters in every language, as the board's clock is: the figure is a
 * token, and the caller keeps it whole in a right-to-left line with `ltrToken`.
 */
internal fun movieDuration(minutes: Int?): String? {
    if (minutes == null || minutes <= 0) return null
    val hours = minutes / MINUTES_PER_HOUR
    val rest = minutes % MINUTES_PER_HOUR
    return when {
        hours == 0 -> "${rest}m"
        rest == 0 -> "${hours}h"
        else -> "${hours}h ${rest}m"
    }
}

/** The facts a film's page lists beside its name, in the order they are read: the year, then the running time. */
internal fun movieFacts(movie: Movie): List<String> =
    listOfNotNull(movie.year?.takeIf { it > 0 }?.toString(), movieDuration(movie.durationMinutes))

/**
 * Which list the films screen is showing: everything, the viewer's favourites, or one category.
 *
 * Read off the holder's state and not kept beside it, so the sidebar, the heading and the filter can
 * never disagree about it.
 */
internal sealed interface MovieList {
    data object All : MovieList
    data object Favorites : MovieList
    data class Category(val id: String) : MovieList
}

/** The list [this] is in -- the favourites win over a category, as they do in the query. */
internal fun BrowseState.movieList(): MovieList {
    val group = selectedGroup
    return when {
        favorites -> MovieList.Favorites
        group != null -> MovieList.Category(group)
        else -> MovieList.All
    }
}

/**
 * The figure beside the heading: how many films the list holds -- or null when there is no figure that
 * is true. The count is the scope's (the open category, or the section); a search narrows to a number
 * the count does not know, and the favourites are counted by another store, so for those the heading
 * says its name and no number.
 */
internal fun BrowseState.headingCount(): Int? =
    if (search.isNotEmpty() || favorites) null else total

private const val MINUTES_PER_HOUR = 60
