package com.castivio.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Midnight = Color(0xFF160A2E)
val AccentBlue = Color(0xFF3D8BFF)
val Crimson = Color(0xFFFF2D55)

/** Translucent fill + hairline border shared by every "glass" card. */
val GlassFill = Color(0x1FFFFFFF)
val GlassBorder = BorderStroke(1.dp, Color(0x33FFFFFF))

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
