package com.castivio.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which places a channel's archive offers to rewind to, and where each one is.
 *
 * Time is given, never read: "now" is a number, so every rule here is a few additions and no device.
 */
class TimeShiftPolicyTest {

    private val minute = CatchUpPolicy.MINUTE_MS
    private val hour = CatchUpPolicy.HOUR_MS

    /** A minute boundary, and a "now" thirty seconds past it, because the real one is never on a minute. */
    private val base = 28_333_333L * minute
    private val now = base + 30_000L

    private fun span(startAgoMin: Double, stopAgoMin: Double, archived: Boolean = true) = ArchiveSpan(
        startMs = now - (startAgoMin * minute).toLong(),
        stopMs = now - (stopAgoMin * minute).toLong(),
        archived = archived,
    )

    /**
     * A channel that keeps five hours: four recorded programmes of an hour each, back to five hours ago; the programme before the
     * one on air, recorded; and the one on air, begun [airedMin] minutes ago and not marked yet, as a panel leaves it.
     */
    private fun channel(airedMin: Double = 40.0, offset: Long? = 3_600_000L): ArchiveCover {
        val recorded = (0 until 4).map { i -> span(startAgoMin = 300.0 - i * 60.0, stopAgoMin = 240.0 - i * 60.0) }
        val previous = if (airedMin < 60.0) listOf(span(startAgoMin = 60.0, stopAgoMin = airedMin)) else emptyList()
        val onAir = span(startAgoMin = airedMin, stopAgoMin = airedMin - 60.0, archived = false)
        return ArchiveCover(spans = recorded + previous + onAir, clockOffsetMs = offset)
    }

    private fun choices(cover: ArchiveCover, hours: Int = 5) = TimeShiftPolicy.choices(cover, now, hours)

    // ------------------------------------------------------------------------------ what is offered

    @Test
    fun `a channel whose archive is proved to reach back is offered the four places in the strip's order`() {
        assertEquals(
            listOf(
                TimeShiftChoice.PROGRAMME_START,
                TimeShiftChoice.MINUTES_15,
                TimeShiftChoice.MINUTES_30,
                TimeShiftChoice.MINUTES_60,
            ),
            choices(channel(airedMin = 40.0)),
        )
    }

    @Test
    fun `a channel with no recorded programme in what the provider listed is offered nothing`() {
        val unmarked = ArchiveCover(
            spans = listOf(span(300.0, 240.0, archived = false), span(40.0, -20.0, archived = false)),
            clockOffsetMs = 3_600_000L,
        )
        assertTrue(choices(unmarked).isEmpty())
        assertTrue(choices(ArchiveCover(spans = emptyList(), clockOffsetMs = 3_600_000L)).isEmpty())
    }

    @Test
    fun `without the provider's clock there is no way to address an instant, so nothing is offered`() {
        assertTrue(choices(channel(offset = null)).isEmpty())
    }

    @Test
    fun `a claim of no hours offers nothing however much was listed`() {
        assertTrue(choices(channel(), hours = 0).isEmpty())
        assertTrue(choices(channel(), hours = -1).isEmpty())
    }

    @Test
    fun `only what the archive is known to reach is offered`() {
        // The oldest recorded programme began 20 minutes ago: a quarter of an hour is inside it, half an hour is not. The programme
        // on air began a minute ago, which is too near the edge to be a place to go back to.
        val shallow = ArchiveCover(
            spans = listOf(span(20.0, 1.0, archived = true), span(1.0, -59.0, archived = false)),
            clockOffsetMs = 0L,
        )
        assertEquals(listOf(TimeShiftChoice.MINUTES_15), choices(shallow))
    }

    @Test
    fun `the programme on air is a choice of its own when it began long enough ago`() {
        val shallow = ArchiveCover(
            spans = listOf(span(29.0, 18.0, archived = true), span(18.0, -42.0, archived = false)),
            clockOffsetMs = 0L,
        )
        assertEquals(listOf(TimeShiftChoice.PROGRAMME_START, TimeShiftChoice.MINUTES_15), choices(shallow))
    }

    @Test
    fun `a programme that has only just begun is not a place to go back to`() {
        // 90 seconds is under the two minutes below which a rewind is not worth the name.
        assertEquals(
            listOf(TimeShiftChoice.MINUTES_15, TimeShiftChoice.MINUTES_30, TimeShiftChoice.MINUTES_60),
            choices(channel(airedMin = 1.5)),
        )
    }

    @Test
    fun `no programme on air in the guide means no choice that names one`() {
        val noOnAir = channel().let { it.copy(spans = it.spans.filter { s -> s.stopMs <= now }) }
        assertEquals(
            listOf(TimeShiftChoice.MINUTES_15, TimeShiftChoice.MINUTES_30, TimeShiftChoice.MINUTES_60),
            choices(noOnAir),
        )
    }

