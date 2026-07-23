package com.castivio.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val Midnight = Color(0xFF0B0620)
val DeepPurple = Color(0xFF2A1258)
val OceanBlue = Color(0xFF0E3A5F)
val AccentBlue = Color(0xFF3D8BFF)
val CardSurface = Color(0x33FFFFFF)

private val CastivioColors = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    background = Midnight,
    onBackground = Color.White,
    surface = DeepPurple,
    onSurface = Color.White,
)

@Composable
fun CastivioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CastivioColors) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(Midnight, DeepPurple, OceanBlue),
                        start = Offset(0f, 0f),
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                    )
                )
        ) {
            content()
        }
    }
}
