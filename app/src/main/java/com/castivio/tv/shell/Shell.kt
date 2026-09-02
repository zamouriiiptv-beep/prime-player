package com.castivio.tv.shell

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.castivio.core.design.components.CastivioShell
import com.castivio.core.design.components.EmptyState
import com.castivio.core.design.components.IconLabel
import com.castivio.core.design.components.MediaCard
import com.castivio.core.design.components.CardShape
import com.castivio.core.design.components.NowPlayingBadge
import com.castivio.core.design.components.NavAction
import com.castivio.core.design.components.SectionHeader
import com.castivio.core.design.components.WatchState
import com.castivio.core.design.components.WatchedTag
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.DeviceClass
import com.castivio.core.design.theme.MotionLevel
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Spacing
import com.castivio.core.navigation.BackPolicy
import com.castivio.core.navigation.ShellBack
import com.castivio.domain.SeriesSummary
import com.castivio.feature.home.BrowseScreen
import com.castivio.feature.home.CatalogSearchScreen
import com.castivio.feature.home.CatalogSection
import com.castivio.feature.home.CatalogSelection
import com.castivio.feature.home.HomeScreen
import com.castivio.feature.home.ShowScreen
import com.castivio.feature.home.R as CatalogStrings
import com.castivio.feature.licence.R as LicenceStrings
import com.castivio.feature.player.PlayerRequest
import com.castivio.feature.player.PlayerRoute
import com.castivio.playback.api.MediaKind
import com.castivio.tv.licence.LicenceWithLanguage

/** The top-level destinations the shell can be on. */
private enum class Dest { Home, Live, Movies, Series, Radio, Favourites, Library, Search, Settings }

/** An overlay drawn above the shell: a show's episodes, the player, or Settings' extras. */
private sealed interface Overlay {
    /** One show's seasons. Series rows are not streams, so a press has to land here. */
    data class Show(val show: SeriesSummary) : Overlay

