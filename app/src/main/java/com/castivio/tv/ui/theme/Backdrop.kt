package com.castivio.tv.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * The Castivio backdrop — the single most recognisable part of the identity.
 *
 * Four layers, drawn in code so it costs nothing to ship and scales to any
 * panel size: a deep navy gradient, two soft aurora glows, a slow mesh of
 * contour waves, and a scattering of drifting motes. Deliberately restrained:
 * every layer sits below 12% opacity so content always wins.
 */
@Composable
fun CastivioBackdrop(content: @Composable () -> Unit) {
    val colors = CastivioTheme.colors
    val transition = rememberInfiniteTransition(label = "backdrop")
    val wave by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            tween(Motion.ambientWave, easing = LinearEasing), RepeatMode.Restart,
        ),
        label = "wave",
    )
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(Motion.ambientDrift, easing = LinearEasing), RepeatMode.Restart,
        ),
        label = "drift",
    )

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.linearGradient(
                    colors = listOf(Palette.Deep, Palette.Violet10, Palette.Azure10),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height),
                )
            )
            glow(Offset(size.width * 0.05f, size.height), size.width * 0.55f, Palette.Violet40, 0.30f)
            glow(Offset(size.width * 0.95f, size.height * 0.30f), size.width * 0.52f, Palette.Azure40, 0.26f)
            mesh(wave, colors.primary)
            motes(drift)
        }
        content()
    }
}

private fun DrawScope.glow(center: Offset, radius: Float, color: Color, alpha: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/** Perspective contour waves across the lower half. */
private fun DrawScope.mesh(phase: Float, color: Color) {
    val horizon = size.height * 0.40f
    val lines = 16
    for (i in 0..lines) {
        val t = i / lines.toFloat()
        val y = horizon + (size.height - horizon) * (t * t)
        val amp = 7f + 44f * t
        val alpha = 0.025f + 0.07f * t
        val path = Path()
        val steps = 64
        for (s in 0..steps) {
            val p = s / steps.toFloat()
            val x = size.width * p
            val yy = y + amp * sin(p * 11f + i * 0.6f + phase)
            if (s == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
        }
        drawPath(path, color = color.copy(alpha = alpha), style = Stroke(width = 1.2f))
    }
}

/** A handful of slow motes that rise, wrap and twinkle. */
private fun DrawScope.motes(drift: Float) {
    val rng = Random(7)
    repeat(13) {
        val baseX = rng.nextFloat()
        val baseY = rng.nextFloat()
        val speed = 0.5f + rng.nextFloat()
        val radius = 1.4f + rng.nextFloat() * 2.2f
        val phase = rng.nextFloat()
        val y = ((baseY - drift * speed) % 1f + 1f) % 1f
        val twinkle = 0.25f + 0.55f * abs(sin((drift + phase) * 2f * PI.toFloat()))
        drawCircle(
            color = Palette.Azure80.copy(alpha = 0.20f * twinkle),
            radius = radius,
            center = Offset(size.width * baseX, size.height * y),
        )
    }
}
