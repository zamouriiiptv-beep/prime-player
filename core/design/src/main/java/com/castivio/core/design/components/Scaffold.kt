package com.castivio.core.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.castivio.core.common.AppError
import com.castivio.core.common.EmptyReason
import com.castivio.core.common.ScreenState
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.DeviceClass
import com.castivio.core.design.theme.Motion
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Sizing
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
 * The adaptive shell chrome: a navigation rail on a television or tablet, a bottom
 * bar on a phone. One shell, chosen by [DeviceClass] — design invariant 4.
 *
 * The rail expands over content on focus rather than pushing it, so nothing moves
 * under the cursor. The bar is fixed and touch-first. Both speak the same selection
 * language: the active destination takes the violet indicator.
 */
@Composable
fun CastivioShell(
    destinations: List<NavAction>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val device = CastivioTheme.device
    Box(modifier.fillMaxSize()) {
        if (device == DeviceClass.Television || device == DeviceClass.Expanded) {
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
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                CastivioBottomBar(destinations, selectedIndex)
            }
        }
    }
}

/**
 * The phone navigation bar: fixed five, Material's active indicator on the
 * selected one. Content scrolls under it, so it carries its own opaque ground.
 */
@Composable
fun CastivioBottomBar(
    destinations: List<NavAction>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.backgroundElevated.copy(alpha = 0.96f))
            .border(
                width = 1.dp,
                color = colors.divider,
                shape = RoundedCornerShape(0.dp),
            )
            .navigationBarsPadding()
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        destinations.forEachIndexed { index, d ->
            BottomBarItem(
                action = d,
                selected = index == selectedIndex,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BottomBarItem(
    action: NavAction,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val tint = when {
        selected -> colors.secondary
        focused -> colors.onBackground
        else -> colors.onBackgroundMuted
    }
    Column(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(interaction, indication = null, onClick = action.onClick)
            .padding(vertical = Spacing.xs)
            .semantics {
                this.selected = selected
                contentDescription = action.label
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.pill))
                .then(
                    if (selected) {
                        Modifier.background(colors.secondaryContainer.copy(alpha = 0.55f))
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = Spacing.lg, vertical = Spacing.xxs),
            contentAlignment = Alignment.Center,
        ) {
            Icon(action.icon, null, tint = tint, modifier = Modifier.size(Sizing.iconMd))
        }
        Text(action.label, style = CastivioType.labelSmall, color = tint)
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
