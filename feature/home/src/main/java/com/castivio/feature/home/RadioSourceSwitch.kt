package com.castivio.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.castivio.core.design.components.castivioChipStyle
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Radius

/**
 * The radio's two sources, one press apart, at the head of the rail: the subscription's own stations, or the world's directory.
 *
 * Two segments of one pill, the open one filled -- the board's own way of saying which list is open -- each as tall as the rail's field, so the
 * pill is a little taller than the field under it ([FIELD_OF_ROW], [SEGMENT_GAP]) and no taller than the rail's other rows. The shell holds which is open, because the sound belongs to the source: opening the other lets the first go.
 */
@Composable
internal fun RadioSourceSwitch(
    source: RadioSource,
    onChoose: (RadioSource) -> Unit,
    m: ChannelsMetrics,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(Radius.pill)
    val label = stringResource(R.string.radio_source_label)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, colors.glassBorderSoft, shape)
            .padding(SEGMENT_GAP)
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(SEGMENT_GAP),
    ) {
        RadioSource.entries.forEach { entry ->
            val chosen = entry == source
            val (focused, focusModifier) = rememberFocusFlag()
            Box(
                Modifier
                    .weight(1f)
                    // As tall as the rail's own field under it, so the pill that holds both is a little taller than the field and
                    // not a control of its own size: the rows of this rail are this tall, and so is what it is pressed with.
                    .height(m.railMin * FIELD_OF_ROW)
                    .clip(shape)
                    .background(if (chosen) colors.secondary else Color.Transparent)
                    .border(1.dp, if (focused) colors.focusRing else Color.Transparent, shape)
                    .then(focusModifier)
                    .semantics { selected = chosen }
                    .clickable(role = Role.Tab, onClick = { if (!chosen) onChoose(entry) })
                    .padding(horizontal = m.railPadH),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(
                        when (entry) {
                            RadioSource.Subscription -> R.string.radio_source_subscription
                            RadioSource.World -> R.string.radio_source_world
                        },
                    ),
                    style = castivioChipStyle(m.frame.fsLabel),
                    color = if (chosen) colors.onSecondary else colors.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** What stands between the pill and its two halves, and between them: the pill is this much taller than the field beside it, twice over. */
private val SEGMENT_GAP = 3.dp
