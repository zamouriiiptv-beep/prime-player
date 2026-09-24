package com.castivio.feature.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.castivio.core.design.components.CastivioLockup
import com.castivio.core.design.components.CastivioThemeSwitchChip
import com.castivio.core.design.components.EmptyState
import com.castivio.core.design.components.InteractiveGlassCard
import com.castivio.core.design.components.castivioBodyStyle
import com.castivio.core.design.components.castivioChipStyle
import com.castivio.core.design.components.castivioTitleStyle
import com.castivio.core.design.components.formatCount
import com.castivio.core.design.components.ltrIsolate
import com.castivio.core.design.components.ltrToken
import com.castivio.core.design.theme.CASTIVIO_ARTWORK_ASPECT
import com.castivio.core.design.theme.CastivioMetrics
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.rememberMetrics
import com.castivio.domain.MediaKind
import com.castivio.domain.Recorded
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
 * ## Four across, on every frame
 *
 * The approved screen is four cards in one row, and it stays four cards in one row on
 * a handset: 873dp gives each about 185, which is wider than the longest of the four
 * names needs. A revision of this screen folded them to two by two below a width
 * threshold, and the result was a phone that looked like a different product from the
 * set. What changes with the frame is the *height* each card is given, which is
 * measured out of the stage, not the number of them in a row.
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
 * `Time Shift` has no catch-up engine, so there is no button for it. Nothing on this
 * screen fetches anything: the sections are still brought onto the device by the
 * section that was opened, and the subscription pair in the header is what the
 * provider said when it was last asked rather than a fresh call.
 */
