package com.castivio.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.castivio.core.design.components.castivioBodyStyle
import com.castivio.core.design.components.castivioChipStyle
import com.castivio.core.design.theme.CastivioTheme

/**
 * The band over the board while it is in a mode of its own -- reordering, choosing what to hide: the word that names the mode,
 * one line of hint, and the two ways out, on one row.
 *
 * One band for the modes, because a mode that looked different from the one beside it would be one more thing to learn.
 * Both used to stack their sentences -- five lines for the reorder band, three for the hide band -- over a board whose list had
 * room for a few more channels. Now each says one thing and cuts it with an ellipsis rather than wrapping it. The buttons
 * ([actions]) keep the size they have: a touch target does not shrink to make a band shorter, so the band is their height and a
 * hair more.
 *
 * The word is the board's amber (see [AMBER_INK_LIFT]) and not the violet of "Live TV" above it: two words of one colour, one
 * over the other, read as a single label. Drawn first in `design/mockups/channels-arrange-bar-ar.html` and
 * `design/mockups/channels-hide-bar-ar.html`.
 *
 * **Laid out left to right in every language, like the band above it and the columns under it.** It sits between the two, and
 * it used to follow the reader's direction while they did not: in Arabic the buttons went to the far left, over the bouquets,
 * and the hint to the right, over the picture -- the one strip of the board that moved when the language did. Now nothing
 * moves; the hint is shaped by its own script inside its cell, as every other line on the board is, and the buttons stay at
 * the trailing end, over the list and the picture they act on.
 *
 * @param hintInk the hint's colour: white, and a different one only for what the viewer has to notice -- a failure.
 */
@Composable
internal fun ModeBand(
    label: String,
    hint: String,
    hintInk: Color,
    m: ChannelsMetrics,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.previewRadius)
    val labelInk = lerp(colors.hueAmber, colors.onBackgroundStrong, AMBER_INK_LIFT)

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier
                .fillMaxWidth()
                .padding(bottom = m.headerGap)
                .clip(shape)
                .background(colors.glassFill)
                .border(1.dp, colors.selectedBorder, shape)
                .padding(horizontal = m.controlPadH, vertical = m.badgePadV * 2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.factGap),
        ) {
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(m.factGap),
            ) {
                Text(
                    text = label,
                    style = castivioChipStyle(m.frame.fsLabel),
                    color = labelInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(Modifier.width(1.dp).height(m.frame.fsBody * HINT_RULE_OF_BODY).background(colors.glassBorder))
                Text(
                    text = hint,
                    style = castivioBodyStyle(m.frame.fsBody),
                    color = hintInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            actions()
        }
    }
}

/** The rule between the band's word and its hint, as a share of the hint's type size: a little taller than a line of it. */
private const val HINT_RULE_OF_BODY = 1.3f
