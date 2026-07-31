package com.castivio.core.common.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastivioLanguageTest {

    /** The product invariant, and the only count that is one. */
    @Test
    fun `there are exactly thirty-seven languages`() {
        assertEquals(CastivioLanguage.COUNT, CastivioLanguage.entries.size)
    }

    @Test
    fun `every language appears once, under its own name`() {
        val names = CastivioLanguage.entries.map { it.nativeName }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.none { it.isBlank() })
    }

    /**
     * Two languages sharing a code would make [CastivioLanguage.forSystem]
     * silently prefer whichever was declared first.
     */
    @Test
    fun `no two languages share a code`() {
        val codes = CastivioLanguage.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun `no two variants share a tag or a resource directory`() {
        val all = CastivioLanguage.entries.flatMap { it.variants }
        assertEquals(all.size, all.map { it.tag }.toSet().size)
        assertEquals(all.size, all.map { it.resourceQualifier }.toSet().size)
    }

    /**
     * The number of directories is an output, not a requirement — but a change to
     * it should be deliberate, so it is stated once here where a diff shows it
     * rather than asserted as a product invariant.
     */
    @Test
    fun `thirty-seven languages currently need thirty-nine resource directories`() {
        assertEquals(39, CastivioLanguage.entries.sumOf { it.variants.size })
    }

    @Test
    fun `only Chinese and Portuguese have more than one variant`() {
        val many = CastivioLanguage.entries.filter { it.variants.size > 1 }
        assertEquals(listOf(CastivioLanguage.Portuguese, CastivioLanguage.Chinese), many)
    }

    @Test
    fun `the three right-to-left languages are the three expected`() {
        val rtl = CastivioLanguage.entries.filter { it.direction == TextDirection.Rtl }
        assertEquals(
            listOf(CastivioLanguage.Arabic, CastivioLanguage.Persian, CastivioLanguage.Urdu),
            rtl,
        )
    }

    @Test
    fun `English is first and its resources are the default directory`() {
        assertEquals(CastivioLanguage.English, CastivioLanguage.ordered.first())
        assertEquals("values", CastivioLanguage.English.defaultVariant.resourceQualifier)
    }

    /**
     * The three the mapping argues about. Written down so that changing one is a
     * visible decision rather than a typo nobody notices until a device does.
     */
    @Test
    fun `the awkward codes keep the qualifiers that were argued for`() {
        assertEquals("values-in", CastivioLanguage.Indonesian.defaultVariant.resourceQualifier)
        assertEquals("values-b+fil", CastivioLanguage.Filipino.defaultVariant.resourceQualifier)
        assertEquals("values-nb", CastivioLanguage.Norwegian.defaultVariant.resourceQualifier)
        assertEquals("values-sr", CastivioLanguage.Serbian.defaultVariant.resourceQualifier)
    }
}

class ChineseAndPortugueseVariantTest {

    private fun zh(script: String? = null, region: String? = null) =
        CastivioLanguage.Chinese.variantFor(LocaleQuery("zh", script, region)).tag

    @Test
    fun `Chinese takes the script the device states`() {
        assertEquals("zh-Hant", zh(script = "Hant"))
        assertEquals("zh-Hans", zh(script = "Hans"))
    }

    @Test
    fun `Chinese infers Traditional from the regions that use it`() {
        assertEquals("zh-Hant", zh(region = "TW"))
        assertEquals("zh-Hant", zh(region = "HK"))
        assertEquals("zh-Hant", zh(region = "MO"))
    }

    @Test
    fun `Chinese defaults to Simplified`() {
        assertEquals("zh-Hans", zh())
        assertEquals("zh-Hans", zh(region = "CN"))
        assertEquals("zh-Hans", zh(region = "SG"))
    }

    /** A stated script beats the region it disagrees with. */
    @Test
    fun `an explicit script wins over a contradicting region`() {
        assertEquals("zh-Hans", zh(script = "Hans", region = "TW"))
        assertEquals("zh-Hant", zh(script = "Hant", region = "CN"))
    }

    @Test
    fun `Chinese chosen on a phone that is not Chinese is Simplified`() {
        val resolved = LanguagePolicy.choose(
            CastivioLanguage.Chinese,
            listOf(LocaleQuery("en", region = "US")),
        )
        assertEquals("zh-Hans", resolved.tag)
    }

    @Test
    fun `Portuguese is European only where the device says Portugal`() {
        val pt = CastivioLanguage.Portuguese
        assertEquals("pt-PT", pt.variantFor(LocaleQuery("pt", region = "PT")).tag)
        assertEquals("pt-BR", pt.variantFor(LocaleQuery("pt", region = "BR")).tag)
        assertEquals("pt-BR", pt.variantFor(LocaleQuery("pt", region = "AO")).tag)
        assertEquals("pt-BR", pt.variantFor(null).tag)
    }

    /**
     * A single-variant language must ignore the device entirely, or a `de-AT`
     * phone would start looking for an Austrian German that does not exist.
     */
    @Test
    fun `a language with one variant ignores the device`() {
        val de = CastivioLanguage.German
        assertEquals("de", de.variantFor(LocaleQuery("de", region = "AT")).tag)
        assertEquals("de", de.variantFor(LocaleQuery("fr", region = "FR")).tag)
    }
}

