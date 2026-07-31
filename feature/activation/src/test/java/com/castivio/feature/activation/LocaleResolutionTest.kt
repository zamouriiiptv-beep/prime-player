package com.castivio.feature.activation

import android.content.Context
import com.castivio.core.common.locale.CastivioLanguage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Locale

/**
 * Does asking Android for a locale actually reach the directory we put its
 * strings in?
 *
 * ## Why this test exists at all
 *
 * The resource mapping in `design/activation-spec.md` §10.2 is an argument, not a
 * fact. Six of the 37 languages need a qualifier that is not `values-<code>`, and
 * every one of those six is a place where a plausible-looking directory silently
 * never matches — the app falls back to English and *looks* translated, which is
 * the failure nobody notices because nobody tests in a language they do not read.
 *
 * The local JVM is worse than useless as an oracle here. JDK 17 and later
 * normalise Indonesian to the modern code — `Locale("in").language` returns `id`
 * — which is the opposite of Android, where the platform reports the obsolete
 * one. A test written against the workstation default would have "confirmed"
 * `values-id` and been wrong on every device.
 *
 * So each directory carries `locale_sentinel`, whose value is that directory's
 * own tag, and this asks for each locale and checks which directory answered.
 * The mapping stops being an argument and becomes a thing that fails the build.
 *
 * ## What this covers, and what it does not
 *
 * Robolectric resolves against the real compiled resource table with the real
 * qualifier-matching rules, so it catches a directory that can never match —
 * which is the whole class of error worth catching early, and it runs on every
 * commit with no device.
 *
 * It is not the last word. A real device's native resolver on **API 21** and on a
 * **current API level** is what finally settles it; those two are different code
 * paths and the older one is the untested one. That gate is recorded in
 * `RELEASE_CHECKLIST.md`, and until it has run, 39 directories is the best
 * available answer rather than a verified one.
 */
@RunWith(RobolectricTestRunner::class)
class LocaleResolutionTest {

    private fun sentinelFor(tag: String): String {
        val context: Context = RuntimeEnvironment.getApplication()
        val configuration = android.content.res.Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(tag))
        return context.createConfigurationContext(configuration)
            .getString(R.string.locale_sentinel)
    }

    /**
     * Every language, asked for by its canonical tag, lands on its own strings.
     *
     * One assertion per variant rather than per language: Chinese and Portuguese
     * have two directories each and it is precisely those four that the argument
     * is about.
     */
    @Test
    fun `every declared locale resolves to its own resource directory`() {
        val wrong = mutableListOf<String>()
        for (language in CastivioLanguage.entries) {
            for (variant in language.variants) {
                val got = sentinelFor(variant.tag)
                if (got != variant.tag) {
                    wrong += "${language.name}: asked ${variant.tag}, " +
                        "reached ${variant.resourceQualifier.ifEmpty { "?" }} -> got '$got'"
                }
            }
        }
        assertEquals("locales resolving to the wrong directory: $wrong", 0, wrong.size)
    }

    /**
     * The Indonesian trap, on its own, because it is the one a workstation JVM
     * will lie about and the one most likely to be "fixed" into `values-id`.
     */
    @Test
    fun `Indonesian reaches values-in and not values-id`() {
        assertEquals("id", sentinelFor("id"))
        assertEquals("id", sentinelFor("in"))
    }

    /** Filipino is three letters, which the old two-letter qualifier cannot spell. */
    @Test
    fun `Filipino reaches its BCP-47 directory`() {
        assertEquals("fil", sentinelFor("fil"))
    }

    /**
     * The two split languages, including the regions that carry no script subtag.
     * `zh-TW` is unambiguously Traditional and says so nowhere in its tag.
     */
    @Test
    fun `Chinese and Portuguese reach the right one of their two`() {
        assertEquals("zh-Hans", sentinelFor("zh-Hans"))
        assertEquals("zh-Hant", sentinelFor("zh-Hant"))
        assertEquals("zh-Hant", sentinelFor("zh-TW"))
        assertEquals("zh-Hans", sentinelFor("zh-CN"))

        assertEquals("pt-PT", sentinelFor("pt-PT"))
        // Brazilian lives in the unqualified directory, so every other Portuguese
        // region lands there too -- which is the point of putting it there.
        assertEquals("pt-BR", sentinelFor("pt-BR"))
        assertEquals("pt-BR", sentinelFor("pt-AO"))
    }

    /**
     * A language Castivio does not have falls back to English rather than to
     * something arbitrary, which is the other half of the first-launch rule.
     */
    @Test
    fun `an unsupported language falls back to the default directory`() {
        assertEquals("en", sentinelFor("is-IS"))
    }

    /**
     * A region we have no resources for still reaches its language.
     *
     * `fr-CA` must not fall through to English: one French ships, and the region
     * is not supposed to matter.
     */
    @Test
    fun `an unlisted region still reaches its language`() {
        assertEquals("fr", sentinelFor("fr-CA"))
        assertEquals("de", sentinelFor("de-AT"))
        assertEquals("ar", sentinelFor("ar-MA"))
    }

    /**
     * The completeness check in `check-invariants.sh` reads files. This reads the
     * compiled table, which is a different claim: a key can be present in every
     * file and still be missing from a locale that failed to compile.
     */
    @Test
    fun `every locale can render every string on the screen`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val keys = listOf(
            R.string.activation_title, R.string.trial_name, R.string.mac_label,
            R.string.key_label, R.string.copy_mac, R.string.copy_key,
            R.string.copied_mac, R.string.copied_key, R.string.add_playlist,
            R.string.refresh, R.string.refresh_checking, R.string.refresh_found,
            R.string.refresh_none, R.string.refresh_error, R.string.qr_caption,
            R.string.legal_player_only, R.string.language, R.string.language_close,
            R.string.language_back_hint, R.string.language_selected,
        )
        val empty = mutableListOf<String>()
        for (language in CastivioLanguage.entries) {
            for (variant in language.variants) {
                val configuration =
                    android.content.res.Configuration(context.resources.configuration)
                configuration.setLocale(Locale.forLanguageTag(variant.tag))
                val localised = context.createConfigurationContext(configuration)
                for (key in keys) {
                    if (localised.getString(key).isBlank()) {
                        empty += "${variant.tag}/${context.resources.getResourceEntryName(key)}"
                    }
                }
                // A plural must produce something for the count the trial uses.
                if (localised.resources.getQuantityString(R.plurals.trial_days, 7, 7).isBlank()) {
                    empty += "${variant.tag}/trial_days"
                }
            }
        }
        assertEquals("blank strings: $empty", 0, empty.size)
    }
}
