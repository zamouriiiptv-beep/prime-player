package com.castivio.core.design.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
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
 * @param height the row's height; the three slots are centred in it.
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
        val loose = constraints.copy(minWidth = 0, maxWidth = total)

        // The two ends take what they need; the title takes what is between them
        // and yields into it. Neither end is ever measured into a remainder,
        // which is the rule that was missing: one of the chips is the language
        // control, and a control squeezed to nothing to make room for a caption
        // is a control the user cannot reach.
        val lock = lockM.first().measure(loose)
        val chip = chipM.first().measure(loose)
        val middle = max(0, total - lock.width - chip.width - gapPx * 2)
        val titleP = titleM.first().measure(constraints.copy(minWidth = 0, maxWidth = middle))

        layout(total, rowH) {
            fun place(p: androidx.compose.ui.layout.Placeable, x: Int) =
                p.place(x, (rowH - p.height) / 2)

            place(lock, 0)
            place(titleP, lock.width + gapPx + (middle - titleP.width) / 2)
            place(chip, total - chip.width)
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
fun CastivioHeaderTitle(text: String, style: TextStyle, color: androidx.compose.ui.graphics.Color) {
    CastivioFittedText(
        text = text,
        style = style.tightBox(),
        color = color,
        modifier = Modifier.semantics { heading() },
    )
}
