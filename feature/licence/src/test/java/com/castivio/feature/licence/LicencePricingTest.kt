package com.castivio.feature.licence

import android.content.Context
import android.content.res.Configuration
import com.castivio.core.common.locale.CastivioLanguage
import com.castivio.domain.entitlement.Plan
import com.castivio.domain.entitlement.PricingDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Locale

/**
 * The price is configuration, and it is nowhere else.
 *
 * ## The rule
 *
 * A price is a business decision that changes without a release. `PricingConfig`
 * exists so that changing one is one edit, and the way that promise dies is a
 * number copied into a screen or, far worse, into a translation — because a price
 * inside `strings.xml` is a price in 38 files that nobody can change at all.
 *
 * So this asserts the negative across every locale, which is the only form of the
 * claim that stays true: not "the price comes from the config" — that is visible
 * in the source and was visible in the source the last time a hardcoded value
 * shipped — but "no resource anywhere contains one".
 */
@RunWith(RobolectricTestRunner::class)
class LicencePricingTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    /**
     * Nothing in **any** language contains a price, a currency or a period.
     *
     * Every locale, not the default one. A check named "in any locale" that reads
     * one locale is the shape of mistake this project has now made four times,
     * and it is the shape that survives review because the name is right.
     *
     * The strings are resolved out of the compiled resource table through a
     * configuration context, so this is what the app would render rather than
     * what a file says.
     */
    @Test
    fun `no licence string in any locale carries a price`() {
        val forbidden = listOf("€", "EUR", "$", "£", "6,00", "6.00", "15,00", "15.00")
        val keys = R.string::class.java.fields.filter { it.name.startsWith("licence_") }
        val offences = mutableListOf<String>()

        for (language in CastivioLanguage.entries) {
            for (variant in language.variants) {
                val localised = context.forTag(variant.tag)
                for (field in keys) {
                    val value = localised.getString(field.getInt(null))
                    for (token in forbidden) {
                        if (value.contains(token)) {
                            offences += "${variant.tag}/${field.name} carries '$token': $value"
                        }
                    }
                }
            }
        }

        assertTrue(
            "a price reached a string resource in ${offences.size} place(s):\n" +
                offences.joinToString("\n"),
            offences.isEmpty(),
        )
    }

    /**
     * And every locale really was reached, rather than falling back to English.
     *
     * Without this the check above is satisfied by 37 copies of the same English
     * bundle -- which is precisely the state it is meant to detect. The sentinel
     * names the directory the strings came from.
     */
    @Test
    fun `the price check actually visited every language`() {
        val seen = CastivioLanguage.entries.flatMap { it.variants }
            .map { context.forTag(it.tag).getString(R.string.licence_sentinel) }
            .toSet()
        assertTrue(
            "only ${seen.size} distinct locale bundles were reached: $seen",
            seen.size >= CastivioLanguage.entries.size,
        )
    }

    private fun Context.forTag(tag: String): Context {
        val configuration = Configuration(resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(tag))
        return createConfigurationContext(configuration)
    }

    /**
     * The two plans on offer are the two the config sells, and the trial is not
     * one of them.
     *
     * A trial is granted, not sold. It is in `PricingConfig.plans` because the
     * policy needs its duration, and `purchasable` is the filter that keeps it
     * off a screen that takes money.
     */
    @Test
    fun `the screen offers exactly the purchasable plans`() {
        val offered = PricingDefaults.config.purchasable.map { it.plan }
        assertEquals(listOf(Plan.ANNUAL, Plan.LIFETIME), offered)
        assertFalse("the trial is being offered for sale", offered.contains(Plan.TRIAL))
    }

    /** Every plan is priced in one currency; "cheaper" has to mean something. */
    @Test
    fun `every offered plan is priced in the same currency`() {
        val currencies = PricingDefaults.config.purchasable.map { it.currency }.toSet()
        assertEquals(setOf("EUR"), currencies)
    }

    /**
     * The amount reads as money in the locale's own conventions.
     *
     * Not asserted as a literal string: the exact spacing and symbol position are
     * the JDK's business and they differ by platform version. What is asserted is
     * what the design depends on — the digits are there, the currency is there,
     * and a whole-euro price has no decimals hanging off it.
     */
    @Test
    fun `a whole price is formatted without decimals`() {
        for (locale in listOf(Locale.UK, Locale.FRANCE, Locale.GERMANY, Locale("ar", "EG"))) {
            val text = formatPrice(600, "EUR", locale)
            assertFalse(
                "$locale formatted 600 minor as '$text', with decimals",
                text.contains("00") || text.contains("٠٠"),
            )
            // The symbol, or the code if the locale prefers it. Which of the two
            // a JDK picks is its business and it changes between versions; that
            // the amount is presented *as money* is the design's claim.
            assertTrue(
                "$locale lost the currency: '$text'",
                text.contains("€") || text.contains("EUR", ignoreCase = true),
            )
            assertTrue("$locale lost the amount: '$text'", text.any { it.isDigit() })
        }
    }

    /** A part-euro price keeps its decimals. The rule is on the value, not the format. */
    @Test
    fun `a price with minor units keeps them`() {
        assertTrue(formatPrice(599, "EUR", Locale.UK).contains("99"))
    }

    /**
     * An unknown currency is a bad line in a config, not a crash on a gate screen.
     *
     * The licence screen is the one a blocked device is sent to. If it throws,
     * the user has no screen at all and no way to buy their way out of it.
     */
    @Test
    fun `an unknown currency degrades instead of throwing`() {
        val text = formatPrice(600, "XTS", Locale.UK)
        assertTrue("the amount is missing from '$text'", text.contains("6"))
        assertTrue("the code is missing from '$text'", text.contains("XTS"))
    }
}
