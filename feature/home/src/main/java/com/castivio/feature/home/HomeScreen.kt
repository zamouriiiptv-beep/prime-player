package com.castivio.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.castivio.core.design.components.CardShape
import com.castivio.core.design.components.CastivioChip
import com.castivio.core.design.components.ChannelCard
import com.castivio.core.design.components.EmptyState
import com.castivio.core.design.components.InteractiveGlassCard
import com.castivio.core.design.components.MediaCard
import com.castivio.core.design.components.MediaRow
import com.castivio.core.design.components.MetaChip
import com.castivio.core.design.components.SkeletonRow
import com.castivio.core.design.components.formatCount
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.DeviceClass
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Spacing
import com.castivio.domain.MediaItem
import com.castivio.domain.MediaKind
import com.castivio.domain.SourceKind
import com.castivio.domain.entitlement.EntitlementState
import java.text.DateFormat
import java.util.Date

/**
 * Home: the four sections, how much is in each, and a taste of what arrived.
 *
 * ## Why the four tiles and not four rows
 *
 * A row of cards is a sample of one section; the four tiles are the *shape of the
 * catalogue* — how many channels, how many films, how many shows, whether there is
 * radio at all — and that is the first question anyone opening an IPTV player has.
 * Every number on them is an indexed `COUNT`, never the size of a loaded list, and
 * they move on their own while an import is still running behind this screen. That
 * is what "the content appears once the subscription succeeds" is made of: no
 * refresh, no navigation, the numbers simply arrive.
 *
 * The rows stay underneath, because a count is not content. There is no hero and
 * nothing is featured: an IPTV catalogue has no editorial, so a large picture at the
 * top would be a picture of whatever happened to import first.
 *
 * ## Two different nothings
 *
 * "No provider yet" and "a provider that carried nothing" look identical if you only
 * check whether the rows are empty, and they need opposite advice — one is *add a
 * subscription*, the other is *this one is empty, try another or refresh a section*.
 * [HomeState.hasSource] separates them, and the two states below say different
 * sentences and offer different buttons.
 *
 * ## No clock
 *
 * The reference dashboards put a ticking time in the corner. A clock is a coroutine
 * that never finishes, and a composition that never goes idle is a Compose test that
 * hangs until CI's timeout kills it — this project has already paid for that once.
 * The corner carries what a viewer cannot get anywhere else instead: which provider
 * is live, and what Castivio's own licence says.
 */
@Composable
fun HomeScreen(
    onPlay: (CatalogSelection) -> Unit,
    onSeeSection: (CatalogSection) -> Unit,
    /** Add a first subscription, or change the one showing. Opens the activation flow. */
    onAddSource: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    model: HomeViewModel = hiltViewModel(),
) {
    val state by model.state.collectAsStateWithLifecycle()
    val padding = CastivioTheme.device.screenPadding

    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
    ) {
        DashboardHeader(state, Modifier.padding(horizontal = padding, vertical = Spacing.md))

        when {
            state.loading -> SkeletonRow(
                cards = 4,
                label = null,
                modifier = Modifier.padding(horizontal = padding),
            )

            !state.hasSource -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    title = stringResource(R.string.home_no_source_title),
                    detail = stringResource(R.string.home_no_source_detail),
                    actionLabel = stringResource(R.string.home_add_source),
                    onAction = onAddSource,
                    icon = Icons.Rounded.PlaylistAdd,
                    secondaryActionLabel = stringResource(R.string.home_settings),
                    onSecondaryAction = onSettings,
                )
            }

            else -> {
                SectionTiles(
                    state = state,
                    onSeeSection = onSeeSection,
                    modifier = Modifier.padding(horizontal = padding),
                )

                QuickActions(
                    onAddSource = onAddSource,
                    onSearch = onSearch,
                    onSettings = onSettings,
                    modifier = Modifier.padding(horizontal = padding),
                )

                if (state.carriesNothing) {
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = padding),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyState(
                            title = stringResource(R.string.home_empty_title),
                            detail = stringResource(R.string.home_empty_detail),
                            actionLabel = stringResource(R.string.home_change_source),
                            onAction = onAddSource,
                        )
                    }
                } else {
                    ChannelRow(
                        title = stringResource(R.string.browse_live),
                        count = state.liveCount,
                        items = state.live,
                        onPlay = onPlay,
                    )
                    PosterRow(
                        title = stringResource(R.string.browse_movies),
                        count = state.movieCount,
                        items = state.movies,
                        onPlay = onPlay,
                    )
                    PosterRow(
                        title = stringResource(R.string.browse_series),
                        count = state.seriesCount,
                        items = state.episodes,
                        onPlay = onPlay,
                    )
                }

                Text(
                    text = stringResource(R.string.home_disclaimer),
                    style = CastivioType.bodySmall,
                    color = CastivioTheme.colors.onBackgroundMuted,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = padding),
                )
            }
        }
    }
}

