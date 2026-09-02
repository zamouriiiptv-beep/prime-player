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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.castivio.core.design.components.CastivioShell
import com.castivio.core.design.components.NavAction
import com.castivio.core.design.components.SectionHeader
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.MotionLevel
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Spacing
import com.castivio.domain.MediaGroup
import com.castivio.domain.SeriesSummary
import com.castivio.feature.home.CatalogSearchScreen
import com.castivio.feature.home.CatalogSection
import com.castivio.feature.home.CatalogSelection
import com.castivio.feature.home.CategoriesScreen
import com.castivio.feature.home.CategoryScreen
import com.castivio.feature.home.HomeScreen
import com.castivio.feature.home.ShowScreen
import com.castivio.feature.home.R as CatalogStrings
import com.castivio.feature.licence.R as LicenceStrings
import com.castivio.feature.player.PlayerRequest
import com.castivio.feature.player.PlayerRoute
import com.castivio.playback.api.MediaKind
import com.castivio.tv.licence.LicenceWithLanguage

/** The three places the rail can be. Everything else is a step inside Browse. */
private enum class Tab { Browse, Search, Settings }

/**
 * One step of the browse flow.
 *
 * A stack rather than a set of destinations, because the flow has real depth —
 * categories, then a category, then a show, then an episode — and each step is opened
 * with the thing the previous one was showing. A flat destination enum would have to
 * store the "current category" somewhere outside the navigation, and that is how back
 * ends up returning to a screen with the wrong contents.
 *
 * Each step carries what it needs, so nothing is re-fetched to redraw it.
 */
private sealed interface Step {

    /** The three choices. Loads nothing at all. */
    data object Home : Step

    /** One section's categories. One request, for that section only. */
    data class Categories(val section: CatalogSection) : Step

    /** One category's contents. One request, for that category only. */
    data class Category(val section: CatalogSection, val group: MediaGroup) : Step

    /** One show's seasons. One request, for that show only. */
    data class Show(val show: SeriesSummary) : Step
}

/**
 * The application shell.
 *
 * ## The flow this draws
 *
 * Sign in, then Home: Channels, Movies, Series. Pressing one lists that section's
 * categories — one request — and pressing a category fetches that category and nothing
 * else. A channel or a film plays; a show opens its seasons and an episode plays.
 *
 * Nothing above is fetched before it is asked for. Home issues no request, and the app
 * never holds more of a provider's catalogue than the user has actually opened.
 *
 * ## Back
 *
 * The player closes, then the browse stack pops one step at a time, then a tab returns
 * to Browse, and only at Home is there nothing left to go back to — which is the whole
 * condition for asking whether to leave.
 */
