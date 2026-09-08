package com.castivio.core.design.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.os.ConfigurationCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
    /**
     * Which of the two grounds to stand on: the void when true, the slate when false.
     *
     * The name still says `dark` after the second ground stopped being a light one,
     * and that is deliberate rather than left over. Both grounds are dark now; what
     * this asks is whether to stand on *the* dark — the near-black void every drawing
     * in this project was made against — or on the lifted indigo beside it. Renaming
     * it would rename the stored preference and the switch and the two strings for a
     * question whose true half is still true.
     *
     * Default true, and that default is load-bearing rather than a preference: a
     * caller that says nothing gets exactly what shipped. `:app` reads the user's
     * stored choice and passes it; nothing else does.
     */
    dark: Boolean = true,
    content: @Composable () -> Unit,
) {
    // Remembered on the flag, so a switch is one allocation and everything below it
    // recomposes off a new `staticCompositionLocalOf` value. No I/O, no resource load,
    // no view model, no navigation: the two palettes are two `CastivioColors` objects
    // and swapping them is the whole of what changing theme costs.
    val colors = remember(dark) { if (dark) castivioDarkColors() else castivioSlateColors() }
    val device = rememberDeviceClass()

    // `darkColorScheme` on both grounds, and not because one branch was tidier.
    //
    // Every value below is handed over explicitly, so the factory's own defaults only
    // reach the roles Castivio does not name — and those defaults are what separate
    // the two factories. `lightColorScheme` fills an unnamed surface or container with
    // a near-white, which was right for the near-white page the second ground used to
    // be and is a hole punched in a #13152D one. Both grounds are dark now, so both
    // ask for the dark set.
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
            // The brand face, chosen once, for the whole tree.
            //
            // Every Castivio style leaves `fontFamily` unset, and `Text` merges
            // the style it is handed onto `LocalTextStyle` -- so setting the
            // family here reaches every screen without a single call site naming
            // a font. Which face depends on the script the interface is in, and
            // that is the one place in the app where the question is asked.
            //
            // Read from the configuration rather than from stored state: `:app`
            // applies the chosen language by wrapping the activity's `Context`
            // and recreating it, so the configuration *is* the user's choice by
            // the time this runs, and a screenshot in Arabic cannot be rendered
            // in a Latin face.
            // `ConfigurationCompat` and not `configuration.locales`, which is API
            // 24 and this app's minSdk is 21.
            val language = ConfigurationCompat.getLocales(LocalConfiguration.current)
                .get(0)?.language.orEmpty()
            CompositionLocalProvider(
                LocalTextStyle provides CastivioType.bodyMedium.copy(
                    color = colors.onBackground,
                    fontFamily = CastivioType.brandFor(language),
                ),
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
