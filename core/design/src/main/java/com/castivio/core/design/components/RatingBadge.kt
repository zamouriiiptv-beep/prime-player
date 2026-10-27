package com.castivio.core.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Spacing

/**
 * A film's rating as a badge: IMDb's gold mark with its name, the figure, and a star, all ringed in the
 * same gold. The one place in the product where gold is the colour, because gold is what a rating is.
 *
 * [rating] arrives formatted -- `8.5` -- so the caller decides the digits; [labelStyle] sets the size and
 * [iconSize] the star's, so the badge scales with the page's own type.
 */
@Composable
fun RatingBadge(
    rating: String,
    labelStyle: TextStyle,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    padH: Dp = Spacing.md,
    padV: Dp = Spacing.xs,
) {
    val shape = RoundedCornerShape(Radius.pill)
    Row(
        modifier
            .clip(shape)
            .background(RatingGold.copy(alpha = GROUND))
            .border(RING, RatingGold, shape)
            .padding(horizontal = padH, vertical = padV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = "IMDb",
            style = labelStyle.copy(fontWeight = FontWeight.Black),
            color = RatingInk,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(MARK_RADIUS))
                .background(RatingGold)
                .padding(horizontal = Spacing.xs, vertical = 1.dp),
        )
        Text(
            text = rating,
            style = labelStyle.copy(fontWeight = FontWeight.Bold),
            color = CastivioTheme.colors.onBackground,
            maxLines = 1,
        )
        Icon(Icons.Rounded.Star, contentDescription = null, tint = RatingGold, modifier = Modifier.size(iconSize))
    }
}

/**
 * A rating as a fact in a row of facts: the gold star and the figure, with no badge round them.
 *
 * The badge is for a page that has the room to give the rating a place of its own; a row that carries a year, a genre
 * and a season count carries the rating the same way -- a mark and a word -- so it reads as one of them. [iconSize] is
 * the star's, so it scales with the row's own type.
 */
@Composable
fun RatingStar(
    rating: String,
    labelStyle: TextStyle,
    iconSize: Dp,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Icon(Icons.Rounded.Star, contentDescription = null, tint = RatingGold, modifier = Modifier.size(iconSize))
        Text(text = rating, style = labelStyle, color = CastivioTheme.colors.onBackgroundVariant, maxLines = 1)
    }
}

/** IMDb's gold. */
private val RatingGold = Color(0xFFF5C518)

/** The ink on the gold mark: near black, as a rating's mark is. */
private val RatingInk = Color(0xFF0B0B14)

/** How much of the gold tints the badge's ground: enough to read as gold, not enough to compete with it. */
private const val GROUND = 0.12f

private val RING = 1.5.dp
private val MARK_RADIUS = 4.dp
