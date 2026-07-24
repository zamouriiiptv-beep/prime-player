package com.castivio.core.common

/**
 * Result type used across module boundaries.
 *
 * Providers fail in mundane, recoverable ways — a timeout, a 403, a malformed
 * playlist — and the UI needs to say which. Throwing across a repository
 * boundary loses that distinction.
 */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: AppError, val cause: Throwable? = null) : Outcome<Nothing>

    fun getOrNull(): T? = (this as? Success)?.value
}

enum class AppError {
    NETWORK_UNAVAILABLE,
    TIMEOUT,
    UNAUTHORIZED,
    NOT_FOUND,
    MALFORMED_PLAYLIST,
    SERVER_ERROR,
    UNKNOWN,
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}
