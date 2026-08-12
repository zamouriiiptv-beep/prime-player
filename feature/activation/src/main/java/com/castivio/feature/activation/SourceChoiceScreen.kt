package com.castivio.feature.activation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.SwitchAccount
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.castivio.core.design.components.CastivioMark
import com.castivio.core.design.components.GlassCard
import com.castivio.core.design.components.InteractiveGlassCard
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing

/**
 * "What did your provider give you?"
 *
 * The question is asked in the user's terms rather than ours. Nobody arrives here
 * knowing they want "Xtream Codes"; they arrive holding an e-mail, and the two IPTV
 * options are described by what that e-mail looks like — a server and a password, or
 * one long link. The protocol names stay as titles because that is what the e-mail
 * calls them.
 *
 * Four destinations, in a two-by-two grid: the two ways to add a subscription, a video
 * file the device already holds, and the subscriptions already saved on it.
 *
 * ## One layout, four identical cards, on every device
 *
 * Stacked in a single column the content came to 424dp against the 393dp a landscape
 * handset has, and the step was inside `verticalScroll`. So it scrolled: the title
 * clipped at the top or Back clipped at the bottom, depending on where the user had
 * left the page, and a remote pressing *down* moved the page rather than the focus.
 *
 * There is no longer a condition anywhere in this file. Two earlier versions asked one
 * — `isTv` first, then `DeviceClass.Expanded`, which is `screenWidthDp >= 840` — and
 * both shipped a stacked, scrolling screen to a landscape handset. `screenWidthDp`
 * describes the *window*; this screen is drawn inside `safeDrawing`, so a display
 * cutout of 41dp is spent before the layout sees a pixel and 873 arrives as 827. Which
 * side of a bucket boundary that lands on varies by handset for reasons that have
 * nothing to do with whether four cards fit.
 *
 * They fit at any width, because nothing here asks for a size: the cards divide what
 * they are given with `weight(1f)`, so the grid cannot overflow horizontally. The
 * activity is `screenOrientation="sensorLandscape"`, so the narrow portrait frame the
 * old column existed for never reaches a user at all.
 *
 * ## Why the four are the same height, and how
 *
 * The container is sized from the frame and the grid takes what it leaves, so the two
 * rows divide a *known* height: `weight(1f)` each makes them exactly equal, and
 * `fillMaxHeight` passes that to all four cards. Equality is structural — there is no
 * arrangement of content that can make one card taller than another, in any language.
 *
 * It was not always this way. While the cards were sized by their content the rows had
 * to be equalised with `height(IntrinsicSize.Min)`, which measures the subtree twice
 * and throws at runtime if anything inside it does not support intrinsics. Sizing the
 * container from the frame retired that pass along with its risk.
 *
 * What replaces the risk is a floor rather than a ceiling: the derived card height has
 * to stay above what a title over two lines of description needs, or the text clips
 * instead of the layout overflowing. `SourceChoiceBudgetTest` computes the derived
 * height on every frame and asserts exactly that.
 *
 * ## Direction
 *
 * `Row` and `Column` resolve their own start and end, so Xtream leads on the right in
 * Arabic and on the left in English with no index, offset or coordinate written down
 * anywhere. The icon is the first child of the title line, which puts it on the
 * starting side in both directions for the same reason.
 *
 * @param onBack what Back does. It lives inside this screen rather than beside it
 *   because it is part of the composition being fitted: the column that measures the
 *   header and the grid has to measure the footer too, or it is fitting two thirds of
 *   a screen.
 */
