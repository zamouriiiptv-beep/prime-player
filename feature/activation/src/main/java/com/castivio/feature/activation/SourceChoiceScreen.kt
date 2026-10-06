package com.castivio.feature.activation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.SettingsInputAntenna
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.castivio.core.design.components.CastivioFittedText
import com.castivio.core.design.components.CastivioChipsGap
import com.castivio.core.design.components.InteractiveGlassCard
import com.castivio.core.design.components.castivioChipStyle
import com.castivio.core.design.components.castivioBodyStyle
import com.castivio.core.design.components.castivioDescriptionColor
import com.castivio.core.design.theme.CastivioMetrics
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.boundedFraction
import com.castivio.core.design.theme.castivioMetrics
import com.castivio.core.design.theme.castivioStage

/**
 * What the three chooser screens are drawn from: the shared metrics, plus the handful
 * of sizes a card and its footnote own.
 *
 * ## It was a table of four devices, and now it is arithmetic
 *
 * `design/mockups/source-choice.html` is still the record and the drawing is unchanged.
 * What changed is how the numbers get to a device. There used to be four rows here —
 * television, tablet, phone, short phone — each stating a value for every gap, and the
 * three screens that read them looked right on the four surfaces somebody had drawn and
 * were guesses everywhere else. A tablet, in particular, got the phone's row.
 *
 * Every size below is now a share of the axis it spends, clamped at both ends, through
 * the one expression the whole product uses:
 * [com.castivio.core.design.theme.boundedFraction]. The shares are read off the 1280×720
 * reference, which is three quarters of the 960×540 television — so the television
 * still lands on the numbers its drawing was approved at, to the dp, and every surface
 * between and beyond is interpolated rather than rounded to the nearest drawing.
 *
 * What that produces on the four surfaces that used to be rows:
 *
 * | surface | card | strip | disc |
 * |---|---|---|---|
 * | 960×540 TV | 173 | 40 | 72 |
 * | 1280×800 tablet | 267 | 44 | 76 |
 * | 873×393 | 125 | 30 | 52 |
 * | 800×360 | 112 | 30 | 50 |
 *
 * The card is still derived rather than declared — two weighted rows of what the header
 * and the strip leave — so those are outcomes, and `SourceChoiceBudgetTest` is what
 * asserts they stay positive on every surface rather than on four of them.
 *
 * ## The type is not this screen's
 *
 * Every size here is one of [CastivioMetrics]' four steps: `fsTitle` for the question,
 * `fsLabel` for a card's name, `fsBody` for its description, `fsChip` for the badge,
 * Back and the footnote. A screen that invents its own scale beside the one before it
 * is two products, and compressing type to make content fit is how a layout hides that
 * it is too small.
 */
internal data class SourceMetrics(
    /**
     * The stage, the header and the shared type steps — from [CastivioMetrics], the
     * one system every screen reads. Two screens that agree because someone typed
     * the same numbers twice agree only until the next edit.
     */
    val frame: CastivioMetrics,
    /* what this screen owns: the geometry of a card, and of the footnote under it */
    val gridGap: Dp,
    val cardPad: Dp,
    val cardGap: Dp,
    val disc: Dp,
    val chevron: Dp,
    val detailLines: Int,
    val subBand: Dp,
    /**
     * The air above the sentence at the foot, and the line it is set on.
     *
     * Two numbers rather than one because they answer to different things: the air is a
     * margin and may be tightened on a short frame, the line has to hold a line of body
     * type and may not. [footLine]'s floor is what a 12dp body line actually measures,
     * which is why it stops falling long before the air does.
     */
    val footAir: Dp,
    val footLine: Dp,
    /** The height of the rule that separates the two ways in from the two that are not. */
    val divider: Dp,
) {
    /* The frame's numbers, reachable as this screen's own. */
    val edge get() = frame.edge
    val stageTop get() = frame.stageTop
    val stageBottom get() = frame.stageBottom
    val header get() = frame.header
    val headGap get() = frame.headGap
    val brand get() = frame.brand
    val back get() = frame.chip
    val backPad get() = frame.chipPad
    val bandTop get() = frame.bandTop
    val radius get() = frame.radius

    /** Between the theme chip and Back, from `:core:design`'s one value. */
    val chipsGap get() = CastivioChipsGap

    /* The four steps, named for what this screen puts on each of them. Named
       rather than aliased away, because a call site that reads `m.fsCard` says
       which step a card's name is on; one that reads `m.frame.fsLabel` says
       only that somebody picked a token. They are getters, so there is still
       exactly one number.

       Four of these used to be table entries of their own. Three of the four
       held the step's own value on every frame — a copy that agreed until
       somebody edited one of them — and the fourth, the tablet's card name,
       had drifted to 17dp: a fifth step, larger than the television's, on the
       one frame whose whole rule is that extra room buys margin and not size. */
    val fsTitle get() = frame.fsTitle
    val fsCard get() = frame.fsLabel
    val fsDetail get() = frame.fsBody
    val fsBadge get() = frame.fsChip
    val fsBack get() = frame.fsChip
    val fsStrip get() = frame.fsChip
}

