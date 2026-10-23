package com.castivio.feature.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * A press, and -- where there is something for it to do -- a press held: a thumb's long press, and on
 * a remote OK held until the key repeats, because a remote has no long press of its own.
 *
 * Held, it is not also a press when the finger or the key comes up. Without [onHold] this is exactly a
 * `clickable` with no indication, which is what every row and button here was, so a surface that has
 * nothing to do with a hold is not changed by being asked through this.
 *
 * The first repeat of a held select key is the long press, and the repeats after it are taken so they are
 * nothing else. It is said once, and what it is told to do should be idempotent besides: a toolkit that
 * also turns a held key into a long click then says it twice, to no harm.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.pressOrHold(
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
    onHold: (() -> Unit)?,
    enabled: Boolean = true,
): Modifier {
    // Whether the key held on this surface has already made its long press, so that the same key coming
    // up is not a press as well.
    val keyHeld = remember { mutableStateOf(false) }
    if (onHold == null) {
        return this.clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
    }
    return this
        .onPreviewKeyEvent { event ->
            val select = event.key == Key.DirectionCenter || event.key == Key.Enter ||
                event.key == Key.NumPadEnter
            when {
                !enabled || !select -> false
                event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount > 0 -> {
                    if (!keyHeld.value) {
                        keyHeld.value = true
                        onHold()
                    }
                    true
                }
                // The release that ends a long press is not a press.
                event.type == KeyEventType.KeyUp && keyHeld.value -> {
                    keyHeld.value = false
                    true
                }
                else -> false
            }
        }
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick,
            onLongClick = onHold,
        )
}