@Composable
internal fun SourceChoiceScreen(
    onXtream: () -> Unit,
    onPlaylist: () -> Unit,
    onLocalVideo: () -> Unit,
    onSavedSources: () -> Unit,
    onTerms: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            // The screen owns the viewport, so it owns its edge inset too --
            // `ActivationSurface` applies no padding in its fixed frame, for the same
            // reason the address screen pads itself. `screenPadding` rather than a
            // hard-coded figure: it is `Spacing.tvOverscan` on a television, which has
            // overscan to clear, and `Spacing.screen` everywhere else, which does not.
            .padding(CastivioTheme.device.screenPadding),
        verticalArrangement = Arrangement.Top,
    ) {
        Heading()
        Spacer(Modifier.height(TitleGap))

        // The container takes the height, and the cards take it from the container.
        //
        // This is the direction the whole screen was solved in until now, reversed.
        // The cards used to be sized by their content and the container by the cards,
        // which left the surface floating in the middle of the frame with the sentence
        // adrift below it -- and it is why a 360dp handset could only afford a 64dp
        // card. `weight(1f)` makes the container the size of what is left instead, so
        // it reaches for the edges the way the approved reference does, and the space
        // it gains lands inside the cards: 86dp on the shortest frame, 102 on the
        // reference handset, 120 on a television.
        //
        // Two things follow that are worth naming. Overflow becomes structural rather
        // than arithmetic -- a weighted child cannot push its siblings out, so the
        // title, Back and the sentence are placed first and the grid absorbs whatever
        // is left. And the equal-height problem stops needing an intrinsic pass: two
        // rows of `weight(1f)` inside a bounded column are exactly equal, measured
        // once. What has to be watched instead is the floor, and that is what
        // `SourceChoiceBudgetTest` computes: the derived card must stay taller than a
        // title over two lines of description, or the text inside it clips.
        SourceContainer(Modifier.weight(1f)) {
            SourceGrid(
                modifier = Modifier.weight(1f),
                onXtream = onXtream,
                onPlaylist = onPlaylist,
                onLocalVideo = onLocalVideo,
                onSavedSources = onSavedSources,
            )
            Spacer(Modifier.height(ContainerGap))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack, Modifier.testTag(ActivationTags.SOURCE_BACK))
                Rule(Modifier.weight(1f).padding(horizontal = RuleGap))
            }
        }

        // At the bottom of the screen, a hair under the container, as the reference
        // has it -- not floating in the middle of a leftover band.
        Spacer(Modifier.height(TermsGap))
        TermsLine(onTerms)
    }
}

/**
 * The container: one surface holding the four choices and the way back.
 *
 * `GlassCard` rather than a `Box` dressed up to look like one — it is the design
 * system's own glass surface, it already carries `glassFillBrush`, `glassBorderBrush`
 * and the elevation shadow, and building a second one here would be a shared component
 * declared twice, which the invariant script rejects for good reason.
 *
 * `Radius.xl` against the cards' `Radius.lg`, so the corners nest rather than trace
 * each other. The fill is the brush the system uses for every glass surface: 7.8%
 * fading to 3.9% down the card, under cards held flat at 7.8%. The cards keep their own
 * border, which is what stops the top pair flattening into the container where the two
 * fills momentarily agree.
 *
 * The inset is [ContainerPadding], and it is 8dp on a handset for a reason that is
 * arithmetic rather than taste — see that token.
 */
@Composable
private fun SourceContainer(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ActivationTags.SOURCE_CONTAINER),
        shape = RoundedCornerShape(Radius.xl),
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .padding(ContainerPadding),
            content = content,
        )
    }
}

/**
 * The title, and nothing under it.
 *
 * There was a subtitle — "both end up in the same place" — written when this screen
 * offered two options. Four named cards each carrying a description made it false and
 * then redundant, so it is deleted rather than reworded: a sentence explaining that
 * the options are alternatives tells the reader what the grid already shows.
 *
 * Deleting it also returned 32dp, which is the difference between a screen with 2.5dp
 * of slack and one that can absorb a description wrapping in a longer language.
 */
@Composable
private fun Heading() {
    // Physical, not logical: the mark belongs in the left corner in Arabic too, so
    // what changes with the direction is which end of the row it is composed at. A
    // `Row` runs start to end, so in RTL the *last* child is the leftmost one.
    //
    // The direction-absolute alignments would say this in one word, and invariant 9
    // bans them outright rather than case by case -- a rule that is argued about is a
    // rule that loses. Composition order is not an exemption from it: it moves this
    // one element and cannot quietly pin anything else to a physical side.
    val leftmostIsLast = LocalLayoutDirection.current == LayoutDirection.Rtl

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MarkGap),
    ) {
        if (!leftmostIsLast) Wordmark(Modifier.alignByBaseline())
        Text(
            text = stringResource(R.string.source_choice_title),
            style = CastivioType.headlineMedium,
            color = CastivioTheme.colors.onBackground,
            modifier = Modifier
                .weight(1f)
                .alignByBaseline()
                .testTag(ActivationTags.SOURCE_HEADING)
                .semantics { heading() },
        )
        if (leftmostIsLast) Wordmark(Modifier.alignByBaseline())
    }
}

