package com.castivio.core.common.metadata

import java.util.concurrent.atomic.AtomicLong

/**
 * How many of the provider's detail replies carried the number of a film or a show at TMDB and at IMDb -- counted, so that
 * "does my provider send them?" has an answer read off a screen and not a guess.
 *
 * **For development.** Written by the code that reads a reply, read by the load strip of a development build; neither has any say
 * in what a reply does. Nothing but counts reaches it: not an id, not a title, not a host. A reply the provider answered with no
 * details at all is counted as a reply without ids -- it is a film or a show that has none -- and a reply that failed is not
 * counted: the provider did not answer, so nothing is known of its ids.
 *
 * A class, with [IdCoverage] as the one the application writes to, so a test reads a tally of its own.
 */
open class IdCoverageTally {

    private val films = AtomicLong()
    private val filmsTmdb = AtomicLong()
    private val filmsImdb = AtomicLong()
    private val shows = AtomicLong()
    private val showsTmdb = AtomicLong()
    private val showsImdb = AtomicLong()

    /** The provider answered for a film, and the answer did ([tmdb], [imdb]) or did not carry its ids. */
    fun film(tmdb: Boolean, imdb: Boolean) {
        films.incrementAndGet()
        if (tmdb) filmsTmdb.incrementAndGet()
        if (imdb) filmsImdb.incrementAndGet()
    }

    /** The provider answered for a show, and the answer did ([tmdb], [imdb]) or did not carry its ids. */
    fun show(tmdb: Boolean, imdb: Boolean) {
        shows.incrementAndGet()
        if (tmdb) showsTmdb.incrementAndGet()
        if (imdb) showsImdb.incrementAndGet()
    }

    /** Everything counted, as it stands. */
    fun figures(): IdCoverageFigures = IdCoverageFigures(
        films = films.get(),
        filmsWithTmdb = filmsTmdb.get(),
        filmsWithImdb = filmsImdb.get(),
        shows = shows.get(),
        showsWithTmdb = showsTmdb.get(),
        showsWithImdb = showsImdb.get(),
    )

    /** Starts the count again. */
    fun reset() {
        films.set(0)
        filmsTmdb.set(0)
        filmsImdb.set(0)
        shows.set(0)
        showsTmdb.set(0)
        showsImdb.set(0)
    }
}

/** The tally the application's detail readers write to and the load strip reads. */
object IdCoverage : IdCoverageTally()

/**
 * [IdCoverageTally] as it stood, and the line the strip draws from it.
 *
 * The line answers one question -- *how often does the provider say what a film or a show is at TMDB?* -- as a fraction of the
 * replies it has given since the application began: `ids: films tmdb 14/20 · imdb 0/20 · shows tmdb 6/8 · imdb 0/8`.
 */
data class IdCoverageFigures(
    val films: Long,
    val filmsWithTmdb: Long,
    val filmsWithImdb: Long,
    val shows: Long,
    val showsWithTmdb: Long,
    val showsWithImdb: Long,
) {

    /** The line for the strip and the log; null while no detail reply has been read. Western digits always. */
    fun describe(): String? {
        val parts = buildList {
            if (films > 0) add("films tmdb $filmsWithTmdb/$films · imdb $filmsWithImdb/$films")
            if (shows > 0) add("shows tmdb $showsWithTmdb/$shows · imdb $showsWithImdb/$shows")
        }
        return if (parts.isEmpty()) null else "ids: " + parts.joinToString(" · ")
    }
}
