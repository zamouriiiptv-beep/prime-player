package com.castivio.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.castivio.core.design.components.ButtonWeight
import com.castivio.core.design.components.CastivioButton
import com.castivio.core.design.components.castivioBodyStyle
import com.castivio.core.design.components.castivioChipStyle
import com.castivio.core.design.theme.CastivioTheme

/**
 * Choosing what to hide, as the board's columns see it: what is being chosen and what is ticked, and
 * the things a press can do.
 *
 * Gathered in one value, like [LockUi] and [FavoritesUi]. [hiding] is null outside the mode, and a board
 * that is not choosing draws exactly what it always drew.
 */
@Immutable
internal class HidingUi(
    /** What is being chosen, or null when the board is not choosing. */
    val hiding: Hiding?,
    /** A press on a bouquet while choosing: it is ticked, or put back. */
    val toggleBouquet: (String) -> Unit,
    /** A press on a channel while choosing: it is ticked, or put back. */
    val toggleChannel: (String) -> Unit,
    /** The bar's Hide selected: hide what is ticked and leave the mode. */
    val confirm: () -> Unit,
    /** The bar's Cancel, and Back: leave the mode, hiding nothing. */
    val cancel: () -> Unit,
    /** The strip's Hide button pressed: start choosing. */
    val press: () -> Unit,
    /** The same button held: the same, so a press held a little long is not lost. */
    val hold: () -> Unit,
) {
    val active: Boolean get() = hiding != null

    /** The rail is for ticking bouquets -- for as long as the board is choosing. */
    val bouquets: Boolean get() = active

    /** The list is for ticking channels -- at the same time as the rail is for bouquets. */
    val channels: Boolean get() = active

    /** What a bouquet's entry is told while the board is choosing, or null when it is not. */
    fun bouquetMarkOf(id: String): RowPick? = hiding?.let { RowPick(picked = id in it.bouquets, favorite = false) }

    /** What a channel's row is told while the board is choosing, or null when it is not. */
    fun channelMarkOf(id: String): RowPick? = hiding?.let { RowPick(picked = id in it.channels, favorite = false) }

    companion object {
        /** A board that is not told about hiding: nothing to draw, nothing ticked, nothing to press. */
        val None = HidingUi(
            hiding = null,
            toggleBouquet = {},
            toggleChannel = {},
            confirm = {},
            cancel = {},
            press = {},
            hold = {},
        )
    }
}

/**
 * The band over the board while it is choosing what to hide: what the mode is, what to press, and the
 * two ways out.
 *
 * It is [ReorderBar]'s own shape -- the same band between the header and the columns, the same pair of
 * the system's buttons at its trailing end -- because it does the same job for a different mode: says
 * which mode the board is in, and keeps the way out one press from anywhere. Hide selected is dark until
 * something is ticked, so a press that would change nothing is not offered. Laid out left to right in
 * every language, like the band above it and the columns under it.
 */
@Composable
internal fun HideBar(
    hiding: Hiding,
    m: ChannelsMetrics,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.previewRadius)

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
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.hide_title),
                    style = castivioChipStyle(m.frame.fsLabel),
                    color = colors.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.hide_hint),
                    style = castivioBodyStyle(m.frame.fsBody),
                    color = colors.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CastivioButton(
                text = stringResource(R.string.reorder_cancel),
                onClick = onCancel,
            )
            CastivioButton(
                text = stringResource(R.string.hide_confirm),
                onClick = onConfirm,
                weight = ButtonWeight.Primary,
                enabled = hiding.any,
            )
        }
    }
}
