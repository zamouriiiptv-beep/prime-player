package com.castivio.feature.home

import androidx.compose.ui.unit.Dp
import com.castivio.domain.Movie
import com.castivio.domain.MovieDetails
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
 * [movie] with what the provider's details add to it: a year, a running time and genres the list row did not
 * carry. What the row does carry is kept as it is, so a list and its page never disagree about a film.
 */
internal fun Movie.withDetails(details: MovieDetails?): Movie {
    if (details == null) return this
    return copy(
        year = year ?: details.releaseYear,
        durationMinutes = durationMinutes ?: details.durationSeconds?.let { it / SECONDS_PER_MINUTE },
        genres = genres.ifEmpty { details.genres },
    )
}

/** A provider's rating as one decimal, `8.1`: the figure IMDb itself shows. */
internal fun ratingText(rating: Double): String = String.format(Locale.ROOT, "%.1f", rating)

/** A day a film was added on, `24/10/2026`, from the provider's time in seconds. */
internal fun addedDateText(epochSeconds: Long): String =
    SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).format(Date(epochSeconds * MILLIS_PER_SECOND))

/**
 * Which list the films screen is showing: everything, the viewer's favourites, the films they played last,
 * or one category.
 *
 * Read off the holder's state and not kept beside it, so the rail, the header and the wall can never
 * disagree about it.
 */
internal sealed interface MovieList {
    data object All : MovieList
    data object Favorites : MovieList
    data object Recent : MovieList
    data class Category(val id: String) : MovieList
}

/** The list [this] is in -- the viewer's own lists win over a category, as they do in the query. */
internal fun BrowseState.movieList(): MovieList {
    val group = selectedGroup
    return when {
        favorites -> MovieList.Favorites
        recent -> MovieList.Recent
        group != null -> MovieList.Category(group)
        else -> MovieList.All
    }
}

/**
 * The figure above the wall: how many films the list holds -- or null when there is no figure that is
 * true. The count is the scope's (the open category, or the section); a search narrows to a number the
 * count does not know, and the favourites and the films played last are counted by other stores, so for
 * those the wall says nothing rather than a number that is not theirs.
 */
internal fun BrowseState.headingCount(): Int? =
    if (search.isNotEmpty() || favorites || recent) null else total

/**
 * How many posters fit across [available] with [gutter] between each pair, none narrower than [minimum]:
 * the same arithmetic as the generic screens' grid, so the cells take an equal share of what is left and
 * the row always fills the width. Never fewer than two, which is where a wall stops reading as a list.
 */
internal fun posterColumns(available: Dp, gutter: Dp, minimum: Dp): Int =
    ((available + gutter) / (minimum + gutter)).toInt().coerceAtLeast(MIN_POSTER_COLUMNS)

/**
 * The height a poster is drawn at, so the wall shows two whole rows and only the top of the third: a sliver
 * of [peek] of a poster's height, which is what tells a viewer there is more below and no more.
 *
 * A row is a poster and what sits under it, [rowExtra], and the gap between rows, [rowGap]. Two rows and
 * the sliver come to [available], so the poster is that less the two extras, shared over two posters and
 * the sliver. Never shorter than [floor], so a very short surface still draws a poster rather than a line.
 */
internal fun posterHeightFor(available: Dp, rowExtra: Dp, rowGap: Dp, peek: Float, floor: Dp): Dp =
    ((available - (rowExtra + rowGap) * 2) / (2f + peek)).coerceAtLeast(floor)

private const val MINUTES_PER_HOUR = 60
private const val SECONDS_PER_MINUTE = 60
private const val MILLIS_PER_SECOND = 1_000L

private const val MIN_POSTER_COLUMNS = 2