/**
 * Castivio, in the corner, in the startup's own ink.
 *
 * Everything here except the size comes from [CastivioMark], which is where the
 * startup keeps it: the word, the violet-into-azure fill, and the tracking as a
 * fraction of the size rather than a number of pixels. The size is 11sp against a
 * 22sp title, which is the whole of what makes this branding rather than a second
 * heading — it is read after the question, not before it.
 *
 * `lineHeight` equal to the size, so the mark contributes no leading of its own.
 * This is load-bearing rather than tidy: the row is baseline-aligned, a
 * baseline-aligned row is as tall as its deepest descender, and a default line box
 * around an 11sp glyph hangs further below the baseline than the title's does. The
 * mockup grew the header by 3dp exactly that way, which came out of the container
 * and took 1.5dp off every card. At `lineHeight = fontSize` the title's own 32sp
 * line box decides the height, as it did before this mark existed.
 *
 * No `contentDescription`, and `heading()` stays on the question. A wordmark read
 * aloud before every screen title is noise; the application's name is in the
 * launcher, the task switcher and the accessibility window title already.
 */
@Composable
private fun Wordmark(modifier: Modifier = Modifier) {
    val size = MarkSize
    Text(
        text = CastivioMark.TEXT,
        style = CastivioType.labelSmall.copy(
            fontSize = size,
            lineHeight = size,
            fontWeight = FontWeight.Bold,
            letterSpacing = CastivioMark.TRACKING_RATIO.em,
            // Left to right in pixels whatever the layout direction, which is what
            // `Brush.horizontalGradient` does and what the startup draws: the violet
            // end is on the left on every screen Castivio signs.
            brush = Brush.horizontalGradient(CastivioMark.colours),
        ),
        maxLines = 1,
        modifier = modifier,
    )
}

/**
 * Two by two, equal in both directions.
 *
 * The reading order is the grid order — Xtream, M3U, then the device's own video and
 * the subscriptions already saved on it. Nothing is emphasised by being larger or
 * filled differently; an earlier draft gave the lower pair a lighter fill and no
 * description and it read as two designs rather than one set. Xtream leads because it
 * is first and holds the focus on entry, which is the only ranking this screen makes.
 */
@Composable
private fun SourceGrid(
    modifier: Modifier,
    onXtream: () -> Unit,
    onPlaylist: () -> Unit,
    onLocalVideo: () -> Unit,
    onSavedSources: () -> Unit,
) {
    Column(
        // Bounded by the container, so the two rows divide a known height and come out
        // equal without an intrinsic pass. That pass used to be here and was the only
        // way to equalise rows whose height came from their content; it measured the
        // subtree twice and threw at runtime if anything in it did not support
        // intrinsics. Sizing the container from the frame retired it.
        modifier,
        verticalArrangement = Arrangement.spacedBy(GridGap),
    ) {
        Row(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(GridGap),
        ) {
            SourceCard(
                icon = Icons.Rounded.Dns,
                title = stringResource(R.string.source_xtream_title),
                detail = stringResource(R.string.source_xtream_detail),
                onClick = onXtream,
                tag = ActivationTags.SOURCE_XTREAM,
            )
            SourceCard(
                icon = Icons.Rounded.Link,
                title = stringResource(R.string.source_m3u_title),
                detail = stringResource(R.string.source_m3u_detail),
                onClick = onPlaylist,
                tag = ActivationTags.SOURCE_M3U,
            )
        }
        Row(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(GridGap),
        ) {
            SourceCard(
                icon = Icons.Rounded.PlayCircle,
                title = stringResource(R.string.source_local_title),
                detail = stringResource(R.string.source_local_detail),
                onClick = onLocalVideo,
                tag = ActivationTags.SOURCE_LOCAL,
            )
            SourceCard(
                icon = Icons.Rounded.SwitchAccount,
                title = stringResource(R.string.source_users_title),
                detail = stringResource(R.string.source_users_detail),
                onClick = onSavedSources,
                tag = ActivationTags.SOURCE_USERS,
            )
        }
    }
}

/**
 * The hairline beside Back. Decoration, and nothing else.
 *
 * Back is a ghost button on a full-width row, so it leaves two thirds of the
 * container's last band empty. That emptiness reads as a missing element rather than
 * as margin, because it is enclosed on all four sides — the grid above it, the
 * container's own edge below and beside it. A hairline is the quietest thing that can
 * occupy it without pretending to be a control.
 *
 * No text, no icon, no semantics and no click, so a D-pad and a screen reader both
 * pass straight over it. `weight(1f)` gives it the leftover and nothing else: Back
 * keeps its own width, its own position and its own height, and the row is as tall as
 * the button was on its own, so nothing above or below it moves.
 *
 * It fades away from Back rather than running at one strength into the corner.
 * `glassBorder` is the same 23.9% every edge on this screen is drawn at — full weight
 * where it leaves the button, nothing where it would otherwise have met the container.
 * A hairline that stops dead needs a reason to stop at that particular pixel, and
 * there is none; so it does not stop, it ends.
 *
 * The colour order is read off the direction because `horizontalGradient` is physical
 * and Back is not: the solid end has to be whichever end the button is at, and the
 * button is at the start.
 */
