package com.castivio.core.common.locale

/**
 * A language Castivio is offered in — one entry in the picker, and the product
 * invariant is that there are **37 of them**.
 *
 * Thirty-seven is the number that is fixed. The number of Android resource
 * directories behind them is not: it is whatever correct locale resolution turns
 * out to require, and it is settled by a test on a device rather than by a
 * constant here. See `design/activation-spec.md` §10.2.
 *
 * ## Why the names are in Kotlin and not in `strings.xml`
 *
 * Every other user-visible string in Castivio comes from resources, and these do
 * not, on purpose. A language's own name is the same in all 37 locales —
 * `Français` is `Français` on a Japanese phone — so a per-locale resource would
 * be 37 copies of one list, each an invitation for a translator to "fix" one of
 * them into an exonym. The one thing that must never happen to this list is
 * translation, and the surest way to prevent it is to keep it out of the files
 * translators work in.
 *
 * ## Why a language has variants
 *
 * Two of the 37 need more than one set of resources, because Simplified and
 * Traditional Chinese are different writing systems and Brazilian and European
 * Portuguese are different enough to notice. Both are still **one entry**: the
 * variant is derived from the device (see [variantFor]), not chosen from a
 * second row.
 *
 * [variants] is a list rather than a nullable second field so that offering a
 * 简体 / 繁體 choice *inside* the 中文 row stays a change to one screen. Nothing
 * downstream of here assumes a language has exactly one locale, and nothing may
 * start: that assumption is what would turn the choice into a 38th entry.
 */
