package com.castivio.core.design.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * How much visual richness this device can afford.
 *
 * Castivio's rule is that performance outranks effects, and this is where that
 * rule is enforced. `:app` derives a profile from `DeviceCapabilities` and
 * provides it; the design system reads it and quietly does less on a weak box.
 *
 * The design system does not depend on `:core:platform` — it receives a plain
 * value instead, so the dependency stays one-directional.
 */
@Immutable
data class PerformanceProfile(
    /** Animate the aurora backdrop. The single most expensive ambient effect. */
    val animatedBackdrop: Boolean,
    /** Drifting particles. Cheap individually, pointless without the animation. */
    val backdropParticles: Boolean,
    /** Ambient float on hero cards. */
    val ambientMotion: Boolean,
    /** Soft shadows on every card cost fill rate on old GPUs. */
    val elevationShadows: Boolean,
    /** Width to request for poster artwork; decoding oversized JPEGs kills frames. */
    val posterWidthPx: Int,
) {
    companion object {
        /** Shield, modern Google TV: everything on. */
        val FULL = PerformanceProfile(
            animatedBackdrop = true,
            backdropParticles = true,
            ambientMotion = true,
            elevationShadows = true,
            posterWidthPx = 480,
        )

        /** Mid-range boxes: keep the identity, trim the extras. */
        val BALANCED = PerformanceProfile(
            animatedBackdrop = true,
            backdropParticles = false,
            ambientMotion = true,
            elevationShadows = true,
            posterWidthPx = 360,
        )

        /**
         * Low-memory sticks. The backdrop becomes a static gradient — it still
         * looks like Castivio, it just stops competing with the scroll for GPU
         * time. Frame rate is the feature here.
         */
        val LEAN = PerformanceProfile(
            animatedBackdrop = false,
            backdropParticles = false,
            ambientMotion = false,
            elevationShadows = false,
            posterWidthPx = 240,
        )
    }
}

/** Defaults to LEAN: an unknown device is assumed weak until proven otherwise. */
val LocalPerformanceProfile: ProvidableCompositionLocal<PerformanceProfile> =
    staticCompositionLocalOf { PerformanceProfile.LEAN }
