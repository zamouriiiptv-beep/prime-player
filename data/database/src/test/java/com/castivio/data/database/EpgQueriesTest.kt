package com.castivio.data.database

import com.castivio.domain.EpgProgramme
import com.castivio.domain.EpgRetention
import com.castivio.domain.EpgSummary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The guide, stored and queried against real SQLite.
 *
 * The engine that feeds this is covered by pure tests; what matters here is that
 * the SQL is right — a wrong now/next boundary shows the wrong programme on every
 * channel row in the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpgQueriesTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    /** 2026-07-25 12:00:00 UTC. */
    private val now = 1_784_980_800_000L

    private lateinit var database: CastivioDatabase

    @Before
    fun setUp() {
        database = CastivioDatabase.inMemory(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `now and next come back for each channel`() = runBlocking {
        write(
            listOf(
                programme("a", "earlier", now - 2 * hour, now - hour),
                programme("a", "on now", now - 30 * minute, now + 30 * minute),
                programme("a", "up next", now + 30 * minute, now + 90 * minute),
                programme("b", "starts later", now + hour, now + 2 * hour),
            ),
        )

        val nowNext = repository().nowNext(listOf("a", "b"), now)

        assertEquals("on now", nowNext.getValue("a").now?.title)
        assertEquals("up next", nowNext.getValue("a").next?.title)
        // Nothing on air: "next" is simply what starts next.
        assertNull(nowNext.getValue("b").now)
        assertEquals("starts later", nowNext.getValue("b").next?.title)
    }

    @Test
    fun `a channel with no guide is absent rather than wrong`() = runBlocking {
        write(listOf(programme("a", "on now", now - minute, now + hour)))

        val nowNext = repository().nowNext(listOf("a", "unknown"), now)

        assertTrue(nowNext.containsKey("a"))
        assertFalse("a channel with no rows must not appear", nowNext.containsKey("unknown"))
    }

    /**
     * Guides have holes. Showing next Tuesday's film as "up next" is worse than
     * showing nothing, so the query stops at a horizon.
     */
    @Test
    fun `a hole in the guide does not promote a distant programme to next`() = runBlocking {
        write(
            listOf(
                programme("a", "on now", now - minute, now + hour),
                programme("a", "next week", now + 6 * day, now + 6 * day + hour),
            ),
        )

        val nowNext = repository().nowNext(listOf("a"), now)

        assertEquals("on now", nowNext.getValue("a").now?.title)
        assertNull(nowNext.getValue("a").next)
    }

    @Test
    fun `the grid window returns only what overlaps the visible range`() = runBlocking {
        write(
            listOf(
                programme("a", "before", now - 3 * hour, now - 2 * hour),
                programme("a", "overlapping start", now - 30 * minute, now + 30 * minute),
                programme("a", "inside", now + 30 * minute, now + 90 * minute),
                programme("a", "after", now + 5 * hour, now + 6 * hour),
            ),
        )

        val window = repository().window(listOf("a"), now, now + 2 * hour)

        assertEquals(
            listOf("overlapping start", "inside"),
            window.getValue("a").map { it.title },
        )
    }

    @Test
    fun `one channel's schedule comes back in order`() = runBlocking {
        write(
            listOf(
                programme("a", "third", now + 2 * hour, now + 3 * hour),
                programme("a", "first", now, now + hour),
                programme("a", "second", now + hour, now + 2 * hour),
            ),
        )

        val schedule = repository().programmes("a", now, now + 4 * hour)

        assertEquals(listOf("first", "second", "third"), schedule.map { it.title })
        assertEquals(hour, schedule.first().durationMs)
        assertTrue(schedule.first().isLiveAt(now + minute))
        assertEquals(0.5f, schedule.first().progressAt(now + 30 * minute), 0.01f)
    }

    /**
     * The reason the primary key is `(channel_id, start_ms)`: a nightly refresh
     * overlaps yesterday's download, and duplicated rows would show the same
     * programme twice in the grid.
     */
    @Test
    fun `re-importing an overlapping guide does not duplicate rows`() = runBlocking {
        val programmes = listOf(
            programme("a", "on now", now, now + hour),
            programme("a", "up next", now + hour, now + 2 * hour),
        )
        write(programmes)
        write(programmes.map { it.copy(title = it.title + " (updated)") })

        assertEquals(2, database.epgDao().programmeCount().first())
        assertEquals("on now (updated)", repository().nowNext(listOf("a"), now).getValue("a").now?.title)
    }

    @Test
    fun `retention prunes what aged out when the import finishes`() = runBlocking {
        write(
            listOf(
                programme("a", "last week", now - 8 * day, now - 8 * day + hour),
                programme("a", "yesterday", now - 20 * hour, now - 19 * hour),
                programme("a", "on now", now - minute, now + hour),
                programme("a", "next month", now + 30 * day, now + 30 * day + hour),
            ),
        )

        // The writer prunes at finish; the engine also refuses out-of-window rows
        // at import, so this is the belt to that braces.
        val titles = database.epgDao().window(listOf("a"), now - 30 * day, now + 60 * day).map { it.title }
        assertEquals(listOf("yesterday", "on now"), titles)
    }

    @Test
    fun `coverage reports what is actually stored`() = runBlocking {
        write(
            listOf(
                programme("a", "first", now, now + hour),
                programme("b", "second", now + hour, now + 2 * hour),
            ),
        )

        val coverage = repository().coverage().first()

        assertEquals(2, coverage.programmes)
        assertEquals(2, coverage.channels)
        assertEquals(now, coverage.earliestMs)
        assertEquals(now + 2 * hour, coverage.latestMs)
    }

    @Test
    fun `freshness is about how far the guide reaches, not how many rows it has`() = runBlocking {
        // Plenty of rows, but the guide ends in two hours.
        write((0 until 50).map { programme("a", "slot $it", now + it * 2 * minute, now + (it + 1) * 2 * minute) })

        val repository = repository()
        assertFalse(repository.hasFreshGuide(now, minimumHorizonMs = 6 * hour))
        assertTrue(repository.hasFreshGuide(now, minimumHorizonMs = 30 * minute))
    }

    /**
     * SQLite's bind-variable limit is 999 on the versions shipped with older
     * Android. A guide grid can ask about more channels than that, and without
     * chunking this fails with "too many SQL variables" on exactly the cheap boxes
     * this app targets.
     */
    @Test
    fun `more channels than SQLite allows variables still works`() = runBlocking {
        val channels = 900
        write((0 until channels).map { programme("ch$it", "on now", now - minute, now + hour) })

        val nowNext = repository().nowNext((0 until channels).map { "ch$it" }, now)

        assertEquals(channels, nowNext.size)
        assertTrue(nowNext.values.all { it.now?.title == "on now" })
    }

    @Test
    fun `a guide can be dropped per source`() = runBlocking {
        write(listOf(programme("a", "from source one", now, now + hour)), sourceId = "one")
        write(listOf(programme("b", "from source two", now, now + hour)), sourceId = "two")
        assertEquals(2, database.epgDao().programmeCount().first())

        database.epgDao().deleteSource("one")

        assertEquals(1, database.epgDao().programmeCount().first())
        assertEquals("from source two", repository().nowNext(listOf("b"), now).getValue("b").now?.title)
    }

    // ------------------------------------------------------------------ fixtures

    private fun programme(channel: String, title: String, startMs: Long, stopMs: Long) =
        EpgProgramme(
            channelId = channel,
            title = title,
            description = "Description of $title",
            startMs = startMs,
            stopMs = stopMs,
        )

    /** Writes through the real writer, including its finish-time retention pass. */
    private fun write(
        programmes: List<EpgProgramme>,
        sourceId: String = "src",
        retention: EpgRetention = EpgRetention.DEFAULT,
    ) {
        val writer = RoomEpgWriter(database, retention) { now }
        writer.begin(sourceId)
        writer.writeProgrammes(programmes)
        writer.commit()
        writer.finish(
            EpgSummary(
                sourceId = sourceId,
                programmes = programmes.size,
                channels = programmes.map { it.channelId }.distinct().size,
                skipped = 0,
                outsideWindow = 0,
                durationMs = 1,
            ),
        )
    }

    private fun repository() = RoomEpgRepository(database.epgDao()) { now }
}