@Composable
private fun Rule(modifier: Modifier) {
    val edge = CastivioTheme.colors.glassBorder
    val fromBack = if (LocalLayoutDirection.current == LayoutDirection.Ltr) {
        listOf(edge, edge.copy(alpha = 0f))
    } else {
        listOf(edge.copy(alpha = 0f), edge)
    }
    Box(
        modifier
            .height(RuleThickness)
            .background(Brush.horizontalGradient(fromBack)),
    )
}

/**
 * The terms sentence, below the container and centred on the frame.
 *
 * `fillMaxWidth()` with `TextAlign.Center`, which is the plainest way to say "the same
 * distance from each edge" and is direction-agnostic by construction. It was not
 * available while Back shared this row -- a full-width text carries its click target
 * across the row and would have taken Back's presses. Back is inside the container
 * now, so the row is the sentence's alone and the simple thing is also the correct one.
 *
 * One control, not two. "Terms of Service" is underlined and the rest is not, because
 * the underline is what tells a reader the line can be pressed; but the whole line is
 * the target, since a partially-pressable sentence is unusable with a D-pad — there is
 * no cursor to put on half a paragraph.
 *
 * `ButtonWeight` has three values and none of them is a text link, and adding a fourth
 * is a change to `:core:design` that every screen would inherit for the sake of one
 * footer. So this is local, and it is the only element on this screen that is.
 */
@Composable
private fun TermsLine(onClick: () -> Unit) {
    val lead = stringResource(R.string.source_terms)
    val note = stringResource(R.string.source_terms_note)

    // Underlined on the two words that name the thing, plain for the sentence that
    // follows. One control, not two: the whole line is the target, because a
    // partially-pressable line is unusable with a D-pad -- there is no cursor to put
    // on the underlined half of a paragraph.
    val sentence = remember(lead, note) {
        buildAnnotatedString {
            withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(lead) }
            append(note)
        }
    }

    Text(
        text = sentence,
        style = CastivioType.bodySmall,
        color = CastivioTheme.colors.onBackgroundVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ActivationTags.SOURCE_TERMS)
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(role = Role.Button, onClick = onClick),
    )
}

/**
 * The corner mark's size: 11sp on a handset, 13sp on a television.
 *
 * Half the title, near enough, and that ratio is the brief. A mark that competes with
 * the question is a mark that has to be read first, and nobody arriving on this screen
 * needs to be told which application they opened. The television gets two points more
 * for the same reason every other figure on this screen steps up there — it is read
 * from three metres, not thirty centimetres.
 */
private val MarkSize: TextUnit
    @Composable @ReadOnlyComposable get() =
        if (CastivioTheme.device.isTv) 13.sp else 11.sp

/** Mark to title. The same step the rest of the screen separates groups by. */
private val MarkGap: Dp
    @Composable @ReadOnlyComposable get() =
        if (CastivioTheme.device.isTv) Spacing.xl else Spacing.lg

/**
 * The hairline's clearance, the same figure at both ends.
 *
 * It does not run into Back and it does not run into the container's inner edge; the
 * gap either side is one number so the line reads as centred in what is left rather
 * than as pushed against something.
 */
private val RuleGap: Dp
    @Composable @ReadOnlyComposable get() =
        if (CastivioTheme.device.isTv) Spacing.xl else Spacing.lg

/** One device-independent pixel. A hairline is the whole point. */
private val RuleThickness = 1.dp

/**
 * The gap between the four cards, across and down alike.
 *
 * One figure in both directions, because a grid whose columns and rows are spaced
 * differently reads as two rows that happen to be near each other. A television has
 * the room for the next step up and a handset does not: `Spacing.lg` there leaves
 * 10.5dp of margin, `Spacing.xl` would leave -6.
 */
private val GridGap: Dp
    @Composable @ReadOnlyComposable get() =
        if (CastivioTheme.device.isTv) Spacing.lg else Spacing.sm

