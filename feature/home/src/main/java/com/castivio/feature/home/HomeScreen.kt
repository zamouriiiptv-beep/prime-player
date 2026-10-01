package com.castivio.feature.home

import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.castivio.core.design.components.CastivioClock
import com.castivio.core.design.components.CastivioLockup
import com.castivio.core.design.components.CastivioThemeSwitchChip
import com.castivio.core.design.components.EmptyState
import com.castivio.core.design.components.GlassCard
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
import com.castivio.core.design.theme.castivioBackdrop
import com.castivio.core.design.theme.rememberMetrics
import com.castivio.domain.MediaKind
import com.castivio.domain.Recorded
import com.castivio.domain.entitlement.EntitlementState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    /**
     * Add another playlist, from the menu of the ones already here.
     *
     * Separate from [onAddSource] because the two are different questions asked by
     * different users. The empty state's button is "I have nothing, set me up", and it
     * opens where the flow has always opened. This one is pressed by somebody who is
     * already watching, already knows what a subscription is, and has just been shown
     * their list — so it opens on the *chooser*, which is the screen that asks how.
     */
    onAddPlaylist: () -> Unit,
    /**
     * Open the saved-subscriptions screen: the one that renames, deletes and shows
     * which is in use. The same activation flow [onAddSource] opens, at the step that
     * screen already occupies — this screen does not know how to get there, only that
     * somebody else does.
     */
    onManageSources: () -> Unit,
    onSettings: () -> Unit,
    /** Catch-up, which has no engine yet: the caller says so rather than doing nothing. */
    onTimeShift: () -> Unit,
    /** Opens the language chooser. */
    onLanguage: () -> Unit,
    /** Ask to leave. The confirmation is the application's, not this screen's. */
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    model: HomeViewModel = hiltViewModel(),
) {
    val state by model.state.collectAsStateWithLifecycle()

    // **Where the remote is when this board appears.**
    //
    // Home had no initial focus of its own and did not need one while it was never taken
    // out of the composition: the shell drew the subscription flow over it, so coming back
    // found the highlight where it was left. The flow replaces this screen now — it paints
    // no ground, and drawn over a composed Home the two read as one screen — so Home is
    // built afresh on the way back and arrives with nothing focused. On a remote that is a
    // board with no way in until a direction key has been spent entering it.
    //
    // Requested rather than restored, which is the deliberate half. The highlight lands in
    // the same place every time the board appears — the first control on the rail — instead
    // of wherever the last visit ended, so returning is a position a user can learn.
    //
    // Attached to the rail rather than to Refresh itself: a `FocusRequester` on a container
    // enters its first focusable child, which is Refresh, and stays right while a refresh
    // in flight has replaced that card with its busy twin. The same thing `ActivationSurface`
    // does with the frame it hands the flow.
    val boardFocus = remember { FocusRequester() }

    // Not while loading. That branch draws an empty box with nothing focusable in it, and
    // the request would be thrown away by the `runCatching` inside — quietly, and for good,
    // because the effect does not run again on a key it has already seen. Keyed on the
    // state instead, it is asked once the board is really there.
    LaunchedFocus(boardFocus, enabled = !state.loading)

    // `safeDrawing`, and the metrics are read from what is left after it: turned
    // sideways the system's own navigation sits on one *side*, so a screen that
    // measured the display would size itself to room it does not have and draw its
    // trailing column underneath the navigation.
    //
    // **The horizontal inset is symmetrised rather than applied as given**, which is
    // the one thing this differs from `safeDrawingPadding()` in. Held sideways, the
    // system's furniture is on the *sides*: a camera cutout on one edge and the
    // navigation bar on the other, and they are not the same width. Insetting each
    // edge by its own obstruction keeps the board out of both and leaves it visibly
    // off-centre — which is what the board did on a device, sitting a few dp nearer
    // one edge than the other with no reason a viewer could see. Taking the larger of
    // the two and spending it on both edges costs that difference in width and buys a
    // block that is centred on the *screen*, which is the thing being looked at.
    //
    // Start and end rather than left and right: the two are compared, never placed,
    // and the maximum of the pair is the same number whichever way the text runs. The
    // result is symmetric by construction, so this screen lays out identically in
    // both directions — which is what invariant 9 is protecting and why reading a
    // physical inset here does not breach it.
    val safeArea = WindowInsets.safeDrawing.asPaddingValues()
    val reading = LocalLayoutDirection.current
    val gutter = maxOf(
        safeArea.calculateStartPadding(reading),
        safeArea.calculateEndPadding(reading),
    )

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .padding(
                top = safeArea.calculateTopPadding(),
                bottom = safeArea.calculateBottomPadding(),
            )
            .padding(horizontal = gutter),
    ) {
        val frame = rememberMetrics(maxWidth, maxHeight)
        val plan = Plan.of(frame)
        val rhythm = Rhythm.of(frame)

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = frame.edge)
                .padding(vertical = rhythm.stage),
            verticalArrangement = Arrangement.spacedBy(rhythm.group),
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

                // The empty states carry the requester too, and for the same reason the
                // board does: their button is the only control on the screen, and this
                // one is a door into the very flow the user is coming back from.
                !state.hasSource -> Box(
                    Modifier.fillMaxSize().focusRequester(boardFocus),
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
                    Modifier.fillMaxSize().focusRequester(boardFocus),
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
                        rhythm = rhythm,
                        railFocus = boardFocus,
                        onSeeSection = onSeeSection,
                        onRefresh = model::refresh,
                        onChoosePlaylist = model::choosePlaylist,
                        onAddPlaylist = onAddPlaylist,
                        onManageSources = onManageSources,
                        onTimeShift = onTimeShift,
                        onSettings = onSettings,
                        onExit = onExit,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                    FooterLine(state, frame, rhythm)
                }
            }
        }

        // **Floating, so it cannot move the board.**
        //
        // A notice that took a row in the column above would push the footer and
        // re-measure the section cards the moment a request failed — the layout
        // changing shape to report that nothing changed. Drawn in the frame instead,
        // over the footer, it says what happened and costs the board nothing.
        state.refreshFault?.let { fault ->
            RefreshNotice(
                fault = fault,
                frame = frame,
                onDismiss = model::clearRefreshFault,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = frame.edge),
            )
        }
    }
}

