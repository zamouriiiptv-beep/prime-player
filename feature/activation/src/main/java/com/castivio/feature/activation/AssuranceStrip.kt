package com.castivio.feature.activation

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.castivio.core.design.components.castivioChipStyle
import com.castivio.core.design.theme.CastivioTheme

/**
 * The footnote under a chooser's cards: a few short claims, each a disc and a line.
 *
 * ## One strip, two screens
 *
 * It was the source choice's, declared privately inside that screen. The local-media
 * chooser needs the same thing under its own four cards, and the same thing drawn
 * twice is two things: one of them gets a corner adjusted, or a disc that is 0.5 of
 * the circle here and 0.52 there, and a reader moving between two screens they see
 * one after the other watches the furniture change without being able to say how.
 * So it is one declaration, and what differs between the two screens is the only
 * thing that should — the claims.
 *
 * ## Drawn as a footnote, deliberately
 *
 * One line per cell, no second sentence, no pane and no border. A panel around it
 * would make it a fifth thing to look at beside the four that matter, and it says
 * nothing a reader needs in order to choose.
 *
 * ## How many cells, and why the count is the frame's
 *
 * The cells divide the row equally, so each one's width is the stage over the count.
 * Three of them on the two phone frames leaves 259 and 236dp a cell, and the labels
 * are one line with an ellipsis: at that width a claim does not get smaller, it gets
 * cut, and half a claim is worse than none. Two leaves 397 and 367dp, which holds
 * the longest of the languages Castivio ships.
 *
 * So [SourceMetrics.stripCells] says how many this frame can hold, and a screen
 * hands over its claims in the order they should survive. Dropping the last rather
 * than shrinking all of them is the frame system's rule applied to content: the
 * phone frames already run their type at the floor, and a footnote legible only
 * because it was set smaller than the description above it has stopped being read.
 *
 * @param claims most important first — the tail is what a narrow frame drops.
 */
@Composable
internal fun AssuranceStrip(
    m: SourceMetrics,
    claims: List<StripClaim>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(m.strip)
            .padding(horizontal = m.strip * STRIP_PAD),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (claim in claims.take(m.stripCells)) StripCell(m, claim)
    }
}

/** One claim: the hue that carries it, the glyph, and the line. */
internal data class StripClaim(
    val hue: Color,
    val icon: ImageVector,
    @StringRes val title: Int,
)

@Composable
private fun RowScope.StripCell(m: SourceMetrics, claim: StripClaim) {
    val head = stringResource(claim.title)
    val disc = RoundedCornerShape(percent = 50)
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
                .clip(disc)
                .background(claim.hue.copy(alpha = CELL_FILL))
                .border(BorderStroke(1.dp, claim.hue.copy(alpha = CELL_EDGE)), disc),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = claim.icon,
                contentDescription = null,
                tint = claim.hue,
                modifier = Modifier.size(m.stripDisc * DISC_ICON),
            )
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

/** The strip's own end padding, and a cell's, as fractions of the strip's height. */
private const val STRIP_PAD = 0.22f
private const val CELL_PAD = 0.16f

/** A disc against its words. */
private const val CELL_GAP = 0.18f

/** How much of its hue a disc keeps in its fill and in its edge. */
private const val CELL_FILL = 0.10f
private const val CELL_EDGE = 0.26f
