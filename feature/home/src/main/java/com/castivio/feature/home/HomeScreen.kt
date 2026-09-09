package com.castivio.feature.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.castivio.core.design.components.CastivioArtwork
import com.castivio.core.design.components.CastivioLockup
import com.castivio.core.design.components.EmptyState
import com.castivio.core.design.components.InteractiveGlassCard
import com.castivio.core.design.components.SkeletonRow
import com.castivio.core.design.components.formatCount
import com.castivio.core.design.components.ltrIsolate
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.DeviceClass
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing
import com.castivio.domain.MediaItem
import com.castivio.domain.MediaKind
import com.castivio.domain.entitlement.EntitlementState
import java.text.DateFormat
import java.util.Date

/**
 * Home: the dashboard the reference was signed off as, and nothing that is not on it.
 *
 * ## Six bands, in this order
 *
 * The lockup and what Castivio's licence says; four section cards in one row; the
 * actions that are not "open a section"; what this device is; the disclaimer. The
 * whole screen answers one question — *what have I got, and how do I change it* —
 * and it answers it in a single frame on a television, without a scroll.
 *
 * ## Why there are no content rows any more
 *
 * There were three, one per kind, and they were a sample of a section rather than an
 * answer about it. On a set they pushed the four counts and every action below the
 * fold, so the screen's own question needed a scroll to read, and the rows themselves
 * were a picture of whatever happened to import first — an IPTV catalogue has no
 * editorial, so there is nothing for a row on Home to be *about*. The section screens
 * page properly and are one press away. What the rows read is still read: the first
 * items of each kind are what each card's artwork is drawn from.
 *
 * ## Two different nothings
 *
 * "No provider yet" and "a provider that carried nothing" look identical if you only
 * check whether the counts are zero, and they need opposite advice. [HomeState.hasSource]
 * separates them; [HomeState.carriesNothing] is only true once every section has
 * actually been asked for.
 *
 * ## The clock, and why it is safe now
 *
 * It was refused once, correctly: a `LaunchedEffect` with a `delay` loop is a
 * coroutine that never completes, and a composition that never goes idle is a Compose
 * test that hangs until CI kills it. `ACTION_TIME_TICK` is a system broadcast that
 * arrives once a minute; a `DisposableEffect` that registers a receiver and
 * unregisters it leaves nothing pending, and costs no timer of ours at all.
 *
 * ## What is deliberately not drawn
 *
 * The reference carries a *provider* subscription status and expiry. The validator
 * returns them and `ProviderSource` has no column to keep them in, so they are not
 * on this screen: a card reading "Active" that is read from nothing is worse than no
 * card. The two here are Castivio's own licence, which is a real stored answer, and
 * they say so. `Time Shift` is absent because catch-up has no engine yet.
 */
@Composable
fun HomeScreen(
    onSeeSection: (CatalogSection) -> Unit,
    /** Add a first subscription, or change the one showing. Opens the activation flow. */
    onAddSource: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    /** Castivio's own licence, and the language chooser that lives with it. */
    onLicence: () -> Unit,
    /** The build's version name. Passed in because `:feature:home` has no BuildConfig. */
    appVersion: String,
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
            .padding(horizontal = padding, vertical = Spacing.md)
            .padding(bottom = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        DashboardHeader(state)

        when {
            state.loading -> SkeletonRow(cards = 4, label = null)

            !state.hasSource -> Box(
                Modifier.fillMaxSize().padding(vertical = Spacing.xl),
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
                SectionCards(state = state, onSeeSection = onSeeSection)

                if (state.carriesNothing) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            title = stringResource(R.string.home_empty_title),
                            detail = stringResource(R.string.home_empty_detail),
                            actionLabel = stringResource(R.string.home_change_source),
                            onAction = onAddSource,
                        )
                    }
                }

                ActionRow(
                    provider = state.provider,
                    onAddSource = onAddSource,
                    onSearch = onSearch,
                    onLicence = onLicence,
                    onSettings = onSettings,
                )

                DeviceStrip(state = state, appVersion = appVersion)

                Disclaimer()
            }
        }
    }
}

// ---------------------------------------------------------------- the header

/**
 * The lockup, what the licence says, and the time.
 *
 * The two status cards are Castivio's licence and nothing else. The provider is
 * named on the button that changes it, which is where a user looking for it will
 * be anyway — and keeping the two systems on different bands is what stops them
 * from being read as one "status".
 */
