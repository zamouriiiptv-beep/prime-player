package com.castivio.feature.activation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.castivio.core.design.components.InteractiveGlassCard
import com.castivio.core.design.components.castivioChipStyle
import com.castivio.core.design.theme.CastivioFrame
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Palette
import com.castivio.core.design.theme.SHORT_FRAME
import com.castivio.core.design.theme.TABLET_FRAME

/**
 * The approved drawing's numbers, per frame.
 *
 * `design/mockups/source-choice.html` is the record, and these are transcribed from
 * it rather than approximated — the same discipline `MacActivationScreen` follows and
 * for the same reason: this screen stacks five things down a 393dp frame, and a
 * `Column` that runs out of height hands **zero** to whatever it measured last.
 *
 * What the drawing measures, in all twelve frame-and-language combinations:
 *
 * | frame | card | strip | description lines |
 * |---|---|---|---|
 * | 960×540 TV | 161 | 40 | 4 |
 * | 873×393 | 119 | 32 | 3 |
 * | 800×360 | 114 | 30 | 3 |
 *
 * Nothing overflows and nothing is cut, in any frame or any language. The card is
 * derived rather than declared — two weighted rows of what the header and the strip
 * leave — so the number above is an outcome, and `SourceChoiceBudgetTest` is what
 * asserts it stays positive.
 *
 * ## The type is not this screen's
 *
 * Every size here is one of [CastivioFrame]'s four steps: `fsTitle` for the question,
 * `fsLabel` for a card's name, `fsBody` for its description, `fsChip` for the badge,
 * Back and the footnote. A screen that invents its own scale beside the one before it
 * is two products, and compressing type to make content fit is how a layout hides that
 * it is too small.
 */
internal data class SourceMetrics(
    /**
     * The stage, the header and the shared type steps — from [CastivioFrame], the
     * one table every screen reads. Two screens that agree because someone typed
     * the same numbers twice agree only until the next edit.
     */
    val frame: CastivioFrame,
    /* what this screen owns: the geometry of a card, and of the footnote under it */
    val gridGap: Dp,
    val cardPad: Dp,
    val cardGap: Dp,
    val disc: Dp,
    val chevron: Dp,
    val detailLines: Int,
    val strip: Dp,
    val stripGap: Dp,
    val stripDisc: Dp,
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

internal fun sourceMetricsFor(tv: Boolean, available: Dp): SourceMetrics = when {
    tv -> SourceMetrics(
        frame = CastivioFrame.Television,
        gridGap = 18.dp, cardPad = 16.dp, cardGap = 16.dp, disc = 72.dp, chevron = 24.dp,
        detailLines = 4,
        strip = 40.dp, stripGap = 14.dp, stripDisc = 26.dp,
    )
    available >= TABLET_FRAME -> SourceMetrics(
        frame = CastivioFrame.Tablet,
        gridGap = 20.dp, cardPad = 20.dp, cardGap = 18.dp, disc = 72.dp, chevron = 22.dp,
        detailLines = 3,
        strip = 36.dp, stripGap = 14.dp, stripDisc = 24.dp,
    )
    available < SHORT_FRAME -> SourceMetrics(
        frame = CastivioFrame.ShortPhone,
        gridGap = 12.dp, cardPad = 9.dp, cardGap = 11.dp, disc = 50.dp, chevron = 17.dp,
        detailLines = 3,
        strip = 30.dp, stripGap = 8.dp, stripDisc = 19.dp,
    )
    else -> SourceMetrics(
        frame = CastivioFrame.Phone,
        gridGap = 14.dp, cardPad = 10.dp, cardGap = 12.dp, disc = 52.dp, chevron = 18.dp,
        detailLines = 3,
        strip = 32.dp, stripGap = 10.dp, stripDisc = 20.dp,
    )
}

/**
 * What is left for the two rows of cards once everything fixed has been placed.
 *
 * The subtitle used to be one of the terms. It said *add your preferred playback
 * method to start watching* under a heading that says *choose how to add* — the
 * same sentence twice, one of them in smaller type — and a reader deciding between
 * four cards got no help from either. Removing it gives the two rows 18–26dp back,
 * which is where a third line of description on the short frame comes from.
 */
internal fun SourceMetrics.gridHeight(frame: Dp): Dp =
    frame - stageTop - header - bandTop - strip - stripGap - stageBottom

/** One card, which is half of that minus the gap between the rows. */
internal fun SourceMetrics.cardHeight(frame: Dp): Dp = (gridHeight(frame) - gridGap) / 2

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
 * ## Recommended and focused are two pictures
 *
 * The suggested card carries a violet edge and a glow; focus carries the azure ring
 * every focusable thing in this app carries. They must not be the same picture: a
 * television has to say where the remote is, and a D-pad whose position looks like a
 * recommendation is a D-pad the viewer has lost. [InteractiveGlassCard] takes the rest
 * colour and lets focus override it, so the ring keeps meaning exactly one thing.
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
    onLocalVideo: () -> Unit,
    onSavedSources: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tv = CastivioTheme.device.isTv
    BoxWithConstraints(modifier.fillMaxSize()) {
        val m = sourceMetricsFor(tv = tv, available = maxHeight)

        Column(
            Modifier
                .fillMaxSize()
                .padding(start = m.edge, end = m.edge, top = m.stageTop, bottom = m.stageBottom)
                .testTag(ActivationTags.SOURCE_CONTAINER),
        ) {
            ChooserHeader(
                m = m,
                title = stringResource(R.string.source_choice_title),
                headingTag = ActivationTags.SOURCE_HEADING,
                backTag = ActivationTags.SOURCE_BACK,
                onBack = onBack,
            )
            Spacer(Modifier.height(m.bandTop))

            // Weighted, so the grid is what is left rather than what it asked for.
            // A weighted child cannot push its siblings out, which makes overflow
            // structural instead of arithmetic: the strip and the sentence are placed
            // first and the cards absorb the remainder.
            SourceGrid(
                m = m,
                modifier = Modifier.weight(1f),
                onXtream = onXtream,
                onPlaylist = onPlaylist,
                onLocalVideo = onLocalVideo,
                onSavedSources = onSavedSources,
            )

            Spacer(Modifier.height(m.stripGap))
            AssuranceStrip(m)
        }
    }
}



