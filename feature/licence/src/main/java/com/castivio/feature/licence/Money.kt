package com.castivio.feature.licence

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * A price, in the interface's own conventions.
 *
 * ## Why this is not a string resource
 *
 * `€6` in English, `6 €` in French, `6 €` with a non-breaking space in German,
 * `٦٫٠٠ €` where the locale uses Arabic-Indic digits — the symbol's position, the
 * separator and the digits are all facts about the locale, and none of them is a
 * translation. Putting a formatted price in `strings.xml` would be 37 files to
 * change every time a price does, and 37 chances to change 36 of them.
 *
 * `PlanOffer` carries [minor] as an integer in the currency's minor unit and
 * [currency] as ISO 4217, and the formatting happens here. No string resource on
 * this screen contains a price, a currency symbol or a period, and
 * `LicencePricingTest` fails the build if one ever does.
 *
 * ## Why the fraction digits are forced to zero
 *
 * Castivio's prices are whole euros by decision, and `€6.00` reads as a form
 * field rather than a price. A plan that ever costs €5.99 changes this line and
 * nothing else — the check is on the value, not on the formatter.
 *
 * An unknown currency code is formatted as a plain number with the code beside
 * it rather than thrown: a licence screen that crashes because a server sent a
 * currency this build has never heard of is worse than one that says `6 XTS`.
 */
internal fun formatPrice(minor: Long, currency: String, locale: Locale): String {
    val format = NumberFormat.getCurrencyInstance(locale)
    val units = minor.toDouble() / MINOR_UNITS
    return runCatching {
        format.currency = Currency.getInstance(currency)
        format.maximumFractionDigits = if (minor % MINOR_UNITS == 0L) 0 else 2
        format.minimumFractionDigits = format.maximumFractionDigits
        format.format(units)
    }.getOrElse {
        val plain = NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = if (minor % MINOR_UNITS == 0L) 0 else 2
            minimumFractionDigits = maximumFractionDigits
        }
        "${plain.format(units)} $currency"
    }
}

/** Every currency Castivio prices in has two. The euro is the only one today. */
private const val MINOR_UNITS = 100L
