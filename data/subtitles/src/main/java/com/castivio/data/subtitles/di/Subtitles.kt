package com.castivio.data.subtitles.di

import javax.inject.Qualifier

/**
 * The HTTP client the subtitle search uses, as opposed to the one the catalogue uses.
 *
 * Without this the two are the same type with the same scope and no qualifier, and Dagger
 * refuses the graph outright — `okhttp3.OkHttpClient is bound multiple times`. That refusal
 * is the correct one and worth keeping rather than dodging by deleting a provider: the
 * catalogue's client is built around a ninety-second video read and a connection pool a
 * stream is waiting on, and the search's around ten seconds and the assumption that giving
 * up costs nothing. Handing a subtitle lookup the streaming client would let the least
 * important request in the application hold a connection the most important one needs.
 *
 * Qualified rather than distinguished by parameter name, because a name is a comment: it
 * documents the intent to the reader and nothing at all to the compiler.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Subtitles
