package com.castivio.core.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Palette
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.castivio.core.design.R
import com.castivio.core.design.theme.CastivioType
import kotlin.math.max

/**
 * The screen header: the lockup, the screen's name, and a row of chips.
 *
 * ## The row does not mirror; its contents do
 *
 * The brand sits at the same edge in every language. It is the mark Castivio
 * signs its name with, and a signature that changes sides per locale is two
 * signatures — the same reason [CastivioLockup] fixes the order of the mark and
 * the word. Once the lockup is pinned there the chrome around it has to be
 * pinned too, or the header would mirror around a fixed point, which reads as a
 * mistake rather than as a decision. So the row is placed left to right always:
 * mark, then the screen's name, then the chips. What each of them *says* still
 * runs in its own script and its own direction, which is the part that is
 * language.
 *
 * Invariant 4 forbids direction-absolute layout APIs, and this is the one place
 * that deliberately takes the exception, in one function, with the reason
 * written down.
 *
 * ## The space
 *
 * The two ends take the width they need and the title takes what is between
 * them, centred in it. Both outer margins are therefore the stage's own edge —
 * the space is split evenly either side without anything having to be measured —
 * and the element that gives when a translation is long is the title, which is
 * the only one of the three whose full size is not load-bearing.
 *
 * ## The level: a baseline, not a box
 *
 * The title and the wordmark sit on **one baseline**. They used to be centred in
 * the row instead, each in its own box, and that is not the same thing and does
 * not look like it: the row sets two scripts, and centring a box centres the
 * metrics of a font rather than the ink of a word. The Arabic title is top-heavy
 * — alef, lam and ta rise where nothing descends to answer them — so its box came
 * out centred and its word came out high, above a wordmark that was exactly where
 * it should be. The drawing did not show this because Chromium's line box for
 * IBM Plex Sans Arabic is not Android's, which is precisely why the rule has to
 * be one that does not depend on either.
 *
 * A shared baseline does not. It is what "on the same line" has meant since type
 * was set in metal, it is the only vertical relationship a reader actually
 * perceives between two words, and it is exact rather than approximate: no font
 * metric, no fudge factor, and no second opinion between the mockup and the
 * device.
 *
 * It only became *possible* once the slots stopped being measured at the row's
 * own fixed height — see the measure block. Both faults pointed the same way and
 * the larger one hid the smaller: eight dp of the title's rise was the forced
 * height, the last dp or so was the box.
 *
 * The chips stay centred, because a pill is a shape and not a word — its ink is
 * its own outline, and an outline is centred, not seated.
 *
 * @param height the row's height. The chips are centred in it and the lockup
 *   with them; the title is then seated on the lockup's baseline.
 * @param gap the one spacing value: between the lockup and the title, and
 *   between the title and the chips.
 */