/* ----------------------------------------------------------------------- grid */

@Composable
private fun SourceGrid(
    m: SourceMetrics,
    modifier: Modifier,
    onXtream: () -> Unit,
    onPlaylist: () -> Unit,
    onLocalVideo: () -> Unit,
    onSavedSources: () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(m.gridGap)) {
        // Two rows of `weight(1f)` inside a bounded column are exactly equal, measured
        // once — no intrinsic pass, and no way for one row to grow at the other's cost.
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(m.gridGap)) {
            SourceCard(
                m = m, hue = Palette.Azure50, icon = Icons.Rounded.Dns,
                title = stringResource(R.string.source_xtream_title),
                detail = stringResource(R.string.source_xtream_detail),
                hint = stringResource(R.string.source_xtream_hint),
                recommended = false, onClick = onXtream, tag = ActivationTags.SOURCE_XTREAM,
            )
            SourceCard(
                m = m, hue = Palette.Violet50, icon = Icons.Rounded.Link,
                title = stringResource(R.string.source_m3u_title),
                detail = stringResource(R.string.source_m3u_detail),
                hint = stringResource(R.string.source_m3u_hint),
                recommended = true, onClick = onPlaylist, tag = ActivationTags.SOURCE_M3U,
            )
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(m.gridGap)) {
            SourceCard(
                m = m, hue = Palette.Amber, icon = Icons.Rounded.OndemandVideo,
                title = stringResource(R.string.source_local_title),
                detail = stringResource(R.string.source_local_detail),
                hint = stringResource(R.string.source_local_hint),
                recommended = false, onClick = onLocalVideo, tag = ActivationTags.SOURCE_LOCAL,
            )
            SourceCard(
                m = m, hue = Palette.Success, icon = Icons.Rounded.Group,
                title = stringResource(R.string.source_users_title),
                detail = stringResource(R.string.source_users_detail),
                hint = stringResource(R.string.source_users_hint),
                recommended = false, onClick = onSavedSources, tag = ActivationTags.SOURCE_USERS,
            )
        }
    }
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
private fun RowScope.SourceCard(
    m: SourceMetrics,
    hue: Color,
    icon: ImageVector,
    title: String,
    detail: String,
    hint: String,
    recommended: Boolean,
    onClick: () -> Unit,
    tag: String,
) {
    val colors = CastivioTheme.colors
    val badge = stringResource(R.string.source_badge_fastest)

    InteractiveGlassCard(
        onClick = onClick,
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .testTag(tag)
            // One node, one label, one target: a reader announces the whole choice as
            // a single item. Without it the disc, the name and the two sentences are
            // four focusable-looking fragments of one decision.
            .semantics(mergeDescendants = true) {
                contentDescription = if (recommended) "$title. $badge. $detail $hint"
                else "$title. $detail $hint"
            },
        shape = RoundedCornerShape(m.radius),
        fill = if (recommended) SolidColor(Palette.Violet10) else colors.glassFillBrush,
        restBorder = if (recommended) RECOMMENDED_EDGE else null,
        restGlow = if (recommended) RECOMMENDED_GLOW else null,
    ) {
        Row(
            Modifier.fillMaxSize().padding(m.cardPad),
            horizontalArrangement = Arrangement.spacedBy(m.cardGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Disc(m, hue, icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.cardPad * TEXT_GAP)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(m.cardPad * BADGE_GAP),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = CastivioType.titleMedium.copy(
                            fontSize = m.fsCard.value.sp,
                            lineHeight = (m.fsCard.value * TITLE_LEADING).sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.sp,
                        ),
                        color = Palette.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (recommended) Badge(m, badge)
                }
                Text(
                    text = buildAnnotatedString {
                        append(detail)
                        append(' ')
                        withStyle(SpanStyle(color = colors.onBackgroundMuted)) { append(hint) }
                    },
                    style = CastivioType.bodySmall.copy(
                        fontSize = m.fsDetail.value.sp,
                        lineHeight = (m.fsDetail.value * BODY_LEADING).sp,
                        letterSpacing = 0.sp,
                    ),
                    color = colors.onBackgroundVariant,
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

/** The card's disc: the one place its hue is loud. */
@Composable
private fun Disc(m: SourceMetrics, hue: Color, icon: ImageVector) {
    Box(
        Modifier
            .size(m.disc)
            .clip(RoundedCornerShape(percent = 50))
            .background(
                Brush.radialGradient(
                    listOf(hue.copy(alpha = DISC_TOP), hue.copy(alpha = DISC_FOOT)),
                ),
            )
            .border(BorderStroke(1.dp, hue.copy(alpha = DISC_EDGE)), RoundedCornerShape(percent = 50)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = hue, modifier = Modifier.size(m.disc * DISC_ICON))
    }
}

/**
 * The recommendation, as a badge.
 *
 * A label rather than a ring, so it cannot be read as focus. It keeps its intrinsic
 * width and the name beside it yields, which is the header's rule one level down: a
 * badge is a fixed fact and a name is the thing here whose full size is not
 * load-bearing.
 */
@Composable
private fun Badge(m: SourceMetrics, text: String) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        Modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(BADGE_TOP, BADGE_FOOT)))
            .border(BorderStroke(1.dp, BADGE_EDGE), shape)
            .padding(horizontal = m.fsBadge * BADGE_PAD, vertical = m.fsBadge * BADGE_PAD_Y),
        horizontalArrangement = Arrangement.spacedBy(m.fsBadge * BADGE_ICON_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Bolt,
            contentDescription = null,
            tint = Palette.Violet60,
            modifier = Modifier.size(m.fsBadge * ICON_RATIO),
        )
        Text(
            text = text,
            style = castivioChipStyle(m.fsBadge).copy(fontWeight = FontWeight.Bold),
            color = Palette.White,
            maxLines = 1,
        )
    }
}

