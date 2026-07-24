package com.castivio.core.design.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.castivio.core.design.theme.Motion

/**
 * Reusable motion primitives.
 *
 * These wrap the raw specs in [Motion] so screens express intent ("this
 * section enters second") instead of hand-rolling animation state.
 */

/**
 * Fades content in while it rises into place.
 *
 * @param order position in the stagger sequence — 0 enters first, 1 follows
 *   [Motion.staggerStep] later, and so on. Keep sequences to four or fewer;
 *   past that the last item feels late.
 */
@Composable
fun Entrance(
    order: Int = 0,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var appear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appear = true }
    val progress by animateFloatAsState(
        targetValue = if (appear) 1f else 0f,
        animationSpec = Motion.enterSpec(delayMillis = order * Motion.staggerStep),
        label = "entrance",
    )
    val density = LocalDensity.current
    Box(
        modifier = modifier.graphicsLayer {
            alpha = progress
            translationY = with(density) { (1f - progress) * Motion.enterOffset.toPx() }
        },
    ) { content() }
}

/**
 * Ambient vertical float. Reserved for a single hero element per screen —
 * more than one and the interface looks unstable.
 */
fun Modifier.ambientFloat(
    travel: Dp = Motion.floatTravel,
    durationMillis: Int = Motion.ambientFloat,
): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "float")
    val offset by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis, easing = Motion.gentle), RepeatMode.Reverse,
        ),
        label = "floatOffset",
    )
    val density = LocalDensity.current
    graphicsLayer { translationY = with(density) { travel.toPx() } * offset }
}
