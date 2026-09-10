package com.castivio.feature.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.castivio.core.design.components.CastivioLockup
import com.castivio.core.design.components.EmptyState
import com.castivio.core.design.components.InteractiveGlassCard
import com.castivio.core.design.components.castivioBodyStyle
import com.castivio.core.design.components.castivioChipStyle
import com.castivio.core.design.components.castivioTitleStyle
import com.castivio.core.design.components.formatCount
import com.castivio.core.design.components.ltrIsolate
import com.castivio.core.design.theme.CastivioFrame
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.rememberFrame
import com.castivio.domain.MediaKind
import com.castivio.domain.entitlement.EntitlementState
import java.text.DateFormat
import java.util.Date

/**
 * Home: the dashboard, composed to the frame it is handed rather than to a scroll.
 *
 * ## It fits, and that is a rule rather than a hope
 *
 * Every band on this screen is measured out of the stage before anything is drawn:
 * the header, the four cards, the actions, the device strip and — only if what is
 * left still gives a card a usable height — the disclaimer. Nothing scrolls, because
 * a dashboard that scrolls has stopped answering its own question. That is the defect
 * this revision exists to fix: fixed card heights meant a 393dp handset drew the
 * television's card and pushed half the screen past the fold.
 *
 * ## Four across, or two by two
 *
 * The set and the tablet get one row of four; the handset gets two rows of two. The
 * choice is the *stage*, not a device name — a television because a reader three
 * metres away needs the card large in angle, and any stage at least [WIDE_STAGE] wide
 * because that is the content block the tablet frame is already built around. Both
 * come out of numbers this project already holds; neither is a second frame table,
 * which is what `CastivioFrame` exists to prevent.
 *
 * The card turns with the shape it is given: tall enough and it stacks plate over
 * name; short and wide and it lays the plate beside the name. One card, two aspects,
 * rather than two cards.
 *
 * ## The plate, and why it is not a picture
 *
 * A section card carried four coloured rectangles standing in for artwork. On a
 * device they do not read as "artwork is still loading" — they read as four squares
 * of colour presented as if they were covers, which is worse than admitting there is
 * no picture. Castivio has no image loader in this build, so the card says what it
 * *is* instead: the section's own glyph on the section's own hue, drawn with
 * `discFill`/`discBorder`/`discGlyph` — the disc recipe already in the theme, at plate
 * size. Nothing here invents a colour, and nothing pretends to be a cover.
 *
 * ## Two different nothings
 *
 * "No provider yet" and "a provider that carried nothing" look identical if you only
 * check whether the counts are zero, and they need opposite advice.
 * [HomeState.hasSource] separates them; [HomeState.carriesNothing] is only true once
 * every section has actually been asked for.
 *
 * ## The clock
 *
 * `ACTION_TIME_TICK` through a `DisposableEffect`: a broadcast the platform already
 * sends, a receiver unregistered when the screen goes away, and no coroutine of ours
 * left running. The `LaunchedEffect` delay loop that was refused would hang a Compose
 * test until CI killed it; this leaves nothing pending.
 *
 * ## What is deliberately not drawn
 *
 * The reference carries a *provider* subscription status and expiry. The validator
 * returns them and `ProviderSource` has no column to keep them in, so the two header
 * cards carry Castivio's own licence — a real stored answer — and say so. `Time
 * Shift` has no catch-up engine. Nothing on this screen fetches anything: the
 * sections are still brought onto the device by the section that was opened.
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

    // `safeDrawing`, not `statusBars`: turned sideways the system's own navigation
    // sits on one *side*, and a screen that pads only the top draws its trailing
    // column underneath it.
    BoxWithConstraints(modifier.fillMaxSize().safeDrawingPadding()) {
        val frame = rememberFrame(maxHeight)
        // Read outside the lambda: `remember` takes an ordinary function, so a
        // composable getter cannot be called inside it — only passed into it.
        val isTv = CastivioTheme.device.isTv
        val plan = remember(maxHeight, maxWidth, frame, isTv) {
            Plan.of(frame, maxHeight, maxWidth, isTv)
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = frame.edge)
                .padding(top = frame.stageTop, bottom = frame.stageBottom),
            verticalArrangement = Arrangement.spacedBy(frame.bandTop),
        ) {
            DashboardHeader(state, frame, plan.headerHeight, showLicence = plan.fourAcross)

            when {
                state.loading -> Box(Modifier.fillMaxSize())

                !state.hasSource -> Box(
                    Modifier.fillMaxSize(),
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

                state.carriesNothing -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        title = stringResource(R.string.home_empty_title),
                        detail = stringResource(R.string.home_empty_detail),
                        actionLabel = stringResource(R.string.home_change_source),
                        onAction = onAddSource,
                    )
                }

                else -> {
                    SectionCards(state, frame, plan, onSeeSection)
                    ActionRow(state.provider, frame, onAddSource, onSearch, onLicence, onSettings)
                    DeviceStrip(state, frame, appVersion)
                    if (plan.showDisclaimer) Disclaimer(frame)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ the plan

/**
 * How this stage is spent, decided once from what the surface actually measured.
 *
 * A value rather than a set of `if`s scattered through the composition: the bands
 * have to add up to the stage, and an arithmetic that lives in one place can be read
 * and checked. Everything it needs comes from [CastivioFrame] and the measured
 * surface; it invents no size of its own beyond [MIN_CARD], which is a floor rather
 * than a size.
 */
