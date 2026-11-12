package com.castivio.data.parsing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The panel's wall clock: read from what a panel lists, and written the way an archive is asked for.
 *
 * An archive is addressed by the time on the *panel's* clock, and the guide gives that time and the instant for the same programme,
 * so the panel's offset is read from the two and not guessed from a zone. These are the arithmetic of that, on the calendar.
 */
class XtreamPanelClockTest {

    private val second = 1_000L
    private val minute = 60 * second
    private val hour = 60 * minute

    /** 2025-11-02 21:30:00 UTC, which is what the panel's own `start_timestamp` says for the programme the fixtures use. */
    private val nov2 = 1_762_119_000_000L

    // ------------------------------------------------------------------------------- reading the clock

    @Test
    fun `a panel's clock is read onto the UTC calendar`() {
        assertEquals(0L, XtreamUrls.wallClockMs("1970-01-01 00:00:00"))
        assertEquals(nov2, XtreamUrls.wallClockMs("2025-11-02 21:30:00"))
        assertEquals(nov2, XtreamUrls.wallClockMs("2025-11-02T21:30:00"))
        assertEquals(nov2, XtreamUrls.wallClockMs("2025-11-02 21:30"))
        assertEquals(nov2 + 45 * second, XtreamUrls.wallClockMs("2025-11-02 21:30:45"))
        assertEquals(nov2, XtreamUrls.wallClockMs("  2025-11-02 21:30:00  "))
    }

    @Test
    fun `the calendar holds before 1970 and before the year 1000`() {
        // Year zero begins at -62167219200 seconds, the figure the calendar is checked against.
        assertEquals(-62_167_219_200_000L, XtreamUrls.wallClockMs("0000-01-01 00:00:00"))
        assertEquals("0000-01-01:00-00", XtreamUrls.timeshiftStartAt(-62_167_219_200_000L))
        assertEquals(-62_164_540_800_000L, XtreamUrls.wallClockMs("0000-02-01 00:00:00"))
        assertEquals("0999-12-31:23-59", XtreamUrls.timeshiftStartAt(XtreamUrls.wallClockMs("0999-12-31 23:59:00")!!))
        assertEquals("1969-07-20:20-17", XtreamUrls.timeshiftStartAt(XtreamUrls.wallClockMs("1969-07-20 20:17:40")!!))
    }

    @Test
    fun `a leap day and a year's end are where they should be`() {
        assertEquals(1_709_208_000_000L, XtreamUrls.wallClockMs("2024-02-29 12:00:00"))
        assertEquals(1_735_689_599_000L, XtreamUrls.wallClockMs("2024-12-31 23:59:59"))
        assertEquals(1_735_689_600_000L, XtreamUrls.wallClockMs("2025-01-01 00:00:00"))
    }

    @Test
    fun `text that is not a time of day on a real date is refused`() {
        assertNull(XtreamUrls.wallClockMs(""))
        assertNull(XtreamUrls.wallClockMs("2025-11-02"))
        assertNull(XtreamUrls.wallClockMs("2025-13-02 21:30:00"))
        assertNull(XtreamUrls.wallClockMs("2025-00-02 21:30:00"))
        assertNull(XtreamUrls.wallClockMs("2025-11-32 21:30:00"))
        assertNull(XtreamUrls.wallClockMs("2025-11-02 24:30:00"))
        assertNull(XtreamUrls.wallClockMs("2025-11-02 21:60:00"))
        assertNull(XtreamUrls.wallClockMs("2025-11-02 21:30:60"))
        assertNull(XtreamUrls.wallClockMs("not a time at all"))
    }

    // ------------------------------------------------------------------------------ the panel's offset