/**
 * The brand, and the two facts a viewer cannot get anywhere else.
 *
 * The provider's name and Castivio's own licence, deliberately side by side and
 * deliberately not merged: they are separate systems with separate consequences —
 * a lapsed provider still opens Home, a lapsed licence does not open the app at
 * all — and a single "status" chip covering both would be the first step toward
 * treating them as one thing.
 */
@Composable
private fun DashboardHeader(state: HomeState, modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(
                stringResource(R.string.home_brand),
                style = CastivioType.titleLarge,
                color = colors.secondary,
            )
            Text(
                stringResource(R.string.home_tagline),
                style = CastivioType.labelSmall,
                color = colors.onBackgroundMuted,
            )
        }
        state.provider?.let { MetaChip(it) }
        state.sourceKind?.let { MetaChip(stringResource(it.label)) }
        licenceLabel(state.entitlement)?.let { MetaChip(it, tint = colors.live) }
    }
}

/**
 * What Castivio's own licence says, in one chip, or nothing.
 *
 * Nothing is the right answer for the states that mean "the app should not be
 * open": Home is not where a locked application explains itself, the gate is, and
 * a chip saying "expired" on a screen the user is plainly using would be a
 * contradiction rather than information.
 */
@Composable
private fun licenceLabel(state: EntitlementState?): String? = when (state) {
    is EntitlementState.TrialActive ->
        stringResource(R.string.home_licence_trial, state.daysRemaining)

    is EntitlementState.AnnualActive ->
        stringResource(R.string.home_licence_days, state.daysRemaining)

    EntitlementState.Lifetime -> stringResource(R.string.home_licence_lifetime)

    else -> null
}

/** How a source kind reads in the header. */
private val SourceKind.label: Int
    get() = when (this) {
        SourceKind.M3U_URL -> R.string.home_source_m3u
        SourceKind.LOCAL_FILE -> R.string.home_source_file
        SourceKind.XTREAM -> R.string.home_source_xtream
        SourceKind.PORTAL -> R.string.home_source_portal
    }

/**
 * The four sections as tiles, in one row where there is width and two where there
 * is not.
 *
 * Four across at 960dp gives a tile about 210dp wide, which holds the longest of
 * the section names Castivio ships. On a phone it would be 80, so the same four
 * become a 2×2 — the same tiles at the same height, re-flowed, rather than a second
 * design.
 */
