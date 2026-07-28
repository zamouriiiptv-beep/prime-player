package com.castivio.core.design.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The Castivio design system entry point.
 *
 * Wrap the app once; every screen then reads tokens through `CastivioTheme`:
 *
 * ```
 * CastivioTheme {
 *     Text("Live", color = CastivioTheme.colors.onBackground,
 *          style = CastivioTheme.type.titleLarge)
 * }
 * ```
 *
 * Material 3 is configured underneath from the same tokens, so any stock M3
 * component dropped into a screen already looks like Castivio.
 */
object CastivioTheme {
    val colors: CastivioColors
        @Composable @ReadOnlyComposable get() = LocalCastivioColors.current

    val type: CastivioType
        @Composable @ReadOnlyComposable get() = CastivioType

    val spacing: Spacing
        @Composable @ReadOnlyComposable get() = Spacing

    val radius: Radius
        @Composable @ReadOnlyComposable get() = Radius

    val shapes: CastivioShapes
        @Composable @ReadOnlyComposable get() = CastivioShapes

    val elevation: Elevation
        @Composable @ReadOnlyComposable get() = Elevation

    val motion: Motion
        @Composable @ReadOnlyComposable get() = Motion

    /** How much of the interface is allowed to move, right now. */
    val motionLevel: MotionLevel
        @Composable @ReadOnlyComposable get() = LocalMotionLevel.current

    /** The screen class this composition is running on. */
    val device: DeviceClass
        @Composable @ReadOnlyComposable get() = LocalDeviceClass.current
}

val LocalCastivioColors: ProvidableCompositionLocal<CastivioColors> =
    staticCompositionLocalOf { castivioDarkColors() }

val LocalDeviceClass: ProvidableCompositionLocal<DeviceClass> =
    staticCompositionLocalOf { DeviceClass.Medium }

/**
 * The motion level in force. Defaults to [MotionLevel.REDUCED] — an unmeasured
 * composition gets calm rather than either extreme, matching the leanest default
 * for [LocalPerformanceProfile].
 */
val LocalMotionLevel: ProvidableCompositionLocal<MotionLevel> =
    staticCompositionLocalOf { MotionLevel.REDUCED }

/**
 * Applies the Castivio design system.
 *
 * @param withBackground draws the signature animated backdrop behind [content].
 *   Set false for screens that supply their own canvas (e.g. the video player).
 */
@Composable
fun CastivioTheme(
    withBackground: Boolean = true,
    /**
     * How much visual richness this device can afford. `:app` derives it from
     * `DeviceCapabilities`; the default is deliberately the leanest profile, so
     * an unmeasured device gets frame rate rather than effects.
     */
    performance: PerformanceProfile = PerformanceProfile.LEAN,
    /**
     * How much of the interface may move. Defaults to the level the device can
     * afford; `:app` resolves the user's preference and the platform's settings
     * against it with [resolveMotionLevel] and passes the result here.
     */
    motionLevel: MotionLevel = performance.suggestedMotion,
    content: @Composable () -> Unit,
) {
    val colors = castivioDarkColors()
    val device = rememberDeviceClass()

    val material = darkColorScheme(
        primary = colors.primary,
        onPrimary = colors.onPrimary,
        primaryContainer = colors.primaryContainer,
        secondary = colors.secondary,
        onSecondary = colors.onSecondary,
        secondaryContainer = colors.secondaryContainer,
        background = colors.background,
        onBackground = colors.onBackground,
        surface = colors.backgroundElevated,
        onSurface = colors.onBackground,
        error = colors.danger,
        outline = colors.divider,
    )

    CompositionLocalProvider(
        LocalCastivioColors provides colors,
        LocalDeviceClass provides device,
        LocalPerformanceProfile provides performance,
        LocalMotionLevel provides motionLevel,
    ) {
        MaterialTheme(
            colorScheme = material,
            typography = CastivioType.material,
            shapes = CastivioShapes.material,
        ) {
            CompositionLocalProvider(
                LocalTextStyle provides CastivioType.bodyMedium.copy(color = colors.onBackground),
            ) {
                if (withBackground) {
                    CastivioBackdrop { content() }
                } else {
                    content()
                }
            }
        }
    }
}