@Composable
fun CastivioHeader(
    height: Dp,
    gap: Dp,
    lockup: @Composable () -> Unit,
    title: @Composable () -> Unit,
    chips: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        contents = listOf(lockup, title, chips),
        modifier = modifier.height(height),
    ) { (lockM, titleM, chipM), constraints ->
        val gapPx = gap.roundToPx()
        val total = constraints.maxWidth
        val rowH = constraints.maxHeight

        // **Every slot is measured loose in BOTH axes**, and the second half of
        // that is not a tidiness: `Modifier.height` arrives here as
        // `minHeight == maxHeight == rowH`, and `constraints.copy(maxWidth = …)`
        // carries it through untouched. A `Row` given a fixed height survives it,
        // because a row centres its own children — which is why the lockup and
        // the chips looked right. A `Text` does not. One line inside a box it was
        // forced to fill sits at the **top** of that box, so the title was riding
        // eight dp above a wordmark that was exactly where it should be, and no
        // amount of arithmetic further down could have moved it: it was already
        // as tall as the row, so `(rowH - height) / 2` was zero.
        //
        // ## The height is unbounded, and that is the second fault this fixes
        //
        // It was `maxHeight = rowH`, which reads as a safety and is the opposite.
        // A chip is drawn at `CastivioFrame.chip` — 44dp on a television, 34 on the
        // shortest phone — and its *interaction* box is `touchTarget`: 56 and 48.
        // Clamped to the row, that box came back 54 and 36, and a control whose hit
        // area has been quietly cut to the row is exactly the failure a floor exists
        // to prevent. It is invisible, too: the pill still looks right.
        //
        // So a slot may exceed the row. What it may **not** do is change the row:
        // this component still reports `rowH`, so nothing below the header moves,
        // and an oversized slot is centred — overhanging by half the difference into
        // the stage's own margins, which are 24/22 on a television down to 11/8 on
        // the shortest phone against a widest overhang of 6dp. Nothing clips it,
        // because nothing between here and the display draws a clip.
        fun slot(width: Int) = androidx.compose.ui.unit.Constraints(
            minWidth = 0, maxWidth = width,
            minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity,
        )

        // The two ends take what they need; the title takes what is between them
        // and yields into it. Neither end is ever measured into a remainder,
        // which is the rule that was missing: one of the chips is the language
        // control, and a control squeezed to nothing to make room for a caption
        // is a control the user cannot reach.
        val lock = lockM.first().measure(slot(total))
        val chip = chipM.first().measure(slot(total))
        val middle = max(0, total - lock.width - chip.width - gapPx * 2)
        val titleP = titleM.first().measure(slot(middle))

        // Where each of the three sits vertically. The lockup and the chips are
        // centred; the title is seated on the lockup's baseline, and falls back
        // to centring if either baseline is missing — which is what Robolectric
        // reports, since it does not lay text out at all.
        fun centre(p: Placeable) = (rowH - p.height) / 2
        val lockY = centre(lock)
        val lockBase = lock[FirstBaseline]
        val titleBase = titleP[FirstBaseline]
        val titleY = if (lockBase == AlignmentLine.Unspecified || titleBase == AlignmentLine.Unspecified) {
            centre(titleP)
        } else {
            // Clamped to the row, so a title larger than the header it was given
            // leans rather than hangs out of it.
            (lockY + lockBase - titleBase).coerceIn(0, max(0, rowH - titleP.height))
        }

        layout(total, rowH) {
            lock.place(0, lockY)
            titleP.place(lock.width + gapPx + (middle - titleP.width) / 2, titleY)
            chip.place(total - chip.width, centre(chip))
        }
    }
}

/**
 * The mark and the name, locked together, in that order, in every language.
 *
 * ## It does not mirror, and that is the point
 *
 * Everything else in a header is language — a title, a chip, a sentence — and
 * belongs on the side the reader's language starts from. This is not language. It
 * is the mark Castivio signs its name with, and a signature that reassembles
 * itself per locale is two signatures. `CastivioIntro` already made the same call
 * for the startup screen, and a header that disagreed with the splash about what
 * the brand looks like would be the worse kind of inconsistency: one nobody can
 * point at, that just feels unfinished.
 *
 * Pinned by giving this subtree its own [LayoutDirection] rather than by putting
 * a `left` on the mark. Inside it the row is ordinary — order and spacing do the
 * work — so there is no direction-absolute API anywhere and nothing to remember
 * to undo. Invariant 4 forbids the API, not a subtree that declares its own
 * direction, which is the mechanism the platform provides for exactly this.
 */
@Composable
fun CastivioLockup(
    markSize: Dp,
    wordSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Ltr,
    ) {
        Row(
            modifier,
            horizontalArrangement = Arrangement.spacedBy(markSize * LOCKUP_GAP_RATIO),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.castivio_mark),
                // The word beside it says the name; a reader that announces the
                // picture as well says it twice.
                contentDescription = null,
                modifier = Modifier.size(markSize),
            )
            Wordmark(wordSize)
        }
    }
}

/**
 * The name, tracked and filled from the shared definition.
 *
 * The tracking is [CastivioMark.TRACKING_RATIO], not a number chosen here: the
 * startup screen draws 26dp of it at 104dp and a header draws its own share of
 * its own size, and the ratio is the part that is portable. A second opinion
 * about the brand's tracking, held in a second file, is how two of them end up
 * differing by a point and nobody knowing which is right.
 *
 * ## The trailing track is taken back out
 *
 * Letter-spacing is added *after* every character, the last one included, so the
 * final O carries a quarter of an em of empty box that nothing draws in. A layout
 * that centres the box therefore centres something the eye cannot see, and the
 * mark reads off centre by half that: measured 26 and 26, seen 34 and 26. The
 * layout modifier reports the width without the trailing track, so the box ends
 * where the O ends and the two numbers agree again.
 */