    @Test
    fun `two choices that are one place are offered once, as the programme`() {
        // The programme began 29.5 minutes ago: "30 minutes before" is the same place to within a minute.
        assertEquals(
            listOf(TimeShiftChoice.PROGRAMME_START, TimeShiftChoice.MINUTES_15, TimeShiftChoice.MINUTES_60),
            choices(channel(airedMin = 29.5)),
        )
        // And a programme that began a quarter of an hour ago swallows the quarter of an hour.
        assertEquals(
            listOf(TimeShiftChoice.PROGRAMME_START, TimeShiftChoice.MINUTES_30, TimeShiftChoice.MINUTES_60),
            choices(channel(airedMin = 15.0)),
        )
    }

    @Test
    fun `the provider's claim is a ceiling and not a floor`() {
        // A guide that goes back five hours on a channel that claims one: what is older than the claim is no proof, so what is
        // known is the last fifty minutes, and an hour back is not in it.
        val cover = ArchiveCover(
            spans = listOf(span(300.0, 240.0), span(50.0, 25.0), span(25.0, -35.0, archived = false)),
            clockOffsetMs = 0L,
        )
        assertEquals(
            listOf(TimeShiftChoice.PROGRAMME_START, TimeShiftChoice.MINUTES_15, TimeShiftChoice.MINUTES_30),
            choices(cover, hours = 1),
        )
    }

    @Test
    fun `a programme the provider listed, finished and did not record is a hole that is not offered`() {
        // Between 70 and 50 minutes ago nothing was recorded: an hour back falls in it, half an hour and a quarter do not.
        val cover = ArchiveCover(
            spans = listOf(
                span(300.0, 70.0, archived = true),
                span(70.0, 50.0, archived = false),
                span(50.0, 20.0, archived = true),
                span(20.0, -40.0, archived = false),
            ),
            clockOffsetMs = 0L,
        )
        assertEquals(
            listOf(TimeShiftChoice.PROGRAMME_START, TimeShiftChoice.MINUTES_15, TimeShiftChoice.MINUTES_30),
            choices(cover),
        )
    }

    @Test
    fun `a programme that ended a moment ago and is not marked yet is not taken for a hole`() {
        // A panel marks a programme some time after it ends, and the recording is there. This one ended ten minutes ago.
        val cover = ArchiveCover(
            spans = listOf(
                span(300.0, 70.0, archived = true),
                span(70.0, 10.0, archived = false),
                span(10.0, -50.0, archived = false),
            ),
            clockOffsetMs = 0L,
        )
        assertEquals(
            listOf(
                TimeShiftChoice.PROGRAMME_START,
                TimeShiftChoice.MINUTES_15,
                TimeShiftChoice.MINUTES_30,
                TimeShiftChoice.MINUTES_60,
            ),
            choices(cover),
        )
    }


    @Test
    fun `a programme that is still on is no proof that the archive reaches anywhere`() {
        // The only programme marked is the one on air: nothing that is over has been recorded yet.
        val onlyOnAir = ArchiveCover(spans = listOf(span(30.0, -30.0, archived = true)), clockOffsetMs = 0L)
        assertNull(TimeShiftPolicy.earliestMs(onlyOnAir, now, windowHours = 5))
        assertTrue(choices(onlyOnAir).isEmpty())
    }

    @Test
    fun `exactly two minutes behind is a place and a moment under it is not`() {
        assertEquals(TimeShiftChoice.PROGRAMME_START, choices(channel(airedMin = 2.0)).first())
        assertTrue(TimeShiftChoice.PROGRAMME_START !in choices(channel(airedMin = 1.99)))
    }

    @Test
    fun `a hole ends where the next programme begins, and that instant is not in it`() {
        // Nothing recorded between 70 and 50 minutes ago, and the programme on air began at the minute the hole ended.
        val cover = ArchiveCover(
            spans = listOf(
                span(300.0, 70.0, archived = true),
                span(70.0, 50.0, archived = false),
                span(50.0, -10.0, archived = false),
            ),
            clockOffsetMs = 0L,
        )
        val offered = choices(cover)
        assertEquals(TimeShiftChoice.PROGRAMME_START, offered.first())
        assertEquals(now - 50 * minute, TimeShiftPolicy.targetMs(TimeShiftChoice.PROGRAMME_START, cover, now))
        // An hour back is inside the hole.
        assertTrue(TimeShiftChoice.MINUTES_60 !in offered)
    }

