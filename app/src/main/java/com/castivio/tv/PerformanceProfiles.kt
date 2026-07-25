package com.castivio.tv

import com.castivio.core.design.theme.PerformanceProfile
import com.castivio.core.platform.DeviceCapabilities
import com.castivio.core.platform.MemoryClass

/**
 * Maps measured device capability onto how much visual richness we can afford.
 *
 * This is the one place `:core:design` and `:core:platform` meet, and it keeps
 * the design system free of any platform dependency.
 */
fun DeviceCapabilities.toPerformanceProfile(): PerformanceProfile = when (memoryClass) {
    MemoryClass.HIGH -> PerformanceProfile.FULL
    MemoryClass.MEDIUM -> PerformanceProfile.BALANCED
    MemoryClass.LOW -> PerformanceProfile.LEAN
}.copy(posterWidthPx = posterWidthPx)
