package com.castivio.core.design.theme

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The dark-or-light choice, reachable from any header.
 *
 * ## Why a composition local rather than a parameter
 *
 * Because the alternative is a `Boolean` and a lambda threaded through six screens,
 * their route, and the scaffold each of them wears — twelve signatures changed to
 * carry one fact that has nothing to do with what any of those screens is *for*. A
 * screen that lists folders does not become a screen about themes because there is a
 * control in its header, any more than it became a screen about brands when the
 * lockup arrived. Both are chrome, and chrome is what the theme layer is for.
 *
 * It is `static`, so reading it does not subscribe: the switch object is created once
 * and the *state it reads* is what changes. That is what keeps a press to one
 * recomposition of the tree rather than one per header.
 *
 * ## Nullable, and that is the useful part
 *
 * A tree that provides no switch draws no chip — a preview, a test that is measuring
 * something else, a screenshot harness. Nothing has to be told to hide it and nothing
 * crashes for want of a callback that would have gone nowhere.
 */
@Stable
interface CastivioThemeSwitch {

    /** Which ground the application is standing on now. */
    val isDark: Boolean

    /** Go to the other one, and remember it. */
    fun toggle()
}

/** Null where no one is offering the choice; see [CastivioThemeSwitch]. */
val LocalThemeSwitch = staticCompositionLocalOf<CastivioThemeSwitch?> { null }