/**
 * The chooser's numbers for a measured surface.
 *
 * `width` and `height` are what a `BoxWithConstraints` around the screen's own content
 * reports — the surface, not the window and not the display. Horizontal things are read
 * off the width and vertical things off the height, because those are the axes they
 * actually spend.
 */
internal fun sourceMetricsFor(tv: Boolean, width: Dp, height: Dp): SourceMetrics {
    val frame = castivioMetrics(width, height, tv)
    return SourceMetrics(
        frame = frame,
        subBand = height.boundedFraction(SUB_BAND, 18.dp, 34.dp),
        gridGap = height.boundedFraction(GRID_GAP, 12.dp, 26.dp),
        cardPad = height.boundedFraction(CARD_PAD, 9.dp, 22.dp),
        cardGap = height.boundedFraction(CARD_GAP, 11.dp, 22.dp),
        disc = height.boundedFraction(DISC, 50.dp, 76.dp),
        chevron = height.boundedFraction(CHEVRON, 17.dp, 26.dp),
        divider = height.boundedFraction(DIVIDER, 16.dp, 28.dp),
        footAir = height.boundedFraction(FOOT_AIR, 8.dp, 16.dp),
        footLine = height.boundedFraction(FOOT_LINE, 16.dp, 24.dp),
        detailLines = DETAIL_LINES,
    )
}

/* ------------------------------------------------------------------ the shares
 *
 * Read off the 1280×720 reference, which is the 960×540 television drawing at 4/3 —
 * so a television reproduces its approved numbers exactly and nothing else has to be
 * drawn to be right.
 */

private const val SUB_BAND = 34.67f / 720f
private const val GRID_GAP = 24f / 720f
private const val CARD_PAD = 21.33f / 720f
private const val CARD_GAP = 21.33f / 720f
private const val DISC = 96f / 720f
private const val CHEVRON = 32f / 720f
private const val DIVIDER = 26f / 720f

/**
 * The sentence at the foot: its air, and its line.
 *
 * Read off the reference like everything else — 14dp of air over a 20dp line at 720 —
 * and they add up to the 34dp the subtitle band used to take out of the same frame, so
 * at the reference not one card moved when the sentence did.
 *
 * They are not equal to that band everywhere, and the direction is the honest one. The
 * subtitle band was a generous box: 34dp for a 19dp line, nearly twice the type it
 * held. A line and a margin stated separately cannot hide air inside themselves, so on
 * a short frame — where [footLine]'s floor is the 16dp a 12dp body line really measures
 * — the pair costs about 6dp more than the band did. The cards pay it, and the budget
 * says so rather than the device finding out.
 */
private const val FOOT_AIR = 14f / 720f
private const val FOOT_LINE = 20f / 720f

/**
 * How tall the row of two smaller cards is, against one of the leading pair.
 *
 * Nine tenths rather than a half: the two under the rule are secondary, not small.
 * They carry the same two sentences the leading cards do, and a row at half height
 * would be two cards whose description had to be cut to fit — which is the hierarchy
 * expressed by making the content worse rather than by making the card quieter.
 */
private const val MINOR_SHARE = 0.9f

/**
 * How many lines a card's description gets: one for each of its two sentences.
 *
 * This number does two jobs, which is why getting it wrong was invisible. It is the
 * `maxLines` of the text the card draws, and it is the reserve the budget holds that
 * text to — so a value larger than the card can hold does not produce a roomier card,
 * it produces a text that composes past the card's own bounds and is cut by them,
 * without the ellipsis that says it was cut.
 *
 * It was 4 above 480dp and 3 below, which was true of a different screen: when the
 * four cards were a two-by-two grid, every one of them had half a band to itself and
 * could hold a description that wrapped twice. The approved drawing is two tiers, the
 * cards are shorter, and what the drawing shows in every one of them is a description
 * over two lines — the detail and the hint, one line each.
 *
 * So the measurements say the same thing the drawing does. At the 1280×720 reference
 * the lower card is 142.6dp and two lines need 137.9; at 4 they needed 191.9, which
 * the reference has never had. A reserve the reference itself fails is not a reserve,
 * it is an assertion about a screen that was replaced.
 *
 * Not a threshold that was lowered to pass: raising it does not buy a taller card, and
 * the budget it feeds is unchanged and still strict — the card must hold its disc, its
 * padding, its title and these two lines, and the frames that cannot are still failed.
 */
