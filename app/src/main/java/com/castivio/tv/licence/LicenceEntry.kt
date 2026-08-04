package com.castivio.tv.licence

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.castivio.feature.activation.LanguagePicker
import com.castivio.domain.entitlement.EntitlementState
import com.castivio.feature.licence.LicenceRoute
import com.castivio.tv.locale.LocalLocaleController

/**
 * The one way into the licence screen, wherever it is reached from.
 *
 * ## Two callers, one composable, one difference between them
 *
 * The gate opens this when the app may not be used, and Settings opens it when
 * it may. Everything about the screen is the same in both cases; the only thing
 * that differs is what [onLeave] means — leave Castivio, or go back to Settings
 * — and that is the caller's to decide because only the caller knows where the
 * user came from. `StartGate` already settles which situation this is, so the
 * screen does not re-derive it.
 *
 * ## Why the language overlay lives here and not in the feature
 *
 * Applying a language means wrapping the `Context` an activity was built on and
 * recreating it, which is the application's business. The feature draws a chip
 * and reports a press. `LanguagePicker` is `:feature:activation`'s because it
 * owns five string resources in 38 languages, and a shared component may not own
 * copy — see its own note for when that changes.
 *
 * ## The Settings integration, and what is actually pending
 *
 * `:feature:settings` is a placeholder object today: no screen, no route, and
 * not on `:app`'s dependency graph. Until it exists, the reachable entry point
 * is the shell's Settings screen, which calls this.
 *
 * When the real Settings arrives the change is one call site — it calls this
 * composable with its own back behaviour and nothing here or in
 * `:feature:licence` moves. That is the whole reason this is a named entry
 * point in its own file rather than a private helper inside `SplashGate`, where
 * it started: an entry point that a future feature has to go looking for inside
 * the splash screen is one it will re-implement instead.
 */
@Composable
internal fun LicenceWithLanguage(
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
    /** Debug only; see `LicenceRoute`, which ignores it in a release build. */
    forcedState: EntitlementState? = null,
) {
    val locale = LocalLocaleController.current
    var picking by rememberSaveable { mutableStateOf(false) }

    LicenceRoute(
        // The overlay is the innermost thing on screen, so it is the first thing
        // back closes; the route handles everything inside that.
        onLeave = { if (picking) picking = false else onLeave() },
        onOpenLanguage = { picking = true },
        modifier = modifier,
        forcedState = forcedState,
    )

    if (picking) {
        LanguagePicker(
            selected = locale.current.language,
            onPick = { language ->
                picking = false
                // No `recreate()`. The controller records the choice and the
                // composition re-reads its strings in place -- see its own note
                // for what the two teardowns this replaced were costing.
                locale.choose(language)
            },
            onDismiss = { picking = false },
        )
    }
}
