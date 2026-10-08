package com.castivio.core.design.components

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme

/**
 * A card's disc: the one place a card's hue is loud.
 *
 * ## What it is for
 *
 * Four cards on a screen, four hues, and the hue lives here and nowhere else — not in
 * the card's fill, its border or its text. It is what lets a reader tell one option
 * from another from across a room before a word of any of them is read, which is why
 * the source chooser, the saved subscriptions and Home's shelves all draw one.
 *
 * ## Why it is in `:core:design` and not in a feature
 *
 * It was `internal` to `:feature:activation`, which meant the one visual mark that
 * says *which kind of thing this is* could not be drawn by any other module. Home
 * reaches for `discFill`/`discBorder`/`discGlyph` straight out of the theme instead
 * and assembles its own container, and the two are a radial fall-off, three alphas
 * and a glyph fraction apart — one set of decisions with two homes, which is one edit
 * away from being two opinions.
 *
 * ## What it is *not* for
 *
 * A data field. A disc is a card's mark: it is the size of a control and reads as
 * one, and in a row whose business is a value a reader copies out it ends the row in
 * a second circle beside the copy button. A field takes its glyph bare, at the
 * label's own scale — which is what `IdentityCapsule`'s `fieldIcon` draws.
 *
 * @param size the disc's diameter. A card's own metric, not a token: a chooser card's
 *   disc and a shelf tile's are the same object at two scales.
 * @param hue the card's own colour, from the theme's four.
 */
@Composable
fun CastivioDisc(
    size: Dp,
    hue: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val round = RoundedCornerShape(percent = 50)
    Box(
        modifier
            .size(size)
            .clip(round)
            .background(colors.discFill(hue))
            .border(BorderStroke(1.dp, colors.discBorder(hue)), round),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.discGlyph(hue),
            modifier = Modifier.size(size * DISC_ICON),
        )
    }
}

/**
 * A glyph inside its disc, as a fraction of the disc.
 *
 * Public because more than one module draws the same relationship at different sizes
 * and none of them may hold a second opinion about it: 0.5 here and 0.52 there is the
 * kind of difference nobody can point at and everybody sees.
 */
const val DISC_ICON = 0.5f
