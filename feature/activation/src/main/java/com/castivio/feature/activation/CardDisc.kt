package com.castivio.feature.activation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * A chooser card's disc: the one place a card's hue is loud.
 *
 * ## What it is for
 *
 * Four cards on a screen, four hues, and the hue lives here and nowhere else — not
 * in the card's fill, its border or its text. It is what lets a reader tell one
 * option from another from across a room before a word of any of them is read, which
 * is the whole reason the local-media chooser and the source choice both draw one.
 *
 * ## Why it is its own file
 *
 * It was private inside `SourceChoiceScreen`, and the local-media chooser drew a bare
 * 32dp glyph instead — no disc, no hue, no glow. The two screens are the same card
 * with different contents, and they did not look like it: on a device the media
 * chooser read as four grey rectangles with the text pushed to the top.
 *
 * Copying the disc across would have made that a different kind of problem rather
 * than fixing it. The radial fall-off, the three alphas and the glyph's share of the
 * circle are one set of decisions, and a second copy of them is one edit away from
 * being a second opinion.
 */
@Composable
internal fun Disc(
    m: SourceMetrics,
    hue: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    val round = RoundedCornerShape(percent = 50)
    Box(
        modifier
            .size(m.disc)
            .clip(round)
            .background(Brush.radialGradient(listOf(hue.copy(alpha = DISC_TOP), hue.copy(alpha = DISC_FOOT))))
            .border(BorderStroke(1.dp, hue.copy(alpha = DISC_EDGE)), round),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = hue,
            modifier = Modifier.size(m.disc * DISC_ICON),
        )
    }
}

/**
 * How much of its hue the disc keeps: at the light, at the foot, and in its edge.
 *
 * The gradient is a fall-off rather than a flat wash because a flat circle of colour
 * reads as a swatch and a lit one reads as an object — and on a television, where
 * the disc is 72dp and seen from three metres, that is the difference between four
 * icons and four buttons.
 */
private const val DISC_TOP = 0.34f
private const val DISC_FOOT = 0.08f
private const val DISC_EDGE = 0.46f

/**
 * A glyph inside its disc, as a fraction of the disc.
 *
 * `internal` because the assurance strip draws the same relationship at a fifth of
 * the size and must not hold a second opinion about it: 0.5 here and 0.52 there is
 * the kind of difference nobody can point at and everybody sees.
 */
internal const val DISC_ICON = 0.5f
