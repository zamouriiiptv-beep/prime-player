package com.castivio.feature.home

import com.castivio.domain.Programme
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How many days a guide page shows, and where each one begins.
 *
 * ## Why this is the part worth testing without a device
 *
 * Everything else about the page is a request and a query. This is the one piece of
 * arithmetic in it, and it is the piece the product rule lives in: *a provider offering
 * four days shows four days, and nothing pads the week out*. A grouping that invented an
 * empty Thursday would be a claim about the provider's schedule, and no test of the
 * network would catch it.
 *
 * Built against `Calendar` in the default zone, exactly as `groupByDay` is, because a day
 * boundary is a local fact: the same instant is Monday in one place and Tuesday in
 * another, and a viewer looks for a programme under the day they watched it.
 */
class GuideDaysTest {

    /**
     * **Nothing in, nothing out — and no day drawn for it.**
     *
     * A channel whose provider sent no guide must produce zero headings rather than one
     * empty today, because a heading over nothing says the provider has nothing on today
     * rather than that it sent nothing at all.
     */
    @Test
    fun `an empty guide produces no days`() {
        assertTrue(groupByDay(emptyList()).isEmpty())
    }

    /**
     * **One day of programmes is one day on screen.**
     *
     * The provider that holds a single day is the commonest case after the full week, and
     * it is the case a fixed seven-day page would have shown as one day of content and six
     * of nothing.
     */
    @Test
    fun `a single day yields a single heading`() {
        val days = groupByDay(
            listOf(
                programme("Breakfast", at(hour = 7)),
                programme("The News", at(hour = 13)),
                programme("The Film", at(hour = 21)),
            ),
        )

        assertEquals(1, days.size)
        assertEquals(3, days.single().programmes.size)
        assertEquals(midnight(0), days.single().startOfDayMs)
    }

    /**
     * **Four days of programmes are four days on screen, and the seventh is not invented.**
     *
     * The product rule, asserted as a number. A provider holding four days gets four
     * headings; nothing reaches for the ceiling.
     */
    @Test
    fun `four days of guide yield four days and no more`() {
        val days = groupByDay(
            listOf(
                programme("Mon", at(day = 0, hour = 20)),
                programme("Tue", at(day = 1, hour = 20)),
                programme("Wed early", at(day = 2, hour = 6)),
                programme("Wed late", at(day = 2, hour = 22)),
                programme("Thu", at(day = 3, hour = 20)),
            ),
        )

        assertEquals(4, days.size)
        assertEquals(listOf(1, 1, 2, 1), days.map { it.programmes.size })
    }

    /**
     * **Order is the provider's, and the page keeps it.**
     *
     * The rows arrive ordered by `start_ms` from the query, and a viewer reads a schedule
     * downward. A grouping that sorted by anything else — a hash map's iteration order, a
     * set — would shuffle a day's evening before its morning.
     */
    @Test
    fun `days and their programmes keep the order they arrived in`() {
        val days = groupByDay(
            listOf(
                programme("first", at(day = 0, hour = 6)),
                programme("second", at(day = 0, hour = 18)),
                programme("third", at(day = 1, hour = 9)),
            ),
        )

        assertEquals(listOf(midnight(0), midnight(1)), days.map { it.startOfDayMs })
        assertEquals(listOf("first", "second"), days[0].programmes.map { it.title })
        assertEquals(listOf("third"), days[1].programmes.map { it.title })
    }

    /**
     * **A programme that runs past midnight belongs to the day it started on.**
     *
     * Grouped by `startMs` and never by `stopMs`. A film beginning at 23:30 is looked for
     * under the night it began, which is also where the provider's own listing puts it;
     * filing it under the following morning would hide it from both.
     */
    @Test
    fun `a programme crossing midnight stays on the day it began`() {
        val start = at(day = 0, hour = 23, minute = 30)
        val days = groupByDay(
            listOf(Programme("c", "Late Film", null, start, start + 3 * HOUR_MS)),
        )

        assertEquals(1, days.size)
        assertEquals(midnight(0), days.single().startOfDayMs)
    }

    // ------------------------------------------------------------------------ helpers

    private fun programme(title: String, startMs: Long) =
        Programme("c", title, null, startMs, startMs + HOUR_MS)

    /** Midnight of today plus [day], in the zone this test and `groupByDay` both read. */
    private fun midnight(day: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, day)
    }.timeInMillis

    /**
     * An instant on a named day, built through `Calendar` rather than by adding hours.
     *
     * Days are not all twenty-four hours long. A test that stepped by `24 * HOUR_MS`
     * across a daylight-saving boundary would land on the wrong day twice a year and
     * would fail for the calendar's reasons rather than the grouping's.
     */
    private fun at(day: Int = 0, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }.timeInMillis

    private companion object {
        const val HOUR_MS = 60 * 60 * 1000L
    }
}
