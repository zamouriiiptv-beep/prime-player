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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.material.icons.rounded.VpnKey
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
         * The header band is the lockup's row and nothing more.
         *
         * It used to be that row *plus* a small line, because a strapline sat under
         * the wordmark. The strapline is gone — it described the application to
         * somebody already using it — and the extra line went with it rather than
         * being left behind as slack the two term cards would silently grow into.
         *
         * **And nothing else is measured out any more.** The board used to be handed a
         * computed height because five bands had to add up: header, cards, actions,
         * device strip, disclaimer. There are two bands now — the board and one footer
         * line — so the board takes what is left with `weight(1f)`. That cannot
         * disagree with the column it lives in the way a second copy of the arithmetic
         * can, and it is why the stage height is no longer a parameter here.
         */
        fun of(frame: CastivioMetrics): Plan = Plan(headerHeight = frame.header)
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
 * ## Two terms, and telling them apart is the point
 *
 * The provider's subscription and Castivio's own licence both run out, and a user who
 * confuses the two rings the wrong support line. The header used to carry one of them
 * across two cards — "Account status" beside "Expires on" — which left the licence
 * with no date anywhere and read as though the screen had one expiry to report.
 *
 * Each term is now one card carrying its own word *and* its own date, and the two are
 * separated four ways at once so the difference is seen rather than read: a hue that
 * is fixed per term and never shared, its own glyph, a label that names the system in
 * full, and its own edge. The hue is the term's identity, not its health — the word
 * carries health — with one exception: a term that has lapsed turns [danger], because
 * an expired licence drawn in its calm identity colour is a warning nobody sees. Two
 * cards can therefore never wear the same colour while either is good.
 *
 * Nothing here is invented. The provider's date is [Recorded.expiresAtMs] as the
 * provider last stated it, and the licence's is whatever [EntitlementState] carries —
 * see [licenceTerm], which is where the nine states become a word and a date.
 *
 * The pair is on every frame, and it shrinks rather than disappearing. A handset is
 * where a user is most likely to be checking whether their subscription is still good,
 * so hiding the two facts there would drop them from the one frame that wanted them.
 *
 * **The two cards take the row's slack, and there is no spacer between them and the
 * clock.** There was one, and it was the defect that shipped: a `Box(weight(1f))`
 * pushing the clock to the trailing end sat beside two cards at `weight(1f, fill =
 * false)`, so Compose divided the leftover width into *three* equal shares and handed
 * a third of it to a box that draws nothing. Each card got 141dp where its words
 * needed 175, and on a device the labels came out as "اشتراك الم…" with the dates
 * clipped away entirely — the header lost the very fact this revision added. With the
 * spacer gone and `fill = true`, the cards absorb the slack themselves and the clock
 * and its chips are pushed to the end by the cards rather than by an empty box.
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
    // **Captured before the pin below overwrites it.** The band's *order* is physical
    // on purpose — the signature does not change sides per locale — but the words
    // inside it are still Arabic, and an Arabic sentence laid out in a pinned
    // left-to-right subtree is read backwards. The cards take this back for their own
    // content, which is the narrow exception the pin was always going to need.
    val reading = LocalLayoutDirection.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier.fillMaxWidth().height(height),
            verticalAlignment = Alignment.CenterVertically,
            // Half the header step, which is the gap the approved drawing measures at
            // this geometry. The full step between six children was spending 113dp of
            // an 801dp row on air, and the cards were paying for it in truncation.
            horizontalArrangement = Arrangement.spacedBy(frame.headGap / 2),
        ) {
            // The lockup alone. The strapline that sat under it — "Premium IPTV
            // player" — described the application to a user already inside it, and
            // it was taking the height the two terms below now spend on their dates.
            CastivioLockup(
                markSize = frame.brand,
                wordSize = (frame.fsTitle.value * WORD_RATIO).sp,
            )

            // **The two terms are one group, spaced as one.** They sat directly in
            // the header row, so the gap between them was `headGap` — the step that
            // separates the lockup from the clock from the chips — and five of those
            // steps were spending 113dp of an 801dp row on air. Nested in a row of
            // their own at `chipPad`, the pair keeps the header's spacing on the
            // outside and takes back what was between them, which is width the dates
            // then have to print in.
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(frame.chipPad),
            ) {
                // Both dates are read before the cards are built, and each is
                // formatted only where one exists: a term with no expiry has nothing
                // to print, and `rememberDate` may not be handed a stand-in date to
                // make the types line up — a formatted epoch zero on a licence card
                // is a lie about a licence.
                val soldUntil = state.subscription?.expiresAtMs
                TermCard(
                    icon = Icons.Rounded.CheckCircle,
                    label = stringResource(R.string.home_term_provider),
                    word = subscriptionLabel(state.subscription),
                    date = if (soldUntil == null) null else rememberDate(soldUntil),
                    hue = if (state.subscription?.usable == false) colors.danger else colors.hueGreen,
                    reading = reading,
                    frame = frame,
                    modifier = Modifier.weight(1f),
                )

                val licence = licenceTerm(state.entitlement)
                val licenceUntil = licence.atMs
                TermCard(
                    icon = Icons.Rounded.Shield,
                    label = stringResource(R.string.home_term_licence),
                    word = stringResource(licence.word),
                    date = if (licenceUntil == null) null else rememberDate(licenceUntil),
                    hue = if (state.licenceHolds) colors.hueAzure else colors.danger,
                    reading = reading,
                    frame = frame,
                    modifier = Modifier.weight(1f),
                )
            }

            Clock(frame)
            trailing()
        }
    }
}

