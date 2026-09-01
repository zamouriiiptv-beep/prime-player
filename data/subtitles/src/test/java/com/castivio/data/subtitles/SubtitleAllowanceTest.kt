package com.castivio.data.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * Which day a download belongs to, and when the count starts again.
 *
 * "The counter resets at midnight" is a claim about arithmetic, and it is wrong in two ways
 * people keep rediscovering: dividing UTC milliseconds by a day resets at UTC midnight —
 * the middle of the afternoon in some places — and adding a fixed offset is wrong for half
 * the year everywhere that keeps summer time.
 *
 * Plain JUnit. There is no Android in any of this, which is exactly why it was pulled out
 * into a function that can be asked about a moment rather than about now.
 */
class SubtitleAllowanceTest {

    /* ------------------------------------------------------------------------ the day */

    /** A day is a day, and the day after is the next one. */
    @Test
    fun `a day boundary is a day apart`() {
        val utc = TimeZone.getTimeZone("UTC")
        val noon = 1_767_268_800_000L // 2026-01-01T12:00:00Z

        assertEquals(localDayOf(noon, utc) + 1, localDayOf(noon + DAY, utc))
        assertEquals("an hour later is the same day", localDayOf(noon, utc), localDayOf(noon + HOUR, utc))
    }

    /**
     * Midnight is the device's midnight, not Greenwich's.
     *
     * These two moments are one hour apart, **on the same UTC day**, and on different days
     * in Casablanca — 23:30 on the first and 00:30 on the second. A count that divided UTC
     * milliseconds by a day would put them together and reset at the wrong hour for almost
     * everyone: lunchtime in Auckland, and twice in one evening in Los Angeles.
     */
    @Test
    fun `the day is the one the device is having`() {
        val casablanca = TimeZone.getTimeZone("Africa/Casablanca")
        val lateEvening = 1_767_306_600_000L // 2026-01-01T22:30:00Z — 23:30 there
        val afterMidnight = 1_767_310_200_000L // 2026-01-01T23:30:00Z — 00:30 there, the 2nd

        assertEquals(
            "one UTC day, and two days where the device is",
            localDayOf(lateEvening, casablanca) + 1,
            localDayOf(afterMidnight, casablanca),
        )
    }

    /**
     * A clock change does not skip or repeat a day.
     *
     * Local noon on the Saturday and local noon on the Sunday, across the night the United
     * Kingdom moves its clocks forward — twenty-three hours apart, and one day apart, which
     * is the pair that catches an implementation using a fixed offset. The offset has to be
     * asked about each instant, not about now.
     */
    @Test
    fun `a summer time change does not lose a day`() {
        val london = TimeZone.getTimeZone("Europe/London")
        val saturdayNoon = 1_774_699_200_000L // 2026-03-28T12:00:00Z — noon GMT
        val sundayNoon = 1_774_782_000_000L // 2026-03-29T11:00:00Z — noon BST

        assertEquals(23 * HOUR, sundayNoon - saturdayNoon)
        assertEquals(localDayOf(saturdayNoon, london) + 1, localDayOf(sundayNoon, london))
    }

    /* --------------------------------------------------------------------- the counting */

    /**
     * The limit is what makes a day spent, and zero downloads never is.
     *
     * The defect this whole type exists for, as one assertion: nothing downloaded today
     * cannot put the application in the state that says the day's downloads are used up.
     */
    @Test
    fun `nothing downloaded today is never a spent day`() {
        assertFalse(allowance(spent = 0, limit = 5).spent())
        assertFalse(allowance(spent = 4, limit = 5).spent())
        assertTrue(allowance(spent = 5, limit = 5).spent())
        assertTrue("a count beyond the limit is still spent", allowance(spent = 9, limit = 5).spent())
    }

    /**
     * A limit of zero does not make an untouched day look spent.
     *
     * Only reachable from a bad configuration — the real limit is a constant — and the
     * guard is there because the default is zero. A state that had not been filled in yet
     * would otherwise satisfy `0 >= 0` and draw the very sentence this work removed.
     */
    @Test
    fun `an unset limit never reads as a spent day`() {
        assertFalse(allowance(spent = 0, limit = 0).spent())
    }

    private fun allowance(spent: Int, limit: Int) = object : SubtitleAllowance {
        override val dailyLimit = limit
        override fun spentToday() = spent
        override fun recordDownload() = Unit
        override fun markSpent() = Unit
    }

    private companion object {
        const val HOUR = 60L * 60 * 1000
        const val DAY = 24 * HOUR
    }
}