@Composable
private fun DashboardHeader(state: HomeState, modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    val big = CastivioTheme.device.wide

    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            CastivioLockup(
                markSize = if (big) MARK_WIDE else MARK_TIGHT,
                wordSize = CastivioType.titleLarge.fontSize,
            )
            Text(
                text = stringResource(R.string.home_tagline),
                style = CastivioType.overline,
                color = colors.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (big) {
            StatusCard(
                icon = Icons.Rounded.CheckCircle,
                label = stringResource(R.string.home_status_licence),
                value = stringResource(state.plan),
                tint = if (state.licenceHolds) colors.success else colors.danger,
            )
            StatusCard(
                icon = Icons.Rounded.CalendarMonth,
                label = stringResource(R.string.home_status_expires),
                value = expiryLabel(state.entitlement),
                tint = colors.hueViolet,
            )
        }

        Box(Modifier.weight(1f))
        Clock()
    }
}

/** One fact about the licence: what it is called, and what it says. */
@Composable
private fun StatusCard(
    icon: ImageVector,
    label: String,
    value: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    Row(
        modifier
            .clip(RoundedCornerShape(Radius.md))
            .background(colors.glassFillBrush)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Sizing.iconLg))
        Column {
            Text(label, style = CastivioType.labelSmall, color = colors.onBackgroundMuted, maxLines = 1)
            Text(value, style = CastivioType.titleMedium, color = tint, maxLines = 1)
        }
    }
}

/**
 * The time, and the day under it.
 *
 * [rememberMinute] is the whole of the ticking: a broadcast the platform already
 * sends, a receiver that is unregistered when this leaves the composition, and no
 * coroutine of ours left running.
 */