/**
 * One term: which system it belongs to, what it says, and when it runs out.
 *
 * The edge is the hue's, not the shared quiet one, because the edge is half of what
 * separates this card from the term beside it — `discBorder` is the palette's own
 * recipe for "this surface belongs to this hue", and reaching for it here rather
 * than inventing a tint is what keeps the two cards in the system.
 *
 * ## The card reads in the reader's direction, and the band does not
 *
 * [DashboardHeader] pins its subtree to left-to-right so the signature does not swap
 * sides per locale. That is right for the *order of the band* and wrong for the
 * *words inside it*: an Arabic sentence laid out in a pinned left-to-right paragraph
 * comes out reversed, and on a device "حتى 08/04/2027" read as the date first and
 * "حتى" behind it — the sentence backwards. [reading] is the direction captured
 * before the pin, restored here for this card's contents only.
 *
 * ## One sentence, not three children competing for the width
 *
 * The state word and the date were separate `Text`s in a row, so the width question
 * was "which child loses", and the answer kept being the wrong one: first the date
 * was truncated, then the word was ellipsised to a single dot. A line of text is not
 * a layout problem — it is one string with one bidirectional order, so it is now one
 * `Text`.
 *
 * The date comes **first** in that string and the word second, which is what makes
 * the truncation safe: an overflow eats the end of a line, and the end is now the
 * word. Losing "نشط" costs a reader nothing they cannot see in the hue and the glyph;
 * losing "2027" costs them the fact they opened the screen for.
 *
 * The date is optional and absent means absent. A licence bought outright never
 * expires and a provider that was never asked has no date to state; either way the
 * line is the word alone rather than a dash that reads like a fault.
 */