enum class CastivioLanguage(
    /** The language's own name, in its own script. Never translated. */
    val nativeName: String,
    val direction: TextDirection,
    /** At least one. The first is what a device tells us nothing about resolves to. */
    val variants: List<LanguageVariant>,
) {
    English("English", TextDirection.Ltr, one("en", DEFAULT_RESOURCES)),
    Arabic("العربية", TextDirection.Rtl, one("ar")),
    French("Français", TextDirection.Ltr, one("fr")),
    Spanish("Español", TextDirection.Ltr, one("es")),
    German("Deutsch", TextDirection.Ltr, one("de")),
    Italian("Italiano", TextDirection.Ltr, one("it")),

    /**
     * Brazilian sits in the unqualified directory because Android falls back from
     * every `pt-*` region to `values-pt`, and the audience that should never fall
     * back is the larger one.
     */
    Portuguese(
        "Português", TextDirection.Ltr,
        listOf(
            LanguageVariant("pt-BR", "values-pt"),
            LanguageVariant("pt-PT", "values-pt-rPT"),
        ),
    ),

    Dutch("Nederlands", TextDirection.Ltr, one("nl")),
    Turkish("Türkçe", TextDirection.Ltr, one("tr")),
    Russian("Русский", TextDirection.Ltr, one("ru")),
    Ukrainian("Українська", TextDirection.Ltr, one("uk")),
    Polish("Polski", TextDirection.Ltr, one("pl")),
    Romanian("Română", TextDirection.Ltr, one("ro")),
    Hungarian("Magyar", TextDirection.Ltr, one("hu")),
    Czech("Čeština", TextDirection.Ltr, one("cs")),
    Slovak("Slovenčina", TextDirection.Ltr, one("sk")),
    Greek("Ελληνικά", TextDirection.Ltr, one("el")),
    Swedish("Svenska", TextDirection.Ltr, one("sv")),
    Danish("Dansk", TextDirection.Ltr, one("da")),

    /** `nb` is a current ISO 639-1 code and what Android reports; no `no` alias. */
    Norwegian("Norsk", TextDirection.Ltr, one("nb")),

    Finnish("Suomi", TextDirection.Ltr, one("fi")),
    Bulgarian("Български", TextDirection.Ltr, one("bg")),
    Croatian("Hrvatski", TextDirection.Ltr, one("hr")),

    /**
     * One Serbian ships, in Cyrillic, so the unqualified directory is right: it
     * matches every Serbian device, where `b+sr+Cyrl` would leave a `sr-Latn`
     * device reading English.
     */
    Serbian("Српски", TextDirection.Ltr, one("sr")),

    Albanian("Shqip", TextDirection.Ltr, one("sq")),
    Persian("فارسی", TextDirection.Rtl, one("fa")),
    Urdu("اردو", TextDirection.Rtl, one("ur")),
    Hindi("हिन्दी", TextDirection.Ltr, one("hi")),
    Bengali("বাংলা", TextDirection.Ltr, one("bn")),

    /**
     * `values-in`, not `values-id`. Android reports Indonesian by its obsolete
     * code. This is the one mapping a JVM on a workstation will actively mislead
     * you about — JDK 17 and later normalise the other way — so it is settled by
     * the sentinel test on a device, not by a local experiment.
     */
    Indonesian("Bahasa Indonesia", TextDirection.Ltr, one("id", "values-in")),

    Malay("Bahasa Melayu", TextDirection.Ltr, one("ms")),
    Thai("ไทย", TextDirection.Ltr, one("th")),
    Vietnamese("Tiếng Việt", TextDirection.Ltr, one("vi")),

    /** Two writing systems, one entry. [variantFor] decides which. */
    Chinese(
        "中文", TextDirection.Ltr,
        listOf(
            LanguageVariant("zh-Hans", "values-b+zh+Hans"),
            LanguageVariant("zh-Hant", "values-b+zh+Hant"),
        ),
    ),

    Japanese("日本語", TextDirection.Ltr, one("ja")),
    Korean("한국어", TextDirection.Ltr, one("ko")),

    /** Three letters, so the old two-letter qualifier form cannot spell it. */
    Filipino("Filipino", TextDirection.Ltr, one("fil", "values-b+fil")),
    ;

    /** The ISO 639-1 (or 639-2) code shared by every one of this language's variants. */
    val code: String get() = variants.first().tag.substringBefore('-')

    /** What this language resolves to with nothing known about the device. */
    val defaultVariant: LanguageVariant get() = variants.first()

    /**
     * Which of this language's variants suits [system].
     *
     * Only Chinese and Portuguese have a decision to make. Both make it the same
     * way — take the device's opinion when it has one, and fall back to the
     * larger audience when it does not — and the caller stores the answer, so a
     * later change to the device's language does not silently change the script
     * under a user who already chose.
     */
    fun variantFor(system: LocaleQuery?): LanguageVariant {
        if (variants.size == 1 || system == null) return defaultVariant
        if (system.language != code) return defaultVariant
        return when (this) {
            Chinese -> when {
                system.script.equals("Hant", ignoreCase = true) -> variant("zh-Hant")
                system.script.equals("Hans", ignoreCase = true) -> variant("zh-Hans")
                system.region in TRADITIONAL_REGIONS -> variant("zh-Hant")
                else -> defaultVariant
            }
            Portuguese -> if (system.region == "PT") variant("pt-PT") else defaultVariant
            // Unreachable while only two languages have variants, and deliberately
            // loud rather than silently defaulting if a third is ever added
            // without deciding how its variant is chosen.
            else -> error("$name has ${variants.size} variants and no rule for choosing one")
        }
    }

    private fun variant(tag: String): LanguageVariant =
        variants.first { it.tag == tag }

    companion object {
        /**
         * The product invariant. Asserted in a test rather than trusted, because
         * "37 languages" is the promise and an enum is easy to add a line to.
         */
        const val COUNT = 37

        /** Picker order: the approved one, and not sorted. See spec §10.5. */
        val ordered: List<CastivioLanguage> get() = entries

        fun byTag(tag: String): CastivioLanguage? =
            entries.firstOrNull { lang -> lang.variants.any { it.tag == tag } }

        /**
         * The language a device asking for [query] should get, or null when
         * Castivio has nothing for it. Matched on the language subtag alone: a
         * `fr-CA` device gets French, which is the whole point of having one
         * French.
         */
        fun forSystem(query: LocaleQuery): CastivioLanguage? =
            entries.firstOrNull { it.code == query.language }

        private val TRADITIONAL_REGIONS = setOf("TW", "HK", "MO")
    }
}

/**
 * One set of resources for a language.
 *
 * @param tag the canonical BCP-47 tag, which is what gets stored and what the
 *   platform is asked for. Always specific enough to be unambiguous: `zh-Hans`,
 *   never `zh`, so a stored choice cannot drift when the device's changes.
 * @param resourceQualifier the directory the strings live in, recorded here so
 *   the sentinel test can assert that asking for [tag] actually reaches it.
 *   This is a claim about Android, and the test is what makes it true.
 */
data class LanguageVariant(val tag: String, val resourceQualifier: String)

enum class TextDirection { Ltr, Rtl }

/**
 * What a platform can tell us about a locale, without a platform type.
 *
 * `:core:common` compiles for every platform Castivio will ever run on, so it
 * cannot mention `java.util.Locale`. The adapter that can, fills this in.
 */
data class LocaleQuery(
    val language: String,
    val script: String? = null,
    val region: String? = null,
)

private const val DEFAULT_RESOURCES = "values"

private fun one(code: String, qualifier: String = "values-$code"): List<LanguageVariant> =
    listOf(LanguageVariant(code, qualifier))