@Composable
private fun Wordmark(size: TextUnit, modifier: Modifier = Modifier) {
    val tracking = size * CastivioMark.TRACKING_RATIO
    Text(
        text = CastivioMark.TEXT,
        style = TextStyle(
            fontFamily = CastivioType.Inter,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            fontSize = size,
            lineHeight = size,
            letterSpacing = tracking,
            brush = Brush.linearGradient(CastivioMark.colours),
        ).tightBox(),
        maxLines = 1,
        modifier = modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val trailing = tracking.toPx().toInt()
            layout(max(0, placeable.width - trailing), placeable.height) {
                placeable.place(0, 0)
            }
        },
    )
}

/**
 * The same style, in a box that ends where the type does.
 *
 * ## Why a header needs this and a paragraph does not
 *
 * Android gives every `Text` a slab of *font padding* above the ascent and below
 * the descent, sized from the font's own hinting metrics. In a paragraph nobody
 * sees it. In a row of three things centred against each other it is the whole
 * problem: the padding is a different size for IBM Plex Sans Arabic — which hangs
 * marks high and drops tails deep — than for Inter, so centring the two boxes
 * puts the two scripts on two different levels, by a few dp that the eye reads
 * immediately as "the title is not on the wordmark's line".
 *
 * Dropping it leaves a box that runs from the font's ascent to its descent and
 * nothing more. For type set in capitals — which the wordmark is — that box's
 * centre and the capitals' own centre are the same point to within a rounding
 * error, because a cap sits as far below the ascent as the descent sits below the
 * baseline. So centring the box centres the ink, which is what the row was always
 * trying to do.
 *
 * The leading is centred rather than distributed, for the second half of the same
 * reason. Compose's default splits a line's extra height between ascent and
 * descent *in proportion to them*, so a face with a deep descent — Arabic, again —
 * takes more of it below than above and the box's centre drifts off the type's.
 * An even split keeps the two together whatever the leading is set to.
 *
 * The drawing had this for free: CSS has no font padding, and `line-height:1`
 * with `align-items:center` is exactly this. It is the one respect in which the
 * mockup was not telling the truth about the device.
 */
private fun TextStyle.tightBox(): TextStyle = copy(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
)

/**
 * The gap inside the lockup, as a fraction of the mark.
 *
 * A ratio rather than a dp, for the same reason the tracking is one: the lockup
 * is drawn at 40dp on a television and 28 on a short phone, and a fixed 13dp gap
 * that looks right at the first is loose at the second.
 */
private const val LOCKUP_GAP_RATIO = 0.32f

/**
 * The style the screen's name is set in, from the frame's own title step.
 *
 * Every screen was building this expression for itself — the same token, the same
 * leading ratio, the same `letterSpacing = 0.sp` — which is three copies of one
 * decision and a fourth screen away from disagreeing with itself.
 *
 * The tracking is zero and is stated rather than inherited. `headlineMedium`
 * carries a small negative track, which is a sensible Latin display correction and
 * wrong for Arabic at any size: the script joins, and pulling the letters together
 * closes the joins rather than tightening the word.
 *
 * @param fsTitle [com.castivio.core.design.theme.CastivioFrame.fsTitle].
 */
@Composable
@ReadOnlyComposable
fun castivioTitleStyle(fsTitle: Dp): TextStyle = CastivioType.headlineMedium.copy(
    fontSize = fsTitle.value.sp,
    lineHeight = (fsTitle.value * TITLE_LEADING).sp,
    letterSpacing = 0.sp,
)

/**
 * The style a chip's own words are set in, from the frame's chip step.
 *
 * @param fsChip [com.castivio.core.design.theme.CastivioFrame.fsChip].
 */
@Composable
@ReadOnlyComposable
fun castivioChipStyle(fsChip: Dp): TextStyle = CastivioType.bodyMedium.copy(
    fontSize = fsChip.value.sp,
    lineHeight = (fsChip.value * CHIP_LEADING).sp,
    letterSpacing = 0.sp,
)

