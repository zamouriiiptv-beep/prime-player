package com.castivio.tv.ui

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
 * Premium animated backdrop drawn entirely in code:
 *  - a deep blue/purple diagonal gradient,
 *  - two soft colour glows (magenta bottom-left, blue right),
 *  - an animated mesh of flowing contour waves in the lower half,
 *  - very subtle drifting particles.
 *
 * Everything is redrawn from animation state, so no assets are required and it
 * scales cleanly from a phone in landscape up to a large TV screen.
 */
@Composable
fun CastivioBackground(content: @Composable () -> Unit) {
    val transition = rememberInfiniteTransition(label = "bg")
    val wavePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(11000, easing = LinearEasing), RepeatMode.Restart),
        label = "wave",
    )
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(26000, easing = LinearEasing), RepeatMode.Restart),
        label = "drift",
    )

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF150A2C), Color(0xFF190E3B), Color(0xFF091A36)),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height),
                )
            )
            glow(Offset(size.width * 0.05f, size.height * 1.0f), size.width * 0.55f, Color(0xFFE0246B), 0.34f)
            glow(Offset(size.width * 0.95f, size.height * 0.30f), size.width * 0.52f, Color(0xFF2E7BFF), 0.30f)
            glow(Offset(size.width * 0.55f, size.height * 1.08f), size.width * 0.5f, Color(0xFF1B4DA6), 0.24f)
            drawMesh(wavePhase)
            drawParticles(drift)
        }
        content()
    }
}

private fun DrawScope.glow(center: Offset, radius: Float, color: Color, maxAlpha: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = maxAlpha), color.copy(alpha = 0f)),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/** Flowing contour waves with perspective spacing; [phase] scrolls them. */
private fun DrawScope.drawMesh(phase: Float) {
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
        drawPath(path, color = Color(0xFF5AA0FF).copy(alpha = alpha), style = Stroke(width = 1.2f))
    }
}

/** A few slow-drifting motes that rise and wrap, twinkling gently. */
private fun DrawScope.drawParticles(drift: Float) {
    val count = 13
    val rng = Random(7)
    for (i in 0 until count) {
        val baseX = rng.nextFloat()
        val baseY = rng.nextFloat()
        val speed = 0.5f + rng.nextFloat()
        val radius = 1.4f + rng.nextFloat() * 2.2f
        val phase = rng.nextFloat()
        val y = ((baseY - drift * speed) % 1f + 1f) % 1f
        val twinkle = 0.25f + 0.55f * abs(sin((drift + phase) * 2f * PI.toFloat()))
        drawCircle(
            color = Color(0xFFBFD8FF).copy(alpha = 0.20f * twinkle),
            radius = radius,
            center = Offset(size.width * baseX, size.height * y),
        )
    }
}
