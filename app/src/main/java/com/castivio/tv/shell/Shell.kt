package com.castivio.tv.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.castivio.core.common.EmptyReason
import com.castivio.core.common.ScreenState
import com.castivio.core.design.components.ButtonWeight
import com.castivio.core.navigation.BackPolicy
import com.castivio.core.navigation.ShellBack
import com.castivio.tv.R
import com.castivio.tv.licence.LicenceWithLanguage
import com.castivio.feature.licence.R as LicenceStrings
import com.castivio.core.design.components.CardShape
import com.castivio.core.design.components.CastivioButton
import com.castivio.core.design.components.CastivioDialog
import com.castivio.core.design.components.ChannelCard
import com.castivio.core.design.components.EmptyState
import com.castivio.core.design.components.IconLabel
import com.castivio.core.design.components.LiveDot
import com.castivio.core.design.components.MediaCard
import com.castivio.core.design.components.MediaRow
import com.castivio.core.design.components.MetaChip
import com.castivio.core.design.components.NavAction
import com.castivio.core.design.components.NowPlayingBadge
import com.castivio.core.design.components.CastivioShell
import com.castivio.core.design.components.ScreenScaffold
import com.castivio.core.design.components.ScreenTopBar
import com.castivio.core.design.components.SectionHeader
import com.castivio.core.design.components.WatchState
import com.castivio.core.design.components.WatchedTag
import com.castivio.core.design.components.formatCount
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.DeviceClass
import com.castivio.core.design.theme.MotionLevel
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Spacing
import com.castivio.core.design.theme.posterPlaceholderBrush

/** The top-level destinations the demo can be on. */
private enum class Dest { Home, Live, Movies, Series, Radio, Favourites, Library, Search, Settings }

/** An overlay drawn above the shell: a detail page or the player. */
private sealed interface Overlay {
    data class Detail(val poster: DemoPoster) : Overlay
    data class Player(val title: String) : Overlay
    data object StateBoard : Overlay

    /**
     * Castivio's own licence, reached from Settings.
     *
     * An overlay rather than a rail destination, and that is the design decision
     * rather than an implementation shortcut: the licence screen owns the whole
     * viewport -- immersive, full-bleed, no scroll, hairlines edge to edge -- and
     * a screen drawn inside the rail and the bottom bar would be a different
     * composition from the one that was approved and measured. Back returns to
     * Settings, which is where it was opened from.
     */
    data object Licence : Overlay
}

/**
 * The UX-validation shell.
 *
 * Everything the specification calls the shell — adaptive navigation, the state
 * language, ScreenScaffold, the rail and bottom bar — driven by mock data so the
 * feel can be judged on a real phone before a provider is wired in. Back follows
 * the same rule [com.castivio.core.navigation.BackPolicy] encodes: an overlay pops,
 * a section returns Home, and Home lets the system handle it.
 */
