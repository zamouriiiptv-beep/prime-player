package com.castivio.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which programmes of an archive can be watched, how they fall into days, and what is remembered about a channel.
 *
 * Time is given, never read: "now" is a number and the viewer's zone is a function, so a week is a few additions.
 */
class CatchUpTest {

    private val hour = CatchUpPolicy.HOUR_MS
    private val day = CatchUpPolicy.DAY_MS
    private val now = 10 * day + 12 * hour

    private fun programme(startMs: Long, hours: Double = 1.0, title: String = "P") = CatchUpProgramme(
        startMs = startMs,
        stopMs = startMs + (hours * hour).toLong(),
        title = title,
        description = null,
        locator = "L$startMs",
    )

    // ------------------------------------------------------------------------------------ offered

    @Test
    fun `a channel offers catch-up only when the catalogue says it has hours of archive`() {
        assertFalse(CatchUpPolicy.offered(null))
        assertFalse(CatchUpPolicy.offered(0))
        assertFalse(CatchUpPolicy.offered(-24))
        assertTrue(CatchUpPolicy.offered(24))
        assertTrue(CatchUpPolicy.offered(7 * 24))
    }

    // ---------------------------------------------------------------------------------- replayable

    @Test
    fun `only a programme that is over and inside the window can be replayed, newest first`() {
        val window = 3 * 24
        val items = listOf(
            programme(now - 5 * hour, title = "ended"),
            programme(now - 30 * hour, title = "older"),
            programme(now - 1 * hour, hours = 2.0, title = "still on"),
            programme(now + 2 * hour, title = "future"),
            programme(now - 4 * day, title = "outside the window"),
            programme(now - 10 * hour, hours = 0.0, title = "gap"),
        )

        val shown = CatchUpPolicy.replayable(items, now, window)

        assertEquals(listOf("ended", "older"), shown.map { it.title })
    }

    @Test
    fun `a programme that began exactly at the edge of the window is still inside it`() {
        val edge = programme(now - 24 * hour, title = "edge")

        assertEquals(listOf("edge"), CatchUpPolicy.replayable(listOf(edge), now, 24).map { it.title })
    }

    @Test
    fun `a programme that ends exactly now is over`() {
        val justOver = programme(now - hour, title = "just over")

        assertEquals(1, CatchUpPolicy.replayable(listOf(justOver), now, 24).size)
    }

    // ---------------------------------------------------------------------------------------- days

    @Test
    fun `programmes fall into the viewer's days, the newest day and the newest programme first`() {
        val monday = 7 * day            // a UTC midnight
        val items = listOf(
            programme(monday + 8 * hour, title = "mon morning"),
            programme(monday + 20 * hour, title = "mon evening"),
            programme(monday + day + 1 * hour, title = "tue early"),
        )

        val days = CatchUpPolicy.days(items) { 0L }

        assertEquals(2, days.size)
        assertEquals(monday + day, days[0].dayStartMs)
        assertEquals(listOf("tue early"), days[0].programmes.map { it.title })
        assertEquals(monday, days[1].dayStartMs)
        assertEquals(listOf("mon evening", "mon morning"), days[1].programmes.map { it.title })
    }

    @Test
    fun `a day is the viewer's own, so a programme just after midnight UTC belongs to the evening before for a viewer west of it`() {
        val utcMidnight = 7 * day
        val late = programme(utcMidnight + 30 * CatchUpPolicy.MINUTE_MS, title = "00:30 UTC")
        val westOfUtc = -5 * hour

        val days = CatchUpPolicy.days(listOf(late)) { westOfUtc }

        // 00:30 UTC is 19:30 the day before for UTC-5, so the day began five hours before the previous UTC midnight's end.
        assertEquals(utcMidnight - day - westOfUtc, days.single().dayStartMs)
    }

    @Test
    fun `the offset is asked at each programme, so a daylight change inside the week is followed`() {
        val a = programme(7 * day + 10 * hour, title = "before the change")
        val b = programme(9 * day + 10 * hour, title = "after the change")
        val offsetAt = { atMs: Long -> if (atMs < 8 * day) 0L else hour }

        val days = CatchUpPolicy.days(listOf(a, b), offsetAt)

        assertEquals(7 * day, days[1].dayStartMs)
        assertEquals(9 * day - hour, days[0].dayStartMs)
    }

    @Test
    fun `no programmes is no days`() {
        assertTrue(CatchUpPolicy.days(emptyList()) { 0L }.isEmpty())
    }

    // --------------------------------------------------------------------------------- duration

    @Test
    fun `a duration is whole minutes, rounded up, and never less than one`() {
        assertEquals(60, CatchUpPolicy.durationMinutes(programme(0, hours = 1.0)))
        assertEquals(31, CatchUpPolicy.durationMinutes(CatchUpProgramme(0, 30 * 60_000L + 1, "x", null, "l")))
        assertEquals(1, CatchUpPolicy.durationMinutes(CatchUpProgramme(0, 10_000L, "x", null, "l")))
        assertEquals(1, CatchUpPolicy.durationMinutes(CatchUpProgramme(5, 5, "x", null, "l")))
    }

    // ------------------------------------------------------------------------------------ memory

    @Test
    fun `a listing is remembered for its time and then asked again`() {
        val memory = CatchUpMemory(listingTtlMs = 1_000, emptyTtlMs = 5_000)
        val items = listOf(programme(1))

        memory.remember("c1", items, nowMs = 100)

        assertEquals(items, memory.listing("c1", nowMs = 1_100))
        assertNull(memory.listing("c1", nowMs = 1_101))
        assertNull("it is gone, not kept past its time", memory.listing("c1", nowMs = 100))
    }

    @Test
    fun `a channel found empty is known empty for longer than its listing is kept`() {
        val memory = CatchUpMemory(listingTtlMs = 1_000, emptyTtlMs = 5_000)

        memory.remember("c1", emptyList(), nowMs = 0)

        assertTrue(memory.knownEmpty("c1", nowMs = 4_999))
        assertTrue(memory.knownEmpty("c1", nowMs = 5_000))
        assertFalse(memory.knownEmpty("c1", nowMs = 5_001))
    }

    @Test
    fun `a channel that has programmes again is no longer known empty`() {
        val memory = CatchUpMemory()

        memory.remember("c1", emptyList(), nowMs = 0)
        memory.remember("c1", listOf(programme(1)), nowMs = 10)

        assertFalse(memory.knownEmpty("c1", nowMs = 20))
    }

    @Test
    fun `a channel never asked for is not known empty`() {
        assertFalse(CatchUpMemory().knownEmpty("never", nowMs = 0))
    }

    @Test
    fun `the memory is bounded and forgets the oldest channel first`() {
        val memory = CatchUpMemory(capacity = 2)

        memory.remember("a", listOf(programme(1)), nowMs = 0)
        memory.remember("b", listOf(programme(2)), nowMs = 1)
        memory.remember("c", listOf(programme(3)), nowMs = 2)

        assertNull(memory.listing("a", nowMs = 3))
        assertEquals(1, memory.listing("b", nowMs = 3)?.size)
        assertEquals(1, memory.listing("c", nowMs = 3)?.size)
    }

    @Test
    fun `a clock that went backwards does not keep a listing`() {
        val memory = CatchUpMemory()
        memory.remember("c1", listOf(programme(1)), nowMs = 1_000)

        assertNull(memory.listing("c1", nowMs = 500))
    }
}