/* ---------------------------------------------------------------------- strip */

/**
 * The assurance strip — a footnote, and drawn as one.
 *
 * It says nothing a reader needs in order to choose, and this screen exists for one
 * choice, so it takes the least room that still lets it be read: one line per cell,
 * no second sentence, no pane and no border around it. A panel would make it a fifth
 * thing to look at beside the four that matter.
 *
 * ## Two cells, and it used to be four
 *
 * *Safe and private* and *full protection* were one claim written twice — half a
 * footnote spent saying the same thing to the same reader — and *broad support*
 * repeated the local-file card's own description from 100dp away, where the sentence
 * actually means something because it is attached to the thing it describes.
 *
 * What the two survivors buy is width: a cell is now half the strip rather than a
 * quarter, so the sentences read at [CastivioFrame.fsChip] instead of at a step
 * invented locally to make four of them fit. Compressing type is how a layout hides
 * that it is holding more than it should.
 */
@Composable
private fun AssuranceStrip(m: SourceMetrics) {
    Row(
        Modifier.fillMaxWidth().height(m.strip).padding(horizontal = m.strip * STRIP_PAD),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StripCell(m, Palette.Violet50, Icons.Rounded.VerifiedUser, R.string.source_trust_private_title)
        StripCell(m, Palette.Azure50, Icons.Rounded.Speed, R.string.source_trust_fast_title)
    }
}

