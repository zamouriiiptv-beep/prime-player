package com.castivio.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.castivio.core.common.AppError
import com.castivio.core.design.components.EmptyState
import com.castivio.core.design.components.ErrorState
import com.castivio.core.design.components.Skeleton
import com.castivio.core.design.theme.Spacing

/**
 * The four states every screen on this path has, decided in one place.
 *
 * Each screen fetches one thing and each has the same four answers, so they are written
 * once here rather than four times with one of them eventually forgotten. Which of the
 * four is showing is decided by two facts and not one: what the fetch is doing, and
 * whether there is anything stored. That pairing is what makes the on-demand path feel
 * fast — a section already on the device draws its rows while a refresh runs behind it,
 * and never blanks to a spinner over content the user can already read.
 *
 * A failure here is a failure of *this* screen. The user can go back and use every other
 * part of the app, which is the point of fetching sections separately and would be given
 * away by an error that took over the whole shell.
 */
@Composable
internal fun SectionBody(
    load: SectionLoad,
    empty: Boolean,
    emptyTitle: String,
    emptyDetail: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    when {
        // Content wins over everything. Rows on the device are rows worth showing, even
        // while a refresh is running and even if that refresh then fails.
        !empty -> content()

        load is SectionLoad.Loading || load is SectionLoad.Idle -> LoadingRows()

        load is SectionLoad.Failed -> Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            FetchFailed(load.error, onRetry, onBack)
        }

        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                title = emptyTitle,
                detail = emptyDetail,
                actionLabel = stringResource(R.string.browse_retry),
                onAction = onRetry,
            )
        }
    }
}

/**
 * Why the fetch failed, in the words that match the cause.
 *
 * "We could not reach your provider" for everything is the message this replaces. A
 * timeout, a refused subscription and a server having a bad afternoon lead to three
 * different actions, and telling a user with an expired line to check their connection
 * sends them to fix the wrong thing.
 */
@Composable
private fun FetchFailed(error: AppError, onRetry: () -> Unit, onBack: () -> Unit) {
    val title = when (error) {
        AppError.NETWORK_UNAVAILABLE -> R.string.fail_network_title
        AppError.TIMEOUT -> R.string.fail_timeout_title
        AppError.UNAUTHORIZED -> R.string.fail_rejected_title
        AppError.NOT_FOUND -> R.string.fail_not_found_title
        AppError.MALFORMED_PLAYLIST -> R.string.fail_unreadable_title
        AppError.SERVER_ERROR -> R.string.fail_server_title
        AppError.NOT_CONFIGURED -> R.string.fail_no_provider_title
        AppError.UNKNOWN -> R.string.fail_unknown_title
    }
    val detail = when (error) {
        AppError.NETWORK_UNAVAILABLE -> R.string.fail_network_detail
        AppError.TIMEOUT -> R.string.fail_timeout_detail
        AppError.UNAUTHORIZED -> R.string.fail_rejected_detail
        AppError.NOT_FOUND -> R.string.fail_not_found_detail
        AppError.MALFORMED_PLAYLIST -> R.string.fail_unreadable_detail
        AppError.SERVER_ERROR -> R.string.fail_server_detail
        AppError.NOT_CONFIGURED -> R.string.fail_no_provider_detail
        AppError.UNKNOWN -> R.string.fail_unknown_detail
    }
    ErrorState(
        title = stringResource(title),
        detail = stringResource(detail),
        // Retry is offered only where pressing it could plausibly work. On a rejected
        // subscription it is the back button that helps, and offering the other one
        // teaches people the button means nothing.
        actionLabel = stringResource(if (error.retryable) R.string.browse_retry else R.string.browse_back),
        onAction = if (error.retryable) onRetry else onBack,
    )
}

/**
 * The shape of what is coming, not a spinner.
 *
 * Rows rather than cards, because both the grids and the channel list arrive top to
 * bottom: a skeleton that does not sit where the content will lands as a second layout
 * change the moment the answer arrives.
 */
@Composable
internal fun LoadingRows() {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        repeat(SKELETON_ROWS) { Skeleton(height = SKELETON_HEIGHT, modifier = Modifier.fillMaxWidth()) }
    }
}

/** Enough placeholder rows to fill a television without pretending to know the count. */
private const val SKELETON_ROWS = 8

/** A row's height, so the skeleton and the content occupy the same space. */
private val SKELETON_HEIGHT = 46.dp