/** A heading's leading, and a chip's. Ratios, because the sizes step per frame. */
private const val TITLE_LEADING = 1.35f
private const val CHIP_LEADING = 1.45f

/** An icon beside type, as a multiple of that type's size. */
private const val CHIP_ICON = 1.25f

/** The gap inside a chip, as a fraction of its own horizontal padding. */
private const val CHIP_GAP = 0.55f

/**
 * The way back, as the one control in a header that is otherwise a statement.
 *
 * ## Why it is here and not on a screen
 *
 * Six screens need it and four of them had built their own — a different height, a
 * different glyph, a different corner, and on two of them a footer built to hold it
 * that the screen did not otherwise need. The chip is the same kind of thing on all
 * six, so it is one thing.
 *
 * ## Two boxes, because a pill and a target are two different sizes
 *
 * The pill is [chip] tall — 44dp on a television, 34 on the shortest phone — and that
 * is what the approved drawings state and what a reader sees. Around it sits a box at
 * least [touchTarget] tall, and that is what a thumb presses and a remote lands on.
 *
 * Reading those as one number has a wrong answer on each side. Grow the pill and an
 * approved drawing has been rewritten to satisfy a rule about fingers; grow the header
 * row to hold it and three of the four frames lose 2 to 12dp of band, which on the
 * licence screen's tightest case turns a reserved sentence from 5dp short into 17.
 * Neither is necessary: the box overhangs the row by at most 6dp, into a margin that
 * is 8dp at its narrowest, and no ancestor clips it.
 *
 * Everything that says *this is a control* is on the outer box — the click, the role,
 * the label — so a screen reader and a D-pad both address the full target. Everything
 * that says *this is what it looks like* is on the pill.
 *
 * ## The chevron points the way the reader came from
 *
 * Auto-mirrored, so it is a left arrow in English and a right arrow in Arabic. That
 * is the opposite of the chevron on a card, which leads *onward* and therefore
 * follows the reading direction — the two look like the same decision and are not,
 * and drawing both the Latin way is the mistake a right-to-left screen makes most
 * often.
 *
 * ## Round, by ratio
 *
 * `percent = 50` rather than the frame's radius: this is a pill, and a pill's corner
 * is half its height whatever the height is. The frame's `radius` is for the surfaces
 * on the stage, which are rectangles.
 *
 * @param chip [com.castivio.core.design.theme.CastivioFrame.chip] — the drawn pill.
 * @param touchTarget [com.castivio.core.design.theme.CastivioFrame.touchTarget] — the
 *   floor for the interaction box around it.
 * @param label the word. Supplied by the caller because a shared component may not
 *   own copy — this one would otherwise carry a string in thirty-eight languages.
 */
@Composable
fun CastivioBackChip(
    chip: Dp,
    touchTarget: Dp,
    pad: Dp,
    fontSize: Dp,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(percent = 50)

    Box(
        modifier
            .heightIn(min = touchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .height(chip)
                .clip(shape)
                .background(colors.glassFill)
                .border(BorderStroke(1.dp, Palette.EdgeQuiet), shape)
                .padding(horizontal = pad),
            horizontalArrangement = Arrangement.spacedBy(pad * CHIP_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = null,
                tint = colors.onBackgroundVariant,
                modifier = Modifier.size(fontSize * CHIP_ICON),
            )
            Text(
                text = label,
                style = castivioChipStyle(fontSize),
                color = colors.onBackgroundVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * The screen's name: a heading, one line, at whatever size that line allows.
 *
 * It is the one element in the header that yields. The chips are a control and a
 * fact and keep their intrinsic width; the mark is the brand and keeps its size;
 * the title names the screen the reader is already looking at, so of the three it
 * is the one whose full size is least load-bearing — and unlike the other two it
 * varies by a factor of two and a half across the languages Castivio ships.
 *
 * Uniform wherever it fits, which is every language but the longest few, and
 * smaller only where the alternative is a clipped word or a language button
 * squeezed out of the row.
 */
@Composable
fun CastivioHeaderTitle(
    text: String,
    style: TextStyle,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    CastivioFittedText(
        text = text,
        style = style.tightBox(),
        color = color,
        modifier = modifier.semantics { heading() },
    )
}