@Composable
fun ShellScreen(
    motionLevel: MotionLevel,
    onMotionLevel: (MotionLevel) -> Unit,
    onExit: () -> Unit,
) {
    var dest by remember { mutableStateOf(Dest.Home) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var confirmingExit by remember { mutableStateOf(false) }
    val device = CastivioTheme.device
    val phone = device == DeviceClass.Compact || device == DeviceClass.Medium

    // ## Back, and the one place it asks before it acts
    //
    // Always enabled now, where it used to stand aside at the root and let the
    // system close the app. The ladder is: an open dialog, then an overlay, then
    // a section, then the root — and only at the root is there nothing left to
    // go back *to*, which is the whole condition for asking.
    //
    // Asking anywhere else would be the familiar mistake of confirming a
    // navigation, and on a remote — where back is the most-pressed key on the
    // device — a dialog between the user and Home is a dialog they learn to
    // dismiss without reading.
    BackHandler(enabled = true) {
        when (
            BackPolicy.fromShell(
                dialogOpen = confirmingExit,
                overlayOpen = overlay != null,
                atRoot = dest == Dest.Home,
            )
        ) {
            ShellBack.CloseDialog -> confirmingExit = false
            ShellBack.CloseOverlay -> overlay = null
            ShellBack.GoToRoot -> dest = Dest.Home
            ShellBack.ConfirmExit -> confirmingExit = true
        }
    }

    val destinations = if (phone) {
        listOf(
            navAction(Icons.Filled.Home, "Home") { dest = Dest.Home },
            navAction(Icons.Filled.LiveTv, "Live") { dest = Dest.Live },
            navAction(Icons.Filled.VideoLibrary, "Library") { dest = Dest.Library },
            navAction(Icons.Filled.Search, "Search") { dest = Dest.Search },
            navAction(Icons.Filled.Settings, "Settings") { dest = Dest.Settings },
        )
    } else {
        listOf(
            navAction(Icons.Filled.Home, "Home") { dest = Dest.Home },
            navAction(Icons.Filled.LiveTv, "Live TV") { dest = Dest.Live },
            navAction(Icons.Filled.Movie, "Movies") { dest = Dest.Movies },
            navAction(Icons.Filled.Tv, "Series") { dest = Dest.Series },
            navAction(Icons.Filled.Radio, "Radio") { dest = Dest.Radio },
            navAction(Icons.Filled.Favorite, "Favourites") { dest = Dest.Favourites },
            navAction(Icons.Filled.Search, "Search") { dest = Dest.Search },
            navAction(Icons.Filled.Settings, "Settings") { dest = Dest.Settings },
        )
    }
    val selectedIndex = if (phone) phoneIndex(dest) else railIndex(dest)

    Box(Modifier.fillMaxSize()) {
        CastivioShell(destinations = destinations, selectedIndex = selectedIndex) {
            when (dest) {
                Dest.Home -> HomeScreen(
                    onOpen = { overlay = Overlay.Detail(it) },
                    onPlay = { overlay = Overlay.Player(it) },
                    onSeeSection = { dest = it },
                )
                Dest.Live -> LiveScreen(onPlay = { overlay = Overlay.Player(it) })
                Dest.Movies -> PosterSectionScreen(
                    title = "Movies",
                    count = DemoCatalog.MOVIE_COUNT,
                    posters = DemoCatalog.movies,
                    onOpen = { overlay = Overlay.Detail(it) },
                )
                Dest.Series -> PosterSectionScreen(
                    title = "Series",
                    count = DemoCatalog.SERIES_COUNT,
                    posters = DemoCatalog.series,
                    onOpen = { overlay = Overlay.Detail(it) },
                )
                Dest.Favourites -> PosterSectionScreen(
                    title = "Favourites",
                    count = DemoCatalog.FAVOURITE_COUNT,
                    posters = DemoCatalog.movies.take(4) + DemoCatalog.series.take(3),
                    onOpen = { overlay = Overlay.Detail(it) },
                )
                Dest.Radio -> EmptySectionScreen(
                    title = "Radio",
                    onBrowseLive = { dest = Dest.Live },
                )
                Dest.Library -> LibraryScreen(onOpenSection = { dest = it })
                Dest.Search -> SearchScreen(onOpen = { overlay = Overlay.Detail(it) })
                Dest.Settings -> SettingsScreen(
                    motionLevel = motionLevel,
                    onMotionLevel = onMotionLevel,
                    onShowStateBoard = { overlay = Overlay.StateBoard },
                    onShowLicence = { overlay = Overlay.Licence },
                )
            }
        }

        when (val o = overlay) {
            is Overlay.Detail -> DetailOverlay(
                poster = o.poster,
                onPlay = { overlay = Overlay.Player(o.poster.title) },
                onBack = { overlay = null },
            )
            is Overlay.Player -> PlayerOverlay(title = o.title, onBack = { overlay = null })
            is Overlay.StateBoard -> StateBoardOverlay(onBack = { overlay = null })
            // Reached from a working app, so leaving means returning to
            // Settings. Reached from the gate it means leaving Castivio, and
            // that difference is the caller's -- the screen itself has no
            // opinion about where back goes.
            is Overlay.Licence -> LicenceWithLanguage(onLeave = { overlay = null })
            null -> {}
        }

        // Drawn last, so it is over the rail, the bar and any overlay. Back is
        // handled by the ladder above rather than by the dialog, because only
        // this screen knows what else back might have meant here.
        if (confirmingExit) {
            CastivioDialog(
                title = stringResource(R.string.exit_title),
                message = stringResource(R.string.exit_message),
                confirmLabel = stringResource(R.string.exit_confirm),
                dismissLabel = stringResource(R.string.exit_cancel),
                onConfirm = onExit,
                onDismiss = { confirmingExit = false },
            )
        }
    }
}

private fun navAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) = NavAction(icon = icon, label = label, onClick = onClick)

