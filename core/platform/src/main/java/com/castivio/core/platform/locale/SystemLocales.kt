package com.castivio.core.platform.locale

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.castivio.core.common.locale.LocaleQuery
import java.util.Locale

/**
 * The adapter between Android's idea of a locale and Castivio's.
 *
 * `:core:common` holds the resolution rules and cannot mention `java.util.Locale`
 * — it compiles for every platform Castivio will run on. This is the one place
 * that translates, and it is where the platform's peculiarities are absorbed.
 */
object SystemLocales {

    /**
     * The device's languages, most preferred first.
     *
     * A list because Android has had `LocaleList` since API 24, and a user who
     * lists Catalan and then Spanish should get Spanish rather than English.
     * Below 24 there is exactly one.
     */
    @Suppress("DEPRECATION")
    fun of(configuration: Configuration): List<LocaleQuery> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val list = configuration.locales
            (0 until list.size()).map { query(list[it]) }
        } else {
            listOf(query(configuration.locale))
        }

    fun of(context: Context): List<LocaleQuery> = of(context.resources.configuration)

    /**
     * One locale, as much of it as Android will tell us.
     *
     * The language subtag goes through [modernLanguageCode] because Android
     * reports Indonesian, Hebrew and Yiddish under codes ISO retired in 1989 and
     * the model speaks the current ones. This is not a detail a workstation JVM
     * will reproduce: JDK 17 and later normalise the other way by default, so a
     * test written against `Locale("in").language` on a laptop agrees with
     * nothing that ships.
     *
     * The script is only asked for on API 21 and above, which is every build we
     * make, and is empty rather than null when the platform has no opinion —
     * normalised here so callers do not have to know that.
     */
    fun query(locale: Locale): LocaleQuery = LocaleQuery(
        language = modernLanguageCode(locale.language),
        script = locale.script.ifBlank { null } ?: scriptFromRegion(locale),
        region = locale.country.ifBlank { null },
    )

    /**
     * `in` → `id`, `iw` → `he`, `ji` → `yi`.
     *
     * Java froze these three at their pre-1989 codes for compatibility and
     * Android kept that behaviour. Only Indonesian is in Castivio's set, and the
     * other two are here so that adding Hebrew later does not reintroduce the
     * bug.
     */
    fun modernLanguageCode(code: String): String = when (code) {
        "in" -> "id"
        "iw" -> "he"
        "ji" -> "yi"
        else -> code
    }

    /**
     * Chinese without a stated script, which older devices are full of.
     *
     * `zh_TW` carries no script subtag but is unambiguously Traditional. Inferring
     * it here rather than in the model keeps the model's rule about scripts
     * simple and puts the platform's gap where the other platform gaps are.
     */
    private fun scriptFromRegion(locale: Locale): String? =
        if (locale.language == "zh" && locale.country in TRADITIONAL) "Hant" else null

    private val TRADITIONAL = setOf("TW", "HK", "MO")
}