@Composable
private fun TermCard(
    icon: ImageVector,
    label: String,
    word: String,
    date: String?,
    hue: Color,
    /** The reader's own direction, captured before the header pinned its subtree. */
    reading: LayoutDirection,
    frame: CastivioMetrics,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(frame.radius / 2)
    val sentence =
        if (date == null) word else stringResource(R.string.home_term_value, date, word)

    CompositionLocalProvider(LocalLayoutDirection provides reading) {
        Row(
            modifier
                .height(frame.chip + frame.chipPad)
                .clip(shape)
                .background(colors.glassFillBrush)
                .border(BorderStroke(1.dp, colors.discBorder(hue)), shape)
                .padding(horizontal = frame.chipPad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(frame.chipPad / 2),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = hue,
                modifier = Modifier.size(Sizing.iconMd),
            )
            // The words give way, not the card: the row above hands this a share it
            // may be smaller than, and a line that cannot fit its share ellipsizes
            // inside it rather than pushing the clock off the edge.
            Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.Center) {
                Text(
                    label,
                    style = castivioChipStyle(frame.fsChip),
                    color = colors.onBackgroundMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    sentence,
                    style = castivioChipStyle(frame.fsLabel),
                    color = hue,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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

/** Whether Castivio's own licence permits use — the one question every screen asks. */
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
 * What the licence card says: one word, and a date only where one is owed.
 *
 * A word and a resource id rather than a formatted string, so the nine states of
 * [EntitlementState] collapse in one pure function that a JVM test can enumerate.
 * Getting this wrong is not a cosmetic defect — telling somebody who paid that their
 * licence has expired is a refund — so it is the part of this screen that is tested,
 * and it is separated from the drawing for exactly that reason.
 *
 * Two of the mappings are deliberate and would each be a bug read the obvious way:
 *
 * **`VerificationUnavailable` carries no date.** It holds `lastKnownExpiresAtMs`, and
 * printing it would say "valid until March" on the strength of a record we have just
 * admitted we cannot confirm. The word says we could not check; a date beside it
 * would quietly contradict the word.
 *
 * **`Lifetime` has no date and that is not a gap.** It was bought outright and does
 * not run out, so the card stops after the word. A dash there would read as a missing
 * value in something that was paid for.
 *
 * `null` is the state before the first answer arrives, and it reads the same as
 * [EntitlementState.Unknown]: nothing has been established. Neither is an accusation.
 */
internal fun licenceTerm(state: EntitlementState?): Term = when (state) {
    is EntitlementState.TrialActive -> Term(R.string.home_licence_trial, state.expiresAtMs)
    is EntitlementState.AnnualActive -> Term(R.string.home_licence_active, state.expiresAtMs)
    is EntitlementState.AnnualExpired -> Term(R.string.home_licence_expired, state.expiredAtMs)
    EntitlementState.TrialExpired -> Term(R.string.home_licence_expired, null)
    EntitlementState.Lifetime -> Term(R.string.home_licence_lifetime, null)
    is EntitlementState.Revoked -> Term(R.string.home_licence_revoked, null)
    is EntitlementState.VerificationUnavailable -> Term(R.string.home_licence_unverified, null)
    is EntitlementState.ServiceUnavailable -> Term(R.string.home_licence_unavailable, null)
    EntitlementState.Unknown, null -> Term(R.string.home_licence_unknown, null)
}

/** A term's two halves: the word for it, and when it runs out if it ever does. */
internal data class Term(val word: Int, val atMs: Long?)

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
 * gave "Refresh" the same visual claim as "Live TV". They are now a quiet column:
 * same six controls, same order, a fainter edge and a smaller label. Nothing was
 * removed. A viewer looking for Settings still finds it in one pass, and a viewer
 * looking for television no longer reads past it.
 *
 * ## Which column sits where, and why it is this way round
 *
 * The menu takes the **leading** edge and Live takes the **trailing** one: on the
 * left and the right respectively in English, and mirrored in Arabic, because these
 * are start and end rather than left and right. A `Row` reverses under
 * `LayoutDirection.Rtl` on its own, so the order declared here *is* both layouts —
 * and the previous revision, which declared Live first, was therefore wrong in both
 * languages at once rather than in one of them.
 *
 * ## The traversal, which a still picture cannot show
 *
 * Three columns, so the D-pad has a spine: the utilities, then the stack, then Live,
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

        HeroCard(
            section = live,
            frame = frame,
            onClick = { onSeeSection(live.section) },
            modifier = Modifier.weight(HERO_SHARE).fillMaxHeight(),
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
 *
 * ## The middle of the panel was empty, and a spacer is not a composition
 *
 * A small glyph at the top and two lines at the bottom left a third of the largest
 * object on the screen as nothing — the void the owner pointed at on a device. The
 * fix is not to centre the words, which only moves the hole; it is to spend the room.
 * The section's own glyph is drawn again at [HERO_GLYPH_SHARE] of the panel's height,
 * faded to [HERO_GLYPH_ALPHA], filling the upper half as a watermark with the words
 * resting on the floor beneath it.
 *
 * It is ornament, and that is the point: it says nothing the panel does not already
 * say. Filling that space with a *fact* would have meant inventing one — an import
 * percentage, a last-watched channel — and neither exists behind this screen.
 *
 * The watermark is sized from the panel rather than from a token because it is a
 * proportion of a box whose height is decided by the frame; the card clips it, so an
 * unusually short panel crops the glyph instead of overflowing the board.
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
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Icon(
                imageVector = section.icon,
                contentDescription = null,
                tint = colors.discGlyph(section.hue).copy(alpha = HERO_GLYPH_ALPHA),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = frame.chipPad)
                    .size(maxHeight * HERO_GLYPH_SHARE),
            )

            Column(
                Modifier.fillMaxSize().padding(frame.chipPad),
                verticalArrangement = Arrangement.Bottom,
            ) {
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
                        Tally.Counted -> castivioTitleStyle(frame.fsTitle * HERO_COUNT_RATIO)
                        else -> castivioBodyStyle(frame.fsBody)
                    },
                    color = section.ink(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
 * What this device is, and what Castivio is not — in that order, on two lines.
 *
 * ## The identity is what gets photographed
 *
 * A user who cannot find their own MAC address cannot be activated by a provider who
 * works that way, and telling them to dig it out of Settings fails exactly the user
 * this footer is for. Printed here it is one photograph. That is why each fact is a
 * card of its own with its own edge rather than a run-on sentence: three bordered
 * things read as three values to copy, where `Device: Activated · MAC: FE:…` reads as
 * a status line and gets cropped out of the picture.
 *
 * They are spread with equal weights, so the line spends the whole width instead of
 * huddling at the leading edge with half the footer empty — which is what a row of
 * intrinsically-sized facts followed by a spacer actually drew.
 *
 * The address is isolated for direction, because a colon-separated hex address
 * reordered by an Arabic paragraph is not the address any more.
 *
 * ## The disclaimer gets its own line, and it is never cut
 *
 * It shared the identity's line and lost, ellipsised mid-sentence — a legal statement
 * that stops at "Castivio does not provide chan…" is not a statement. On its own line
 * it has the full width, and two lines to use if a translation needs them.
 *
 * ## The device key, which now exists
 *
 * It shipped once without one, because nothing in the build minted a key and a
 * plausible-looking code a user then sends to their provider is worse than no card at
 * all. `DeviceKeyV1` is that value now: derived from the same seed as the address,
 * under its own label so the two cannot be turned into one another, stable across
 * launches and reinstalls, and reproducible by a support tool or a licence server
 * without the device in hand.
 *
 * It is drawn in amber rather than the muted ink the address uses, because the two
 * are answers to *different* questions a provider asks and a user copying one into a
 * form for the other is the mistake this line exists to prevent.
 */
@Composable
private fun FooterLine(
    state: HomeState,
    frame: CastivioMetrics,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(frame.chipPad / 2),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(frame.chipPad),
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
                Modifier.weight(1f),
            )
            Fact(
                Icons.Rounded.Memory,
                stringResource(R.string.home_device_mac, ltrIsolate(state.mac)),
                colors.onBackgroundMuted,
                frame,
                Modifier.weight(1f),
            )
            // Only once there is one. The card is not drawn from a blank, because an
            // empty third box beside two filled ones reads as a value that failed to
            // load rather than one that has not arrived yet.
            if (state.deviceKey.isNotBlank()) {
                Fact(
                    Icons.Rounded.VpnKey,
                    stringResource(R.string.home_device_key, ltrIsolate(state.deviceKey)),
                    colors.hueAmber,
                    frame,
                    Modifier.weight(1f),
                )
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(frame.chipPad / 2, Alignment.CenterHorizontally),
        ) {
            Icon(
                Icons.Rounded.Info,
                contentDescription = null,
                tint = colors.hueViolet,
                modifier = Modifier.size(Sizing.iconSm),
            )
            Text(
                stringResource(R.string.home_disclaimer),
                style = castivioBodyStyle(frame.fsChip),
                color = colors.onBackgroundMuted,
                textAlign = TextAlign.Center,
                maxLines = DISCLAIMER_LINES,
            )
        }
    }
}

/** One value a user copies: a glyph, a label and the value, inside its own edge. */
@Composable
private fun Fact(
    icon: ImageVector,
    text: String,
    tint: Color,
    frame: CastivioMetrics,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(frame.radius / 2)
    Row(
        modifier
            .height(frame.chip)
            .clip(shape)
            .background(colors.glassFill)
            .border(BorderStroke(1.dp, colors.edgeQuiet), shape)
            .padding(horizontal = frame.chipPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(frame.chipPad / 2, Alignment.CenterHorizontally),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Sizing.iconSm))
        Text(
            text,
            style = castivioBodyStyle(frame.fsChip),
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
 * How the board's width is divided: the rail, the stack, the hero.
 *
 * Weights rather than widths, so the three columns share whatever the frame's edge
 * and gaps leave instead of each screen size needing its own arithmetic. The figures
 * are the approved drawing's, read off it at the owner's own geometry — 833x385dp,
 * where the board is 801dp wide and the three columns measure 133, 262 and 378 with
 * two gaps of 11 between them.
 *
 * The hero is not merely the largest. It is larger than the stack *and* the rail
 * together are wide, which is what makes the board read as one destination with
 * alternatives rather than as three equal columns.
 */
private const val RAIL_SHARE = 0.166f
private const val STACK_SHARE = 0.327f
private const val HERO_SHARE = 0.472f

/**
 * The watermark: how much of the hero panel's height its glyph takes, and how far it
 * is faded.
 *
 * Just over half the height, so it reaches into the upper area without crowding the
 * words at the floor, and at a tenth of the glyph's own ink — enough to read as a
 * shape from across a room, far too little to compete with a count set in the title
 * step directly under it. Any stronger and the panel has two subjects.
 */
private const val HERO_GLYPH_SHARE = 0.52f
private const val HERO_GLYPH_ALPHA = 0.10f

/**
 * How much larger the hero's count is than the screen's title step.
 *
 * The one figure a returning viewer checks is the size of their catalogue, and it is
 * the largest thing on the board on purpose. Expressed as a ratio of `fsTitle` rather
 * than a size of its own, so it rides the frame's own type scale up and down instead
 * of being a second, independent opinion about how big text should be — the same
 * device [WORD_RATIO] uses for the wordmark.
 */
private const val HERO_COUNT_RATIO = 1.26f

/**
 * How many lines the disclaimer may use.
 *
 * Two, not one. It shared a line with the device facts and was ellipsised mid-clause,
 * which is the failure this fixes: a legal sentence that stops early has not been
 * shown. One line is enough for it in English and Arabic at the frames Castivio
 * targets; the second exists so a longer translation wraps rather than gets cut.
 */
private const val DISCLAIMER_LINES = 2

/** The wordmark's share of the frame's title step, as the licence screen sets it. */
private const val WORD_RATIO = 0.8f