@Composable
fun HomeScreen(
    onSeeSection: (CatalogSection) -> Unit,
    /** Add a first subscription, or change the one showing. Opens the activation flow. */
    onAddSource: () -> Unit,
    onSettings: () -> Unit,
    /** Catch-up, which has no engine yet: the caller says so rather than doing nothing. */
    onTimeShift: () -> Unit,
    /** What this build is: its licence, its device, its version. */
    onAbout: () -> Unit,
    /** Opens the language chooser. */
    onLanguage: () -> Unit,
    /** Ask to leave. The confirmation is the application's, not this screen's. */
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    model: HomeViewModel = hiltViewModel(),
) {
    val state by model.state.collectAsStateWithLifecycle()

    // `safeDrawing`, and the metrics are read from what is left after it: turned
    // sideways the system's own navigation sits on one *side*, so a screen that
    // measured the display would size itself to room it does not have and draw its
    // trailing column underneath the navigation.
    BoxWithConstraints(modifier.fillMaxSize().safeDrawingPadding()) {
        val frame = rememberMetrics(maxWidth, maxHeight)
        val plan = Plan.of(frame)

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = frame.edge)
                .padding(top = frame.stageTop, bottom = frame.stageBottom),
            verticalArrangement = Arrangement.spacedBy(frame.bandTop),
        ) {
            DashboardHeader(state, frame, plan.headerHeight) {
                // The trailing end of the header, in the order the other five screens
                // use: the page's own control keeps the outer end and the theme sits
                // just inside it. `CastivioThemeSwitchChip` draws nothing where no
                // switch has been provided, so a preview or a test measuring something
                // else does not have to know whether to ask for one.
                CastivioThemeSwitchChip(
                    chip = frame.chip,
                    touchTarget = frame.touchTarget,
                    fontSize = frame.fsChip,
                    toLighter = stringResource(R.string.home_theme_lighter),
                    toDarker = stringResource(R.string.home_theme_darker),
                )
                LanguageChip(frame, onLanguage)
            }

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
                    SectionBoard(
                        state = state,
                        frame = frame,
                        onSeeSection = onSeeSection,
                        onRefresh = model::refresh,
                        onAddSource = onAddSource,
                        onTimeShift = onTimeShift,
                        onSettings = onSettings,
                        onAbout = onAbout,
                        onExit = onExit,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                    FooterLine(state, frame)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ the plan

/**
 * How the canvas is spent: the two bands whose height is not a frame token.
 *
 * There is nothing conditional left in it. On the reference canvas the stage is
 * always 720dp tall, so every band is the same on every device and this arithmetic
 * produces one answer — which is the point of the canvas rather than a simplification
 * of it. What it still does is make the bands *add up*: the cards take what the
 * header, the actions, the strip, the disclaimer and the five gaps between them
 * leave, so a change to any token moves the cards instead of overflowing the stage.
 */
private data class Plan(
    /** The header band: the frame's row plus the strapline this screen sets under it. */
    val headerHeight: Dp,
) {
    companion object {
        /**
         * The frame's header is the lockup's row. This screen puts a strapline under
         * it, so the band is that row plus one small line — stated here, where the
         * arithmetic can see it, rather than discovered by clipping.
         *
         * **And nothing else is measured out any more.** The board used to be handed a
         * computed height because five bands had to add up: header, cards, actions,
         * device strip, disclaimer. There are two bands now — the board and one footer
         * line — so the board takes what is left with `weight(1f)`. That cannot
         * disagree with the column it lives in the way a second copy of the arithmetic
         * can, and it is why the stage height is no longer a parameter here.
         */
        fun of(frame: CastivioMetrics): Plan = Plan(headerHeight = frame.header + frame.fsChip)
    }
}

// ---------------------------------------------------------------- the header

/**
 * The lockup, what the licence says, and the time.
 *
 * ## The row is physical, and that is on purpose
 *
 * `CastivioHeader` — the header every other screen in the app uses — places its
 * slots with `place` rather than `placeRelative`, so the mark and the name sit on
 * the same physical edge in both text directions. A signature that swaps sides per
 * locale is two signatures, and Home disagreeing with every other screen about
 * where the brand lives is the worse kind of inconsistency: one nobody can point at,
 * that just feels unfinished.
 *
 * Home said the opposite until now. It was an ordinary `Row`, so in Arabic the
 * lockup went to the right and the clock to the left — the mirror image of the same
 * band on the activation screen, the licence screen and the pickers. Declaring this
 * subtree's own direction pins it, which is the mechanism the platform provides and
 * the one `CastivioLockup` already uses internally. Invariant 4 forbids a
 * direction-absolute *API*, not a subtree that states its direction.
 *
 * The subscription pair is on every frame, and it shrinks rather than disappearing.
 * A handset is where a user is most likely to be checking whether their subscription
 * is still good, so hiding the two facts there would drop them from the one frame
 * that wanted them. `weight(fill = false)` is what makes that safe: each card takes
 * what its words need and no more, and gives width back when the row is tight, so
 * the failure mode is an ellipsis rather than a card pushed off the edge.
 *
 * ## Why it is `internal` and takes a slot
 *
 * The Channels board draws this same band — the same lockup, the same two subscription
 * cards, the same clock — and a second declaration of it would be two headers that
 * agree today and disagree after the first edit to either. What differs is only what
 * hangs off the trailing end: Home keeps its theme and language controls there, and the
 * reference Channels page keeps that end clear. So the difference is a parameter, which
 * is invariant 6's rule rather than an exception to it.
 */
@Composable
internal fun DashboardHeader(
    state: HomeState,
    frame: CastivioMetrics,
    height: Dp,
    modifier: Modifier = Modifier,
    /** The trailing controls. Home fills it; the Channels board leaves it empty. */
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val colors = CastivioTheme.colors
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
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

            StatusCard(
                icon = Icons.Rounded.CheckCircle,
                label = stringResource(R.string.home_status_account),
                value = subscriptionLabel(state.subscription),
                tint = when (state.subscription?.usable) {
                    null -> colors.onBackgroundMuted
                    true -> colors.success
                    false -> colors.danger
                },
                frame = frame,
                modifier = Modifier.weight(1f, fill = false),
            )
            StatusCard(
                icon = Icons.Rounded.CalendarMonth,
                label = stringResource(R.string.home_status_expires),
                value = expiryLabel(state.subscription),
                tint = colors.hueViolet,
                frame = frame,
                modifier = Modifier.weight(1f, fill = false),
            )

            Box(Modifier.weight(1f))
            Clock(frame)
            trailing()
        }
    }
}