private const val DETAIL_LINES = 2

/**
 * What is left for the cards once everything fixed has been placed.
 *
 * The assurance strip used to be one of the terms and is gone from this screen: three
 * bands of cards and a rule between them is what the frame now holds, and a footnote
 * under all of it was the thing that did not fit. Its claims were about the product
 * rather than about this decision, and the screen asks the decision.
 *
 * ## The header's second row moved to the foot, and this counts it there
 *
 * `CastivioHeader` is two bands when a screen gives it a sentence: it measures the row,
 * measures the sentence under it, and reports `layout(total, rowH + band)`. This screen
 * no longer gives it one, so [subBand] is not spent here any more and is not subtracted
 * -- it stays on [SourceMetrics] because the saved-subscriptions header still wears its
 * own sentence.
 *
 * What is spent instead is [footAir] and [footLine], at the other end of the same
 * `Column` and for the same words. The sentence is a line of prose about the screen, and
 * under four cards it reads as a footnote rather than as a heading's second line, which
 * is where it was asked to go.
 *
 * Both ends are counted because neither is optional. A band that forgot either would
 * promise the cards room the `Column` does not have, and the budget that reads this
 * would pass a frame that clips on the device -- which is the one direction a budget is
 * not allowed to be wrong in.
 */
internal fun SourceMetrics.bandHeight(frame: Dp): Dp =
    frame - stageTop - header - bandTop - footAir - footLine - stageBottom

/**
 * One of the two leading cards.
 *
 * The band holds two of them, the rule, and the row of two smaller ones, with a gap
 * between each pair. The smaller row is [MINOR_SHARE] of a leading card, so the
 * division is by `2 + MINOR_SHARE` rather than by three — which is the same arithmetic
 * the weights in the layout do, written once more here so the budget is checking the
 * layout rather than a copy of it.
 */
internal fun SourceMetrics.leadHeight(frame: Dp): Dp =
    (bandHeight(frame) - divider - gridGap * 3) / (2f + MINOR_SHARE)

/** One of the two smaller cards under the rule, which is the shortest card on screen. */
internal fun SourceMetrics.cardHeight(frame: Dp): Dp = leadHeight(frame) * MINOR_SHARE

/**
 * The four ways in, as a grid.
 *
 * ## Why a grid and not a list
 *
 * The frame is twice as wide as it is tall — this screen is only ever seen in
 * landscape — so a column of four rows wastes the width and crowds the height. Two
 * rows of two use the shape the screen actually has, and it is what lets a card be
 * 94dp with a 52dp disc rather than 64dp of text.
 *
 * ## The header is the activation screen's
 *
 * Same row, same rule: the lockup at the same physical edge in every language, the
 * question beside it, and the row's one control at the far end — the language chip
 * there, Back here. A brand that moves between two screens a user sees one after the
 * other is two brands, and a header that reassembles itself is the kind of fault
 * nobody can point at and everybody feels.
 *
 * ## One lit card at a time, and it is the focused one
 *
 * There used to be a second lit state: the suggested card carried a violet edge and a
 * glow, beside the azure ring that focus carries. The two had to be told apart from
 * across a room, which is a burden a screen only has to carry if it is making a
 * recommendation — and this one no longer is. The cards are all drawn at rest the same
 * way, so the ring means exactly one thing: here is the remote.
 *
 * ## Direction
 *
 * `Row` and `Column` resolve their own start and end, so Xtream leads on the right in
 * Arabic and on the left in English with no coordinate written anywhere. The two
 * chevrons are auto-mirrored and point opposite ways on purpose: a card's leads onward
 * and follows the reading direction, Back points the way the reader came from.
 *
 * @param onBack what Back does. It is in the header now rather than under the grid,
 *   which is where it was asked to go.
 */
