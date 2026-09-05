package com.castivio.feature.activation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.castivio.core.design.components.ButtonWeight
import com.castivio.core.design.components.CastivioButton
import com.castivio.core.design.components.InteractiveGlassCard
import com.castivio.core.design.components.castivioChipStyle
import com.castivio.core.design.components.castivioBodyStyle
import com.castivio.core.design.components.castivioDescriptionColor
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.castivioStage
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
 * The stage, the header and the four type steps are
 * [com.castivio.core.design.theme.CastivioFrame]'s, chosen by the measured height of
 * this surface. Before that this screen drew a bare title at `headlineMedium`, took
 * its margins from `DeviceClass.screenPadding` — one number for every handset and
 * tablet alike — and put Back in a full-width button under the list. A reader arriving
 * from the source choice met a different brand, a different title size and a Back in a
 * different place, on the screen that card had just opened.
 *
 * It is a list, so it is the one step here that can be taller than the screen, and it
 * is given the *fixed* frame rather than the scrolling one. `ActivationSurface`'s
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
    val tv = CastivioTheme.device.isTv
    BoxWithConstraints(modifier.fillMaxSize()) {
        val m = sourceMetricsFor(tv = tv, available = maxHeight)

        Column(
            Modifier
                .fillMaxSize()
                .castivioStage(m.frame),
        ) {
            ChooserHeader(
                m = m,
                title = stringResource(R.string.saved_sources_title),
                headingTag = ActivationTags.SAVED_TITLE,
                backTag = ActivationTags.SAVED_BACK,
                onBack = onBack,
            )
            Spacer(Modifier.height(m.bandTop))

            // The band, weighted in all three states so the two add buttons keep their
            // place while the list arrives. A `Column` whose children exceed its height
            // hands zero to whatever it measured last, and here that would be the only
            // two controls on the screen.
            SavedBand(m, state, onChoose)

            Spacer(Modifier.height(m.gridGap))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(m.cardGap),
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
        }
    }
}

/**
 * What sits between the header and the two buttons, whichever of the three states
 * this screen is in.
 *
 * All three take the same weighted band, so nothing above or below them moves as the
 * repository answers. Loading shows nothing at all rather than telling a returning
 * user they have no subscriptions and correcting it a moment later.
 */
@Composable
private fun ColumnScope.SavedBand(
    m: SourceMetrics,
    state: SavedSourcesState,
    onChoose: (String) -> Unit,
) {
    val band = Modifier.weight(1f).fillMaxWidth()

    when (state) {
        SavedSourcesState.Loading -> Spacer(band)

        is SavedSourcesState.Ready -> if (state.isEmpty) {
            Box(band.padding(m.cardPad), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.saved_sources_empty),
                    style = castivioBodyStyle(m.fsDetail),
                    color = castivioDescriptionColor,
                    modifier = Modifier.testTag(ActivationTags.SAVED_EMPTY),
                )
            }
        } else {
            LazyColumn(
                modifier = band.testTag(ActivationTags.SAVED_LIST),
                verticalArrangement = Arrangement.spacedBy(m.cardGap * ROW_GAP),
            ) {
                items(state.saved, key = { it.id }) { source ->
                    SavedSourceRow(
                        m = m,
                        source = source,
                        isActive = source.id == state.activeId,
                        onClick = { onChoose(source.id) },
                    )
                }
            }
        }
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
 *
 * The row is at least the frame's own target tall before it is anything else, so a
 * subscription with a short label and no address is still something a remote can land
 * on and a thumb can hit.
 */
@Composable
private fun SavedSourceRow(
    m: SourceMetrics,
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
        shape = RoundedCornerShape(m.radius),
        fill = SolidColor(colors.glassFillStrong),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = m.frame.touchTarget)
                .padding(horizontal = m.cardPad, vertical = m.cardPad * ROW_GAP),
            horizontalArrangement = Arrangement.spacedBy(m.cardGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = source.label,
                    style = castivioChipStyle(m.fsCard),
                    color = colors.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                source.url?.let { url ->
                    Text(
                        text = url,
                        style = castivioBodyStyle(m.fsDetail),
                        color = castivioDescriptionColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (isActive) {
                // A tick and the word, not a tick alone: on a 10-foot display a
                // 20dp glyph is the whole difference between "this one" and "not
                // this one", and it is the difference a user cannot afford to miss.
                Text(
                    text = inUse,
                    style = castivioChipStyle(m.fsBadge),
                    color = colors.onBackground,
                )
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

/** Between rows, and a row's own vertical padding — half the gap between cards. */
private const val ROW_GAP = 0.5f