private fun phoneIndex(dest: Dest): Int = when (dest) {
    Dest.Home -> 0
    Dest.Live -> 1
    Dest.Movies, Dest.Series, Dest.Radio, Dest.Favourites, Dest.Library -> 2
    Dest.Search -> 3
    Dest.Settings -> 4
}

private fun railIndex(dest: Dest): Int = when (dest) {
    Dest.Home, Dest.Library -> 0
    Dest.Live -> 1
    Dest.Movies -> 2
    Dest.Series -> 3
    Dest.Radio -> 4
    Dest.Favourites -> 5
    Dest.Search -> 6
    Dest.Settings -> 7
}

// ----------------------------------------------------------------------- Home

@Composable
private fun HomeScreen(
    onOpen: (DemoPoster) -> Unit,
    onPlay: (String) -> Unit,
    onSeeSection: (Dest) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screen, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("CASTIVIO", style = CastivioType.titleLarge, color = CastivioTheme.colors.secondary)
            Box(Modifier.weight(1f))
            MetaChip(DemoCatalog.PROVIDER)
        }

        Spotlight(
            onWatch = { onPlay(DemoCatalog.spotlightTitle) },
            modifier = Modifier.padding(horizontal = Spacing.screen),
        )

        MediaRow(
            title = "Continue watching",
            items = DemoCatalog.continueWatching,
            key = { it.id },
            count = DemoCatalog.continueWatching.size,
        ) { item ->
            MediaCard(
                title = item.title,
                subtitle = item.subtitle,
                caption = item.caption,
                shape = CardShape.Landscape,
                width = 200.dp,
                artworkSeed = item.seed,
                watchState = WatchState.InProgress(item.progress),
                onClick = { onPlay(item.title) },
            )
        }

        MediaRow(
            title = "Live TV",
            items = DemoCatalog.liveChannels,
            key = { it.id },
            count = DemoCatalog.LIVE_COUNT,
        ) { ch ->
            ChannelCard(
                name = ch.name,
                nowPlaying = ch.nowPlaying,
                number = ch.number,
                seed = ch.seed,
                watchState = ch.watch,
                onClick = { onPlay(ch.name) },
            )
        }

        MediaRow(
            title = "Movies",
            items = DemoCatalog.movies,
            key = { it.id },
            count = DemoCatalog.MOVIE_COUNT,
        ) { m ->
            MediaCard(
                title = m.title,
                subtitle = m.subtitle,
                shape = CardShape.Poster,
                width = 120.dp,
                artworkSeed = m.seed,
                watchState = m.watch,
                onClick = { onOpen(m) },
            )
        }

        MediaRow(
            title = "Series",
            items = DemoCatalog.series,
            key = { it.id },
            count = DemoCatalog.SERIES_COUNT,
        ) { s ->
            MediaCard(
                title = s.title,
                subtitle = s.subtitle,
                shape = CardShape.Poster,
                width = 120.dp,
                artworkSeed = s.seed,
                watchState = s.watch,
                onClick = { onOpen(s) },
            )
        }
    }
}

@Composable
private fun Spotlight(onWatch: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    val phone = CastivioTheme.device == DeviceClass.Compact
    Box(
        modifier
            .fillMaxWidth()
            .height(if (phone) 200.dp else 232.dp)
            .clip(RoundedCornerShape(Radius.xxl))
            .background(posterPlaceholderBrush(1))
            .border(1.dp, colors.glassBorderSoft, RoundedCornerShape(Radius.xxl)),
    ) {
        // A scrim so text stays legible over artwork.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(colors.background.copy(alpha = 0.95f), colors.background.copy(alpha = 0.15f)),
                    ),
                ),
        )
        Column(
            Modifier
                .fillMaxWidth(if (phone) 1f else 0.62f)
                .align(Alignment.CenterStart)
                .padding(Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(colors.live.copy(alpha = 0.16f))
                        .border(1.dp, colors.live.copy(alpha = 0.45f), RoundedCornerShape(Radius.pill))
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
                ) {
                    LiveDot()
                    Text("LIVE", style = CastivioType.labelSmall, color = colors.live)
                }
                Text(
                    "${DemoCatalog.spotlightChannel} · ${DemoCatalog.spotlightNumber}",
                    style = CastivioType.bodySmall,
                    color = colors.onBackgroundVariant,
                )
            }
            Text(
                DemoCatalog.spotlightTitle,
                style = CastivioType.headlineSmall,
                color = colors.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(DemoCatalog.spotlightStart, style = CastivioType.bodySmall, color = colors.onBackgroundMuted)
                Box(
                    Modifier
                        .width(120.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(colors.onBackground.copy(alpha = 0.16f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(DemoCatalog.spotlightProgress)
                            .height(4.dp)
                            .clip(RoundedCornerShape(Radius.pill))
                            .background(colors.secondary),
                    )
                }
                Text(DemoCatalog.spotlightEnd, style = CastivioType.bodySmall, color = colors.onBackgroundMuted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                CastivioButton(
                    text = "Watch",
                    icon = Icons.Filled.PlayArrow,
                    weight = ButtonWeight.Primary,
                    onClick = onWatch,
                )
                CastivioButton(text = "Guide", weight = ButtonWeight.Secondary, onClick = onWatch)
            }
        }
    }
}