@Composable
internal fun SourceChoiceScreen(
    onXtream: () -> Unit,
    onPlaylist: () -> Unit,
    onPortal: () -> Unit,
    onSavedSources: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tv = CastivioTheme.device.isTv
    BoxWithConstraints(modifier.fillMaxSize()) {
        val m = sourceMetricsFor(tv = tv, width = maxWidth, height = maxHeight)

        Column(
            Modifier
                .fillMaxSize()
                .castivioStage(m.frame)
                .testTag(ActivationTags.SOURCE_CONTAINER),
        ) {
            // No sentence under the title. It says what the screen is, which is what
            // four labelled cards under a question already say, and in the header it
            // was charging the cards a band of their own height to repeat them. The
            // same words are at the foot now, where a line about the screen reads as a
            // footnote rather than as a second heading.
            ChooserHeader(
                m = m,
                title = stringResource(R.string.source_choice_title),
                headingTag = ActivationTags.SOURCE_HEADING,
                backTag = ActivationTags.SOURCE_BACK,
                onBack = onBack,
            )
            Spacer(Modifier.height(m.bandTop))

            // Weighted, so the cards are what is left rather than what they asked
            // for. A weighted child cannot push its siblings out, which makes overflow
            // structural instead of arithmetic.
            SourceTiers(
                m = m,
                modifier = Modifier.weight(1f),
                onXtream = onXtream,
                onPlaylist = onPlaylist,
                onPortal = onPortal,
                onSavedSources = onSavedSources,
            )

            // A sibling of the tiers rather than a child of them, which is the whole of
            // what it costs. Inside that `Column` it would also be charged a grid gap,
            // and the air above it is a decision rather than a leftover -- so it is
            // stated once, here, as `footAir`.
            //
            // No bar, no border, no fill. The legal line on the activation screen wears
            // one because it is a different kind of sentence; this is the screen talking
            // about itself and is the quietest thing on it.
            Spacer(Modifier.height(m.footAir))
            Text(
                text = stringResource(R.string.source_choice_subtitle),
                style = castivioBodyStyle(m.fsDetail),
                color = CastivioTheme.colors.onBackgroundMuted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(m.footLine)
                    .wrapContentHeight(Alignment.CenterVertically)
                    .testTag(ActivationTags.SOURCE_SUBTITLE),
            )
        }
    }
}



/* ---------------------------------------------------------------------- tiers */

/**
 * The four ways in, in two tiers rather than four equal quarters.
 *
 * ## Why they are not equal any more
 *
 * They were a two-by-two grid, which says the four things on it are four equally
 * likely answers to the question in the heading. They are not, and the grid was the
 * only thing claiming they were: two of them are how a subscription arrives, and the
 * other two are a different kind of provider and the subscriptions already on the box.
 * A user opening this screen has almost always come to do one of the first two.
 *
 * So Xtream and the playlist take the full width, one above the other, and the two
 * that are not a new subscription sit under a rule that names them as what they are.
 * The hierarchy is carried by size and by the rule, and by nothing else. There is no
 * badge and no featured card: a "fastest" label on one of the two leading cards was a
 * recommendation nobody had asked this screen to make, and with the two of them now
 * the whole of the first tier, being first is the recommendation.
 *
 * ## The heights are weights, not numbers
 *
 * Each leading card takes a share, the smaller row takes [MINOR_SHARE] of one, and the
 * rule takes its own fixed height. Nothing is declared in dp, so the band divides
 * whatever the frame leaves and the screen cannot overflow on a surface nobody drew.
 * [leadHeight] states the same arithmetic for the budget to check.
 */
