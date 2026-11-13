package com.castivio.domain

/**
 * What a debuggable build prints of the ids a provider gave a film or a show: `tmdb 603 · imdb tt0133093`, either half alone when
 * only that one was sent, and `no ids` when neither was.
 *
 * [read] is false for details kept before the ids were read (see `MovieDetails.idsRead`): they have none to print, and saying
 * `no ids` of them would tell a tester the provider has none when nobody has asked it yet -- so the line says `ids not read yet`.
 *
 * One line for both kinds of page, so a tester reads the same words on a film and on a show. Western digits always: they are an
 * address in someone else's catalogue, not a number of this application's.
 */
fun externalIdsLine(tmdbId: Long?, imdbId: String?, read: Boolean = true): String {
    if (!read) return "ids not read yet"
    val parts = listOfNotNull(tmdbId?.let { "tmdb $it" }, imdbId?.let { "imdb $it" })
    return if (parts.isEmpty()) "no ids" else parts.joinToString(" · ")
}