/**
 * Title to container.
 *
 * The one fixed gap left in the column. Everything below the container is a computed
 * region rather than a spacer, so this is the only figure that has to be chosen.
 */
private val TitleGap: Dp
    @Composable @ReadOnlyComposable get() =
        if (CastivioTheme.device.isTv) Spacing.lg else Spacing.sm

/**
 * Container to the terms sentence.
 *
 * Small, and deliberately so: the reference puts the sentence on the bottom edge of
 * the screen, a hair under the surface, rather than floating in a band of its own. The
 * container is `weight(1f)`, so this gap is subtracted from what the container gets --
 * every dp here is a dp off the cards.
 */
private val TermsGap: Dp
    @Composable @ReadOnlyComposable get() =
        if (CastivioTheme.device.isTv) Spacing.lg else Spacing.xs

/** Grid to Back, inside the container. */
private val ContainerGap: Dp
    @Composable @ReadOnlyComposable get() =
        if (CastivioTheme.device.isTv) Spacing.lg else Spacing.xs

/**
 * The container's own inset.
 *
 * `Spacing.sm` on a handset and `Spacing.lg` on a television — enough that no card
 * touches the edge it sits in, and not a dp more, because on the short frame there is
 * not a dp more to give. See [CardPadding] for the arithmetic that sets all of these.
 */
private val ContainerPadding: Dp
    @Composable @ReadOnlyComposable get() =
        if (CastivioTheme.device.isTv) Spacing.lg else Spacing.sm

/**
 * The card's own inset, and the figure the whole screen is solved around.
 *
 * ## Why 8dp on a handset and not 16
 *
 * The requirement that sets it is not appearance, it is that a description which wraps
 * to two lines must still fit — on every frame, including the shortest one the project
 * ships to, an 800x360 landscape window that is an ordinary 360dp-wide phone turned
 * sideways.
 *
 * That frame gives the layout 312dp once `screenPadding` is taken. Three things in it
 * cannot move: a 32dp title, a 48dp Back at the touch-target floor, and a 20dp
 * sentence. 100dp gone, 212 left for two rows of cards, the container's inset twice
 * over, and four gaps. Two cards with a wrapped description are `4 * padY + 136`, so
 * every dp of card padding costs four.
 *
 * The token space was searched rather than guessed: of the combinations that fit both
 * one line and two on both 360 and 393, **none has a card taller than 64dp**. 8dp of
 * card padding is therefore not a preference, it is the maximum, and everything else
 * on the screen was set from what it left — which is the order the priorities asked
 * for: no overflow first, room for a wrap second, card size third, spacing last.
 *
 * A television has 444dp and none of this pressure, so it keeps `Spacing.xl` and a
 * 96dp card.
 */
private val CardPadding: PaddingValues
    @Composable @ReadOnlyComposable get() = if (CastivioTheme.device.isTv) {
        PaddingValues(horizontal = Spacing.xxl, vertical = Spacing.xl)
    } else {
        PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm)
    }

/**
 * One card. There is no second kind.
 *
 * @param tag the handle the layout gates measure this card by. Passed rather than
 *   applied by the caller so that every card is built the same way and a future one
 *   cannot arrive with a different modifier chain — the four being structurally
 *   identical is the property `SourceChoiceLayoutTest` asserts.
 */
@Composable
private fun RowScope.SourceCard(
    icon: ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
    tag: String,
) {
    val colors = CastivioTheme.colors

    InteractiveGlassCard(
        onClick = onClick,
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .testTag(tag)
            // One node, one label, one target: the card is the control, and a screen
            // reader announces the title and the description as a single item. Without
            // this the icon, the title and the detail are three focusable-looking
            // fragments of one choice.
            .semantics(mergeDescendants = true) { contentDescription = "$title. $detail" },
        fill = SolidColor(colors.glassFillStrong),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    // Null rather than a description: the card above already carries
                    // the whole label, and an icon that names itself makes a screen
                    // reader say the thing twice.
                    contentDescription = null,
                    tint = colors.onBackground,
                    modifier = Modifier.size(Sizing.iconMd),
                )
                // `titleLarge` over `bodySmall`. One point of size and no difference in
                // weight is not a hierarchy -- only the colour was saying which line
                // was the name of the thing.
                Text(text = title, style = CastivioType.titleLarge, color = colors.onBackground)
            }
            Text(
                text = detail,
                style = CastivioType.bodySmall,
                color = colors.onBackgroundVariant,
            )
        }
    }
}