@Composable
private fun SourceTiers(
    m: SourceMetrics,
    modifier: Modifier,
    onXtream: () -> Unit,
    onPlaylist: () -> Unit,
    onPortal: () -> Unit,
    onSavedSources: () -> Unit,
) {
    val colors = CastivioTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(m.gridGap)) {
        SourceCard(
            m = m, hue = colors.hueViolet, icon = Icons.Rounded.Dns,
            title = stringResource(R.string.source_xtream_title),
            detail = stringResource(R.string.source_xtream_detail),
            hint = stringResource(R.string.source_xtream_hint),
            onClick = onXtream, tag = ActivationTags.SOURCE_XTREAM,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        SourceCard(
            m = m, hue = colors.hueAzure, icon = Icons.Rounded.Link,
            title = stringResource(R.string.source_m3u_title),
            detail = stringResource(R.string.source_m3u_detail),
            hint = stringResource(R.string.source_m3u_hint),
            onClick = onPlaylist, tag = ActivationTags.SOURCE_M3U,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        OtherOptions(m)

        Row(
            Modifier.weight(MINOR_SHARE).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(m.gridGap),
        ) {
            SourceCard(
                m = m, hue = colors.hueAmber, icon = Icons.Rounded.SettingsInputAntenna,
                title = stringResource(R.string.source_portal_title),
                // Both sentences are short here, and measured rather than guessed. The
                // two cards under the rule are half the width of the two above them,
                // and at that width this pair ran to two lines in a card that holds
                // one -- the end of it was cut on the device. They now fit on the line.
                detail = stringResource(R.string.source_portal_detail),
                hint = stringResource(R.string.source_portal_hint),
                onClick = onPortal, tag = ActivationTags.SOURCE_PORTAL,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            SourceCard(
                m = m, hue = colors.hueGreen, icon = Icons.Rounded.Group,
                title = stringResource(R.string.source_users_title),
                detail = stringResource(R.string.source_users_detail),
                hint = stringResource(R.string.source_users_hint),
                onClick = onSavedSources, tag = ActivationTags.SOURCE_USERS,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

/**
 * The rule between the tiers, and the three words on it.
 *
 * A heading would be a third voice on a screen that already has a question and four
 * answers. A rule with a quiet label is the smallest thing that says "and these are
 * the others" — it reads as punctuation rather than as a section, which is what the
 * two cards under it deserve: available, and not what you came for.
 */
@Composable
private fun OtherOptions(m: SourceMetrics) {
    val colors = CastivioTheme.colors
    Row(
        Modifier.fillMaxWidth().height(m.divider),
        horizontalArrangement = Arrangement.spacedBy(m.cardGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Rule(Modifier.weight(1f), colors.edgeQuiet)
        Text(
            text = stringResource(R.string.source_other_options),
            style = castivioChipStyle(m.fsBadge),
            color = colors.onBackgroundMuted,
            maxLines = 1,
            modifier = Modifier.testTag(ActivationTags.SOURCE_OTHERS),
        )
        Rule(Modifier.weight(1f), colors.edgeQuiet)
    }
}

/** One side of the rule: a hairline that fades out towards the frame's edge. */
@Composable
private fun Rule(modifier: Modifier, ink: Color) {
    Box(
        modifier
            .height(1.dp)
            .background(Brush.horizontalGradient(listOf(Color.Transparent, ink, Color.Transparent))),
    )
}

/**
 * One card. There is no second kind — the recommendation is a parameter, not a variant.
 *
 * The description is **one block of two sentences**, capped at the frame's line count,
 * rather than two lines nobody bounds the total of. Portuguese wraps the first to two
 * lines and overran the card by ten dp when they were separate; a card that overruns
 * does not clip, it takes its row's height from whatever was measured after it. The
 * hint keeps a quieter ink, because that is what separates a fact from an aside — not
 * the line break.
 */
@Composable
private fun SourceCard(
    m: SourceMetrics,
    hue: Color,
    icon: ImageVector,
    title: String,
    detail: String,
    hint: String,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors

    InteractiveGlassCard(
        onClick = onClick,
        modifier = modifier
            .testTag(tag)
            // One node, one label, one target: a reader announces the whole choice as
            // a single item. Without it the disc, the name and the two sentences are
            // four focusable-looking fragments of one decision.
            .semantics(mergeDescendants = true) {
                contentDescription = "$title. $detail $hint"
            },
        shape = RoundedCornerShape(m.radius),
        fill = colors.glassFillBrush,
    ) {
        Row(
            Modifier.fillMaxSize().padding(m.cardPad),
            horizontalArrangement = Arrangement.spacedBy(m.cardGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Disc(m, hue, icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.cardPad * TEXT_GAP)) {
                // Fitted, not clipped. A card's name is one line by design -- it sits
                // above a description, and wrapping it would change the card's whole
                // shape in the languages that need two lines. So it *shrinks* to the
                // width it has, down to an 11sp floor, which is the other half of the
                // rule: text may reflow or scale, and may never be cut. `maxLines = 1`
                // with an ellipsis was the third thing, and it is the one that is not
                // allowed -- a reader sees "Xtream Cod…" and cannot tell it is the same
                // product.
                CastivioFittedText(
                    text = title,
                    style = CastivioType.titleMedium.copy(
                        fontSize = m.fsCard.value.sp,
                        lineHeight = (m.fsCard.value * TITLE_LEADING).sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.sp,
                    ),
                    color = colors.onBackgroundStrong,
                )
                Text(
                    text = buildAnnotatedString {
                        append(detail)
                        append(' ')
                        withStyle(SpanStyle(color = colors.onBackgroundMuted)) { append(hint) }
                    },
                    style = castivioBodyStyle(m.fsDetail),
                    color = castivioDescriptionColor,
                    maxLines = m.detailLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.onBackgroundMuted,
                modifier = Modifier.size(m.chevron),
            )
        }
    }
}

/* --------------------------------------------------------------------- ratios */


private const val TITLE_LEADING = 1.35f

/** An icon beside type, as a multiple of that type's size. */
private const val ICON_RATIO = 1.25f

private const val TEXT_GAP = 0.5f



