package com.castivio.core.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Spacing

/**
 * The bar a screen's last line sits in.
 *
 * ## Why this is a container and not a footer
 *
 * Because the four things at the four screens' feet are not the same object. The
 * activation screen's is a legal sentence; Home's is a strip of device facts; the
 * licence screen's is a link somebody presses; the chooser's is a line about the
 * screen itself. Folding them into one component would have had to fold their
 * contents too, and that is a change to what each screen *says* rather than to how
 * Castivio looks.
 *
 * So this is the surface and the edge, and each screen keeps its own words. What was
 * actually different between them — a bar on one screen and bare type on the next, so
 * that two screens a user moves between in a second had different silhouettes — is
 * what this settles.
 *
 * ## The three heights
 *
 * [FooterLevel] is about how much room a screen can spare, not about how important
 * its sentence is. A screen whose band is already tight takes [FooterLevel.Minimal]
 * and still reads as the same product; the chooser is the case this was written for,
 * where a full bar costs 48dp of a 390dp band and 15% of the card a user came to
 * press.
 */
enum class FooterLevel {
    /** A bar with room for a mark beside the words. Activation, Channels, Home. */
    Full,

    /** The same bar, at about half the height and without the mark. */
    Compact,

    /** A line with an edge: the least a screen can spend and still wear the language. */
    Minimal,
}

/**
 * @param level how much height the screen can spare; see [FooterLevel].
 * @param height the bar's own height, which a screen derives from its frame. The
 *   level sets the padding and the mark, not the height — a television's full bar and
 *   a phone's are the same level at two sizes.
 * @param corner the bar's radius, from the screen's own frame radius.
 * @param mark an optional glyph at the leading edge. Drawn only at [FooterLevel.Full];
 *   at the smaller levels a disc is the bar.
 */
@Composable
fun CastivioFooterBar(
    height: Dp,
    corner: Dp,
    modifier: Modifier = Modifier,
    level: FooterLevel = FooterLevel.Full,
    mark: ImageVector? = null,
    content: @Composable () -> Unit,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(corner)
    val pad = when (level) {
        FooterLevel.Full -> Spacing.lg
        FooterLevel.Compact -> Spacing.md
        FooterLevel.Minimal -> Spacing.sm
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(colors.glassFill)
            .border(BorderStroke(1.dp, colors.edgeQuiet), shape)
            .padding(horizontal = pad),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mark != null && level == FooterLevel.Full) {
            Icon(
                imageVector = mark,
                contentDescription = null,
                tint = colors.onBackgroundMuted,
                modifier = Modifier.size(height * MARK_OF_BAR),
            )
        }
        content()
    }
}

/**
 * The mark's share of the bar it sits in.
 *
 * A share rather than a size, because the bar is a share of the frame and a glyph
 * that stayed at one dp while its container grew would be a different drawing on a
 * television than on a phone.
 */
private const val MARK_OF_BAR = 0.63f