    @Test
    fun `a programme that was recorded is never a hole, however long ago it ended`() {
        val cover = ArchiveCover(
            spans = listOf(
                span(300.0, 100.0, archived = true),
                span(100.0, 50.0, archived = true),
                span(50.0, -10.0, archived = false),
            ),
            clockOffsetMs = 0L,
        )
        assertEquals(
            listOf(
                TimeShiftChoice.PROGRAMME_START,
                TimeShiftChoice.MINUTES_15,
                TimeShiftChoice.MINUTES_30,
                TimeShiftChoice.MINUTES_60,
            ),
            choices(cover),
        )
    }

    @Test
    fun `a choice exactly a minute from the programme is another place, and under a minute is the same one`() {
        // Began 29 minutes ago: half an hour is a minute away, and is kept.
        assertEquals(
            listOf(
                TimeShiftChoice.PROGRAMME_START,
                TimeShiftChoice.MINUTES_15,
                TimeShiftChoice.MINUTES_30,
                TimeShiftChoice.MINUTES_60,
            ),
            choices(channel(airedMin = 29.0)),
        )
    }

    // ------------------------------------------------------------------------------ where a choice is

    @Test
    fun `a distance is measured from now and a programme is where the guide says it began`() {
        val cover = channel(airedMin = 40.0)
        assertEquals(now - 15 * minute, TimeShiftPolicy.targetMs(TimeShiftChoice.MINUTES_15, cover, now))
        assertEquals(now - 30 * minute, TimeShiftPolicy.targetMs(TimeShiftChoice.MINUTES_30, cover, now))
        assertEquals(now - hour, TimeShiftPolicy.targetMs(TimeShiftChoice.MINUTES_60, cover, now))
        assertEquals(now - 40 * minute, TimeShiftPolicy.targetMs(TimeShiftChoice.PROGRAMME_START, cover, now))
    }

    @Test
    fun `no programme on air leaves the start of it nowhere`() {
        val cover = ArchiveCover(spans = listOf(span(300.0, 240.0)), clockOffsetMs = 0L)
        assertNull(TimeShiftPolicy.targetMs(TimeShiftChoice.PROGRAMME_START, cover, now))
    }

    @Test
    fun `the oldest recorded start counts only for what is over, marked, inside the window and not empty`() {
        val cover = ArchiveCover(
            spans = listOf(
                span(400.0, 380.0, archived = true), // before the five-hour claim
                span(250.0, 250.0, archived = true), // a gap in a guide, not a programme
                span(200.0, 180.0, archived = false), // not marked
                span(150.0, 120.0, archived = true), // the oldest that counts
                span(100.0, 80.0, archived = true), // newer, and counts too
                span(30.0, -30.0, archived = true), // not over yet
            ),
            clockOffsetMs = 0L,
        )
        assertEquals(now - 150 * minute, TimeShiftPolicy.earliestMs(cover, now, windowHours = 5))
        assertNull(TimeShiftPolicy.earliestMs(ArchiveCover(emptyList(), 0L), now, windowHours = 5))
    }

    @Test
    fun `the programme on air is the one that covers now, and an empty entry is not one`() {
        val cover = ArchiveCover(
            spans = listOf(span(10.0, 10.0, archived = false), span(40.0, -20.0, archived = false), span(100.0, 40.0)),
            clockOffsetMs = 0L,
        )
        assertEquals(now - 40 * minute, TimeShiftPolicy.onAir(cover, now)?.startMs)
        assertNull(TimeShiftPolicy.onAir(ArchiveCover(listOf(span(100.0, 40.0)), 0L), now))
    }

    // ------------------------------------------------------------------------------ how it is asked for

    @Test
    fun `an archive is addressed by the minute and asked for from the start of that minute`() {
        assertEquals(base, TimeShiftPolicy.minuteOf(base + 44_000L))
        assertEquals(base, TimeShiftPolicy.minuteOf(base))
        assertEquals(-minute, TimeShiftPolicy.minuteOf(-1L))
    }

    @Test
    fun `it is asked for up to now and then on past it, so that a rewind stays a rewind`() {
        // Fifteen minutes back, both on the minute: fifteen minutes to now and then the stretch past it.
        assertEquals(15 + TimeShiftPolicy.CONTINUE_MINUTES, TimeShiftPolicy.durationMinutes(base - 15 * minute, base))
        // Now thirty seconds into a minute: the minute the instant falls in began thirty seconds earlier, and part of a minute is a
        // whole one asked for.
        assertEquals(16 + TimeShiftPolicy.CONTINUE_MINUTES, TimeShiftPolicy.durationMinutes(now - 15 * minute, now))
        // An instant in the future is not a negative length.
        assertEquals(TimeShiftPolicy.CONTINUE_MINUTES, TimeShiftPolicy.durationMinutes(now + hour, now))
    }

    @Test
    fun `the choices come in the strip's order and name each place once`() {
        val all = TimeShiftChoice.entries
        assertEquals(listOf(null, 15, 30, 60), all.map { it.minutes })
        assertEquals(all.size, all.toSet().size)
    }
}
