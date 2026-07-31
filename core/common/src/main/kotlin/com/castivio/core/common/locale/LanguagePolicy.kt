package com.castivio.core.common.locale

/** A language and the one set of resources it will actually be read from. */
data class ResolvedLocale(
    val language: CastivioLanguage,
    val variant: LanguageVariant,
) {
    val tag: String get() = variant.tag
    val direction: TextDirection get() = language.direction
}

/**
 * Which language Castivio opens in.
 *
 * The whole rule, and it is short because it has to be obeyed exactly:
 *
 * 1. **First launch** — take the device's language if Castivio has it, English
 *    otherwise.
 * 2. **After the user chooses** — that choice, on every launch after it.
 * 3. The device's language **never** overrides an explicit choice.
 *
 * > A phone in German opens Castivio in German. The user switches Castivio to
 * > English. Every launch after that is English, whatever the phone says.
 *
 * Pure on purpose. This is the part of per-app languages that is easy to get
 * subtly wrong — the wrong precedence, or a stored value that loses its script —
 * and it is also the part no emulator is needed to test.
 */
object LanguagePolicy {

    /**
     * @param stored the tag the user last chose, or null if they never have.
     * @param system the device's languages, most preferred first. A list because
     *   Android has had more than one since API 24, and a user who lists Catalan
     *   then Spanish should get Spanish rather than English.
     */
    fun resolve(stored: String?, system: List<LocaleQuery>): ResolvedLocale {
        storedLocale(stored)?.let { return it }
        for (query in system) {
            val language = CastivioLanguage.forSystem(query) ?: continue
            return ResolvedLocale(language, language.variantFor(query))
        }
        return fallback()
    }

    /**
     * What to store when the user picks [language] from the picker.
     *
     * The device still gets a say — but only here, once, about which variant of
     * a two-variant language is meant. What comes back is a specific tag, and it
     * is that tag which is persisted: storing bare `zh` would let a later change
     * to the phone's language switch a user between Simplified and Traditional
     * without them touching anything.
     */
    fun choose(language: CastivioLanguage, system: List<LocaleQuery>): ResolvedLocale {
        val query = system.firstOrNull { it.language == language.code }
        return ResolvedLocale(language, language.variantFor(query))
    }

    /**
     * A stored tag, if it still names something.
     *
     * Unknown tags are dropped rather than repaired. A tag that is no longer in
     * the set means the app was downgraded, or the value was edited, and in both
     * cases falling back to the device's language is the behaviour a user would
     * expect over being pinned to something the build cannot render.
     */
    fun storedLocale(stored: String?): ResolvedLocale? {
        if (stored.isNullOrBlank()) return null
        val language = CastivioLanguage.byTag(stored) ?: return null
        val variant = language.variants.firstOrNull { it.tag == stored } ?: return null
        return ResolvedLocale(language, variant)
    }

    fun fallback(): ResolvedLocale =
        ResolvedLocale(CastivioLanguage.English, CastivioLanguage.English.defaultVariant)
}
