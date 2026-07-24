package com.castivio.tv.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Motion principles.
 *
 * 1. **Motion explains, it never decorates.** Every animation answers "where
 *    did this come from?" or "what did I just select?".
 * 2. **Enter softly, exit quickly.** Content fades up over [medium]; it leaves
 *    in [fast] so the interface never feels like it is holding the user back.
 * 3. **Focus is instant.** On a remote, the highlight must land within
 *    [quick]–[fast] or the D-pad feels laggy. Never spring focus scale with a
 *    bouncy curve — it reads as sloppy at 10 feet.
 * 4. **Nothing loops loudly.** Ambient motion (background waves, a floating
 *    card) stays under 6% travel and over 2.5s per cycle.
 * 5. **Stagger, don't cascade.** Sections enter [staggerStep] apart; never
 *    stagger individual list items beyond the first screenful.
 */
object Motion {

    // -- Durations (ms) -----------------------------------------------------
    const val quick = 120
    const val fast = 180
    const val medium = 280
    const val slow = 420
    const val deliberate = 620

    /** Delay between staggered section entrances. */
    const val staggerStep = 100

    /** Ambient loops: background waves, floating hero card. */
    const val ambientWave = 11_000
    const val ambientDrift = 26_000
    const val ambientFloat = 3_000

    // -- Easings ------------------------------------------------------------
    /** Default for most transitions — decelerate into place. */
    val standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** Entrances that should feel expressive (hero content). */
    val emphasized: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    /** Exits — leave briskly. */
    val exit: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)
    /** Ambient, symmetric loops. */
    val gentle: Easing = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)

    // -- Ready-made specs ---------------------------------------------------
    fun <T> focusSpec(): FiniteAnimationSpec<T> = tween(fast, easing = standard)
    fun <T> enterSpec(delayMillis: Int = 0): FiniteAnimationSpec<T> =
        tween(deliberate, delayMillis = delayMillis, easing = emphasized)
    fun <T> exitSpec(): FiniteAnimationSpec<T> = tween(fast, easing = exit)
    fun <T> pressSpec(): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh)

    // -- Focus & press geometry --------------------------------------------
    /** Scale applied to a focused card. Subtle — the glow does the work. */
    const val focusScaleCard = 1.04f
    /** Scale for a focused button or toolbar item. */
    const val focusScaleButton = 1.05f
    /** Scale for a small focused icon button. */
    const val focusScaleIcon = 1.10f
    /** Scale while pressed — always *down*, and always slight. */
    const val pressScale = 0.98f

    /** Distance content travels upward as it fades in. */
    val enterOffset: Dp = 26.dp
    /** Peak travel of an ambient float. */
    val floatTravel: Dp = 5.dp
}
