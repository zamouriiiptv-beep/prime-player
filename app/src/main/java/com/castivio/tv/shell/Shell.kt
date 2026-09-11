package com.castivio.tv.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
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
import com.castivio.core.common.AppError
import com.castivio.core.common.ScreenState
import com.castivio.core.design.components.CastivioShell
import com.castivio.core.design.components.EmptyState
import com.castivio.core.design.components.IconLabel
import com.castivio.core.design.components.MediaCard
import com.castivio.core.design.components.CardShape
import com.castivio.core.design.components.NowPlayingBadge
import com.castivio.core.design.components.ScreenScaffold
import com.castivio.core.design.components.SectionHeader
import com.castivio.core.design.components.WatchState
import com.castivio.core.design.components.WatchedTag
import com.castivio.core.design.theme.castivioBackdrop
import com.castivio.core.design.theme.CastivioMetrics
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.MotionLevel
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.rememberMetrics
import com.castivio.core.design.theme.Spacing
import com.castivio.core.navigation.BackPolicy
import com.castivio.core.navigation.ShellBack
import com.castivio.domain.SeriesSummary
import com.castivio.feature.activation.ActivationRoute
import com.castivio.feature.activation.LanguagePicker
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
import com.castivio.tv.locale.LocalLocaleController
import com.castivio.tv.player.PlayerHost

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
     * The subscription flow, over the shell.
     *
     * The same `ActivationRoute` the gate shows before there is a catalogue, opened
     * here by a working app to add a second provider or replace the one showing. It
     * is an overlay rather than a rail destination for the reason the licence screen
     * is: the flow owns the whole viewport — its own header, its own back ladder, its
     * own immersive mode — and drawing it inside the rail would be a different
     * composition from the one that was designed and measured.
     *
     * Nothing about the flow changed to make this work. It already reported success
     * through `onActivated` and exhaustion of its back stack through `onExit`; the
     * gate turns those into "re-ask where the app should open" and the shell turns
     * both into "close me". What makes the new content appear afterwards is not a
     * callback at all — Home reads Room through flows, so the counts move on their
     * own while this is still on screen.
     */
    data object AddSource : Overlay

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

    /**
     * The language chooser, over the shell.
     *
     * The same picker the licence screen opens, hosted here because Home now offers
     * the choice too. An overlay and not a destination for the reason the others are:
     * the grid owns the viewport and dismisses back to whatever opened it.
     */
    data object Language : Overlay

    /**
     * Catch-up, which this build does not have.
     *
     * The control is on Home because the approved row has it. What it opens is the
     * app's own sentence for a part of Castivio that is not ready yet — the same
     * `NOT_CONFIGURED` copy every other screen uses — rather than nothing at all. A
     * button that does nothing when pressed teaches a user that the row is decorative;
     * one that explains itself teaches them the feature is coming.
     */
    data object TimeShift : Overlay
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
    dark: Boolean,
    onDark: (Boolean) -> Unit,
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

    Box(Modifier.fillMaxSize()) {
        CastivioShell {
            when (dest) {
                Dest.Home -> HomeScreen(
                    onSeeSection = { dest = it.destination },
                    onAddSource = { overlay = Overlay.AddSource },
                    onSettings = { dest = Dest.Settings },
                    onLanguage = { overlay = Overlay.Language },
                    onTimeShift = { overlay = Overlay.TimeShift },
                    // What "about" means here is what this build is: its licence, the
                    // device it is bound to, and its version. That screen exists.
                    onAbout = { overlay = Overlay.Licence },
                    onExit = onExit,
                )
                Dest.Live -> BrowseScreen(
                    section = CatalogSection.Live,
                    onPlay = play,
                    onOpenShow = { overlay = Overlay.Show(it) },
                    onSearch = { dest = Dest.Search },
                )
                Dest.Movies -> BrowseScreen(
                    section = CatalogSection.Movies,
                    onPlay = play,
                    onOpenShow = { overlay = Overlay.Show(it) },
                    onSearch = { dest = Dest.Search },
                )
                Dest.Series -> BrowseScreen(
                    section = CatalogSection.Series,
                    onPlay = play,
                    onOpenShow = { overlay = Overlay.Show(it) },
                    onSearch = { dest = Dest.Search },
                )
                Dest.Radio -> BrowseScreen(
                    section = CatalogSection.Radio,
                    onPlay = play,
                    onOpenShow = { overlay = Overlay.Show(it) },
                    onSearch = { dest = Dest.Search },
                )
                Dest.Favourites -> FavouritesScreen()
                Dest.Library -> LibraryScreen(onOpenSection = { dest = it })
                Dest.Search -> CatalogSearchScreen(onPlay = play)
                Dest.Settings -> SettingsScreen(
                    motionLevel = motionLevel,
                    onMotionLevel = onMotionLevel,
                    dark = dark,
                    onDark = onDark,
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
            is Overlay.TimeShift -> NotReadyOverlay(onBack = { overlay = null })
            is Overlay.Language -> {
                val locale = LocalLocaleController.current
                LanguagePicker(
                    selected = locale.current.language,
                    onPick = { language ->
                        overlay = null
                        // No `recreate()`: the controller records the choice and the
                        // composition re-reads its strings in place. See its own note.
                        locale.choose(language)
                    },
                    onDismiss = { overlay = null },
                )
            }
            // Both seams mean the same thing from here. `onActivated` fires when an
            // import succeeds and `onExit` when the flow runs out of back stack, and
            // in a working app either one is "put me back where I was".
            //
            // `PlayerHost` for the same reason the gate wraps it: the flow lists the
            // device's own media and a press on one of those is a press on a file, not
            // on a subscription. Without it, that press would be swallowed.
            is Overlay.AddSource -> {
                val locale = LocalLocaleController.current
                PlayerHost { onPlayLocal ->
                    ActivationRoute(
                        onActivated = { overlay = null },
                        onExit = { overlay = null },
                        language = locale.current.language,
                        onLanguage = locale::choose,
                        onPlay = onPlayLocal,
                    )
                }
            }
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

/**
 * A part of Castivio that is not ready in this build, said in the app's own words.
 *
 * `ScreenState.Failed(NOT_CONFIGURED)` and not a bespoke dialog: the scaffold already
 * owns that sentence — "Not available yet · This part of Castivio isn't ready in this
 * build" — and it already offers Back rather than a retry, because retrying is not
 * what fixes it. A second copy of the same message is a second copy to translate.
 */
@Composable
private fun NotReadyOverlay(onBack: () -> Unit) {
    BackHandler(enabled = true, onBack = onBack)
    Box(
        Modifier
            .fillMaxSize()
            .background(CastivioTheme.colors.scrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onBack,
            ),
    ) {
        ScreenScaffold<Unit>(
            state = ScreenState.Failed(AppError.NOT_CONFIGURED, retryable = false),
            onAction = onBack,
        ) { _, _ -> }
    }
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
private fun FavouritesScreen() = PlaceholderStage { frame ->
    Column(
        Modifier
            .fillMaxSize()
            .padding(frame.edge),
        verticalArrangement = Arrangement.spacedBy(frame.bandTop),
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
    PlaceholderStage { frame ->
    Column(
        Modifier
            .fillMaxSize()
            .padding(frame.edge),
        verticalArrangement = Arrangement.spacedBy(frame.bandTop),
    ) {
        SectionHeader(title = "Library")
        entries.forEach { (icon, label, target) ->
            SettingRow(icon = icon, label = label, onClick = { onOpenSection(target) })
        }
    }
    }
}

// ------------------------------------------------------------------ settings

@Composable
private fun SettingsScreen(
    motionLevel: MotionLevel,
    onMotionLevel: (MotionLevel) -> Unit,
    dark: Boolean,
    onDark: (Boolean) -> Unit,
    onShowStateBoard: () -> Unit,
    onShowLicence: () -> Unit,
) {
    val colors = CastivioTheme.colors
    PlaceholderStage { frame ->
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(frame.edge),
        verticalArrangement = Arrangement.spacedBy(frame.bandTop),
    ) {
        SectionHeader(title = "Settings")

        // Two chips and nothing else: the same control the motion level takes, so a
        // reader meets one pattern rather than a switch here and chips there.
        Text("Appearance", style = CastivioType.titleMedium, color = colors.onBackground)
        Text(
            "Two dark grounds, not a dark mode and a light one: a picture reads as a " +
                "picture when the room is darker than it is. Deep is the near-black the " +
                "product is drawn on; Slate is lifted, for a lit room. The choice is " +
                "remembered, and Castivio does not follow the system.",
            style = CastivioType.bodySmall,
            color = colors.onBackgroundMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            CategoryChipPlain(label = "Deep", selected = dark, onClick = { onDark(true) })
            CategoryChipPlain(label = "Slate", selected = !dark, onClick = { onDark(false) })
        }

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

/**
 * The measured stage the shell's placeholder screens compose on.
 *
 * These three are UX-validation screens, not product ones, and they were the last
 * consumers of `DeviceClass.screenPadding` — a margin chosen by what kind of box this
 * is, 24dp or 48. They take the product's own stage now, from the surface each is
 * actually given, so a placeholder does not sit differently from the screen that will
 * replace it.
 *
 * `statusBarsPadding` stays here rather than in each screen: it is an inset the system
 * demands, and the margin inside it is the design's.
 */
@Composable
private fun PlaceholderStage(content: @Composable (CastivioMetrics) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
        content(rememberMetrics(maxWidth, maxHeight))
    }
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
    // The backdrop is on the box rather than the column so it still runs under the
    // status bar, and the margin inside it is measured rather than looked up: this
    // was the application's last reader of `DeviceClass.screenPadding`, a margin
    // chosen by what kind of box this is.
    BoxWithConstraints(
        Modifier
            // An overlay over a destination, so it carries the backdrop rather
            // than the flat colour that sits beneath it.
            .fillMaxSize()
            .castivioBackdrop(),
    ) {
    val m = rememberMetrics(maxWidth, maxHeight)
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = m.edge, vertical = m.stageTop),
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
}