// -------------------------------------------------------------------- sections

@Composable
private fun PosterSectionScreen(
    title: String,
    count: Int,
    posters: List<DemoPoster>,
    onOpen: (DemoPoster) -> Unit,
) {
    val cols = CastivioTheme.device.gridColumns
    LazyVerticalGrid(
        columns = GridCells.Fixed(cols),
        contentPadding = PaddingValues(Spacing.screen),
        horizontalArrangement = Arrangement.spacedBy(Spacing.gridGutter),
        verticalArrangement = Arrangement.spacedBy(Spacing.gridGutter),
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                SectionHeader(title = title, count = count)
                CategoryRow(DemoCatalog.movieCategories)
            }
        }
        gridItems(posters, key = { it.id }) { p ->
            MediaCard(
                title = p.title,
                subtitle = p.subtitle,
                shape = CardShape.Poster,
                width = 200.dp,
                artworkSeed = p.seed,
                watchState = p.watch,
                onClick = { onOpen(p) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LiveScreen(onPlay: (String) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                SectionHeader(title = "Live TV", count = DemoCatalog.LIVE_COUNT)
                CategoryRow(DemoCatalog.liveCategories)
                Box(Modifier.height(Spacing.xs))
            }
        }
        items(DemoCatalog.liveChannels, key = { it.id }) { ch ->
            ChannelCard(
                name = ch.name,
                nowPlaying = ch.nowPlaying,
                number = ch.number,
                seed = ch.seed,
                watchState = ch.watch,
                width = 520.dp,
                onClick = { onPlay(ch.name) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun EmptySectionScreen(title: String, onBrowseLive: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenTopBar(title = title, subtitle = "· 0 titles")
        ScreenScaffold(
            state = ScreenState.Empty(
                reason = EmptyReason.PROVIDER_HAS_NO_CONTENT,
                providerLabel = DemoCatalog.PROVIDER,
            ),
            onAction = onBrowseLive,
            empty = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = "${DemoCatalog.PROVIDER} doesn't include radio",
                        detail = "This provider carries live channels, movies and series only. " +
                            "Your other sections are unaffected — and adding a second provider " +
                            "brings its radio here.",
                        actionLabel = "Browse Live TV",
                        onAction = onBrowseLive,
                        secondaryActionLabel = "Add a provider",
                        onSecondaryAction = onBrowseLive,
                    )
                }
            },
            content = { _, _ -> },
        )
    }
}

@Composable
private fun LibraryScreen(onOpenSection: (Dest) -> Unit) {
    val entries = listOf(
        Triple(Icons.Filled.Movie, "Movies", Dest.Movies),
        Triple(Icons.Filled.Tv, "Series", Dest.Series),
        Triple(Icons.Filled.Radio, "Radio", Dest.Radio),
        Triple(Icons.Filled.Favorite, "Favourites", Dest.Favourites),
    )
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        SectionHeader(title = "Library")
        entries.forEach { (icon, label, target) ->
            SettingRow(icon = icon, label = label, onClick = { onOpenSection(target) })
        }
    }
}

