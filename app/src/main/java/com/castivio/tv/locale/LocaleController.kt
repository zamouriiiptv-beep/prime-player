package com.castivio.tv.locale

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.castivio.core.common.locale.CastivioLanguage
import com.castivio.core.common.locale.ResolvedLocale

/**
 * The application's language, changed without tearing the window down.
 *
 * ## What this replaces, and why
 *
 * Every language change used to call `activity.recreate()`. On API 33 and up it
 * got a second one for free: `LocaleManager.applicationLocales` makes the
 * platform recreate the activity too. So one press of a language produced two
 * window teardowns — two flashes of the `#0B0620` window background through the
 * aurora, the backdrop's animation restarted from nothing twice, and every
 * screen rebuilt from its saved state. On a phone that reads as a flicker and on
 * a television it reads as a fault.
 *
 * Neither teardown was buying anything. `attachBaseContext` wraps the context the
 * activity is built on, so the language is already applied *before* the first
 * composition of every launch; the only thing a recreate added was a second
 * application of a decision already made.
 *
 * ## What happens instead
 *
 * The choice is written to storage exactly as before — that is what makes it
 * survive, and what makes notifications, toasts and accessibility announcements
 * follow it, none of which read a composition local. Then [current] changes, the
 * composition is given a context in the new language, and every `stringResource`
 * in the tree re-reads. Nothing is destroyed and nothing animates.
 *
 * The manifest's `configChanges="locale|layoutDirection"` is the other half: it
 * stops the platform recreating the activity when it notices the per-app locale
 * has changed. That change carries no information this class does not already
 * have, so it arrives at `onConfigurationChanged` and is answered by
 * [refreshFromSystem].
 *
 * ## Why not a `CompositionLocalProvider` alone
 *
 * Because that was the objection that put the recreate there in the first place,
 * and it was right: a composition local translates the screen and leaves every
 * notification in the device's language. The answer is not to choose between the
 * two — it is to do both. Storage is authoritative and the composition local is
 * how *this* frame sees the decision immediately.
 */
@Stable
class LocaleController(private val context: Context) {

    /** The language Castivio is in, as the composition should see it now. */
    var current: ResolvedLocale by mutableStateOf(AppLocale.current(context))
        private set

    /**
     * Record a choice and apply it to the running composition.
     *
     * Storage first: if the process dies between the two, the user gets the
     * language they picked rather than the one they were leaving.
     */
    fun choose(language: CastivioLanguage) {
        current = AppLocale.choose(context, language)
    }

    /**
     * The device's language changed underneath us.
     *
     * Only matters for a user who has never made a choice, because
     * `LanguagePolicy` falls back to the system for them. For everyone else this
     * re-resolves to the same answer, which is the cheapest possible way to be
     * correct in both cases.
     */
    fun refreshFromSystem() {
        current = AppLocale.current(context)
    }
}

/**
 * No default.
 *
 * A default would be a second source of truth for the application's language, and
 * a screen that read it because nobody provided one would be a screen quietly
 * rendering in the wrong language. Failing to compose is the better outcome.
 */
val LocalLocaleController = staticCompositionLocalOf<LocaleController> {
    error("No LocaleController. MainActivity provides it around the whole composition.")
}
