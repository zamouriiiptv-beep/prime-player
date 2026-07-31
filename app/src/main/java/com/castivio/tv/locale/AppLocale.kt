package com.castivio.tv.locale

import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.LocaleList
import com.castivio.core.common.locale.CastivioLanguage
import com.castivio.core.common.locale.LanguagePolicy
import com.castivio.core.common.locale.ResolvedLocale
import com.castivio.core.platform.locale.SystemLocales
import com.castivio.data.preferences.LanguageStore
import java.util.Locale

/**
 * Applies Castivio's language to the whole application, not only to Compose.
 *
 * The tempting version of this is a `CompositionLocalProvider` around a localised
 * `Context`: no activity recreation, no storage, twenty lines. It is also wrong,
 * and quietly — the composition would be translated and everything outside it
 * would not, so the first notification, `Toast` or accessibility announcement
 * comes out in the device's language while the screen behind it is in the user's.
 *
 * So the locale is applied where the platform applies locales: to the `Context`
 * the activity is built on, before it is built.
 *
 * See `design/activation-spec.md` §10.6.1.
 */
object AppLocale {

    /**
     * Wraps [base] in the language Castivio should be in.
     *
     * Called from `attachBaseContext`, which is why [LanguageStore] reads
     * synchronously: there is no scope to launch in here, and returning a
     * `Context` in the wrong language and fixing it later means a visible flash
     * of the wrong language on every cold start.
     */
    fun wrap(base: Context): ContextWrapper = wrap(base, current(base))

    fun wrap(base: Context, resolved: ResolvedLocale): ContextWrapper {
        val locale = Locale.forLanguageTag(resolved.tag)
        val configuration = android.content.res.Configuration(base.resources.configuration)

        Locale.setDefault(locale)
        configuration.setLocale(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocales(LocaleList(locale))
        }
        // Layout direction follows the locale rather than being set from our own
        // model: the platform already knows which scripts run right to left, and
        // two sources for one fact is how they come to disagree.
        configuration.setLayoutDirection(locale)

        return ContextWrapper(base.createConfigurationContext(configuration))
    }

    /** The rule in §10.6, with the device read through the platform adapter. */
    fun resolve(stored: String?, context: Context): ResolvedLocale =
        LanguagePolicy.resolve(stored, SystemLocales.of(context))

    /**
     * Records a choice, in both places that need to know.
     *
     * From API 33 the platform's own per-app language setting is written too, so
     * Android's Settings shows what Castivio is actually in. Ours is written on
     * every version, because below 33 there is nothing else, and above it a user
     * who clears the platform's value should land on their choice rather than on
     * the device's language.
     *
     * The caller recreates the activity afterwards. Nothing here does it: whether
     * to recreate is a question about the screen, not about storage.
     */
    fun choose(context: Context, language: CastivioLanguage): ResolvedLocale {
        val resolved = LanguagePolicy.choose(language, SystemLocales.of(context))
        LanguageStore(context.applicationContext).set(resolved.tag)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)
                ?.applicationLocales = LocaleList.forLanguageTags(resolved.tag)
        }
        return resolved
    }

    /** Which of the 37 the application is in right now, for the picker's tick. */
    fun current(context: Context): ResolvedLocale =
        resolve(storedTag(context), context)

    /**
     * The choice on record, from whichever store is authoritative on this device.
     *
     * From API 33 that is the platform's, because on those versions the user can
     * change it in Android's Settings and a value of ours that quietly overrode
     * it would make one device say two different things. Below 33 the platform
     * has no such setting and ours is the only one.
     *
     * Ours is still the fallback on 33+, for the case where the platform's value
     * has been cleared but the user did once choose.
     */
    private fun storedTag(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val platform = context.getSystemService(LocaleManager::class.java)
                ?.applicationLocales
                ?.takeIf { !it.isEmpty }
                ?.get(0)
            if (platform != null) normalise(platform)?.let { return it }
        }
        return LanguageStore(context.applicationContext).stored()
    }

    /**
     * A platform tag, reduced to one of ours.
     *
     * Android hands back whatever it was given — `en-US`, `zh-Hant-TW` — and the
     * model only knows `en` and `zh-Hant`. Reducing it here rather than string
     * matching means `pt-BR` and `pt-PT` land on the right resources and
     * `fr-CA` lands on French instead of falling through to English.
     */
    private fun normalise(locale: Locale): String? {
        val query = SystemLocales.query(locale)
        val language = CastivioLanguage.forSystem(query) ?: return null
        return language.variantFor(query).tag
    }
}

/**
 * The activity behind a composition's `Context`, or null.
 *
 * `LocalContext` is a `ContextWrapper` here — that is how the language is applied
 * — so the activity sits at the end of a chain rather than one cast away.
 */
tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