    @Test
    fun `the offset is the panel's clock against the instant it gave`() {
        assertEquals(0L, XtreamUrls.clockOffsetMs("2025-11-02 21:30:00", nov2))
        assertEquals(hour, XtreamUrls.clockOffsetMs("2025-11-02 22:30:00", nov2))
        assertEquals(-5 * hour, XtreamUrls.clockOffsetMs("2025-11-02 16:30:00", nov2))
        // Zones that are not whole hours: India is five and a half, Nepal five and three quarters.
        assertEquals(5 * hour + 30 * minute, XtreamUrls.clockOffsetMs("2025-11-03 03:00:00", nov2))
        assertEquals(5 * hour + 45 * minute, XtreamUrls.clockOffsetMs("2025-11-03 03:15:00", nov2))
    }

    @Test
    fun `a few seconds of disagreement between the two figures is not a zone`() {
        assertEquals(hour, XtreamUrls.clockOffsetMs("2025-11-02 22:30:03", nov2))
        assertEquals(hour, XtreamUrls.clockOffsetMs("2025-11-02 22:29:57", nov2))
        assertEquals(hour, XtreamUrls.clockOffsetMs("2025-11-02 22:30:00", nov2 + 400))
    }

    @Test
    fun `figures that are not a zone give no offset`() {
        // Twenty hours ahead is no zone on Earth.
        assertNull(XtreamUrls.clockOffsetMs("2025-11-03 17:30:00", nov2))
        assertNull(XtreamUrls.clockOffsetMs("2025-11-01 01:30:00", nov2))
        assertNull(XtreamUrls.clockOffsetMs("garbage", nov2))
        // Fourteen hours either way is the widest there is, and is allowed.
        assertEquals(14 * hour, XtreamUrls.clockOffsetMs("2025-11-03 11:30:00", nov2))
        assertEquals(-12 * hour, XtreamUrls.clockOffsetMs("2025-11-02 09:30:00", nov2))
    }

    // -------------------------------------------------------------------------------- writing the start

    @Test
    fun `an instant on the panel's clock is written to its minute the way the path wants it`() {
        assertEquals("1970-01-01:00-00", XtreamUrls.timeshiftStartAt(0L))
        assertEquals("2025-11-02:21-30", XtreamUrls.timeshiftStartAt(nov2))
        assertEquals("2025-11-02:21-30", XtreamUrls.timeshiftStartAt(nov2 + 59_999L))
        assertEquals("2025-11-02:21-31", XtreamUrls.timeshiftStartAt(nov2 + minute))
        assertEquals("2024-02-29:12-00", XtreamUrls.timeshiftStartAt(1_709_208_000_000L))
        assertEquals("2024-12-31:23-59", XtreamUrls.timeshiftStartAt(1_735_689_599_000L))
        assertEquals("2025-01-01:00-00", XtreamUrls.timeshiftStartAt(1_735_689_600_000L))
    }

    @Test
    fun `before 1970 and under ten are written whole`() {
        assertEquals("1969-12-31:23-59", XtreamUrls.timeshiftStartAt(-1L))
        assertEquals("2025-01-09:00-05", XtreamUrls.timeshiftStartAt(XtreamUrls.wallClockMs("2025-01-09 00:05:59")!!))
    }

    @Test
    fun `reading and writing agree with the formatter an archived programme already uses`() {
        for (text in listOf("2025-11-02 21:30:00", "2024-02-29 00:00:00", "2023-12-31 23:59:59", "2026-03-08 02:30:00")) {
            assertEquals(XtreamUrls.timeshiftStart(text), XtreamUrls.timeshiftStartAt(XtreamUrls.wallClockMs(text)!!))
        }
    }

    @Test
    fun `any instant written and read back is the minute it fell in`() {
        var t = -3 * 365 * 24 * hour
        repeat(4_000) {
            val written = XtreamUrls.timeshiftStartAt(t)
            val back = XtreamUrls.wallClockMs(written.substring(0, 10) + " " + written.substring(11, 13) + ":" + written.substring(14, 16))
            assertEquals("at $t written as $written", t - Math.floorMod(t, minute), back)
            t += 7 * hour + 13 * minute + 17 * second
        }
    }
}
