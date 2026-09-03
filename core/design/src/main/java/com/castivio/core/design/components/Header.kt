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
import androidx.compose.ui.text.TextStyle
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
import kotlin.math.min

/**
 * The screen header: a title, the lockup, and a row of chips.
 *
 * ## One layout, two facts
 *
 * The mark has to sit at the row's true centre, and the space either side of it
 * has to be the same. Those are two different claims and the obvious composition
 * — a `Row` with the title, a spacer, the chips, and the mark drawn over the top
 * at the centre — delivers only the first. The gaps then fall out of two
 * translated strings' lengths: at the top of a television that was 72dp on one
 * side and 7 on the other, and it changed with the language.
 *
 * So the row is measured as three columns instead:
 *
 *     [ side ][ gap ][ mark ][ gap ][ side ]
 *
 * The two side columns are given the same width, which puts the middle column —
 * and the mark in it — at the exact centre. The title is placed at the **end** of
 * the leading column and the chips at the **start** of the trailing one, so both
 * of their inner edges land on a column boundary and the space either side of the
 * mark is the gap itself, the same number, whatever the strings do.
 *
 * When one side's content will not fit its share, that column takes what it needs
 * and the other takes the rest — the same yielding CSS grid does for `1fr` — so
 * a long title crowds the mark off centre rather than being clipped. The gaps
 * survive that; the centring is what gives, because a clipped title is a defect
 * and a mark 20dp off centre is a compromise.
 *
 * ## Direction
 *
 * Nothing here is left or right. The columns are leading-to-trailing and the
 * placement mirrors with [LayoutDirection], so Arabic reads title-mark-chips from
 * the right and English reads it from the left with the same numbers.
 *
 * @param height the row's height; the three slots are centred in it.
 * @param gap the one spacing value, and the space that will appear either side
 *   of the mark.
 */
@Composable
fun CastivioHeader(
    height: Dp,
    gap: Dp,
    title: @Composable () -> Unit,
    lockup: @Composable () -> Unit,
    chips: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        contents = listOf(title, lockup, chips),
        modifier = modifier.height(height),
    ) { (titleM, lockM, chipM), constraints ->
        val gapPx = gap.roundToPx()
        val total = constraints.maxWidth
        val rowH = constraints.maxHeight

        val lock = lockM.first().measure(constraints.copy(minWidth = 0, maxWidth = total))
        val available = max(0, total - lock.width - gapPx * 2)
        val share = available / 2

        val titleWanted = titleM.first().maxIntrinsicWidth(rowH)
        val chipsWanted = chipM.first().maxIntrinsicWidth(rowH)

        // `1fr` floors at its content: the column that needs more than its share
        // takes it, and the other keeps the remainder.
        var lead = share
        var trail = share
        if (chipsWanted > share) {
            trail = min(chipsWanted, available)
            lead = available - trail
        } else if (titleWanted > share) {
            lead = min(titleWanted, available)
            trail = available - lead
        }

        val titleP = titleM.first().measure(constraints.copy(minWidth = 0, maxWidth = max(0, lead)))
        val chipsP = chipM.first().measure(constraints.copy(minWidth = 0, maxWidth = max(0, trail)))

        layout(total, rowH) {
            fun place(p: androidx.compose.ui.layout.Placeable, leadingEdge: Int) {
                val x = if (layoutDirection == LayoutDirection.Ltr) {
                    leadingEdge
                } else {
                    total - leadingEdge - p.width
                }
                p.place(x, (rowH - p.height) / 2)
            }
            // The title ends where the leading column ends.
            place(titleP, lead - titleP.width)
            place(lock, lead + gapPx)
            // The chips begin where the trailing column begins.
            place(chipsP, lead + gapPx + lock.width + gapPx)
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
        ),
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
 * The gap inside the lockup, as a fraction of the mark.
 *
 * A ratio rather than a dp, for the same reason the tracking is one: the lockup
 * is drawn at 40dp on a television and 28 on a short phone, and a fixed 13dp gap
 * that looks right at the first is loose at the second.
 */
private const val LOCKUP_GAP_RATIO = 0.32f

/** A heading, for a reader that navigates by them. */
@Composable
fun CastivioHeaderTitle(text: String, style: TextStyle, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        modifier = Modifier.semantics { heading() },
    )
}
