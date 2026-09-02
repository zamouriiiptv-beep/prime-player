package com.castivio.feature.home

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import com.castivio.core.design.components.MetaChip
import com.castivio.core.design.components.castivioFocusScale
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.DeviceClass
import com.castivio.core.design.theme.Motion
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Spacing

/**
 * Home: which of the three, and nothing else.
 *
 * ## Why there is no content on it
 *
 * There used to be rows here — a sample of channels, films and series with counts — and
 * every one of them was a query that had to have been answered before Home could draw,
 * which in turn meant a catalogue had to have been imported before Home could exist.
 * That is the cost this whole flow removes. Home now issues no request and reads no
 * table: it is three destinations, drawn instantly, and the first data this app fetches
 * is whichever one the user presses.
 *
 * ## Why three tiles and not a rail
 *
 * The choice is the screen. On a remote the first press should land on something
 * meaningful, and three large targets on an otherwise empty screen is the least
 * ambiguous thing a 10-foot interface can offer. The same three read as a card list on
 * a phone, where they are the whole first screen rather than a row above content.
 */
@Composable
fun HomeScreen(
    onOpen: (CatalogSection) -> Unit,
    modifier: Modifier = Modifier,
    model: ProviderViewModel = hiltViewModel(),
) {
    val provider by model.label.collectAsStateWithLifecycle()
    val colors = CastivioTheme.colors
    val device = CastivioTheme.device
    val wide = device == DeviceClass.Television || device == DeviceClass.Expanded

    // The remote's first press has to land somewhere, and it should be the first
    // choice rather than wherever the framework happened to leave the highlight.
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = device.screenPadding, vertical = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(
                    stringResource(R.string.home_brand),
                    style = CastivioType.titleLarge,
                    color = colors.secondary,
                )
                Text(
                    stringResource(R.string.home_choose),
                    style = CastivioType.bodyMedium,
                    color = colors.onBackgroundMuted,
                )
            }
            Box(Modifier.weight(1f))
            provider?.let { MetaChip(it) }
        }

        // Side by side where there is room, stacked where there is not. Not a grid
        // with a column count: there are exactly three, and the layout should say so.
        if (wide) {
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
            ) {
                CatalogSection.entries.forEachIndexed { index, section ->
                    SectionTile(
                        section = section,
                        onOpen = onOpen,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .then(if (index == 0) Modifier.focusRequester(first) else Modifier),
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                CatalogSection.entries.forEachIndexed { index, section ->
                    SectionTile(
                        section = section,
                        onOpen = onOpen,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(PHONE_TILE)
                            .then(if (index == 0) Modifier.focusRequester(first) else Modifier),
                    )
                }
            }
        }
    }
}

/**
 * One of the three, as a target a remote can reach and a thumb can hit.
 *
 * Focusable in its own right rather than by accident: `clickable` makes it reachable
 * with a D-pad, the lift and the ring say where the highlight is, and the whole tile is
 * the target rather than the label inside it.
 */
@Composable
private fun SectionTile(
    section: CatalogSection,
    onOpen: (CatalogSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Radius.xl)
    Column(
        modifier
            .castivioFocusScale(Motion.focusScaleCard, interaction)
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, colors.glassBorder, shape)
            .clickable(interaction, indication = null) { onOpen(section) }
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterVertically),
    ) {
        Icon(
            section.icon,
            contentDescription = null,
            tint = colors.secondary,
            modifier = Modifier.size(40.dp),
        )
        Text(
            stringResource(section.label),
            style = CastivioType.headlineSmall,
            color = colors.onBackground,
        )
        Text(
            stringResource(section.hint),
            style = CastivioType.bodySmall,
            color = colors.onBackgroundMuted,
        )
    }
}

/** Its own name, as the user reads it. */
internal val CatalogSection.label: Int
    get() = when (this) {
        CatalogSection.Channels -> R.string.browse_channels
        CatalogSection.Movies -> R.string.browse_movies
        CatalogSection.Series -> R.string.browse_series
    }

/** One line saying what is behind the tile, so the choice needs no learning. */
private val CatalogSection.hint: Int
    get() = when (this) {
        CatalogSection.Channels -> R.string.home_channels_hint
        CatalogSection.Movies -> R.string.home_movies_hint
        CatalogSection.Series -> R.string.home_series_hint
    }

private val CatalogSection.icon: ImageVector
    get() = when (this) {
        CatalogSection.Channels -> Icons.Filled.LiveTv
        CatalogSection.Movies -> Icons.Filled.Movie
        CatalogSection.Series -> Icons.Filled.Tv
    }

/** Tall enough to be an obvious target on a phone without the three needing a scroll. */
private val PHONE_TILE = 108.dp
