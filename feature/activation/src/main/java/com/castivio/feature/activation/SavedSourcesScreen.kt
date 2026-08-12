package com.castivio.feature.activation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.castivio.core.design.components.ButtonWeight
import com.castivio.core.design.components.CastivioButton
import com.castivio.core.design.components.InteractiveGlassCard
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing
import com.castivio.domain.ProviderSource

/**
 * The subscriptions this box already holds.
 *
 * Reached from the fourth card on the source choice, and it is the one destination of
 * the four that had nowhere to go: Xtream and M3U open forms that have existed since
 * the flow was written, and a saved-subscription list did not exist at all.
 *
 * ## What it is, and what it deliberately is not
 *
 * It lists what `SourceRepository` holds, marks the one in use, and switches to
 * another when it is chosen. Adding a subscription is the two buttons at the bottom,
 * and they do not open anything new — they call the same `useXtream()` and
 * `usePlaylistUrl()` the source choice calls, and land on the same two forms. There is
 * one way to add a provider in this application and this screen is a second door onto
 * it, not a second implementation of it.
 *
 * Deleting is absent, and that is a decision rather than an omission: removing a
 * subscription drops its catalogue, its favourites and its progress with it, which
 * needs a confirmation and a story about what happens if it was the active one. That
 * is its own piece of work, and half of it shipped quietly would be worse than none.
 *
 * ## The frame
 *
 * A list, so it is the one step here that can be taller than the screen — and it is
 * given the *fixed* frame rather than the scrolling one. `ActivationSurface`'s
 * scrolling branch wraps its content in `verticalScroll`, and a `LazyColumn` inside an
 * unbounded height does not scroll, it crashes. Inside the fixed frame the list has a
 * bounded height and scrolls itself, which is what a list is supposed to do.
 */
@Composable
internal fun SavedSourcesScreen(
    state: SavedSourcesState,
    onChoose: (String) -> Unit,
    onAddXtream: () -> Unit,
    onAddPlaylist: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(CastivioTheme.device.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Text(
            text = stringResource(R.string.saved_sources_title),
            style = CastivioType.headlineMedium,
            color = CastivioTheme.colors.onBackground,
            modifier = Modifier
                .testTag(ActivationTags.SAVED_TITLE)
                .semantics { heading() },
        )

        when (state) {
            // Nothing at all for one frame. The alternative is telling a returning
            // user they have no subscriptions and correcting it a moment later.
            SavedSourcesState.Loading -> Unit

            is SavedSourcesState.Ready -> if (state.isEmpty) {
                Text(
                    text = stringResource(R.string.saved_sources_empty),
                    style = CastivioType.bodyLarge,
                    color = CastivioTheme.colors.onBackgroundVariant,
                    modifier = Modifier.testTag(ActivationTags.SAVED_EMPTY),
                )
            } else {
                LazyColumn(
                    // `weight`, so the list takes what the title and the buttons leave
                    // and no more. Given the rest it would push them off the screen.
                    modifier = Modifier.weight(1f).testTag(ActivationTags.SAVED_LIST),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(state.saved, key = { it.id }) { source ->
                        SavedSourceRow(
                            source = source,
                            isActive = source.id == state.activeId,
                            onClick = { onChoose(source.id) },
                        )
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            CastivioButton(
                text = stringResource(R.string.saved_sources_add_xtream),
                weight = ButtonWeight.Secondary,
                onClick = onAddXtream,
                modifier = Modifier.testTag(ActivationTags.SAVED_ADD_XTREAM),
            )
            CastivioButton(
                text = stringResource(R.string.saved_sources_add_m3u),
                weight = ButtonWeight.Secondary,
                onClick = onAddPlaylist,
                modifier = Modifier.testTag(ActivationTags.SAVED_ADD_M3U),
            )
        }

        BackButton(onBack, Modifier.testTag(ActivationTags.SAVED_BACK))
    }
}

/**
 * One saved subscription.
 *
 * The label leads and the address explains, which is the same hierarchy the source
 * cards use. The address is the one string on this screen that genuinely can be
 * unbounded -- an Xtream host with a port and a path, or a playlist URL with a token
 * in it -- so it is the one place an ellipsis is right: a row that grows to fit a
 * 300-character URL is a row that pushes every other subscription off the screen.
 */
@Composable
private fun SavedSourceRow(
    source: ProviderSource,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val colors = CastivioTheme.colors
    val inUse = stringResource(R.string.saved_sources_active)

    InteractiveGlassCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = if (isActive) "${source.label}. $inUse" else source.label
            },
        fill = SolidColor(colors.glassFillStrong),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = source.label,
                    style = CastivioType.titleLarge,
                    color = colors.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                source.url?.let { url ->
                    Text(
                        text = url,
                        style = CastivioType.bodySmall,
                        color = colors.onBackgroundVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (isActive) {
                // A tick and the word, not a tick alone: on a 10-foot display a
                // 20dp glyph is the whole difference between "this one" and "not
                // this one", and it is the difference a user cannot afford to miss.
                Text(text = inUse, style = CastivioType.labelMedium, color = colors.onBackground)
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = colors.onBackground,
                    modifier = Modifier.size(Sizing.iconMd),
                )
            }
        }
    }
}