@Composable
fun ShellScreen(
    motionLevel: MotionLevel,
    onMotionLevel: (MotionLevel) -> Unit,
    /**
     * Ask to leave Castivio.
     *
     * Ask, not leave. The confirmation is the application's now and is drawn above this
     * screen; what happens once the user answers it is not the shell's business.
     */
    onExit: () -> Unit,
) {
    var tab by remember { mutableStateOf(Tab.Browse) }
    val stack = remember { mutableStateListOf<Step>(Step.Home) }
    var playing by remember { mutableStateOf<PlayerRequest?>(null) }

    // One conversion, in the one place that knows both the catalogue and the player.
    //
    // `:feature:home` must not depend on `:feature:player`, so a press arrives here as
    // what the row already had on screen and becomes a request here -- the same seam a
    // local file already uses. The player still fetches nothing before its first frame,
    // because there is nothing left for it to fetch.
    val play: (CatalogSelection) -> Unit = { selection ->
        playing = selection.asPlayerRequest()
    }

    val back: () -> Unit = {
        when {
            playing != null -> playing = null
            stack.size > 1 -> stack.removeAt(stack.lastIndex)
            tab != Tab.Browse -> tab = Tab.Browse
            else -> onExit()
        }
    }

    // Always enabled. On a remote, back is the most-pressed key on the device, so it
    // has to mean the same thing at every depth: undo the last step. It only reaches
    // the exit question when there is genuinely nothing left to undo.
    BackHandler(enabled = true, onBack = back)

    val destinations = listOf(
        navAction(Icons.Filled.Home, "Home") {
            tab = Tab.Browse
        },
        navAction(Icons.Filled.Search, stringResource(CatalogStrings.string.search_label)) {
            tab = Tab.Search
        },
        navAction(Icons.Filled.Settings, "Settings") { tab = Tab.Settings },
    )

    Box(Modifier.fillMaxSize()) {
        CastivioShell(destinations = destinations, selectedIndex = tab.ordinal) {
            when (tab) {
                Tab.Browse -> when (val step = stack.last()) {
                    is Step.Home -> HomeScreen(
                        onOpen = { stack.add(Step.Categories(it)) },
                    )

                    is Step.Categories -> CategoriesScreen(
                        section = step.section,
                        onOpen = { group -> stack.add(Step.Category(step.section, group)) },
                        onBack = back,
                    )

                    is Step.Category -> CategoryScreen(
                        section = step.section,
                        group = step.group,
                        onPlay = play,
                        onOpenShow = { show -> stack.add(Step.Show(show)) },
                        onBack = back,
                    )

                    is Step.Show -> ShowScreen(
                        show = step.show,
                        onPlay = play,
                        onBack = back,
                    )
                }

                Tab.Search -> CatalogSearchScreen(onPlay = play)

                Tab.Settings -> SettingsScreen(
                    motionLevel = motionLevel,
                    onMotionLevel = onMotionLevel,
                )
            }
        }

        // Over everything, and rebuilt from its request. A player drawn beside the
        // shell would leave the rail focusable behind a full-screen video.
        playing?.let { request ->
            PlayerRoute(request = request, onLeave = { playing = null })
        }
    }
}

/**
 * A catalogue press, as the player's request.
 *
 * `SERIES_EPISODE` rather than `VOD` for an episode: the engine reads it to decide what
 * "next" means, and a season that behaves like a single film is the bug this
 * distinction prevents.
 */
private fun CatalogSelection.asPlayerRequest() = PlayerRequest(
    url = url,
    title = title,
    kind = when {
        live -> MediaKind.LIVE
        episode -> MediaKind.SERIES_EPISODE
        else -> MediaKind.VOD
    },
    subtitle = subtitle,
    channelNumber = channelNumber,
    epgChannelId = epgChannelId,
    catchUpHours = catchUpHours,
)

private fun navAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) = NavAction(icon = icon, label = label, onClick = onClick)

// ------------------------------------------------------------------ settings

@Composable
private fun SettingsScreen(
    motionLevel: MotionLevel,
    onMotionLevel: (MotionLevel) -> Unit,
) {
    val colors = CastivioTheme.colors
    var licence by remember { mutableStateOf(false) }

    if (licence) {
        // Reached from a working app, so leaving means returning to Settings. Reached
        // from the gate it means leaving Castivio, and that difference is the caller's
        // -- the screen itself has no opinion about where back goes.
        LicenceWithLanguage(onLeave = { licence = false })
        return
    }

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
                MotionChip(
                    label = level.name.lowercase().replaceFirstChar { it.uppercase() },
                    selected = level == motionLevel,
                    onClick = { onMotionLevel(level) },
                )
            }
        }

        Text("Player", style = CastivioType.titleMedium, color = colors.onBackground)
        SettingRow(icon = Icons.Filled.PlayArrow, label = "Internal player", value = "Default")

        // Castivio's licence, which is not the provider's subscription. The two are
        // separate systems and this row says so by living under its own heading.
        Text("Licence", style = CastivioType.titleMedium, color = colors.onBackground)
        SettingRow(
            icon = Icons.Filled.Settings,
            label = stringResource(LicenceStrings.string.licence_title),
            onClick = { licence = true },
        )

        SettingRow(
            icon = Icons.Filled.VideoLibrary,
            label = "Device class",
            value = CastivioTheme.device.name,
        )
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
private fun MotionChip(label: String, selected: Boolean, onClick: () -> Unit) {
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
