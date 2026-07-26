package com.castivio.core.design.theme

/**
 * How much of the interface is allowed to move.
 *
 * Three levels rather than an on/off switch, because "animations off" and "animations
 * calmer" are different requests and collapsing them serves neither. Each level is a
 * complete experience, not a degraded one — the product is fully usable, and every
 * state in `UI_ARCHITECTURE.md` §5.1 is fully legible, at all three.
 *
 * That is the acceptance test for the whole state design: **if a state needs motion to
 * be readable, the state is designed wrong.** The playing meter animates at [FULL] and
 * is static bars at [DISABLED]; either way the aqua bar and the word "Playing" say the
 * same thing.
 *
 * Deliberately plain Kotlin, with no Compose types in it. A level is a decision, not a
 * widget, and the same three will drive SwiftUI and the web shells later.
 */
enum class MotionLevel {

    /** Everything the identity is made of, on a device that can afford it. */
    FULL,

    /**
     * The interface stops travelling but still responds.
     *
     * Focus changes are instant in *position* — the ring and the colour land with no
     * scale animation — which is the specific thing that makes a D-pad feel laggy when
     * it is animated on a weak box, and the specific thing motion-sensitive users ask
     * to lose.
     */
    REDUCED,

    /** Nothing moves anywhere. Every transition is a state swap. */
    DISABLED;

    /** The aurora backdrop animates. The single most expensive ambient effect. */
    val backdropAnimates: Boolean get() = this == FULL

    /** A focused surface animates its lift, rather than simply being lifted. */
    val focusTravels: Boolean get() = this == FULL

    /** The playing meter's bars move. Static bars at every other level. */
    val meterAnimates: Boolean get() = this == FULL

    /**
     * A changed count ticks to its new value.
     *
     * Note this is about a count that *changed*: no level animates a count on arrival,
     * because that is noise rather than motion. See §3.3.
     */
    val countsTick: Boolean get() = this == FULL

    /** A row scrolls smoothly rather than jumping to the new offset. */
    val rowScrollAnimates: Boolean get() = this != DISABLED

    /** How one screen becomes another. */
    val screenTransition: ScreenTransition
        get() = when (this) {
            FULL -> ScreenTransition.Emphasised
            REDUCED -> ScreenTransition.CrossFade
            DISABLED -> ScreenTransition.None
        }

    /** Duration for a transition at this level, in milliseconds. */
    val transitionMillis: Int
        get() = when (this) {
            FULL -> Motion.medium
            REDUCED -> Motion.quick
            DISABLED -> 0
        }
}

enum class ScreenTransition { Emphasised, CrossFade, None }

/**
 * What the user asked for, which is not the same as what the device can manage.
 *
 * [System] is the default and means "decide for me". The other three are an override,
 * and they override in both directions: a user on a capable box who wants stillness
 * gets it, and a user on a weak stick who wants the full identity can have it and live
 * with the frame rate. The automatic choice is a starting point, never a ceiling.
 */
enum class MotionPreference { System, Full, Reduced, Disabled }

/**
 * Resolves the level actually in force.
 *
 * @param preference the setting in Settings → Appearance.
 * @param systemAnimationsDisabled the platform has animations switched off outright —
 *   on Android, an animator duration scale of zero. This is an instruction, not a hint,
 *   so it wins over device capability.
 * @param systemPrefersReducedMotion the platform's softer "prefer less movement"
 *   signal, which asks for calm rather than for stillness.
 * @param deviceCanAnimate the capability verdict — see
 *   [PerformanceProfile.suggestedMotion].
 */
fun resolveMotionLevel(
    preference: MotionPreference = MotionPreference.System,
    systemAnimationsDisabled: Boolean = false,
    systemPrefersReducedMotion: Boolean = false,
    deviceCanAnimate: Boolean = false,
): MotionLevel = when (preference) {
    // An explicit choice is honoured as made. Quietly overriding it with a platform
    // setting the user already worked around would make the setting a lie.
    MotionPreference.Full -> MotionLevel.FULL
    MotionPreference.Reduced -> MotionLevel.REDUCED
    MotionPreference.Disabled -> MotionLevel.DISABLED

    MotionPreference.System -> when {
        systemAnimationsDisabled -> MotionLevel.DISABLED
        systemPrefersReducedMotion -> MotionLevel.REDUCED
        deviceCanAnimate -> MotionLevel.FULL
        // An unmeasured or weak device gets frame rate rather than effects — and gets
        // REDUCED rather than DISABLED, because stillness is a preference and this is
        // only a capability limit.
        else -> MotionLevel.REDUCED
    }
}