private data class Plan(
    val fourAcross: Boolean,
    val columns: Int,
    /** The header band: the frame's row plus the strapline this screen sets under it. */
    val headerHeight: Dp,
    val cardHeight: Dp,
    val showDisclaimer: Boolean,
) {
    /** A card taller than it is wide stacks; a wide, short one lays out sideways. */
    val stacked: Boolean get() = fourAcross

    companion object {
        fun of(frame: CastivioFrame, height: Dp, width: Dp, isTv: Boolean): Plan {
            val fourAcross = isTv || width >= WIDE_STAGE
            val rows = if (fourAcross) 1 else 2
            val gap = frame.bandTop
            val stage = height - frame.stageTop - frame.stageBottom

            // The frame's header is the lockup's row. This screen puts a strapline
            // under it, so the band is that row plus one small line — stated here,
            // where the arithmetic can see it, rather than discovered by clipping.
            val header = frame.header + frame.fsChip

            // Everything that is not the cards. The disclaimer is the one band that
            // gives way, because it is the only one nothing is lost by reading later.
            val fixed = header + frame.touchTarget + frame.chip
            val withLine = stage - fixed - frame.chip - gap * 4
            val withoutLine = stage - fixed - gap * 3

            val showLine = (withLine - gap * (rows - 1)) / rows >= MIN_CARD
            val band = if (showLine) withLine else withoutLine

            return Plan(
                fourAcross = fourAcross,
                columns = if (fourAcross) 4 else 2,
                headerHeight = header,
                cardHeight = (band - gap * (rows - 1)) / rows,
                showDisclaimer = showLine,
            )
        }
    }
}

// ---------------------------------------------------------------- the header

/**
 * The lockup, what the licence says, and the time.
 *
 * The licence pair appears where the stage has the width for it. On a handset the
 * header carries the lockup and the clock, and the licence is still answered — by
 * "Device: activated" in the strip, which is the same fact in the place a short frame
 * has room for.
 */