/**
 * What a refresh that produced no answer has to say.
 *
 * Two sentences, because the two failures need opposite ones: a provider that was
 * never there is asking for a subscription, and a provider that did not answer is
 * asking for another press in a minute. The same split `Refreshed` draws in the
 * domain, arriving here rather than being decided again.
 *
 * Dismissed by pressing it, and by nothing else. A notice on a timer is one a viewer
 * looks up to find already gone, and on a television there is no "swipe away" — the
 * press that acknowledges it is the only gesture every input has.
 */
@Composable
private fun RefreshNotice(
    fault: RefreshFault,
    frame: CastivioMetrics,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(frame.radius / 2)
    val message = stringResource(
        when (fault) {
            RefreshFault.NoProvider -> R.string.home_refresh_no_provider
            RefreshFault.Unreachable -> R.string.home_refresh_unreachable
        },
    )
    Row(
        modifier
            .heightIn(min = frame.touchTarget)
            .clip(shape)
            .clickable(onClick = onDismiss)
            .background(colors.glassFillBrush)
            .border(BorderStroke(1.dp, colors.discBorder(colors.danger)), shape)
            .padding(horizontal = frame.chipPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(frame.chipPad / 2),
    ) {
        Icon(
            Icons.Rounded.Info,
            contentDescription = null,
            tint = colors.danger,
            modifier = Modifier.size(Sizing.iconSm),
        )
        Text(
            message,
            style = castivioBodyStyle(frame.fsChip),
            color = colors.onBackgroundVariant,
            maxLines = NOTICE_LINES,
            overflow = TextOverflow.Ellipsis,
        )
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

/**
 * The vertical rhythm: four steps, and the relationships between them are the point.
 *
 * ## Why this is not `bandTop` four times
 *
 * It was. One token spaced the header from the board, the board from the footer, *and*
 * the section cards from one another — so the distance between two **groups** was the
 * same as the distance between two **members of a group**, and nothing told a reader
 * where the header ended and the content began. Beside it the utility rail ran at
 * `chipPad / 2`, a third of the cards' step, so two columns standing side by side kept
 * two different time signatures.
 *
 * Four steps now, and each is defined by its relation to the others rather than
 * chosen:
 *
 *  - [group] separates groups, and is the largest. Nothing inside a group may equal it.
 *  - [item] separates members of a group — the three section cards.
 *  - [rail] is exactly **half** [item], so the two columns of the board are the same
 *    rhythm at two densities rather than two unrelated ones. Six controls in the
 *    height of three cards is a 2:1 count; a 2:1 gap is what makes that read as
 *    deliberate.
 *  - [foot] holds the footer's own sentence off its cards, and is the quietest.
 *
 * ## Why the stage padding is [item] and not its own token
 *
 * `stageTop` and `stageBottom` are 17.1 and 15.7 at this frame — different from each
 * other for no reason a viewer could name, and generous on a screen this short. Home
 * is the densest surface in the application and it spends that difference on content.
 * Using [item] for both makes the block symmetric top to bottom, which is what the
 * horizontal gutter already does side to side.
 *
 * The shared tokens are deliberately **not** touched. `stageTop`, `stageBottom` and
 * `bandTop` are read by the shell, the activation flow, the pickers and their tests;
 * moving them to suit one screen would move seven, and none of those was reviewed.
 * This screen states its own rhythm and derives it from the frame, so it still scales
 * with every other size rather than pinning a number to one phone.
 *
 * At the owner's 833×385dp: [group] 20, [item] 12, [rail] 6, [foot] 8, stage 12.
 */
private data class Rhythm(
    /** Above and below the whole block, equal on both sides. */
    val stage: Dp,
    /** Header ↔ board ↔ footer. */
    val group: Dp,
    /** Between the three section cards. */
    val item: Dp,
    /** Between the utility controls. */
    val rail: Dp,
    /** Inside the footer: the identity cards ↔ the sentence under them. */
    val foot: Dp,
) {
    companion object {
        fun of(frame: CastivioMetrics): Rhythm {
            val item = frame.bandTop * ITEM_RATIO
            return Rhythm(
                stage = item,
                group = frame.bandTop * GROUP_RATIO,
                item = item,
                rail = item / 2,
                foot = frame.bandTop * FOOT_RATIO,
            )
        }
    }
}

/**
 * The three ratios, off `bandTop`, that produce the approved drawing's figures.
 *
 * Ratios rather than sizes because a television is not a handset: expressed this way
 * the whole rhythm rides the frame's own scale, so a 1280×720 set gets 37.3 / 22.4 /
 * 11.2 / 15.0 in place of 20 / 12 / 6 / 8 and keeps every relationship between them.
 */
private const val GROUP_RATIO = 1.274f
private const val ITEM_RATIO = 0.764f
private const val FOOT_RATIO = 0.509f

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
                // Each card is handed a finished sentence rather than the parts of
                // one. Composing it here keeps the two compositions — "expires on a
                // date" and "a plan, and when it runs out" — beside the facts they
                // are made from, and leaves `TermCard` with nothing to arbitrate.
                TermCard(
                    icon = Icons.Rounded.CheckCircle,
                    label = stringResource(R.string.home_term_provider),
                    value = providerSentence(state.subscription),
                    hue = if (state.subscription?.usable == false) colors.danger else colors.hueGreen,
                    reading = reading,
                    frame = frame,
                    modifier = Modifier.weight(1f),
                )

                TermCard(
                    icon = Icons.Rounded.Shield,
                    label = stringResource(R.string.home_term_licence),
                    value = licenceSentence(licenceTerm(state.entitlement)),
                    hue = if (state.licenceHolds) colors.hueAzure else colors.danger,
                    reading = reading,
                    frame = frame,
                    modifier = Modifier.weight(1f),
                )
            }

            CastivioClock(fsTime = frame.fsTitle, fsDate = frame.fsChip)
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
    /** The finished second line. See [providerSentence] and [licenceSentence]. */
    value: String,
    hue: Color,
    /** The reader's own direction, captured before the header pinned its subtree. */
    reading: LayoutDirection,
    frame: CastivioMetrics,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(frame.radius / 2)

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
                    value,
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

// The clock and its ticker were both here, private to this file, until the activation
// header wanted the same pair in the same corner. They are `CastivioClock` and
// `rememberMinute` in `:core:design` now — one declaration, one isolation rule for the
// digits, one receiver. See the note on the component.

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
 * The provider card's second line: the date the subscription runs out, and nothing
 * else.
 *
 * ## Why there is no verb in front of it
 *
 * There was: "Expires on 08/04/2027". It fits in Arabic and does not fit in English,
 * and the half it lost was the date — the line ellipsises from its end, and "Expires
 * on 08/04/2…" is the one shape this line must never take. The card is 158dp in a
 * Latin locale against 188 in Arabic, because the trailing furniture in the header is
 * wider there, and the sentence needs 128dp of the 114 that leaves.
 *
 * So the words go and the figures stay. The label directly above already says
 * *Provider subscription*; a date under it is an expiry by the same convention every
 * subscription card in the world uses, and at 65dp it fits every language this
 * application ships with and leaves 49dp spare — which is the difference between a
 * line that fits today and a line that fits after the next translation.
 *
 * A provider that stated no date falls back to its word, which is the one case where
 * the word carries the whole line: "Not checked" is a real answer and an empty second
 * line is not. Three answers, not two — see [subscriptionLabel].
 */
@Composable
private fun providerSentence(status: Recorded?): String {
    val at = status?.expiresAtMs ?: return subscriptionLabel(status)
    return rememberDate(at)
}

/**
 * The licence card's second line: the date it runs out, or what it is when it does
 * not run out.
 *
 * **The date alone where there is one**, for the reason [providerSentence] gives: a
 * verb in front of it is the half that fits in one language and not in the next, and
 * the figures are the half that cannot be recovered from anywhere else on the screen.
 * The plan name went the same way and for the same reason — trial and annual differ
 * in *when* they end, not in what the card is for, and the licence screen answers
 * which plan it is with room to answer it in.
 *
 * **The word alone where there is no date.** A lifetime licence never expires, so the
 * line is "Lifetime" and stops — a date slot filled with a dash would read as a value
 * that failed to load in something bought outright. Every other state — expired,
 * withdrawn, unverified, not established — arrives from [licenceTerm] with no date for
 * the same reason, and says its word.
 *
 * Which is to say the two branches are one rule, and it is the rule
 * [providerSentence] follows beside it: the date if there is one, the word if there
 * is not. The two cards read as a pair because they are built the same way.
 */
@Composable
private fun licenceSentence(term: Term): String {
    val at = term.atMs ?: return stringResource(term.word)
    return rememberDate(at)
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
    is EntitlementState.AnnualActive -> Term(R.string.home_licence_annual, state.expiresAtMs)
    // **No date, although the state carries one.** `expiredAtMs` is when it lapsed,
    // and this card's date slot means "runs out on", which is future tense. "Expired
    // · expires on 4 March" is the screen contradicting itself in four words. The
    // licence screen is where a lapse says *when*; here it says *that*.
    is EntitlementState.AnnualExpired -> Term(R.string.home_licence_expired, null)
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
    rhythm: Rhythm,
    /**
     * Where the remote lands when this board appears — the rail, and so its first
     * control. Held by the screen rather than by the rail because the screen is what
     * knows the board has just been built; see its use in [HomeScreen].
     */
    railFocus: FocusRequester,
    onSeeSection: (CatalogSection) -> Unit,
    onRefresh: () -> Unit,
    onChoosePlaylist: (String) -> Unit,
    onAddPlaylist: () -> Unit,
    onManageSources: () -> Unit,
    onTimeShift: () -> Unit,
    onSettings: () -> Unit,
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
            state = state,
            frame = frame,
            rhythm = rhythm,
            onRefresh = onRefresh,
            onChoosePlaylist = onChoosePlaylist,
            onAddPlaylist = onAddPlaylist,
            onManageSources = onManageSources,
            onTimeShift = onTimeShift,
            onSettings = onSettings,
            onExit = onExit,
            modifier = Modifier
                .weight(RAIL_SHARE)
                .fillMaxHeight()
                .focusRequester(railFocus),
        )

        Column(
            Modifier.weight(STACK_SHARE).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(rhythm.item),
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
            // **Filled with a gradient, not a tint, and placed dead centre.** It hung
            // from the top edge in a flat wash of the section's hue at a tenth of its
            // ink, which read as an icon somebody forgot in a corner rather than a
            // mark the panel was built around. Centred, a little larger and carrying
            // the azure-to-violet ramp the backdrop and the wordmark already use, it
            // reads as part of the identity instead of a shape added to it.
            //
            // `Icon` paints a vector in one colour, so the gradient is applied after
            // the fact: the glyph is drawn into its own layer and `SrcIn` replaces
            // every opaque pixel of it with the brush, which is why the layer is
            // forced offscreen — without that the blend would reach the card behind.
            // The alpha rides on the same layer so it multiplies the finished ramp
            // once rather than each colour separately.
            val ramp = Brush.linearGradient(
                listOf(colors.discGlyph(section.hue), colors.hueViolet),
            )
            Icon(
                imageVector = section.icon,
                contentDescription = null,
                tint = colors.onBackgroundStrong,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(maxHeight * HERO_GLYPH_SHARE)
                    .graphicsLayer {
                        alpha = HERO_GLYPH_ALPHA
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(ramp, blendMode = BlendMode.SrcIn)
                    },
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
    state: HomeState,
    frame: CastivioMetrics,
    rhythm: Rhythm,
    onRefresh: () -> Unit,
    onChoosePlaylist: (String) -> Unit,
    onAddPlaylist: () -> Unit,
    onManageSources: () -> Unit,
    onTimeShift: () -> Unit,
    onSettings: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(rhythm.rail)) {
        // The refresh control, which says whether it is working.
        //
        // The card is the same card at the same size: only the glyph is swapped for a
        // spinner, so the rail's geometry cannot move while a request is in flight.
        // Disabled while busy, because the press has already been taken — a second one
        // would start a second request whose answer overwrites the first's.
        if (state.refreshing) {
            UtilityBusy(stringResource(R.string.home_refresh), frame)
        } else {
            Utility(Icons.Rounded.Refresh, stringResource(R.string.home_refresh), frame, onRefresh)
        }
        PlaylistPicker(
            state = state,
            frame = frame,
            onChoose = onChoosePlaylist,
            onAdd = onAddPlaylist,
            onManage = onManageSources,
        )
        Utility(Icons.Rounded.History, stringResource(R.string.home_time_shift), frame, onTimeShift)
        Utility(Icons.Rounded.Settings, stringResource(R.string.home_settings), frame, onSettings)
        Utility(Icons.Rounded.PowerSettingsNew, stringResource(R.string.home_exit), frame, onExit)
    }
}

/**
 * The rail control that changes which playlist is showing.
 *
 * ## Why it is a menu and not a screen
 *
 * It used to open the activation flow, which is three presses from here to the list
 * of saved subscriptions and a full-screen departure from Home to perform what is,
 * for a user with two playlists, a toggle. The subscriptions were always there —
 * `SourceRepository` has carried a list, an active one and a switch since the
 * saved-sources screen shipped — and the only thing missing was somewhere near the
 * user to spend them. The saved-sources screen is not replaced: it still owns adding
 * and managing, and the menu's last entry is a door to it — which is what separates
 * the two. This menu does one thing to a playlist, immediately: it switches. Renaming
 * one, deleting one, or reading what a subscription actually is are not menu-sized
 * jobs, and they already have a screen.
 *
 * ## Why it costs the layout nothing
 *
 * The rail is five cards in a fixed column and this is still one of them: the picker
 * is a `Box` that takes exactly the weight the card took, with the same card drawn to
 * fill it. A [DropdownMenu] draws in a popup window of its own, so an open menu does
 * not participate in this layout at all — nothing reflows, nothing is measured twice,
 * and the board behind it keeps every dimension it had.
 *
 * ## The names
 *
 * What the user typed when they added the subscription, and `Playlist 1`, `Playlist
 * 2` when they typed nothing — the numbering is the repository's order, which is the
 * order they were added, so the number beside a playlist does not move when another
 * is added after it. They may be long and they are one line each: a playlist called
 * after a provider's full marketing name is a name to recognise at a glance, not to
 * read to the end, and the menu is wider than the rail card it hangs off precisely so
 * that glance usually succeeds.
 */
@Composable
private fun ColumnScope.PlaylistPicker(
    state: HomeState,
    frame: CastivioMetrics,
    onChoose: (String) -> Unit,
    onAdd: () -> Unit,
    onManage: () -> Unit,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(frame.radius / 2)
    // `rememberSaveable`: a rotation with the menu open should not silently close it,
    // and the boolean is the whole of this control's state — what is *in* the menu
    // comes from the store, so there is nothing else here that could go stale.
    var open by rememberSaveable { mutableStateOf(false) }

    // **Where the remote goes, and where it comes back to.**
    //
    // A menu that opens without taking focus is a menu a D-pad cannot reach: the
    // highlight stays on the rail behind it, Down moves to the card *below* the
    // button rather than into the list, and the only way to choose a playlist is a
    // touchscreen. So the first entry asks for focus when the menu opens, and the
    // button asks for it back when the menu closes — otherwise the highlight is left
    // pointing at a popup that no longer exists and the next press goes nowhere.
    //
    // Two requesters and not one, because they belong to two different windows: the
    // menu is a popup of its own, and a requester attached to a composable that has
    // left the composition throws rather than moving anything. The `runCatching` in
    // [LaunchedFocus] is what makes a request arriving a frame too early harmless.
    val anchorFocus = remember { FocusRequester() }
    val firstItemFocus = remember { FocusRequester() }

    // **Where the application's backdrop is, seen from inside the menu's own window.**
    //
    // The menu is a popup, so `castivioBackdrop()` applied to it would size all four
    // layers to a 200dp panel: the cool glow at a 104dp radius instead of 433, which
    // is a bright patch rather than ambient light, and the whole three-stop gradient
    // compressed into a box that then starts at the darkest stop. `ground` is what
    // lets it draw its *slice* of the one backdrop instead.
    //
    // ## Why there are two of them
    //
    // The menu only learns where it sits during its own layout pass. The rail card
    // does not: it is laid out with the board, long before anything can be pressed,
    // so its ground is known and correct while the menu is still closed. It seeds
    // the menu's, and the menu refines it once its own position arrives.
    //
    // That seeding is the answer to the flash. Without it the first drawn frame would
    // fall back to `Rect(0, 0, size)` — the compressed miniature — and correct itself
    // immediately after, which is a visible jump at exactly the moment a user is
    // looking at the menu appearing. With it the first frame is already within a few
    // dp of the final one, because the menu opens *at* the anchor.
    val view = LocalView.current
    // The backdrop's rectangle on screen, and the anchor's own view of it. Both are
    // known while the menu is still closed, because the rail card is laid out with
    // the board — which is what lets the menu's first drawn frame already be right.
    var window by remember { mutableStateOf<Rect?>(null) }
    var anchorGround by remember { mutableStateOf<Rect?>(null) }
    val menuGround = remember { MutableGround() }

    // **Focus is returned deliberately, not as a side effect of the press.** Choosing
    // a playlist, adding one, opening the subscriptions screen and dismissing with
    // Back all end in the same place, so the rule is written once instead of at each
    // of the four exits.
    //
    // A flag rather than an effect keyed on `open`, because `open` starts false: an
    // effect on it would grab the remote on the first composition of Home and pull it
    // onto this button before the user had touched anything.
    var returning by remember { mutableStateOf(false) }
    LaunchedFocus(anchorFocus, enabled = returning)
    LaunchedEffect(returning) { if (returning) returning = false }

    Box(Modifier.fillMaxWidth().weight(1f)) {
        UtilityCard(
            icon = Icons.Rounded.PlaylistAdd,
            label = stringResource(R.string.home_change),
            frame = frame,
            onClick = { open = true },
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(anchorFocus)
                .onGloballyPositioned { coords ->
                    val onScreen = coords.backdropWindowOnScreen(view)
                    window = onScreen
                    anchorGround = onScreen?.let { coords.groundFor(it, view) }
                },
        )

        // **Material's own surface is switched off, not painted over.**
        //
        // `DropdownMenu` draws a `Surface` filled from `colorScheme.surfaceContainer`
        // with a tonal overlay on top, and on this backdrop that is the flat grey
        // rectangle the owner photographed — an application-shaped hole in a screen
        // made of glass. This build's Material 3 has no `containerColor` parameter on
        // the menu to set instead, so the three tokens it reaches for are made
        // transparent for the subtree and the panel paints its own fill — see the
        // modifier below for what, and why it is two brushes rather than one.
        //
        // Scoped to this composable, so nothing else on the screen sees the override.
        // It costs a `MaterialTheme` node while the menu is open and nothing at all
        // while it is closed, and it keeps everything `DropdownMenu` is worth having
        // for: it clamps itself inside the window, takes D-pad focus, dismisses on
        // back and on an outside press, and animates in.
        // **The locale has to be carried into the popup by hand.**
        //
        // `MainActivity` applies the chosen language by providing a `Context` wrapped
        // in it, and every `stringResource` on this screen resolves against that. A
        // `DropdownMenu` does not draw in this composition though — it draws in a
        // `Popup`, which is a window of its own, and Compose re-provides `LocalContext`
        // there from *that window's* context. The wrapper is gone by then, so the menu
        // resolved its strings against the device's language while the board behind it
        // was in the user's: Spanish everywhere and `Add playlist` in English, which is
        // exactly what the owner photographed.
        //
        // Nothing was missing from the translations — `values-es` has had `Añadir
        // lista` since the menu shipped. So these three are captured *here*, in the
        // composition where the activity's providers are still in scope, and handed
        // back inside the popup. The layout direction goes with them for the same
        // reason: a popup that reset the context would reset the direction too, and an
        // Arabic menu laid out left to right is the same defect wearing a different hat.
        val localised = LocalContext.current
        val configuration = LocalConfiguration.current
        val reading = LocalLayoutDirection.current

        val scheme = MaterialTheme.colorScheme
        MaterialTheme(
            colorScheme = scheme.copy(
                surface = Color.Transparent,
                surfaceContainer = Color.Transparent,
                surfaceTint = Color.Transparent,
            ),
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
        ) {
            DropdownMenu(
                expanded = open,
                // Back on a remote, and a press outside on a touchscreen, both arrive
                // here. The popup is focusable, so the system routes Back to it rather
                // than to Home — the menu closes and the board behind it stays exactly
                // where it was, which is the whole of requirement 2.
                onDismissRequest = {
                    open = false
                    returning = true
                },
                // Bounds only for the width. Material 3's own menu content already
                // sizes its column to `IntrinsicSize.Max`, so the menu is as wide as
                // its widest name by default and repeating that here would be two
                // rules deciding one width.
                modifier = Modifier
                    .widthIn(min = PICKER_MIN_WIDTH, max = PICKER_MAX_WIDTH)
                    .clip(shape)
                    // **The ground is the application's, not a second copy of it.**
                    //
                    // `glassFillBrush` is 8% white over 5%: it is *lift*, not a colour,
                    // and it works on every other card because it sits on the board's
                    // backdrop and borrows it. A popup has no backdrop to borrow — it
                    // is its own window — so the glass alone left the menu 92%
                    // transparent and the rail's buttons read straight through the
                    // playlist names.
                    //
                    // `castivioBackdrop()` is what an overlay is supposed to use, and
                    // its own documentation says so: the four layers behind whatever
                    // it is applied to, opaque from the first one. Painting the aurora
                    // gradient here by hand was a *second* implementation of the ground
                    // — it reproduced one of the four layers and missed the two glows
                    // and the mesh, so the panel came out darker and its gradient ran
                    // the other way from the board's. This is the one system, and the
                    // day those four layers change the menu changes with them.
                    .castivioBackdrop { menuGround.rect ?: anchorGround }
                    .background(colors.glassFillBrush)
                    .border(BorderStroke(1.dp, colors.edgeQuiet), shape),
            ) {
                // The popup's own view, which is what knows where the popup window
                // sits on the screen. `LocalView` outside the menu is Home's view and
                // would put the menu's ground in the wrong window.
                val menuView = LocalView.current

                // Handed back what the popup's own window took away. See above.
                CompositionLocalProvider(
                    LocalContext provides localised,
                    LocalConfiguration provides configuration,
                    LocalLayoutDirection provides reading,
                ) {
                    // **The remote cannot leave the menu while it is open.**
                    //
                    // `focusGroup` makes the column one stop rather than a handful of
                    // siblings the focus search can wander out of, and the popup is a
                    // window of its own so there is nothing outside it to wander *to*.
                    // Up and Down then move between entries and stop at the ends
                    // instead of silently handing the highlight back to the board.
                    Column(
                        Modifier
                            .focusGroup()
                            // **Written during layout, read during draw, same frame.**
                            //
                            // That is why `castivioBackdrop` takes a lambda and why
                            // this holder is a plain field rather than snapshot state:
                            // `onGloballyPositioned` fires at the end of the layout
                            // pass, before drawing, so a value written here is visible
                            // to *this* frame's draw. A `mutableStateOf` would schedule
                            // another frame instead, and the one frame in between —
                            // drawn against the compressed miniature — is the flash
                            // this whole arrangement exists to prevent.
                            //
                            // It measures the content column rather than the menu's
                            // own surface, which differs by the menu's internal
                            // padding: a few dp, against a glow 433dp across.
                            .onGloballyPositioned { coords ->
                                menuGround.rect = window?.let { coords.groundFor(it, menuView) }
                            },
                    ) {
                        // Asked for as the menu opens, once. Without it the popup takes
                        // window focus but nothing inside it is focused, so the first Down
                        // is spent arriving rather than moving.
                        LaunchedFocus(firstItemFocus, enabled = open)

                        for ((index, playlist) in state.playlists.withIndex()) {
                            val name = playlist.name.ifBlank {
                                stringResource(R.string.home_playlist_numbered, playlist.position)
                            }
                            val isActive = playlist.id == state.activePlaylistId
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        name,
                                        style = castivioChipStyle(frame.fsLabel),
                                        color = if (isActive) colors.onBackgroundStrong else colors.onBackgroundVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                // The tick leads, and the row keeps its indent when there is no
                                // tick to draw: a list whose names shift sideways as the active
                                // one moves is a list that looks like it reflowed rather than
                                // one that answered.
                                leadingIcon = {
                                    if (isActive) {
                                        Icon(
                                            Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = colors.hueGreen,
                                            modifier = Modifier.size(Sizing.iconSm),
                                        )
                                    } else {
                                        Spacer(Modifier.size(Sizing.iconSm))
                                    }
                                },
                                onClick = {
                                    open = false
                                    returning = true
                                    // Guarded, not because a second write would corrupt anything —
                                    // `setActive` is idempotent and transactional — but because
                                    // re-activating the current source makes the store re-emit and
                                    // every flow hanging off it recompute for no change.
                                    if (!isActive) onChoose(playlist.id)
                                },
                                // Only the first: it is where the remote lands when the menu
                                // opens, and every other entry is one Down away from it.
                                modifier = if (index == 0) {
                                    Modifier.focusRequester(firstItemFocus)
                                } else {
                                    Modifier
                                },
                            )
                        }

                        // Separated, because it is not one of the things above: the list answers
                        // "which one", and this answers "another one". Drawn only when there is a
                        // list to separate it from — on a device with a single playlist the menu
                        // is one entry and a rule above it would be a divider dividing nothing.
                        if (state.playlists.isNotEmpty()) {
                            HorizontalDivider(color = colors.edgeQuiet)
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.home_add_playlist),
                                    style = castivioChipStyle(frame.fsLabel),
                                    color = colors.onBackgroundVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Add,
                                    contentDescription = null,
                                    tint = colors.hueViolet,
                                    modifier = Modifier.size(Sizing.iconSm),
                                )
                            },
                            onClick = {
                                open = false
                                // **No `returning` here, and that is the fix.** This exit
                                // leaves Home: the activation flow opens over the board
                                // and owns the remote from that moment. Asking Home's
                                // rail button for focus at the same instant put the
                                // highlight on a control *underneath* a full-screen
                                // overlay, so the D-pad was driving the screen nobody
                                // could see and the flow looked like it had not opened.
                                //
                                // Only the two exits that stay on Home return focus:
                                // choosing a playlist, and dismissing the menu.
                                onAdd()
                            },
                            // The landing place when there are no playlists to land on.
                            // A menu whose only entries are "add" and "manage" still
                            // has to be reachable by remote, and the requester above
                            // is attached to a row that does not exist.
                            modifier = if (state.playlists.isEmpty()) {
                                Modifier.focusRequester(firstItemFocus)
                            } else {
                                Modifier
                            },
                        )

                        // **The menu switches; this opens the place that manages.**
                        //
                        // Both of the entries above are one-press actions on the playlists
                        // themselves. Renaming one, deleting one, or reading what a subscription
                        // actually is are not menu-sized jobs and they already have a screen —
                        // the saved-subscriptions screen this flow has always carried, reached
                        // until now only by going through the source chooser. So this is a door
                        // to that screen, not a second implementation of it: `onManage` opens the
                        // same `ActivationRoute`, at the step that screen already occupies.
                        //
                        // No divider above it, because it belongs with "add" rather than with the
                        // list: the two together are "do something about my subscriptions", and
                        // the one rule in this menu separates that group from the names.
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.home_my_subscriptions),
                                    style = castivioChipStyle(frame.fsLabel),
                                    color = colors.onBackgroundVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Settings,
                                    contentDescription = null,
                                    tint = colors.hueViolet,
                                    modifier = Modifier.size(Sizing.iconSm),
                                )
                            },
                            onClick = {
                                open = false
                                // Leaves Home, so no focus return. See the entry above.
                                onManage()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The refresh card while its request is in flight.
 *
 * A separate composable rather than a flag on [Utility], because the two differ in
 * more than a glyph: this one is not pressable and must not draw as though it were.
 * It keeps the card, the padding, the label and the weight, so nothing in the column
 * moves when it appears — which is the whole reason the spinner is the size of the
 * icon it replaces rather than something laid out beside it.
 */
@Composable
private fun ColumnScope.UtilityBusy(label: String, frame: CastivioMetrics) {
    val colors = CastivioTheme.colors
    GlassCard(
        modifier = Modifier.fillMaxWidth().weight(1f),
        shape = RoundedCornerShape(frame.radius / 2),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = frame.chipPad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(frame.chipPad / 2),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(Sizing.iconSm),
                color = colors.hueViolet,
                strokeWidth = BUSY_STROKE,
            )
            Text(
                label,
                style = castivioChipStyle(frame.fsChip),
                color = colors.onBackgroundMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A ground rectangle written during layout and read during drawing.
 *
 * Deliberately *not* snapshot state. `onGloballyPositioned` runs at the end of the
 * layout pass and drawing runs after it in the same frame, so a plain field written
 * there is visible to that frame's draw. A `mutableStateOf` would invalidate the
 * composition instead and the corrected ground would land one frame later — and that
 * one frame, drawn against a miniature of the backdrop, is exactly the flash a menu
 * must not have at the moment it appears.
 *
 * The cost of the plain field is that a *change* of position does not invalidate the
 * drawing by itself. It does not need to: a popup is laid out when it opens and does
 * not move while it is open, and the layout that would move it redraws it anyway.
 */
private class MutableGround {
    var rect: Rect? = null
}

/**
 * The rectangle the application's backdrop occupies, **in screen coordinates**.
 *
 * Read from the board, whose composition root *is* the activity's window — the same
 * box `castivioBackdrop()` fills for the shell. The screen is the common frame
 * between two windows, which is the whole reason this is expressed in it: a popup is
 * a second window on the same screen, and nothing inside it can describe the first
 * one in its own terms.
 *
 * Null until the node is attached and measured, which the caller reads as "not yet".
 */
private fun LayoutCoordinates.backdropWindowOnScreen(view: View): Rect? {
    if (!isAttached) return null
    val window = rootCoordinates()
    val width = window.size.width
    val height = window.size.height
    if (width == 0 || height == 0) return null
    val origin = IntArray(2).also { view.getLocationOnScreen(it) }
    return Rect(
        left = origin[0].toFloat(),
        top = origin[1].toFloat(),
        right = (origin[0] + width).toFloat(),
        bottom = (origin[1] + height).toFloat(),
    )
}

/**
 * The outermost node of this composition, reached by walking parents.
 *
 * `parentLayoutCoordinates` is a member of `LayoutCoordinates`, which is the whole
 * reason the walk is written out rather than delegated: the library's own helper for
 * this is an extension function, and an extension is only as available as its import.
 * A member cannot be missed the same way.
 */
private fun LayoutCoordinates.rootCoordinates(): LayoutCoordinates {
    var node: LayoutCoordinates = this
    while (true) {
        val parent = node.parentLayoutCoordinates ?: return node
        if (!parent.isAttached) return node
        node = parent
    }
}

/**
 * That same rectangle, moved into this node's own coordinates.
 *
 * This node's origin on screen is where *its* window sits plus where it sits inside
 * that window, and the backdrop's rectangle translated by the negative of that is
 * where the backdrop begins as far as this node is concerned. Handed to
 * `castivioBackdrop`, it makes the node draw its slice of the one ground.
 *
 * `getLocationOnScreen` rather than a Compose coordinate call, because the Compose
 * ones stop at the window: `positionInWindow` inside a popup is a position inside
 * *the popup*, which is the confusion this resolves rather than inherits.
 */
private fun LayoutCoordinates.groundFor(windowOnScreen: Rect, view: View): Rect? {
    if (!isAttached) return null
    val origin = IntArray(2).also { view.getLocationOnScreen(it) }
    val here = positionInRoot()
    return windowOnScreen.translate(-(origin[0] + here.x), -(origin[1] + here.y))
}

/**
 * Put the remote somewhere useful, once, without fighting the user for it.
 *
 * Requested when [enabled] turns true and not on every recomposition, which would drag
 * the highlight back each time anything else on the screen changed — exactly when the
 * user is reading somewhere else. `runCatching` because a `FocusRequester` whose
 * composable has not been attached yet, or has just left, throws rather than doing
 * nothing: the popup and the board are two windows and their frames do not line up.
 *
 * The same helper the licence screen uses, deliberately written again rather than
 * shared: it is four lines, and moving it to `:core:design` would make a focus policy
 * out of what is currently a local decision each screen is free to make differently.
 */
@Composable
private fun LaunchedFocus(focus: FocusRequester, enabled: Boolean) {
    LaunchedEffect(enabled) {
        if (enabled) runCatching { focus.requestFocus() }
    }
}

@Composable
private fun ColumnScope.Utility(
    icon: ImageVector,
    label: String,
    frame: CastivioMetrics,
    onClick: () -> Unit,
) {
    UtilityCard(icon, label, frame, onClick, Modifier.fillMaxWidth().weight(1f))
}

/**
 * One rail card, without the column's weight.
 *
 * Split from [Utility] only so [PlaylistPicker] can draw the identical card inside
 * the `Box` that anchors its menu. The weight stays on the caller, which is what
 * keeps the five cards the five heights they were.
 */
@Composable
private fun UtilityCard(
    icon: ImageVector,
    label: String,
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
    rhythm: Rhythm,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(rhythm.foot),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(frame.chipPad),
        ) {
            Fact(
                // **The padlock is open when the licence permits use.**
                //
                // It was closed in both states, with only the hue and the word
                // separating them, and a closed padlock is the one glyph in this
                // vocabulary that means "you may not". Drawn beside "Activated" it
                // contradicted the sentence it was illustrating — and it is the part
                // of the card a viewer reads first, because a shape at a glance beats
                // a word every time. Open says the device is free to play; closed says
                // the licence ran out and it is not, which is the only thing this fact
                // has ever been about.
                //
                // Both come from `licenceHolds`, which is `entitlement.allowsUse` —
                // the same question the header's licence card and the section board
                // already ask. Nothing new decides it here; the glyph simply stopped
                // disagreeing with the other two things on this line.
                if (state.licenceHolds) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
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
 * An expiry date, as figures, the same shape in every language.
 *
 * ## Why this is not the reader's own date format
 *
 * It was `DateFormat.MEDIUM`, which is the right default nearly everywhere and the
 * wrong one here. Medium is a *worded* format in most locales — "8 abr 2027" in
 * Spanish, "8 avr. 2027" in French, "8. Apr. 2027" in German — and the term cards it
 * feeds are two of the narrowest surfaces in the application. On a device the Spanish
 * card read "Expires on 8 abr 2…": the month name spent the width the year needed,
 * and the year is the part a reader is actually checking.
 *
 * `dd/MM/yyyy` is eight figures and two slashes in every language, so the card can be
 * measured once and trusted. The zero fill is part of that: `8/4/2027` is eight
 * characters on one device and ten on the next, which is a card that fits until it
 * does not.
 *
 * ## Why [Locale.ROOT] and not the reader's locale
 *
 * Because "the same in every language" has to include the *digits* and the *calendar*.
 * A locale-aware formatter may render Arabic-Indic figures, and a locale carrying a
 * calendar extension — `ar-SA-u-ca-islamic` — would print a Hijri date, so the same
 * moment would be two different dates on two phones. `Locale.ROOT` pins Gregorian and
 * Latin figures, which is what this row has always drawn and what a user reads out to
 * a provider.
 *
 * The sentence around it still runs in the reader's direction; only this token is
 * fixed. [ltrToken] is what keeps it that way — an unisolated `08/04/2027` inside an
 * Arabic paragraph is reordered into `2708/04/20` by the bidirectional algorithm.
 *
 * `remember` keyed on the value, because building a formatter and running it is real
 * work and composition runs whenever anything on this screen moves.
 */
@Composable
internal fun rememberDate(atMs: Long): String = remember(atMs) {
    ltrToken(SimpleDateFormat(EXPIRY_PATTERN, Locale.ROOT).format(Date(atMs)))
}

/** Frozen with [rememberDate]: eight figures and two slashes, zero-filled. */
private const val EXPIRY_PATTERN = "dd/MM/yyyy"

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
/**
 * How wide the playlist menu is allowed to be.
 *
 * Fixed dp rather than a share of the frame, and deliberately: the menu is sized by
 * its *contents*, which are names the user typed and this screen cannot measure in
 * advance. Material 3's menu already draws as wide as its widest item, and these two
 * bound that answer — a floor so a menu of one-word names is still a comfortable
 * target on a remote, and a ceiling so a provider's forty-character marketing name
 * cannot grow the menu across the board behind it.
 *
 * The floor is above the rail card it hangs off (108dp on the reference handset) on
 * purpose. A menu the width of its anchor would truncate names the anchor never had
 * to hold, and a popup is the one thing on this screen that may be wider than the
 * thing that opened it without costing the layout anything.
 */
private val PICKER_MIN_WIDTH = 180.dp
private val PICKER_MAX_WIDTH = 320.dp

private const val RAIL_SHARE = 0.166f
private const val STACK_SHARE = 0.327f
private const val HERO_SHARE = 0.472f

/**
 * The watermark: how much of the hero panel's height its glyph takes, and how far it
 * is faded.
 *
 * Just under two thirds of the height and centred, so the panel has a subject rather
 * than a corner with something in it, and the glyph still ends above the words at the
 * floor. Eighteen per cent because it is a *gradient* now, not a flat tint: a ramp at
 * a tenth reads as grey on this ground and loses the only reason to have coloured it.
 *
 * It stays a watermark at this strength, which the count proves — set in the title
 * step and in the section's full hue, the number sits a long way clear of a ramp at
 * 18%. Any stronger and the panel has two subjects.
 */
private const val HERO_GLYPH_SHARE = 0.64f
private const val HERO_GLYPH_ALPHA = 0.18f

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

/** The failure notice wraps rather than truncating; two lines is every translation. */
private const val NOTICE_LINES = 2

/** The busy ring inside a rail card, in proportion to the glyph it replaces. */
private val BUSY_STROKE = 2.dp

/** The wordmark's share of the frame's title step, as the licence screen sets it. */
private const val WORD_RATIO = 0.8f