    /** A real stream, with everything the engine is allowed to be given. */
    data class Play(val request: PlayerRequest) : Overlay

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
 * The application shell, over the provider's real catalogue.
 *
 * Every section here reads the database the activation flow imported into: categories
 * from the group table, rows from the pager, counts from an indexed `COUNT`, and a
 * press opens the provider's own stream URL in the real engine. Nothing on this screen
 * is a fixture any more, which is what the comment on `MainActivity` used to promise
 * and this slice delivers.
 *
 * Back follows the rule [BackPolicy] encodes: an overlay pops, a section returns Home,
 * and Home asks whether to leave.
 */
@Composable
fun ShellScreen(
    motionLevel: MotionLevel,
    onMotionLevel: (MotionLevel) -> Unit,
    /**
     * Ask to leave Castivio.
     *
     * Ask, not leave. The confirmation is the application's now and is drawn
     * above this screen; what happens once the user answers it is not the
     * shell's business, which is why this used to be `finish()` and is not.
     */
    onExit: () -> Unit,
) {
    var dest by remember { mutableStateOf(Dest.Home) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    val device = CastivioTheme.device
    val phone = device == DeviceClass.Compact || device == DeviceClass.Medium

    // ## Back, and the one place it asks before it acts
    //
    // Always enabled now, where it used to stand aside at the root and let the
    // system close the app. The ladder is: an overlay, then a section, then the
    // root — and only at the root is there nothing left to go back *to*, which
    // is the whole condition for asking.
    //
    // Asking anywhere else would be the familiar mistake of confirming a
    // navigation, and on a remote — where back is the most-pressed key on the
    // device — a dialog between the user and Home is a dialog they learn to
    // dismiss without reading.
    BackHandler(enabled = true) {
        when (
            BackPolicy.fromShell(
                overlayOpen = overlay != null,
                atRoot = dest == Dest.Home,
            )
        ) {
            ShellBack.CloseOverlay -> overlay = null
            ShellBack.GoToRoot -> dest = Dest.Home
            ShellBack.ConfirmExit -> onExit()
        }
    }

    // One conversion, in the one place that knows both the catalogue and the player.
    //
    // `:feature:home` must not depend on `:feature:player`, so a press arrives here as
    // what the row already had on screen and becomes a request here -- exactly as a
    // local file does in `PlayerHost`. The player still fetches nothing before its
    // first frame, because there is nothing left for it to fetch.
    val play: (CatalogSelection) -> Unit = { selection ->
        overlay = Overlay.Play(selection.asPlayerRequest())
    }

    val destinations = if (phone) {
        listOf(
            navAction(Icons.Filled.Home, "Home") { dest = Dest.Home },
            navAction(Icons.Filled.LiveTv, stringResource(CatalogStrings.string.browse_live)) { dest = Dest.Live },
            navAction(Icons.Filled.VideoLibrary, "Library") { dest = Dest.Library },
            navAction(Icons.Filled.Search, stringResource(CatalogStrings.string.search_label)) { dest = Dest.Search },
            navAction(Icons.Filled.Settings, "Settings") { dest = Dest.Settings },
        )
    } else {
        listOf(
            navAction(Icons.Filled.Home, "Home") { dest = Dest.Home },
            navAction(Icons.Filled.LiveTv, stringResource(CatalogStrings.string.browse_live)) { dest = Dest.Live },
            navAction(Icons.Filled.Movie, stringResource(CatalogStrings.string.browse_movies)) { dest = Dest.Movies },
            navAction(Icons.Filled.Tv, stringResource(CatalogStrings.string.browse_series)) { dest = Dest.Series },
            navAction(Icons.Filled.Radio, stringResource(CatalogStrings.string.browse_radio)) { dest = Dest.Radio },
            navAction(Icons.Filled.Favorite, "Favourites") { dest = Dest.Favourites },
            navAction(Icons.Filled.Search, stringResource(CatalogStrings.string.search_label)) { dest = Dest.Search },
            navAction(Icons.Filled.Settings, "Settings") { dest = Dest.Settings },
        )
    }
    val selectedIndex = if (phone) phoneIndex(dest) else railIndex(dest)

    Box(Modifier.fillMaxSize()) {
        CastivioShell(destinations = destinations, selectedIndex = selectedIndex) {
            when (dest) {
                Dest.Home -> HomeScreen(
                    onPlay = play,
                    onSeeSection = { dest = it.destination },
                )
                Dest.Live -> BrowseScreen(
                    section = CatalogSection.Live,
                    onPlay = play,
                    onOpenShow = { overlay = Overlay.Show(it) },
                )
                Dest.Movies -> BrowseScreen(
                    section = CatalogSection.Movies,
                    onPlay = play,
                    onOpenShow = { overlay = Overlay.Show(it) },
                )
                Dest.Series -> BrowseScreen(
                    section = CatalogSection.Series,
                    onPlay = play,
                    onOpenShow = { overlay = Overlay.Show(it) },
                )
                Dest.Radio -> BrowseScreen(
                    section = CatalogSection.Radio,
                    onPlay = play,
                    onOpenShow = { overlay = Overlay.Show(it) },
                )
                Dest.Favourites -> FavouritesScreen()
                Dest.Library -> LibraryScreen(onOpenSection = { dest = it })
                Dest.Search -> CatalogSearchScreen(onPlay = play)
                Dest.Settings -> SettingsScreen(
                    motionLevel = motionLevel,
                    onMotionLevel = onMotionLevel,
                    onShowStateBoard = { overlay = Overlay.StateBoard },
                    onShowLicence = { overlay = Overlay.Licence },
                )
            }
        }

        when (val o = overlay) {
            is Overlay.Show -> ShowScreen(
                show = o.show,
                onPlay = play,
                onBack = { overlay = null },
            )
            is Overlay.Play -> PlayerRoute(request = o.request, onLeave = { overlay = null })
            is Overlay.StateBoard -> StateBoardOverlay(onBack = { overlay = null })
            // Reached from a working app, so leaving means returning to
            // Settings. Reached from the gate it means leaving Castivio, and
            // that difference is the caller's -- the screen itself has no
            // opinion about where back goes.
            is Overlay.Licence -> LicenceWithLanguage(onLeave = { overlay = null })
            null -> {}
        }
    }
}

/**
 * A catalogue press, as the player's request.
 *
 * `SERIES_EPISODE` rather than `VOD` for an episode: the engine reads it to decide
 * what "next" means, and a season that behaves like a single film is the bug this
 * distinction prevents.
 */
private fun CatalogSelection.asPlayerRequest() = PlayerRequest(
    url = url,
    title = title,
    kind = when {
        live -> MediaKind.LIVE
        subtitle?.startsWith('S') == true || subtitle?.startsWith('E') == true -> MediaKind.SERIES_EPISODE
        else -> MediaKind.VOD
    },
    subtitle = subtitle,
    channelNumber = channelNumber,
    epgChannelId = epgChannelId,
    catchUpHours = catchUpHours,
)

/** Which rail entry a section belongs to, so Home's "see all" lands somewhere real. */
private val CatalogSection.destination: Dest
    get() = when (this) {
        CatalogSection.Live -> Dest.Live
        CatalogSection.Movies -> Dest.Movies
        CatalogSection.Series -> Dest.Series
        CatalogSection.Radio -> Dest.Radio
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

// -------------------------------------------------------------------- sections

/**
 * Favourites, which is empty because nothing can be favourited yet.
 *
 * Drawn rather than hidden, and saying exactly that. A destination that vanishes when
 * it has no content teaches people the app is unreliable; one that explains itself
 * costs a sentence. The store and the paged reader for this already exist — what is
 * missing is the control that adds to it, and that is the next slice rather than
 * something to fake here.
 */
@Composable
private fun FavouritesScreen() {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(CastivioTheme.device.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        SectionHeader(title = "Favourites", count = 0)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                title = "Nothing favourited yet",
                detail = "Marking a channel or a film as a favourite arrives with the next " +
                    "update. Until then this list stays empty rather than showing you " +
                    "something you did not choose.",
                actionLabel = "OK",
                onAction = {},
            )
        }
    }
}

@Composable
private fun LibraryScreen(onOpenSection: (Dest) -> Unit) {
    val entries = listOf(
        Triple(Icons.Filled.Movie, stringResource(CatalogStrings.string.browse_movies), Dest.Movies),
        Triple(Icons.Filled.Tv, stringResource(CatalogStrings.string.browse_series), Dest.Series),
        Triple(Icons.Filled.Radio, stringResource(CatalogStrings.string.browse_radio), Dest.Radio),
        Triple(Icons.Filled.Favorite, "Favourites", Dest.Favourites),
    )
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(CastivioTheme.device.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        SectionHeader(title = "Library")
        entries.forEach { (icon, label, target) ->
            SettingRow(icon = icon, label = label, onClick = { onOpenSection(target) })
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
            .padding(CastivioTheme.device.screenPadding),
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
        SettingRow(icon = Icons.Filled.Settings, label = "Version", value = "1.0.0")
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

/**
 * The four readings of an item's history, in one place.
 *
 * A design surface rather than a product one, reached from Settings, and it is the one
 * screen in the shell that is allowed to draw cards with no data behind them: what it
 * is showing *is* the state language.
 */
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
            .padding(CastivioTheme.device.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Text("State language", style = CastivioType.headlineSmall, color = colors.onBackground)
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
        IconLabel(Icons.Filled.LiveTv, "Aqua is now · violet is navigation · neutral is the past")
    }
}
