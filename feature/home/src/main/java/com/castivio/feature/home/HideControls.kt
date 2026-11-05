package com.castivio.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.castivio.core.design.components.ButtonWeight
import com.castivio.core.design.components.CastivioButton
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
 * The band over the board while it is choosing what to hide: the word, one line saying what to do and that nothing is lost, and
 * Cancel and Hide selected -- all of it [ModeBand]'s, which the reorder mode's band is too.
 *
 * Hide selected is dark until something is ticked, so a press that would change nothing is not offered. The hint does not say
 * to press it: the button is beside the sentence, and a sentence that says what the eye is already on is a sentence cut with an
 * ellipsis for nothing.
 */
@Composable
internal fun HideBar(
    hiding: Hiding,
    m: ChannelsMetrics,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModeBand(
        label = stringResource(R.string.hide_title),
        hint = stringResource(R.string.hide_hint),
        hintInk = CastivioTheme.colors.onBackground,
        m = m,
        modifier = modifier,
    ) {
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
