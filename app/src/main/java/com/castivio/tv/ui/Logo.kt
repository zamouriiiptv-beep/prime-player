package com.castivio.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Placeholder brand mark drawn in code: a red-to-crimson disc with a white
 * play triangle, echoing the icon inside the QR code. Replace with the real
 * Castivio logo (a drawable/PNG) when the asset is available.
 */
@Composable
fun CastivioLogo(size: Dp = 44.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val d = this.size.minDimension
        drawCircle(
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFFFF2D55), Color(0xFFC01033)),
                start = Offset(0f, 0f),
                end = Offset(d, d),
            ),
            radius = d / 2f,
            center = Offset(d / 2f, d / 2f),
        )
        val play = Path().apply {
            moveTo(d * 0.40f, d * 0.32f)
            lineTo(d * 0.40f, d * 0.68f)
            lineTo(d * 0.70f, d * 0.50f)
            close()
        }
        drawPath(play, color = Color.White)
    }
}