/** One fact about the subscription: what it is called, and what it says. */
@Composable
private fun StatusCard(
    icon: ImageVector,
    label: String,
    value: String,
    tint: Color,
    frame: CastivioMetrics,
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
        // The words give way, not the card: the row above hands this a share it may
        // be smaller than, and a line that cannot fit its share ellipsizes inside it
        // rather than pushing the clock off the edge.
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.Center) {
            Text(
                label,
                style = castivioChipStyle(frame.fsChip),
                color = colors.onBackgroundMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                value,
                style = castivioChipStyle(frame.fsLabel),
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The language, in the corner the rest of the app keeps it in.
 *
 * The same pill the activation and licence screens draw: the word, then the globe,
 * at the outer end of the header with the theme control just inside it. It says
 * "Language" rather than naming the current one, because that is what the other
 * screens say and a header that disagreed with them about its own furniture would be
 * the kind of inconsistency nobody can point at.
 *
 * Local to this screen rather than shared, which is the same call the other screens
 * made: a chip is a screen's own furniture, and the one shared piece here — the theme
 * switch — is shared because it carries state, not because it is a pill.
 */
@Composable
private fun LanguageChip(frame: CastivioMetrics, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    val label = stringResource(R.string.home_language)
    val shape = RoundedCornerShape(percent = 50)

    Box(
        modifier
            .heightIn(min = frame.touchTarget)
            .clickable(onClick = onClick)
            .clearAndSetSemantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .height(frame.chip)
                .clip(shape)
                .background(colors.glassFill)
                .border(BorderStroke(1.dp, colors.edgeQuiet), shape)
                .padding(horizontal = frame.chipPad),
            horizontalArrangement = Arrangement.spacedBy(frame.chipPad / 2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = castivioChipStyle(frame.fsChip),
                color = colors.onBackgroundVariant,
                maxLines = 1,
            )
            Icon(
                imageVector = Icons.Rounded.Language,
                contentDescription = null,
                tint = colors.onBackgroundMuted,
                modifier = Modifier.size(Sizing.iconMd),
            )
        }
    }
}

/** The time, and the day under it. */
@Composable
private fun Clock(frame: CastivioMetrics, modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    val now = rememberMinute()
    val locale = LocalConfiguration.current
    // Isolated, both of them. A clock and a date are fixed-shape tokens: drawn
    // unisolated in an Arabic composition, `16/09/2026` is reordered into
    // `162026/09/` by the bidirectional algorithm. See [ltrIsolate].
    val time = remember(now, locale) {
        ltrToken(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)))
    }
    val day = remember(now, locale) {
        ltrToken(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(now)))
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
internal fun rememberMinute(): Long {
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

/** Whether Castivio's own licence permits use. Read by the device strip, not the header. */
private val HomeState.licenceHolds: Boolean
    get() = entitlement?.allowsUse == true

/**
 * What the provider said about the subscription.
 *
 * Three answers and not two. A provider that has never been asked is not the same as
 * one that answered "no": the first is a blank the user can fill by refreshing, the
 * second is a fact about their subscription, and a card that showed "Inactive" for
 * both would be accusing a working provider of being dead.
 *
 * The panel's own word is preferred where it gave one — "Active", "Expired",
 * "Banned" — because that is the word the user will see if they log into their
 * provider, and translating it into ours would make the two disagree.
 */
@Composable
internal fun subscriptionLabel(status: Recorded?): String {
    if (status == null) return stringResource(R.string.home_account_unknown)
    // Bound to a local first: `label` is a property of a class in another module, so
    // the compiler will not carry a null check across the dot however plainly the
    // check reads.
    val panel = status.label
    return when {
        !panel.isNullOrBlank() -> panel
        status.usable -> stringResource(R.string.home_account_active)
        else -> stringResource(R.string.home_account_inactive)
    }
}

/**
 * When the subscription runs out.
 *
 * A dash where the provider stated no date, and the same dash where it was never
 * asked — the difference between those two is carried by the card beside this one,
 * and putting it in both would say it twice.
 */
@Composable
internal fun expiryLabel(status: Recorded?): String {
    val at = status?.expiresAtMs ?: return stringResource(R.string.home_expires_none)
    return rememberDate(at)
}

// ------------------------------------------------------------- the board

/**
 * The four sections, weighted by how often they are opened.
 *
 * ## Why one of them is large
 *
 * The four were one row of equal cards, and the equality was the defect. Live
 * television is what this application is opened for; Radio is a section most
 * subscriptions do not even carry. Four cards of one size say those are the same
 * decision, so the board offered no path — the eye had to read all four names to
 * find the one it wanted, every time, and a launcher whose items are all equally
 * important is a settings list wearing a launcher's clothes.
 *
 * So Live takes a panel and the other three take a line each. That is not decoration:
 * the room Live gains is what lets it carry its count at a size readable across a
 * living room, and the room the other three give up is room they were spending on
 * nothing — a glyph floating above two short words, with a third of the card empty
 * beneath it.
 *
 * ## And the utilities are demoted, deliberately
 *
 * They were six pills the width of the section cards and directly under them, which
 * gave "Refresh" the same visual claim as "Live TV". They are now a quiet column at
 * the trailing edge: same six controls, same order, a fainter edge and a smaller
 * label. Nothing was removed. A viewer looking for Settings still finds it in one
 * pass, and a viewer looking for television no longer reads past it.
 *
 * ## The traversal, which a still picture cannot show
 *
 * Three columns, so the D-pad has a spine: Live, then the stack, then the utilities,
 * with up and down staying inside whichever column has focus. Compose's own focus
 * search resolves all of that from the geometry here — there is no focus order to
 * declare, and declaring one would be a second description of a layout that already
 * says it.
 */
@Composable
private fun SectionBoard(
    state: HomeState,
    frame: CastivioMetrics,
    onSeeSection: (CatalogSection) -> Unit,
    onRefresh: () -> Unit,
    onAddSource: () -> Unit,
    onTimeShift: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val live = Section(
        CatalogSection.Live, Icons.Rounded.LiveTv, colors.hueAzure,
        R.string.browse_live, state.liveCount, R.string.home_count_live,
        R.string.home_section_no_live, state.sections[MediaKind.LIVE],
    )
    val rest = listOf(
        Section(
            CatalogSection.Movies, Icons.Rounded.Movie, colors.hueViolet,
            R.string.browse_movies, state.movieCount, R.string.home_count_movies,
            R.string.home_section_no_movies, state.sections[MediaKind.MOVIE],
        ),
        Section(
            CatalogSection.Series, Icons.Rounded.Tv, colors.hueGreen,
            R.string.browse_series, state.seriesCount, R.string.home_count_series,
            R.string.home_section_no_series, state.sections[MediaKind.SERIES],
        ),
        Section(
            CatalogSection.Radio, Icons.Rounded.Radio, colors.hueAmber,
            R.string.browse_radio, state.radioCount, R.string.home_count_radio,
            R.string.home_section_no_radio, state.sections[MediaKind.RADIO],
        ),
    )

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(frame.bandTop)) {
        HeroCard(
            section = live,
            frame = frame,
            onClick = { onSeeSection(live.section) },
            modifier = Modifier.weight(HERO_SHARE).fillMaxHeight(),
        )

        Column(
            Modifier.weight(STACK_SHARE).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(frame.bandTop),
        ) {
            for (section in rest) {
                SectionLine(
                    section = section,
                    frame = frame,
                    onClick = { onSeeSection(section.section) },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }

        UtilityRail(
            frame = frame,
            onRefresh = onRefresh,
            onAddSource = onAddSource,
            onTimeShift = onTimeShift,
            onSettings = onSettings,
            onAbout = onAbout,
            onExit = onExit,
            modifier = Modifier.weight(RAIL_SHARE).fillMaxHeight(),
        )
    }
}

/** One section's worth of decisions, so the lists above read as lists rather than walls. */
private data class Section(
    val section: CatalogSection,
    val icon: ImageVector,
    val hue: Color,
    val name: Int,
    val count: Int,
    /** "%s channels" — used only when there is a number worth printing. */
    val unit: Int,
    /** What this section says when the provider was asked and sent nothing. */
    val empty: Int,
    /** When this section was brought onto the device, or null if it never has been. */
    val fetchedAtMs: Long?,
)

/**
 * What a section says about itself, which is three different sentences.
 *
 * Never a zero. "0 channels" is the sentence a viewer reads as a fault in the
 * application, and it is indistinguishable at a glance from a section that has not
 * been asked yet — the two states this screen exists to keep apart. A section the
 * provider answered with nothing says so in words, and it stays pressable, because
 * pressing it is how it is asked again.
 */
private enum class Tally { Unfetched, Empty, Counted }

private val Section.tally: Tally
    get() = when {
        fetchedAtMs == null -> Tally.Unfetched
        count <= 0 -> Tally.Empty
        else -> Tally.Counted
    }

@Composable
private fun Section.sentence(): String = when (tally) {
    Tally.Unfetched -> stringResource(R.string.home_not_fetched)
    Tally.Empty -> stringResource(empty)
    Tally.Counted -> stringResource(unit, formatCount(count))
}

@Composable
private fun Section.ink(): Color {
    val colors = CastivioTheme.colors
    return when (tally) {
        Tally.Unfetched -> colors.primary
        Tally.Empty -> colors.onBackgroundMuted
        Tally.Counted -> hue
    }
}

/**
 * Live television, at the size its use deserves.
 *
 * The count is the largest figure on the screen and the only one set in the title
 * step, because it is the one fact a returning viewer checks: that the catalogue on
 * this device is the size it was. It is `tabular` for the same reason every other
 * changing figure in this application is — a number that shifts width as it rises
 * reads as instability in the thing counting.
 *
 * The glyph is drawn on the panel rather than inside a plate of its own. Every
 * section used to be two visible rectangles, one nested in the other, and two edges
 * around one idea is the detail that makes a screen look assembled rather than
 * designed.
 */
@Composable
private fun HeroCard(
    section: Section,
    frame: CastivioMetrics,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    InteractiveGlassCard(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(frame.radius),
    ) {
        Column(Modifier.fillMaxSize().padding(frame.chipPad)) {
            Icon(
                imageVector = section.icon,
                contentDescription = null,
                tint = colors.discGlyph(section.hue),
                modifier = Modifier.size(frame.brand),
            )

            // **The glyph opens the panel and the words close it**, rather than three
            // children stacked at the top with the bottom third empty — which is the
            // shape the four equal cards had and the reason they read as unfinished.
            // One spacer between two groups, not `SpaceBetween` over three children:
            // the panel's composition must not change when a count becomes a sentence.
            Spacer(Modifier.weight(1f))

            Text(
                text = stringResource(section.name),
                style = castivioTitleStyle(frame.fsLabel),
                color = colors.onBackgroundStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = section.sentence(),
                style = when (section.tally) {
                    Tally.Counted -> castivioTitleStyle(frame.fsTitle)
                    else -> castivioBodyStyle(frame.fsBody)
                },
                color = section.ink(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One of the other three, on one line.
 *
 * Glyph, name, and what the section says — read in that order and in one pass. The
 * name and the sentence are stacked so the row survives a long translation without
 * the count being pushed off the end, which is what a three-column row of fixed
 * shares would have done to Arabic.
 */
@Composable
private fun SectionLine(
    section: Section,
    frame: CastivioMetrics,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    InteractiveGlassCard(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(frame.radius / 2),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = frame.chipPad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(frame.chipPad),
        ) {
            Icon(
                imageVector = section.icon,
                contentDescription = null,
                tint = colors.discGlyph(section.hue),
                modifier = Modifier.size(Sizing.iconMd),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(section.name),
                    style = castivioChipStyle(frame.fsLabel),
                    color = colors.onBackgroundStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = section.sentence(),
                    style = castivioBodyStyle(frame.fsBody),
                    color = section.ink(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// --------------------------------------------------------------- the utilities

/**
 * What a viewer does from Home that is not "open a section": six controls, in the
 * approved order, at the trailing edge.
 *
 * They were a centred row of pills directly under the section cards, at the same
 * width and the same weight. That row read as a fifth and sixth and seventh
 * destination, and it sat in the path between the sections and everything below
 * them. As a column at the edge they are where a hand reaches for a tool and where
 * an eye looking for television does not go.
 *
 * ## Two of these needed something built behind them
 *
 * **Refresh** re-asks the provider the one cheap question and records the answer,
 * which is what moves the two facts in the header. It downloads no catalogue — see
 * [com.castivio.domain.RefreshProvider].
 *
 * **Time Shift** has no catch-up engine in this build, and it is on the rail anyway
 * because it was asked for twice. What it must not be is silent: pressing it says
 * so, in the app's own words for a part of Castivio that is not ready yet, rather
 * than doing nothing and teaching the user that the rail cannot be trusted.
 *
 * The language is not here. It sits in the header at the outer end, with the theme
 * control just inside it — the corner the other five screens keep it in.
 */
@Composable
private fun UtilityRail(
    frame: CastivioMetrics,
    onRefresh: () -> Unit,
    onAddSource: () -> Unit,
    onTimeShift: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(frame.chipPad / 2)) {
        Utility(Icons.Rounded.Refresh, stringResource(R.string.home_refresh), frame, onRefresh)
        Utility(Icons.Rounded.PlaylistAdd, stringResource(R.string.home_change), frame, onAddSource)
        Utility(Icons.Rounded.History, stringResource(R.string.home_time_shift), frame, onTimeShift)
        Utility(Icons.Rounded.Settings, stringResource(R.string.home_settings), frame, onSettings)
        Utility(Icons.Rounded.Info, stringResource(R.string.home_about), frame, onAbout)
        Utility(Icons.Rounded.PowerSettingsNew, stringResource(R.string.home_exit), frame, onExit)
    }
}

@Composable
private fun ColumnScope.Utility(
    icon: ImageVector,
    label: String,
    frame: CastivioMetrics,
    onClick: () -> Unit,
) {
    val colors = CastivioTheme.colors
    InteractiveGlassCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().weight(1f),
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
                modifier = Modifier.size(Sizing.iconSm),
            )
            Text(
                label,
                style = castivioChipStyle(frame.fsChip),
                color = colors.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------- the footer

/**
 * One line: what this device is, and what Castivio is not.
 *
 * It was three bands — a full-width filled strip holding two short facts with a
 * great deal of nothing between them, and the disclaimer centred under it. A box
 * that wide around that little is the shape a screen takes when a band was given a
 * height before anyone asked what would go in it.
 *
 * The address is the one a user reads out to a provider who activates by MAC, so it
 * stays; it is isolated for direction, because a colon-separated hex address
 * reordered by an Arabic paragraph is not the address any more. The device key that
 * a reference design put beside it is deliberately absent: a key printed on the
 * screen a phone is most often photographed on is a key that leaves with the photo,
 * and Settings is one press away.
 */
@Composable
private fun FooterLine(
    state: HomeState,
    frame: CastivioMetrics,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    Row(
        modifier.fillMaxWidth().height(frame.chip),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(frame.headGap),
    ) {
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
        )
        Fact(
            Icons.Rounded.Memory,
            stringResource(R.string.home_device_mac, ltrIsolate(state.mac)),
            colors.onBackgroundMuted,
            frame,
        )
        Spacer(Modifier.weight(1f))
        Fact(
            Icons.Rounded.Shield,
            stringResource(R.string.home_disclaimer),
            colors.hueViolet,
            frame,
            Modifier.weight(DISCLAIMER_SHARE, fill = false),
        )
    }
}

@Composable
private fun Fact(
    icon: ImageVector,
    text: String,
    tint: Color,
    frame: CastivioMetrics,
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
internal fun rememberDate(atMs: Long): String {
    val locale = LocalConfiguration.current
    return remember(atMs, locale) {
        ltrToken(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(atMs)))
    }
}

/**
 * How the board's width is divided: the hero, the stack, the rail.
 *
 * Weights rather than widths, so the three columns share whatever the frame's edge
 * and gaps leave instead of each screen size needing its own arithmetic. The figures
 * are the approved drawing's, read off it at the owner's own geometry — 833x385dp,
 * where the board is 801dp wide and the three columns measure 378, 262 and 133 with
 * two gaps of 11 between them.
 *
 * The hero is not merely the largest. It is larger than the stack *and* the rail
 * together are wide, which is what makes the board read as one destination with
 * alternatives rather than as three equal columns.
 */
private const val HERO_SHARE = 0.472f
private const val STACK_SHARE = 0.327f
private const val RAIL_SHARE = 0.166f

/**
 * What the disclaimer may take of the footer line before it is cut.
 *
 * `fill = false`, so it asks for what its words need and gives the rest back: the
 * two device facts keep their width on a narrow frame and the sentence ellipsises,
 * which is the right one of the three to lose. Half, because a footer whose legal
 * line is longer than everything else on it is a legal line pretending to be
 * content.
 */
private const val DISCLAIMER_SHARE = 0.5f

/** The wordmark's share of the frame's title step, as the licence screen sets it. */
private const val WORD_RATIO = 0.8f
