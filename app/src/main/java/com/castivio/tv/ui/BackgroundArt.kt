package com.castivio.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.sin

/**
 * Layered decorative backdrop drawn entirely in code: a diagonal base
 * gradient, two soft colour glows (magenta bottom-left, blue right), and a
 * perspective grid of contour lines that evokes a topographic landscape —
 * matching the polished IPTV welcome-screen look.
 */
@Composable
fun CastivioBackground(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF160A2E),
                        Color(0xFF1A0E3C),
                        Color(0xFF0A1B38),
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height),
                )
            )
            glow(
                center = Offset(size.width * 0.06f, size.height * 0.98f),
                radius = size.width * 0.52f,
                color = Color(0xFFE0246B),
                maxAlpha = 0.38f,
            )
            glow(
                center = Offset(size.width * 0.9f, size.height * 0.32f),
                radius = size.width * 0.5f,
                color = Color(0xFF2E7BFF),
                maxAlpha = 0.30f,
            )
            glow(
                center = Offset(size.width * 0.5f, size.height * 1.05f),
                radius = size.width * 0.45f,
                color = Color(0xFF1B4DA6),
                maxAlpha = 0.25f,
            )
            drawContours()
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

/** Perspective wireframe of flowing contour lines in the lower half. */
private fun DrawScope.drawContours() {
    val horizon = size.height * 0.42f
    val lines = 18
    for (i in 0..lines) {
        val t = i / lines.toFloat()
        // Quadratic spacing => lines bunch near the horizon, spread near the bottom.
        val y = horizon + (size.height - horizon) * (t * t)
        val amp = 8f + 46f * t
        val alpha = 0.05f + 0.10f * t
        val path = Path()
        val steps = 72
        for (s in 0..steps) {
            val p = s / steps.toFloat()
            val x = size.width * p
            val yy = y + amp * sin(p * 12.566f + i * 0.7f)
            if (s == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
        }
        drawPath(
            path = path,
            color = Color(0xFF5AA0FF).copy(alpha = alpha),
            style = Stroke(width = 1.4f),
        )
    }
}