@Composable
private fun RowScope.StripCell(m: SourceMetrics, hue: Color, icon: ImageVector, title: Int) {
    val head = stringResource(title)
    Row(
        Modifier
            .weight(1f)
            .padding(horizontal = m.strip * CELL_PAD)
            .semantics(mergeDescendants = true) { contentDescription = head },
        horizontalArrangement = Arrangement.spacedBy(m.strip * CELL_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(m.stripDisc)
                .clip(RoundedCornerShape(percent = 50))
                .background(hue.copy(alpha = CELL_FILL))
                .border(BorderStroke(1.dp, hue.copy(alpha = CELL_EDGE)), RoundedCornerShape(percent = 50)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = hue, modifier = Modifier.size(m.stripDisc * DISC_ICON))
        }
        Text(
            text = head,
            style = castivioChipStyle(m.fsStrip).copy(fontWeight = FontWeight.SemiBold),
            color = CastivioTheme.colors.onBackgroundMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/* --------------------------------------------------------------------- ratios */


private const val TITLE_LEADING = 1.35f
private const val BODY_LEADING = 1.5f

/** An icon beside type, as a multiple of that type's size. */
private const val ICON_RATIO = 1.25f

private const val TEXT_GAP = 0.5f
private const val BADGE_GAP = 0.9f
private const val BADGE_PAD = 0.62f
private const val BADGE_PAD_Y = 0.17f
private const val BADGE_ICON_GAP = 0.34f

private const val DISC_ICON = 0.5f
private const val DISC_TOP = 0.34f
private const val DISC_FOOT = 0.08f
private const val DISC_EDGE = 0.46f

private const val STRIP_PAD = 0.22f
private const val CELL_PAD = 0.16f
private const val CELL_GAP = 0.18f
private const val CELL_FILL = 0.10f
private const val CELL_EDGE = 0.26f

/**
 * The suggested card's edge and the light around it.
 *
 * Violet, and deliberately not the azure the focus ring uses: the two say different
 * things and a viewer has to be able to tell which is which from across a room.
 */
private val RECOMMENDED_EDGE = Palette.Violet60.copy(alpha = 0.85f)
private val RECOMMENDED_GLOW = Palette.Violet50.copy(alpha = 0.42f)

private val BADGE_TOP = Palette.Violet50.copy(alpha = 0.42f)
private val BADGE_FOOT = Palette.Violet40.copy(alpha = 0.30f)
private val BADGE_EDGE = Palette.Violet60.copy(alpha = 0.46f)