@Composable
private fun DashboardHeader(
    state: HomeState,
    frame: CastivioFrame,
    height: Dp,
    showLicence: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    Row(
        modifier.fillMaxWidth().height(height),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(frame.headGap),
    ) {
        Column(verticalArrangement = Arrangement.Center) {
            CastivioLockup(
                markSize = frame.brand,
                wordSize = (frame.fsTitle.value * WORD_RATIO).sp,
            )
            Text(
                text = stringResource(R.string.home_tagline),
                style = castivioChipStyle(frame.fsChip),
                color = colors.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (showLicence) {
            LicenceCard(
                icon = Icons.Rounded.CheckCircle,
                label = stringResource(R.string.home_status_licence),
                value = stringResource(state.plan),
                tint = if (state.licenceHolds) colors.success else colors.danger,
                frame = frame,
            )
            LicenceCard(
                icon = Icons.Rounded.CalendarMonth,
                label = stringResource(R.string.home_status_expires),
                value = expiryLabel(state.entitlement),
                tint = colors.hueViolet,
                frame = frame,
            )
        }

        Box(Modifier.weight(1f))
        Clock(frame)
    }
}

/** One fact about the licence: what it is called, and what it says. */
@Composable
private fun LicenceCard(
    icon: ImageVector,
    label: String,
    value: String,
    tint: Color,
    frame: CastivioFrame,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    Row(
        modifier
            .height(frame.chip + frame.chipPad)
            .clip(RoundedCornerShape(frame.radius / 2))
            .background(colors.glassFillBrush)
            .padding(horizontal = frame.chipPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(frame.chipPad / 2),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Sizing.iconMd))
        Column(verticalArrangement = Arrangement.Center) {
            Text(
                label,
                style = castivioChipStyle(frame.fsChip),
                color = colors.onBackgroundMuted,
                maxLines = 1,
            )
            Text(
                value,
                style = castivioChipStyle(frame.fsLabel),
                color = tint,
                maxLines = 1,
            )
        }
    }
}

/** The time, and the day under it. */
@Composable
private fun Clock(frame: CastivioFrame, modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    val now = rememberMinute()
    val locale = LocalConfiguration.current
    val time = remember(now, locale) {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now))
    }
    val day = remember(now, locale) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(now))
    }
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(
            time,
            style = castivioTitleStyle(frame.fsTitle),
            color = colors.onBackgroundStrong,
            maxLines = 1,
        )
        Text(
            day,
            style = castivioChipStyle(frame.fsChip),
            color = colors.onBackgroundMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The wall clock, to the minute, without a timer.
 *
 * `ACTION_TIME_TICK` cannot be declared in a manifest — the platform only delivers it
 * to a receiver registered at runtime, which is exactly the lifetime a
 * `DisposableEffect` has. `RECEIVER_NOT_EXPORTED` because nothing but the system
 * should be able to send it; on the API levels with no such flag `ContextCompat`
 * drops it.
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
 * Lifetime says so rather than showing a date it does not have, and everything with
 * no expiry shows a dash instead of a date invented from the clock.
 */
@Composable
private fun expiryLabel(state: EntitlementState?): String = when (state) {
    is EntitlementState.TrialActive -> rememberDate(state.expiresAtMs)
    is EntitlementState.AnnualActive -> rememberDate(state.expiresAtMs)
    EntitlementState.Lifetime -> stringResource(R.string.home_expires_never)
    else -> stringResource(R.string.home_expires_none)
}

// ------------------------------------------------------------ the four cards

/** The four sections, in the grid the stage was measured for. */
@Composable
private fun SectionCards(
    state: HomeState,
    frame: CastivioFrame,
    plan: Plan,
    onSeeSection: (CatalogSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val sections = listOf(
        Section(CatalogSection.Live, Icons.Rounded.LiveTv, colors.hueAzure, R.string.browse_live, state.liveCount, R.string.home_count_live, state.sections[MediaKind.LIVE]),
        Section(CatalogSection.Movies, Icons.Rounded.Movie, colors.hueViolet, R.string.browse_movies, state.movieCount, R.string.home_count_movies, state.sections[MediaKind.MOVIE]),
        Section(CatalogSection.Series, Icons.Rounded.Tv, colors.hueGreen, R.string.browse_series, state.seriesCount, R.string.home_count_series, state.sections[MediaKind.SERIES]),
        Section(CatalogSection.Radio, Icons.Rounded.Radio, colors.hueAmber, R.string.browse_radio, state.radioCount, R.string.home_count_radio, state.sections[MediaKind.RADIO]),
    )

    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(frame.bandTop),
    ) {
        for (group in sections.chunked(plan.columns)) {
            Row(
                Modifier.fillMaxWidth().height(plan.cardHeight),
                horizontalArrangement = Arrangement.spacedBy(frame.bandTop),
            ) {
                for (section in group) {
                    SectionCard(
                        section = section,
                        frame = frame,
                        stacked = plan.stacked,
                        onClick = { onSeeSection(section.section) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
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
)

@Composable
private fun SectionCard(
    section: Section,
    frame: CastivioFrame,
    stacked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    InteractiveGlassCard(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(frame.radius),
    ) {
        if (stacked) {
            Column(
                Modifier.fillMaxSize().padding(frame.chipPad),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(frame.chipPad / 2),
            ) {
                SectionPlate(section, frame, Modifier.fillMaxWidth().weight(1f))
                SectionLabels(section, frame, TextAlign.Center, Modifier.fillMaxWidth())
            }
        } else {
            Row(
                Modifier.fillMaxSize().padding(frame.chipPad),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(frame.chipPad),
            ) {
                SectionPlate(section, frame, Modifier.fillMaxHeight().aspectRatio(1f))
                SectionLabels(section, frame, TextAlign.Start, Modifier.weight(1f))
            }
        }
    }
}

/**
 * What the card carries instead of a picture.
 *
 * The section's glyph on the section's hue, from the theme's own disc recipe. It is
 * plainly a plate and not a cover, which is the point: this build has no image
 * loader, and four coloured rectangles arranged like posters claim otherwise.
 */
@Composable
private fun SectionPlate(section: Section, frame: CastivioFrame, modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(frame.radius / 2)
    Box(
        modifier
            .clip(shape)
            .background(colors.discFill(section.hue))
            .border(BorderStroke(1.dp, colors.discBorder(section.hue)), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = section.icon,
            contentDescription = null,
            tint = colors.discGlyph(section.hue),
            modifier = Modifier.size(frame.brand),
        )
    }
}

/**
 * The section's name, and the one sentence under it.
 *
 * The difference between those two sentences is the whole point of the lazy fetch:
 * "not downloaded yet" is an invitation, a count is a fact about this device. A count
 * with no mark behind it cannot happen.
 */
@Composable
private fun SectionLabels(
    section: Section,
    frame: CastivioFrame,
    align: TextAlign,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(
            text = stringResource(section.name),
            style = castivioChipStyle(frame.fsLabel),
            color = colors.onBackgroundStrong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = align,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = if (section.fetchedAtMs == null) {
                stringResource(R.string.home_not_fetched)
            } else {
                stringResource(section.unit, formatCount(section.count))
            },
            style = castivioBodyStyle(frame.fsBody),
            color = if (section.fetchedAtMs == null) colors.primary else section.hue,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = align,
            modifier = Modifier.fillMaxWidth(),
        )
    }
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
    frame: CastivioFrame,
    onAddSource: () -> Unit,
    onSearch: () -> Unit,
    onLicence: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().height(frame.touchTarget),
        horizontalArrangement = Arrangement.spacedBy(frame.bandTop),
    ) {
        Action(Icons.Rounded.PlaylistAdd, stringResource(R.string.home_change_source), provider, frame, onAddSource, Modifier.weight(1f))
        Action(Icons.Rounded.Search, stringResource(R.string.search_label), null, frame, onSearch, Modifier.weight(1f))
        Action(Icons.Rounded.VerifiedUser, stringResource(R.string.home_action_licence), null, frame, onLicence, Modifier.weight(1f))
        Action(Icons.Rounded.Settings, stringResource(R.string.home_settings), null, frame, onSettings, Modifier.weight(1f))
    }
}

@Composable
private fun Action(
    icon: ImageVector,
    title: String,
    detail: String?,
    frame: CastivioFrame,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    InteractiveGlassCard(
        onClick = onClick,
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(frame.radius / 2),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = frame.chipPad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(frame.chipPad / 2),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = colors.hueViolet,
                modifier = Modifier.size(Sizing.iconMd),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(
                    title,
                    style = castivioChipStyle(frame.fsChip),
                    color = colors.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detail != null) {
                    Text(
                        detail,
                        style = castivioBodyStyle(frame.fsBody),
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
 * belongs where it can be read without hunting — isolated for direction, because a
 * colon-separated hex address reordered by an Arabic paragraph is not the address any
 * more.
 */
@Composable
private fun DeviceStrip(
    state: HomeState,
    frame: CastivioFrame,
    appVersion: String,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(frame.chip)
            .clip(RoundedCornerShape(frame.radius / 2))
            .background(colors.glassFillBrush)
            .padding(horizontal = frame.chipPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(frame.headGap),
    ) {
        Fact(
            Icons.Rounded.Memory,
            stringResource(R.string.home_device_mac, ltrIsolate(state.mac)),
            colors.onBackgroundMuted,
            frame,
            Modifier.weight(1f),
        )
        Fact(
            Icons.Rounded.Lock,
            stringResource(
                R.string.home_device_state,
                stringResource(
                    if (state.licenceHolds) R.string.home_device_activated
                    else R.string.home_device_locked,
                ),
            ),
            if (state.licenceHolds) colors.success else colors.danger,
            frame,
            Modifier.weight(1f),
        )
        Fact(
            Icons.Rounded.Shield,
            stringResource(R.string.home_device_version, ltrIsolate(appVersion)),
            colors.onBackgroundMuted,
            frame,
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun Fact(
    icon: ImageVector,
    text: String,
    tint: Color,
    frame: CastivioFrame,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(frame.chipPad / 2),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Sizing.iconSm))
        Text(
            text,
            style = castivioBodyStyle(frame.fsBody),
            color = colors.onBackgroundVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Disclaimer(frame: CastivioFrame, modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    Row(
        modifier.fillMaxWidth().height(frame.chip),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(frame.chipPad / 2),
    ) {
        Icon(
            Icons.Rounded.Shield,
            contentDescription = null,
            tint = colors.hueViolet,
            modifier = Modifier.size(Sizing.iconSm),
        )
        Text(
            text = stringResource(R.string.home_disclaimer),
            style = castivioBodyStyle(frame.fsBody),
            color = colors.onBackgroundMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ----------------------------------------------------------------- utilities

/**
 * A date, in the reader's own locale, formatted once.
 *
 * `remember` keyed on the value, because building a `DateFormat` and running it is
 * real work and composition runs whenever anything on this screen moves.
 */
@Composable
private fun rememberDate(atMs: Long): String {
    val locale = LocalConfiguration.current
    return remember(atMs, locale) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(atMs))
    }
}

/**
 * The stage at which four cards abreast are worth more than two rows of two.
 *
 * The tablet frame is built around a 1000dp content block — see `CastivioFrame` — so
 * this is that block, not a new number. Below it the handset gets two by two, which
 * keeps each card wide enough for its name at the height a landscape phone has.
 */
private val WIDE_STAGE: Dp = 1000.dp

/**
 * The shortest a section card may be before the disclaimer gives up its band.
 *
 * A floor, not a size: every other height on this screen is measured out of the
 * stage, and this is the one number that says when the arithmetic has taken too much.
 */
private val MIN_CARD: Dp = 92.dp

/** The wordmark's share of the frame's title step, as the licence screen sets it. */
private const val WORD_RATIO = 0.8f