@Composable
private fun CategoryRow(categories: List<DemoCategory>) {
    var selected by remember { mutableStateOf(categories.first().id) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        items(categories, key = { it.id }) { c ->
            CategoryChip(
                label = c.label,
                count = c.count,
                selected = c.id == selected,
                onClick = { selected = c.id },
            )
        }
    }
}

@Composable
private fun CategoryChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(if (selected) colors.glassFillStrong else colors.glassFill)
            .border(
                1.dp,
                if (selected) colors.glassBorder else colors.glassBorderSoft,
                RoundedCornerShape(Radius.pill),
            )
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        Text(
            label,
            style = CastivioType.labelLarge,
            color = if (selected) colors.onBackground else colors.onBackgroundVariant,
        )
        Text(formatCount(count), style = CastivioType.labelSmall, color = colors.onBackgroundMuted)
    }
}

// ------------------------------------------------------------------- search

@Composable
private fun SearchScreen(onOpen: (DemoPoster) -> Unit) {
    var query by remember { mutableStateOf("") }
    val colors = CastivioTheme.colors
    val results = remember(query) {
        if (query.isBlank()) emptyList()
        else DemoCatalog.searchable.filter { it.title.contains(query, ignoreCase = true) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.pill))
                .background(colors.glassFill)
                .border(1.dp, colors.glassBorder, RoundedCornerShape(Radius.pill))
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        ) {
            Icon(Icons.Filled.Search, null, tint = colors.onBackgroundMuted, modifier = Modifier.size(20.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text("Search channels, movies, series", style = CastivioType.bodyMedium, color = colors.onBackgroundMuted)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    cursorBrush = SolidColor(colors.primary),
                    textStyle = CastivioType.bodyMedium.copy(color = colors.onBackground),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        when {
            query.isBlank() -> Text(
                "Results appear as you type — no button, no wait.",
                style = CastivioType.bodyMedium,
                color = colors.onBackgroundMuted,
            )
            results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "No results for “$query”",
                    detail = "Nothing matched. Try fewer letters.",
                    actionLabel = "Clear",
                    onAction = { query = "" },
                )
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                item {
                    SectionHeader(title = "Results", count = results.size)
                }
                items(results, key = { it.id }) { r ->
                    ChannelCard(
                        name = r.title,
                        nowPlaying = r.subtitle,
                        seed = r.seed,
                        width = 520.dp,
                        onClick = { onOpen(r) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ settings

@Composable
private fun SettingsScreen(
    motionLevel: MotionLevel,
    onMotionLevel: (MotionLevel) -> Unit,
    onShowStateBoard: () -> Unit,
    onShowLicence: () -> Unit,
) {
    val colors = CastivioTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        SectionHeader(title = "Settings")

        Text("Motion", style = CastivioType.titleMedium, color = colors.onBackground)
        Text(
            "Three levels, each fully usable. Change it and watch the backdrop and the " +
                "playing meter respond.",
            style = CastivioType.bodySmall,
            color = colors.onBackgroundMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            MotionLevel.entries.forEach { level ->
                CategoryChipPlain(
                    label = level.name.lowercase().replaceFirstChar { it.uppercase() },
                    selected = level == motionLevel,
                    onClick = { onMotionLevel(level) },
                )
            }
        }

        Text("Player", style = CastivioType.titleMedium, color = colors.onBackground)
        SettingRow(icon = Icons.Filled.PlayArrow, label = "Internal player", value = "Default")

        Text("Design", style = CastivioType.titleMedium, color = colors.onBackground)
        SettingRow(
            icon = Icons.Filled.VideoLibrary,
            label = "Show the state language",
            onClick = onShowStateBoard,
        )
        // Castivio's licence, which is not the provider's subscription. The two
        // are separate systems and this row says so by living under its own
        // heading rather than beside the playlist.
        Text("Licence", style = CastivioType.titleMedium, color = colors.onBackground)
        SettingRow(
            icon = Icons.Filled.Settings,
            label = stringResource(LicenceStrings.string.licence_title),
            onClick = onShowLicence,
        )

        SettingRow(icon = Icons.Filled.Settings, label = "Device class", value = CastivioTheme.device.name)
        SettingRow(icon = Icons.Filled.Settings, label = "Version", value = "0.1 · shell preview")
    }
}

@Composable
private fun SettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(colors.glassFill)
            .border(1.dp, colors.glassBorderSoft, RoundedCornerShape(Radius.md))
            .then(
                if (onClick != null) Modifier.clickable(interaction, indication = null, onClick = onClick)
                else Modifier,
            )
            .padding(Spacing.lg),
    ) {
        Icon(icon, null, tint = colors.secondary, modifier = Modifier.size(20.dp))
        Text(label, style = CastivioType.bodyMedium, color = colors.onBackground, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(value, style = CastivioType.bodySmall, color = colors.onBackgroundMuted)
        }
    }
}

@Composable
private fun CategoryChipPlain(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Text(
        label,
        style = CastivioType.labelLarge,
        color = if (selected) colors.onBackground else colors.onBackgroundVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(if (selected) colors.secondaryContainer.copy(alpha = 0.6f) else colors.glassFill)
            .border(
                1.dp,
                if (selected) colors.secondary else colors.glassBorderSoft,
                RoundedCornerShape(Radius.pill),
            )
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
    )
}

// ------------------------------------------------------------------ overlays

@Composable
private fun DetailOverlay(poster: DemoPoster, onPlay: () -> Unit, onBack: () -> Unit) {
    val colors = CastivioTheme.colors
    BackHandler(onBack = onBack)
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onBack)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .padding(horizontal = Spacing.screen)
                .clip(RoundedCornerShape(Radius.xl))
                .background(posterPlaceholderBrush(poster.seed)),
        )
        Column(
            Modifier.padding(Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(poster.title, style = CastivioType.headlineMedium, color = colors.onBackground)
            Text(poster.subtitle, style = CastivioType.bodyMedium, color = colors.onBackgroundMuted)
            Text(
                "A demo detail page — enough to exercise the push, the back gesture and the " +
                    "play hand-off while the shell is being evaluated.",
                style = CastivioType.bodyMedium,
                color = colors.onBackgroundVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                CastivioButton("Play", icon = Icons.Filled.PlayArrow, weight = ButtonWeight.Primary, onClick = onPlay)
                CastivioButton("Favourite", weight = ButtonWeight.Secondary, onClick = onBack)
            }
        }
    }
}

@Composable
private fun PlayerOverlay(title: String, onBack: () -> Unit) {
    val colors = CastivioTheme.colors
    BackHandler(onBack = onBack)
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            NowPlayingBadge()
            Text(title, style = CastivioType.headlineSmall, color = colors.onBackground)
            Text(
                "Player placeholder — the internal engine lands in a later slice.",
                style = CastivioType.bodyMedium,
                color = colors.onBackgroundMuted,
            )
            CastivioButton("Back", weight = ButtonWeight.Secondary, onClick = onBack)
        }
        Box(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(Spacing.md)) {
            BackButton(onBack)
        }
    }
}

