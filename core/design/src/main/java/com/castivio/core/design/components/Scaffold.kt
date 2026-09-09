package com.castivio.core.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.castivio.core.common.AppError
import com.castivio.core.common.EmptyReason
import com.castivio.core.common.ScreenState
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Motion
import com.castivio.core.design.theme.Spacing

/**
 * Renders a [ScreenState] as the right one of the four states, every time.
 *
 * This is design invariant 10 with the compiler holding it: the `when` is
 * exhaustive, so a screen physically cannot forget its empty or error case. A
 * refresh behind content stays content — the caller is handed `refreshing` and
 * shows a quiet indicator, never a spinner over the list.
 *
 * The default loading, empty and error renderings are sensible; a screen overrides
 * the slot it needs to (an empty section's action depends on what the provider
 * carries, so it is usually supplied).
 */
@Composable
fun <T> ScreenScaffold(
    state: ScreenState<T>,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    loading: @Composable () -> Unit = { DefaultLoading() },
    empty: @Composable (ScreenState.Empty) -> Unit = { DefaultEmpty(it, onAction) },
    failed: @Composable (ScreenState.Failed) -> Unit = { DefaultError(it, onAction) },
    content: @Composable (value: T, refreshing: Boolean) -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        when (state) {
            is ScreenState.Loading -> loading()
            is ScreenState.Empty -> empty(state)
            is ScreenState.Failed -> failed(state)
            is ScreenState.Content -> content(state.value, state.refreshing)
        }
    }
}

@Composable
private fun DefaultLoading() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
    ) {
        SkeletonRow()
        SkeletonRow()
    }
}

@Composable
private fun DefaultEmpty(state: ScreenState.Empty, onAction: () -> Unit) {
    val copy = emptyCopy(state.reason, state.providerLabel)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
            title = copy.title,
            detail = copy.detail,
            actionLabel = copy.action,
            onAction = onAction,
        )
    }
}

@Composable
private fun DefaultError(state: ScreenState.Failed, onAction: () -> Unit) {
    val copy = errorCopy(state.error)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ErrorState(
            title = copy.title,
            detail = copy.detail,
            actionLabel = if (state.retryable) "Try again" else copy.action,
            onAction = onAction,
        )
    }
}

private data class StateCopy(val title: String, val detail: String, val action: String)

/**
 * Default empty copy. A screen usually overrides the section case with a provider
 * name and a contextual action, but these keep any un-overridden screen honest.
 */
private fun emptyCopy(reason: EmptyReason, provider: String?): StateCopy = when (reason) {
    EmptyReason.NO_PROVIDER -> StateCopy(
        "No provider yet",
        "Add a provider to start watching.",
        "Add a provider",
    )
    EmptyReason.PROVIDER_HAS_NO_CONTENT -> StateCopy(
        "${provider ?: "This provider"} doesn't include this",
        "It carries other kinds of content. Your other sections are unaffected.",
        "Add another provider",
    )
    EmptyReason.CATEGORY_EMPTY -> StateCopy(
        "Nothing here",
        "This category is empty — that's the provider's doing, not a fault.",
        "Back",
    )
    EmptyReason.NO_SEARCH_RESULTS -> StateCopy(
        "No results",
        "Nothing matched. Try fewer letters.",
        "Clear",
    )
    EmptyReason.NO_FAVORITES -> StateCopy(
        "No favourites yet",
        "Mark something a favourite and it turns up here.",
        "Browse Live TV",
    )
    EmptyReason.NO_HISTORY -> StateCopy(
        "Nothing watched yet",
        "What you watch shows up here so you can pick it back up.",
        "Browse Live TV",
    )
}

private fun errorCopy(error: AppError): StateCopy = when (error) {
    AppError.NETWORK_UNAVAILABLE -> StateCopy("No connection", "Can't reach the network.", "Retry")
    AppError.TIMEOUT -> StateCopy("Timed out", "The provider took too long to answer.", "Retry")
    AppError.UNAUTHORIZED -> StateCopy(
        "Details rejected",
        "The provider didn't accept those credentials.",
        "Edit details",
    )
    AppError.NOT_FOUND -> StateCopy("Not found", "That isn't on the provider any more.", "Back")
    AppError.MALFORMED_PLAYLIST -> StateCopy(
        "Couldn't read the playlist",
        "The provider sent something we couldn't parse.",
        "Retry",
    )
    AppError.SERVER_ERROR -> StateCopy("Provider error", "The provider had a problem.", "Retry")
    // Ours, not theirs, and not the user's. It reaches a content screen only if something
    // upstream let it through, so the wording admits the fault rather than blaming the
    // provider — and offers no retry, because retrying is not what fixes it.
    AppError.NOT_CONFIGURED -> StateCopy(
        "Not available yet",
        "This part of Castivio isn't ready in this build.",
        "Back",
    )
    AppError.UNKNOWN -> StateCopy("Something went wrong", "That didn't work.", "Retry")
}

// ------------------------------------------------------------------ the shell

/**
 * The shell chrome: one navigation rail, on every frame.
 *
 * ## Why there is no bottom bar any more
 *
 * There were two — a rail on a set and a tall bottom bar on a phone — and the bar was
 * the wrong instrument for this app twice over. Castivio is locked to landscape, so
 * *height* is the dimension that runs out; a 393dp-tall handset was giving about a
 * sixth of it to five icons with labels under them, on a screen whose whole job is to
 * show a dashboard in one frame. And the bar is a phone-app idiom on a product that
 * is a television first: the rail is what a viewer three metres away can find with a
 * D-pad, and it costs width, which is the dimension this app has to spare.
 *
 * One chrome also means one selection language rather than two that have to be kept
 * in step by hand — the same reason `CastivioFrame` exists.
 *
 * The rail expands over content on focus rather than pushing it, so nothing moves
 * under the cursor, and the active destination takes the violet indicator.
 */
@Composable
fun CastivioShell(
    destinations: List<NavAction>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            CastivioNavRail(
                destinations = destinations,
                selectedIndex = selectedIndex,
                expanded = false,
                modifier = Modifier
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(Spacing.sm),
            )
            Box(Modifier.weight(1f).fillMaxSize()) { content() }
        }
    }
}

/** A minimal top bar for a content screen: title on the leading edge, one action. */
@Composable
fun ScreenTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = CastivioTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screen, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = CastivioType.headlineSmall, color = colors.onBackground)
            if (subtitle != null) {
                Text(subtitle, style = CastivioType.bodySmall, color = colors.onBackgroundMuted)
            }
        }
        if (action != null) action()
    }
}

/** A one-off separator height used between the top bar and the first row. */
val ShellGap = Spacing.sm
