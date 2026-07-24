package com.castivio.tv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import com.castivio.tv.ui.theme.Motion

/**
 * Focus and press behaviour — the heart of a 10-foot interface.
 *
 * On a television the user cannot point; they can only move a highlight. Every
 * interactive surface in Castivio therefore answers three questions at a
 * glance: *where am I*, *can I press this*, and *did my press register*.
 * The answer is always the same language — a slight lift, a brighter edge,
 * and a coloured glow — so the vocabulary is learned once.
 */

/** Tracks focus state for a component, exposing it as a plain [State]. */
@Composable
fun rememberIsFocused(): Pair<State<Boolean>, Modifier> {
    var focused by remember { mutableStateOf(false) }
    val state = remember { mutableStateOf(false) }
    state.value = focused
    val modifier = Modifier.onFocusChanged { focused = it.isFocused || it.hasFocus }
    return state to modifier
}

/**
 * Scales an element up when focused and down while pressed.
 *
 * @param focusedScale target scale on focus; use the [Motion] constants so the
 *   whole app lifts by the same amount.
 */
fun Modifier.castivioFocusScale(
    focusedScale: Float = Motion.focusScaleCard,
    interactionSource: MutableInteractionSource? = null,
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val pressed = interactionSource?.collectIsPressedAsState()?.value ?: false
    val target = when {
        pressed -> Motion.pressScale
        focused -> focusedScale
        else -> 1f
    }
    val scale by animateFloatAsState(target, Motion.focusSpec(), label = "focusScale")
    this
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Convenience alias kept short because it appears on nearly every component. */
fun Modifier.focusLift(focusedScale: Float = Motion.focusScaleCard): Modifier =
    castivioFocusScale(focusedScale)