@Composable
private fun StateBoardOverlay(onBack: () -> Unit) {
    val colors = CastivioTheme.colors
    BackHandler(onBack = onBack)
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            BackButton(onBack)
            Text("State language", style = CastivioType.headlineSmall, color = colors.onBackground)
        }
        Text(
            "Four readings of an item's history, one grammar, one place. Switch Motion to " +
                "Disabled in Settings — all four still read.",
            style = CastivioType.bodyMedium,
            color = colors.onBackgroundVariant,
        )
        val samples = listOf(
            "Not started" to WatchState.None,
            "In progress" to WatchState.InProgress(0.58f),
            "Recently watched" to WatchState.Watched,
            "Playing now" to WatchState.Playing,
        )
        samples.forEach { (label, state) ->
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                MediaCard(
                    title = label,
                    shape = CardShape.Landscape,
                    width = 240.dp,
                    artworkSeed = 2,
                    watchState = state,
                    badge = when (state) {
                        is WatchState.Playing -> {
                            { NowPlayingBadge() }
                        }
                        is WatchState.Watched -> {
                            { WatchedTag("Watched") }
                        }
                        else -> null
                    },
                    onClick = onBack,
                )
            }
        }
        IconLabel(Icons.Filled.LiveTv, "Aqua is now · violet is navigation · neutral is the past")
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(colors.glassFill)
            .border(1.dp, colors.glassBorderSoft, RoundedCornerShape(Radius.pill))
            .clickable(interaction, indication = null, onClick = onBack)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = colors.onBackground, modifier = Modifier.size(18.dp))
        Text("Back", style = CastivioType.labelLarge, color = colors.onBackground)
    }
}
