package com.castivio.core.platform

/**
 * What this OS provides.
 *
 * [voiceSearch] is nullable on purpose: Google TV has Assistant, Fire TV has
 * Alexa, a generic box has neither. The search screen hides its microphone when
 * this is null rather than offering a button that does nothing.
 */
interface PlatformServices {
    val voiceSearch: VoiceSearchProvider?
    val hasPlayServices: Boolean
    val store: StoreTarget
    val isLeanback: Boolean
}

interface VoiceSearchProvider {
    val label: String
    suspend fun listen(): Result<String>
}

/** Decides how update checks are performed — or suppressed entirely. */
enum class StoreTarget { PLAY, AMAZON, SIDELOAD, UNKNOWN }
