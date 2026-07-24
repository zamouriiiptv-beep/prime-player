package com.castivio.tv.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Midnight = Color(0xFF160A2E)
val AccentBlue = Color(0xFF3D8BFF)

private val CastivioColors = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    background = Midnight,
    onBackground = Color.White,
    surface = Midnight,
    onSurface = Color.White,
)

@Composable
fun CastivioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CastivioColors) {
        CastivioBackground {
            content()
        }
    }
}
