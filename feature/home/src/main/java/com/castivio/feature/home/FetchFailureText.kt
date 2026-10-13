package com.castivio.feature.home

import com.castivio.core.common.AppError

/**
 * The sentence under a section that could not be fetched, chosen by what went wrong.
 *
 * ## Why it is one sentence per cause
 *
 * It was one of two — "did not answer in time" or "refused" — and the line over a board
 * that still had rows said "your provider did not answer" whatever had happened. A
 * playlist the provider refused, one it could not be reached for and one whose answer
 * could not be read are three different things to do next: check the subscription,
 * check the connection, ask the provider. The sentence is the only place a viewer is
 * told which.
 *
 * Exhaustive over [AppError], so a cause added there cannot reach a screen without a
 * sentence of its own being chosen here first.
 */
internal fun AppError.fetchFailureDetail(): Int = when (this) {
    AppError.UNAUTHORIZED, AppError.NOT_FOUND, AppError.NOT_CONFIGURED -> R.string.browse_fetch_failed_final
    AppError.TIMEOUT -> R.string.browse_fetch_failed_retryable
    AppError.NETWORK_UNAVAILABLE -> R.string.browse_fetch_failed_network
    AppError.SERVER_ERROR -> R.string.browse_fetch_failed_server
    AppError.MALFORMED_PLAYLIST -> R.string.browse_fetch_failed_unreadable
    AppError.UNKNOWN -> R.string.browse_fetch_failed_unknown
}