@Composable
private fun Clock(modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    val now = rememberMinute()
    val locale = LocalConfiguration.current
    val time = remember(now, locale) {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now))
    }
    val day = remember(now, locale) {
        DateFormat.getDateInstance(DateFormat.FULL).format(Date(now))
    }
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(time, style = CastivioType.headlineMedium, color = colors.onBackgroundStrong, maxLines = 1)
        Text(
            day,
            style = CastivioType.labelSmall,
            color = colors.onBackgroundMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The wall clock, to the minute, without a timer.
 *
 * `ACTION_TIME_TICK` cannot be declared in a manifest — the platform only delivers
 * it to a receiver registered at runtime — which is exactly the lifetime a
 * `DisposableEffect` has. `RECEIVER_NOT_EXPORTED` because nothing but the system
 * should ever be able to send this; on the API levels that have no such flag,
 * `ContextCompat` drops it.
 */
@Composable
private fun rememberMinute(): Long {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(from: Context?, intent: Intent?) {
                now = System.currentTimeMillis()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_TIME_TICK),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    return now
}

/** Which plan this is, in one word, or that there is not one. */
private val HomeState.plan: Int
    get() = when (entitlement) {
        is EntitlementState.TrialActive -> R.string.home_plan_trial
        is EntitlementState.AnnualActive -> R.string.home_plan_annual
        EntitlementState.Lifetime -> R.string.home_plan_lifetime
        else -> R.string.home_plan_inactive
    }

/** Whether the licence currently permits use, which is the only question the card asks. */
private val HomeState.licenceHolds: Boolean
    get() = entitlement?.allowsUse == true

/**
 * When the licence runs out.
 *
 * Lifetime says so rather than showing a date it does not have, and everything
 * without an expiry — an unknown state, a service outage — shows a dash instead of
 * a date invented from the clock.
 */
@Composable
private fun expiryLabel(state: EntitlementState?): String = when (state) {
    is EntitlementState.TrialActive -> rememberStamp(state.expiresAtMs, withTime = false)
    is EntitlementState.AnnualActive -> rememberStamp(state.expiresAtMs, withTime = false)
    EntitlementState.Lifetime -> stringResource(R.string.home_expires_never)
    else -> stringResource(R.string.home_expires_none)
}

// ------------------------------------------------------------ the four cards

/**
 * The four sections, in one row where there is width and two by two where there is not.
 *
 * Four across at 960dp gives a card about 210dp wide, which holds the longest section
 * name Castivio ships. On a handset the same four re-flow to a 2×2 — the same cards
 * at a smaller height, not a second design.
 */
@Composable
private fun SectionCards(
    state: HomeState,
    onSeeSection: (CatalogSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val wide = CastivioTheme.device.wide

    val cards = listOf(
        Section(CatalogSection.Live, Icons.Rounded.LiveTv, colors.hueAzure, R.string.browse_live, state.liveCount, R.string.home_count_live, state.sections[MediaKind.LIVE], state.live),
        Section(CatalogSection.Movies, Icons.Rounded.Movie, colors.hueViolet, R.string.browse_movies, state.movieCount, R.string.home_count_movies, state.sections[MediaKind.MOVIE], state.movies),
        Section(CatalogSection.Series, Icons.Rounded.Tv, colors.hueGreen, R.string.browse_series, state.seriesCount, R.string.home_count_series, state.sections[MediaKind.SERIES], state.episodes),
        Section(CatalogSection.Radio, Icons.Rounded.Radio, colors.hueAmber, R.string.browse_radio, state.radioCount, R.string.home_count_radio, state.sections[MediaKind.RADIO], emptyList()),
    )

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        for (group in if (wide) listOf(cards) else cards.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                for (card in group) {
                    SectionCard(
                        section = card,
                        onClick = { onSeeSection(card.section) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** One card's worth of decisions, so the list above reads as a list rather than a wall. */
private data class Section(
    val section: CatalogSection,
    val icon: ImageVector,
    val hue: Color,
    val name: Int,
    val count: Int,
    val unit: Int,
    /** When this section was brought onto the device, or null if it never has been. */
    val fetchedAtMs: Long?,
    /** The head of the section, which is what its artwork is drawn from. */
    val items: List<MediaItem>,
)

@Composable
private fun SectionCard(section: Section, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    val wide = CastivioTheme.device.wide

    InteractiveGlassCard(
        onClick = onClick,
        modifier = modifier.height(if (wide) CARD_WIDE else CARD_TIGHT),
        shape = RoundedCornerShape(Radius.lg),
    ) {
        Column(
            Modifier.fillMaxSize().padding(Spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Collage(
                seeds = section.artworkSeeds,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (wide) ART_WIDE else ART_TIGHT),
            )
            Icon(
                imageVector = section.icon,
                contentDescription = null,
                tint = colors.onBackgroundStrong,
                modifier = Modifier
                    .padding(top = Spacing.md)
                    .size(if (wide) GLYPH_WIDE else GLYPH_TIGHT),
            )
            Text(
                text = stringResource(section.name),
                style = CastivioType.titleMedium,
                color = colors.onBackgroundStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.sm),
            )
            // The difference between these two sentences is the whole point of the
            // lazy fetch: "not downloaded yet" is an invitation, a count is a fact
            // about this device. A count with no mark behind it cannot happen.
            if (section.fetchedAtMs == null) {
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
                    text = stringResource(section.unit, formatCount(section.count)),
                    style = CastivioType.labelSmall,
                    color = section.hue,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Four covers, or as many as the section has.
 *
 * These are [CastivioArtwork] placeholders, which is what every tile in this app
 * shows until an image arrives — a provider's own artwork needs a loader this build
 * does not ship, and a grey rectangle with a broken-image glyph reads as a fault.
 * The seeds come from the items themselves, so the same section redraws the same
 * collage and a screenshot stays comparable.
 */
@Composable
private fun Collage(seeds: List<Int>, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(Radius.sm)),
        verticalArrangement = Arrangement.spacedBy(COLLAGE_GAP),
    ) {
        for (row in 0 until 2) {
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(COLLAGE_GAP),
            ) {
                for (column in 0 until 2) {
                    val seed = seeds[(row * 2 + column) % seeds.size]
                    Box(
                        Modifier
                            .fillMaxSize()
                            .weight(1f)
                            .background(CastivioArtwork.placeholder(seed)),
                    )
                }
            }
        }
    }
}

/**
 * Four numbers to draw the collage from.
 *
 * The section's own items where it has them, and the section's position where it has
 * none — so an unfetched card still carries artwork rather than four empty squares,
 * and two unfetched cards do not carry the *same* artwork.
 */
private val Section.artworkSeeds: List<Int>
    get() = if (items.isEmpty()) {
        val base = section.ordinal * 4
        listOf(base, base + 1, base + 2, base + 3)
    } else {
        items.take(4).map { it.id.hashCode() }
    }

// --------------------------------------------------------------- the actions

/**
 * What a viewer does from Home that is not "open a section".
 *
 * Four, and every one of them goes somewhere that exists. The reference's `Time
 * Shift` and `Logout` are not here: catch-up has no engine, and there is no account
 * to log out of — back already leaves, with a confirmation.
 */
@Composable
private fun ActionRow(
    provider: String?,
    onAddSource: () -> Unit,
    onSearch: () -> Unit,
    onLicence: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Action(
            icon = Icons.Rounded.PlaylistAdd,
            title = stringResource(R.string.home_change_source),
            detail = provider,
            onClick = onAddSource,
            modifier = Modifier.weight(1f),
        )
        Action(
            icon = Icons.Rounded.Search,
            title = stringResource(R.string.search_label),
            detail = null,
            onClick = onSearch,
            modifier = Modifier.weight(1f),
        )
        Action(
            icon = Icons.Rounded.VerifiedUser,
            title = stringResource(R.string.home_action_licence),
            detail = null,
            onClick = onLicence,
            modifier = Modifier.weight(1f),
        )
        Action(
            icon = Icons.Rounded.Settings,
            title = stringResource(R.string.home_settings),
            detail = null,
            onClick = onSettings,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Action(
    icon: ImageVector,
    title: String,
    detail: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    InteractiveGlassCard(
        onClick = onClick,
        modifier = modifier.heightIn(min = Sizing.minTvTarget),
        shape = RoundedCornerShape(Radius.md),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = colors.hueViolet,
                modifier = Modifier.size(Sizing.iconLg),
            )
            Column {
                Text(
                    title,
                    style = CastivioType.labelLarge,
                    color = colors.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detail != null) {
                    Text(
                        detail,
                        style = CastivioType.labelSmall,
                        color = colors.onBackgroundMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- the device

/**
 * What this device is: its address, whether Castivio is unlocked on it, and which
 * build is running.
 *
 * The address is the one a user reads out to a provider who activates by MAC, so it
 * belongs where it can be read without hunting — and it is isolated for direction,
 * because a colon-separated hex address reordered by an Arabic paragraph is not the
 * address any more.
 */
@Composable
private fun DeviceStrip(state: HomeState, appVersion: String, modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(colors.glassFillBrush)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Fact(
            icon = Icons.Rounded.Memory,
            text = stringResource(R.string.home_device_mac, ltrIsolate(state.mac)),
            tint = colors.onBackgroundMuted,
            modifier = Modifier.weight(1f),
        )
        Fact(
            icon = Icons.Rounded.Lock,
            text = stringResource(
                R.string.home_device_state,
                stringResource(
                    if (state.licenceHolds) R.string.home_device_activated
                    else R.string.home_device_locked,
                ),
            ),
            tint = if (state.licenceHolds) colors.success else colors.danger,
            modifier = Modifier.weight(1f),
        )
        Fact(
            icon = Icons.Rounded.Shield,
            text = stringResource(R.string.home_device_version, ltrIsolate(appVersion)),
            tint = colors.onBackgroundMuted,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Fact(icon: ImageVector, text: String, tint: Color, modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Sizing.iconMd))
        Text(
            text,
            style = CastivioType.labelSmall,
            color = colors.onBackgroundVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Disclaimer(modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(
            Icons.Rounded.Shield,
            contentDescription = null,
            tint = colors.hueViolet,
            modifier = Modifier.size(Sizing.iconMd),
        )
        Text(
            text = stringResource(R.string.home_disclaimer),
            style = CastivioType.bodySmall,
            color = colors.onBackgroundMuted,
        )
    }
}

// ----------------------------------------------------------------- utilities

/**
 * An instant, in the reader's own locale, formatted once.
 *
 * `remember` keyed on the value, because building a `DateFormat` and running it is
 * real work and composition runs whenever anything on this screen moves — a count
 * arriving from an import behind the screen would otherwise reformat several dates
 * on every emission, which is the per-frame work the performance budget bans.
 */
@Composable
private fun rememberStamp(atMs: Long, withTime: Boolean = true): String {
    val locale = LocalConfiguration.current
    return remember(atMs, locale, withTime) {
        val format =
            if (withTime) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            else DateFormat.getDateInstance(DateFormat.MEDIUM)
        format.format(Date(atMs))
    }
}

/** Where four cards fit across, and where they have to fold to two by two. */
private val DeviceClass.wide: Boolean
    get() = this == DeviceClass.Television || this == DeviceClass.Expanded

/**
 * Fixed heights rather than intrinsic ones.
 *
 * Four cards of three different heights is what a row of `wrapContentHeight` cards
 * produces the first time a translation is longer than English.
 */
private val CARD_WIDE: Dp = 232.dp
private val CARD_TIGHT: Dp = 188.dp
private val ART_WIDE: Dp = 108.dp
private val ART_TIGHT: Dp = 76.dp
private val GLYPH_WIDE: Dp = 40.dp
private val GLYPH_TIGHT: Dp = 32.dp
private val COLLAGE_GAP: Dp = 2.dp
private val MARK_WIDE: Dp = 44.dp
private val MARK_TIGHT: Dp = 34.dp