@Composable
private fun SectionTiles(
    state: HomeState,
    onSeeSection: (CatalogSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val device = CastivioTheme.device
    val wide = device == DeviceClass.Television || device == DeviceClass.Expanded

    val tiles = listOf(
        Tile(CatalogSection.Live, Icons.Rounded.LiveTv, colors.hueAzure, R.string.browse_live, state.liveCount, R.string.home_count_live, state.sections[MediaKind.LIVE]),
        Tile(CatalogSection.Movies, Icons.Rounded.Movie, colors.hueViolet, R.string.browse_movies, state.movieCount, R.string.home_count_movies, state.sections[MediaKind.MOVIE]),
        Tile(CatalogSection.Series, Icons.Rounded.Tv, colors.hueGreen, R.string.browse_series, state.seriesCount, R.string.home_count_series, state.sections[MediaKind.SERIES]),
        Tile(CatalogSection.Radio, Icons.Rounded.Radio, colors.hueAmber, R.string.browse_radio, state.radioCount, R.string.home_count_radio, state.sections[MediaKind.RADIO]),
    )

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        for (group in if (wide) listOf(tiles) else tiles.chunked(2)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                for (tile in group) {
                    SectionTile(
                        tile = tile,
                        onClick = { onSeeSection(tile.section) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** One tile's worth of decisions, so the list above reads as a list rather than a wall. */
private data class Tile(
    val section: CatalogSection,
    val icon: ImageVector,
    val hue: Color,
    val name: Int,
    val count: Int,
    val unit: Int,
    /** When this section was brought onto the device, or null if it never has been. */
    val fetchedAtMs: Long?,
)

@Composable
private fun SectionTile(tile: Tile, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    InteractiveGlassCard(
        onClick = onClick,
        modifier = modifier.height(TILE_HEIGHT),
        shape = RoundedCornerShape(Radius.lg),
    ) {
        Column(
            Modifier.fillMaxSize().padding(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .size(TILE_DISC)
                    .clip(CircleShape)
                    .background(colors.discFill(tile.hue))
                    .border(BorderStroke(1.dp, colors.discBorder(tile.hue)), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = tile.icon,
                    contentDescription = null,
                    tint = colors.discGlyph(tile.hue),
                    modifier = Modifier.size(TILE_DISC * DISC_ICON),
                )
            }
            Text(
                text = stringResource(tile.name),
                style = CastivioType.titleMedium,
                color = colors.onBackgroundStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.sm),
            )
            // Three sentences, and the difference between them is the point of the
            // whole lazy fetch. "Not downloaded yet" is an invitation; a count with a
            // date is a fact about this device; a count without one cannot happen.
            if (tile.fetchedAtMs == null) {
                Text(
                    text = stringResource(R.string.home_not_fetched),
                    style = CastivioType.labelSmall,
                    color = colors.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            } else {
                Text(
                    text = stringResource(tile.unit, formatCount(tile.count)),
                    style = CastivioType.labelSmall,
                    color = colors.onBackgroundMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.home_fetched_at, rememberStamp(tile.fetchedAtMs)),
                    style = CastivioType.labelSmall,
                    color = colors.live,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * The three things a viewer does from Home that are not "open a section".
 *
 * Chips rather than a menu: on a remote every level of nesting is a press, and
 * changing the subscription is the one action a first-time user has to be able to
 * find without being told where it lives.
 */
@Composable
private fun QuickActions(
    onAddSource: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = Spacing.xxxl),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CastivioChip(
            text = stringResource(R.string.home_change_source),
            onClick = onAddSource,
            icon = Icons.Rounded.PlaylistAdd,
        )
        CastivioChip(
            text = stringResource(R.string.search_label),
            onClick = onSearch,
            icon = Icons.Rounded.Search,
        )
        CastivioChip(
            text = stringResource(R.string.home_settings),
            onClick = onSettings,
            icon = Icons.Rounded.Settings,
        )
    }
}

@Composable
private fun ChannelRow(
    title: String,
    count: Int,
    items: List<MediaItem>,
    onPlay: (CatalogSelection) -> Unit,
) {
    if (items.isEmpty()) return
    MediaRow(title = title, items = items, key = { it.id }, count = count) { item ->
        val selection = item.asSelection() ?: return@MediaRow
        ChannelCard(
            name = item.title,
            nowPlaying = selection.channelNumber.orEmpty(),
            seed = item.id.hashCode(),
            onClick = { onPlay(selection) },
        )
    }
}

@Composable
private fun PosterRow(
    title: String,
    count: Int,
    items: List<MediaItem>,
    onPlay: (CatalogSelection) -> Unit,
) {
    if (items.isEmpty()) return
    MediaRow(title = title, items = items, key = { it.id }, count = count) { item ->
        val selection = item.asSelection() ?: return@MediaRow
        MediaCard(
            title = item.title,
            subtitle = selection.subtitle,
            shape = CardShape.Poster,
            width = 120.dp,
            artworkSeed = item.id.hashCode(),
            onClick = { onPlay(selection) },
        )
    }
}

/**
 * An instant, in the reader's own locale, formatted once.
 *
 * `remember` keyed on the value, because building a `DateFormat` and running it is
 * real work and composition runs whenever anything on this screen moves — a count
 * arriving from an import behind the screen would otherwise reformat four dates on
 * every emission, which is exactly the per-frame work the performance budget bans.
 *
 * The locale comes from the configuration rather than the default, so the date
 * follows the language Castivio is in and not the one the box was set up in.
 */
@Composable
private fun rememberStamp(atMs: Long): String {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current
    return remember(atMs, locale) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(atMs))
    }
}

/**
 * A tile's height, and the disc inside it.
 *
 * Fixed rather than intrinsic so the four are identical whatever their labels wrap
 * to: four tiles of three different heights is what a row of `wrapContentHeight`
 * cards produces the first time a translation is longer than English.
 */
private val TILE_HEIGHT: Dp = 148.dp
private val TILE_DISC: Dp = 52.dp

/** A glyph inside its disc, as a fraction of the disc — the chooser cards' own ratio. */
private const val DISC_ICON = 0.5f
