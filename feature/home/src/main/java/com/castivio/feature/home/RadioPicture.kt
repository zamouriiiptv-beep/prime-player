package com.castivio.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.castivio.core.design.theme.CastivioTheme

/**
 * The picture that says radio, where the channels' board has its preview: a set with its waves on the board's own ground.
 *
 * A station has no picture to preview -- a panel sends a name, a logo, a number and a category -- and nothing is drawn to stand in for
 * one, so this is not the station's: it is the section's, the same whichever station sounds. The badge that says it is live is drawn
 * only while something sounds, where the preview draws its own. Drawn first in `design/mockups/radio-list-ar.html`.
 */
@Composable
internal fun RadioPicture(live: Boolean, m: ChannelsMetrics, modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.previewRadius)
    Box(
        modifier
            .clip(shape)
            // The ground the preview's plate stands on while a channel has no picture: the page's own two grounds, top to bottom.
            .background(Brush.verticalGradient(listOf(colors.secondaryContainer, colors.backgroundElevated))),
        contentAlignment = Alignment.Center,
    ) {
        val body = colors.secondary
        val azure = colors.hueAzure
        val violet = colors.hueViolet
        val fill = colors.backgroundElevated
        Canvas(Modifier.fillMaxSize().padding(m.wellPad)) { drawSet(body, azure, violet, fill) }
        if (live) {
            Box(Modifier.align(Alignment.BottomStart).padding(m.osdPad)) { LiveBadge(m) }
        }
    }
}

/** The set, drawn in a 150 by 120 box scaled to fit the canvas and centred in it: the body, the speaker, the two knobs, the waves. */
private fun DrawScope.drawSet(body: Color, azure: Color, violet: Color, fill: Color) {
    val scale = minOf(size.width / BOX_W, size.height / BOX_H)
    val dx = (size.width - BOX_W * scale) / 2f
    val dy = (size.height - BOX_H * scale) / 2f
    fun at(x: Float, y: Float) = Offset(dx + x * scale, dy + y * scale)
    val thick = Stroke(width = LINE * scale, cap = StrokeCap.Round)
    val thin = Stroke(width = FINE * scale, cap = StrokeCap.Round)

    // The waves: two arcs a side, azure at the start and violet at the end, the way the board's two hues stand.
    fun wave(startX: Float, startY: Float, c1x: Float, c1y: Float, c2x: Float, c2y: Float, endX: Float, endY: Float, ink: Color) {
        val path = Path().apply {
            moveTo(at(startX, startY).x, at(startX, startY).y)
            cubicTo(at(c1x, c1y).x, at(c1x, c1y).y, at(c2x, c2y).x, at(c2x, c2y).y, at(endX, endY).x, at(endX, endY).y)
        }
        drawPath(path, ink.copy(alpha = WAVE_ALPHA), style = thick)
    }
    wave(22f, 52f, 14f, 58f, 14f, 74f, 22f, 80f, azure)
    wave(12f, 44f, -2f, 55f, -2f, 81f, 12f, 92f, azure)
    wave(128f, 52f, 136f, 58f, 136f, 74f, 128f, 80f, violet)
    wave(138f, 44f, 152f, 55f, 152f, 81f, 138f, 92f, violet)

    // The aerial, then the body with the speaker on its leading side and two knobs and a dial on the other.
    drawLine(Color.White.copy(alpha = AERIAL_ALPHA), at(44f, 36f), at(94f, 14f), strokeWidth = FINE * scale, cap = StrokeCap.Round)
    drawRoundRect(fill, at(34f, 34f), Size(82f * scale, 62f * scale), CornerRadius(12f * scale))
    drawRoundRect(body, at(34f, 34f), Size(82f * scale, 62f * scale), CornerRadius(12f * scale), style = thick)
    drawCircle(azure, radius = 17f * scale, center = at(60f, 65f), style = thick)
    for (y in floatArrayOf(58f, 65f, 72f)) drawLine(azure, at(52f, y), at(68f, y), strokeWidth = SPEAKER * scale, cap = StrokeCap.Round)
    drawCircle(violet, radius = 5f * scale, center = at(98f, 52f))
    drawCircle(azure, radius = 5f * scale, center = at(98f, 75f))
    drawLine(body, at(86f, 63f), at(110f, 63f), strokeWidth = FINE * scale, cap = StrokeCap.Round)
}

private const val BOX_W = 150f
private const val BOX_H = 120f
private const val LINE = 3.5f
private const val FINE = 3f
private const val SPEAKER = 2.4f
private const val WAVE_ALPHA = 0.9f
private const val AERIAL_ALPHA = 0.8f
