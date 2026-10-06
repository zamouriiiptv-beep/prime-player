package com.castivio.core.common.format

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The one place a date becomes a string in Castivio.
 *
 * ## Why there is one
 *
 * There were five, and they produced five different dates for the same instant. On
 * 6 October 2026 the header clock said `6 Oct 2026`, Home's expiry said `06/10/2026`,
 * Channels' header said `06-10-2026`, and the saved subscriptions and the licence
 * screen each asked the platform for its own best pattern. A reader moving between two
 * screens of the same application was being shown the same fact in two shapes and had
 * to work out that it was the same fact.
 *
 * Five call sites meant five decisions, and nobody had made them together. This is the
 * decision, made once.
 *
 * ## Why it is not the platform's own formatter
 *
 * `DateFormat.getDateInstance` and `getBestDateTimePattern` both answer "what does this
 * *language* do with a date", which is the right question for prose and the wrong one
 * here. These dates sit in a product whose language can be switched independently of
 * the device, beside other fixed-shape tokens — a clock, a MAC address, a channel
 * number — that do not reshape per locale either. A subscription that expires on the
 * sixth of October expires on the sixth of October in every language this ships in.
 *
 * [Locale.ROOT] is deliberate rather than incidental: it fixes the digits as Western
 * and the calendar as Gregorian, so an Arabic or a Persian device shows `6-10-2026`
 * and not `٦-١٠-٢٠٢٦`, which is the same choice the clock and the device address
 * already make.
 *
 * ## What this does not do
 *
 * It does not isolate the result for a right-to-left composition. That is a property of
 * where the string is drawn rather than of the string, and `:core:common` has no view
 * layer to do it in — so every caller wraps this in `ltrToken`, which is what three of
 * the five already did. A numeric date is exactly the shape that the bidirectional
 * algorithm reorders: drawn unisolated in an Arabic paragraph, `6-10-2026` can come out
 * with its parts moved, which is a wrong date rather than an ugly one.
 */
object CastivioDates {

    /**
     * Day, month, year, separated by hyphens, with no leading zero on either.
     *
     * `d` and `M` rather than `dd` and `MM`: `6-10-2026`, not `06-10-2026`. The pattern
     * is a constant rather than an argument because a second pattern is how the five
     * formats happened.
     */
    const val PATTERN: String = "d-M-yyyy"

    /** The instant as the date it falls on, in the device's own time zone. */
    fun date(atMs: Long): String =
        SimpleDateFormat(PATTERN, Locale.ROOT).format(Date(atMs))
}