class LanguageResolutionTest {

    private fun system(vararg tags: String) = tags.map { tag ->
        val parts = tag.split("-")
        LocaleQuery(
            language = parts[0],
            script = parts.getOrNull(1)?.takeIf { it.length == 4 },
            region = parts.lastOrNull()?.takeIf { it.length == 2 },
        )
    }

    // -- first launch --------------------------------------------------------

    @Test
    fun `first launch takes the device language when Castivio has it`() {
        val resolved = LanguagePolicy.resolve(stored = null, system = system("de-DE"))
        assertEquals(CastivioLanguage.German, resolved.language)
        assertEquals("de", resolved.tag)
    }

    @Test
    fun `first launch ignores the region of a language with one variant`() {
        assertEquals("fr", LanguagePolicy.resolve(null, system("fr-CA")).tag)
    }

    @Test
    fun `first launch falls back to English for a language we do not have`() {
        val resolved = LanguagePolicy.resolve(stored = null, system = system("is-IS"))
        assertEquals(CastivioLanguage.English, resolved.language)
    }

    @Test
    fun `first launch falls back to English when the device says nothing`() {
        assertEquals(CastivioLanguage.English, LanguagePolicy.resolve(null, emptyList()).language)
    }

    /** Android has had a locale *list* since API 24; the second entry counts. */
    @Test
    fun `first launch walks the device list in order`() {
        val resolved = LanguagePolicy.resolve(null, system("is-IS", "es-MX"))
        assertEquals(CastivioLanguage.Spanish, resolved.language)
    }

    @Test
    fun `first launch resolves a variant from the device`() {
        assertEquals("zh-Hant", LanguagePolicy.resolve(null, system("zh-Hant-TW")).tag)
        assertEquals("pt-PT", LanguagePolicy.resolve(null, system("pt-PT")).tag)
        assertEquals("pt-BR", LanguagePolicy.resolve(null, system("pt-BR")).tag)
    }

    // -- the rule that matters ----------------------------------------------

    /** The example from the specification, verbatim. */
    @Test
    fun `a chosen language survives a device that says otherwise`() {
        val german = LanguagePolicy.resolve(stored = null, system = system("de-DE"))
        assertEquals(CastivioLanguage.German, german.language)

        val chosen = LanguagePolicy.choose(CastivioLanguage.English, system("de-DE"))
        val relaunch = LanguagePolicy.resolve(stored = chosen.tag, system = system("de-DE"))
        assertEquals(CastivioLanguage.English, relaunch.language)
    }

    @Test
    fun `a chosen language survives the device language changing afterwards`() {
        val chosen = LanguagePolicy.choose(CastivioLanguage.Thai, system("en-GB"))
        val later = LanguagePolicy.resolve(chosen.tag, system("ja-JP"))
        assertEquals(CastivioLanguage.Thai, later.language)
    }

    /**
     * The reason a resolved tag is stored rather than a bare language: a user who
     * picked 中文 on a Traditional phone keeps Traditional after switching the
     * phone to Simplified.
     */
    @Test
    fun `a chosen script survives the device switching script`() {
        val chosen = LanguagePolicy.choose(CastivioLanguage.Chinese, system("zh-Hant-TW"))
        assertEquals("zh-Hant", chosen.tag)

        val later = LanguagePolicy.resolve(chosen.tag, system("zh-Hans-CN"))
        assertEquals("zh-Hant", later.tag)
        assertEquals(CastivioLanguage.Chinese, later.language)
    }

    // -- stored values that are not usable ------------------------------------

    @Test
    fun `an unknown stored tag falls back to the device rather than to nothing`() {
        val resolved = LanguagePolicy.resolve(stored = "kl-GL", system = system("de-DE"))
        assertEquals(CastivioLanguage.German, resolved.language)
    }

    /**
     * A bare `zh` is not a variant, so it is not a usable stored value. Falling
     * back is right: it can only come from an edited or downgraded store.
     */
    @Test
    fun `a stored language without its variant is not usable`() {
        assertNull(LanguagePolicy.storedLocale("zh"))
        assertNull(LanguagePolicy.storedLocale("pt"))
        assertNotNull(LanguagePolicy.storedLocale("zh-Hans"))
        assertNotNull(LanguagePolicy.storedLocale("pt-BR"))
    }

    @Test
    fun `an empty stored tag is the same as none`() {
        assertNull(LanguagePolicy.storedLocale(""))
        assertNull(LanguagePolicy.storedLocale("   "))
        assertNull(LanguagePolicy.storedLocale(null))
    }

    // -- every language is reachable ------------------------------------------

    @Test
    fun `choosing any of the thirty-seven yields a storable tag that round-trips`() {
        for (language in CastivioLanguage.entries) {
            val chosen = LanguagePolicy.choose(language, emptyList())
            val back = LanguagePolicy.storedLocale(chosen.tag)
            assertEquals(language, back?.language)
            assertEquals(chosen.tag, back?.tag)
        }
    }

    @Test
    fun `every variant of every language round-trips, not only the default`() {
        for (language in CastivioLanguage.entries) {
            for (variant in language.variants) {
                val back = LanguagePolicy.storedLocale(variant.tag)
                assertEquals(language, back?.language)
                assertEquals(variant.tag, back?.tag)
            }
        }
    }
}
