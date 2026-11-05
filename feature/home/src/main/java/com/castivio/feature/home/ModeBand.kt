package com.castivio.feature.home

import android.view.View
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
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
 * ([actions]) are compact -- `CastivioButton(compact = true)` -- so the band is their height and a hair more.
 *
 * The word is the board's amber (see [AMBER_INK_LIFT]) and not the violet of "Live TV" above it: two words of one colour, one
 * over the other, read as a single label. Drawn first in `design/mockups/channels-arrange-bar-ar.html` and
 * `design/mockups/channels-hide-bar-ar.html`.
 *
 * **In Arabic the band follows the reader's direction** ([modeBandFollowsReader]): the word is at the right end, its sentence runs from
 * it toward the left, and the two buttons are at the left end -- the end of the line. In every other language the band is as it was,
 * left to right like the band above it and the columns under it, the word at the left and the buttons at the right: that is the
 * owner's decision, drawn in `design/mockups/channels-mode-bar-fix-ar.html`.
 *
 * **The sentence is a paragraph in its own language's direction.** It was left to the band's, and an Arabic sentence with a Latin
 * word in it ("press OK") set as a left-to-right paragraph is laid out run by run from the left, so that "press" stood at one
 * end and "OK" at the other and the sentence read backwards. A right-to-left language in a band that is left to right -- Hebrew,
 * Persian -- sets the sentence right to left and against the end beside the word.
 *
 * The buttons are `compact`: 34dp under a thumb, so the band is 40dp and not 54.
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

    val configuration = LocalConfiguration.current
    val language = ConfigurationCompat.getLocales(configuration).get(0)?.language.orEmpty()
    val follows = modeBandFollowsReader(language)
    val rtlLanguage = configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    // The sentence is a paragraph in its language's direction. Beside the word it starts at the band's own start; a right-to-left
    // sentence in a band that is left to right is set against its end, which is the side the word is on.
    val hintStyle = castivioBodyStyle(m.frame.fsBody).copy(
        textDirection = searchTextDirection(rtlLanguage),
        textAlign = if (rtlLanguage && !follows) TextAlign.End else TextAlign.Start,
    )

    CompositionLocalProvider(LocalLayoutDirection provides (if (follows) LayoutDirection.Rtl else LayoutDirection.Ltr)) {
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
                    style = hintStyle,
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
