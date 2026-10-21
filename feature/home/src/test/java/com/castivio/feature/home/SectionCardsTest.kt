package com.castivio.feature.home

import com.castivio.core.common.AppError
import com.castivio.domain.MediaKind
import com.castivio.domain.SectionLoad
import com.castivio.domain.SectionRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The line under each card on Home, one test per state the approved drawing shows
 * (`design/mockups/home-loading.html`, frames 1 to 7).
 */
class SectionCardsTest {

    @Test
    fun `a section nobody asked for says nothing about a load`() {
        assertEquals(CardLoad.Idle, cardLoad(run = null, onDevice = false, justArrived = false, hinted = false))
    }

    @Test
    fun `a section waiting its turn says so, and says when it opens once pressed`() {
        val waiting = run(SectionLoad.Queued, elapsedMs = null)

        assertEquals(CardLoad.Waiting(), cardLoad(waiting, onDevice = false, justArrived = false, hinted = false))
        assertEquals(CardLoad.Waiting(hinted = true), cardLoad(waiting, onDevice = false, justArrived = false, hinted = true))
    }

    /** Frame 1: one reply carries the whole section and names no total, so rows are counted. */
    @Test
    fun `a load that cannot count its categories counts its rows, with no percent`() {
        val arriving = run(SectionLoad.Loading(items = 24_310, groups = 835, groupsDone = null))

        assertEquals(
            CardLoad.Arriving(items = 24_310, percent = null, refresh = false),
            cardLoad(arriving, onDevice = false, justArrived = false, hinted = false),
        )
    }

    /** Frames 3 and 4: a walk of the categories knows its whole, so the percent is real. */
    @Test
    fun `a load that counts its categories says how far along it is`() {
        val arriving = run(SectionLoad.Loading(items = 6_100, groups = 50, groupsDone = 24))

        assertEquals(
            CardLoad.Arriving(items = 6_100, percent = 48, refresh = false),
            cardLoad(arriving, onDevice = false, justArrived = false, hinted = false),
        )
    }

    @Test
    fun `the percent rounds down, so the last category arriving is never a hundred`() {
        assertEquals(99, SectionLoad.Loading(items = 1, groups = 835, groupsDone = 834).percent())
        assertEquals(100, SectionLoad.Loading(items = 1, groups = 835, groupsDone = 835).percent())
        assertEquals(0, SectionLoad.Loading(items = 0, groups = 835, groupsDone = 0).percent())
        assertNull("no categories is no whole to divide by", SectionLoad.Loading(items = 0, groups = 0, groupsDone = 0).percent())
    }

    /** Frame 5: pressed before its first load ended, it says when it will open. */
    @Test
    fun `a first load pressed says when it will open`() {
        val arriving = run(SectionLoad.Loading(items = 100, groups = 50, groupsDone = 31))

        assertEquals(
            CardLoad.Arriving(items = 100, percent = 62, refresh = false, hinted = true),
            cardLoad(arriving, onDevice = false, justArrived = false, hinted = true),
        )
    }

    /** Frame 7: a section with rows opens during its refresh, so it has nothing to hint. */
    @Test
    fun `a refresh keeps the rows on the card and never hints, because the section opens`() {
        val arriving = run(SectionLoad.Loading(items = 900, groups = 835, groupsDone = null))

        assertEquals(
            CardLoad.Arriving(items = 900, percent = null, refresh = true, hinted = false),
            cardLoad(arriving, onDevice = true, justArrived = false, hinted = true),
        )
    }

    /** Frame 3: the beat after a load ends, with how long it took for the builds that print it. */
    @Test
    fun `a load that ended moments ago says it arrived, in whole seconds`() {
        val done = run(SectionLoad.Done(54_130), elapsedMs = 33_600)

        assertEquals(CardLoad.Arrived(54_130, seconds = 34), cardLoad(done, onDevice = true, justArrived = true, hinted = false))
        assertEquals("and then the card goes back to its count", CardLoad.Idle, cardLoad(done, onDevice = true, justArrived = false, hinted = false))
    }

    /** Frame 6: a failure waits for a press; with rows under it, the section still opens. */
    @Test
    fun `a failure is said, with or without rows under it`() {
        val failed = run(SectionLoad.Failed(AppError.NETWORK_UNAVAILABLE, retryable = true))

        assertEquals(CardLoad.Failed(refresh = false), cardLoad(failed, onDevice = false, justArrived = false, hinted = false))
        assertEquals(CardLoad.Failed(refresh = true), cardLoad(failed, onDevice = true, justArrived = false, hinted = false))
    }

    @Test
    fun `a marked section the loader answered without fetching says nothing`() {
        assertEquals(CardLoad.Idle, cardLoad(run(SectionLoad.Ready), onDevice = true, justArrived = true, hinted = false))
        assertEquals(CardLoad.Idle, cardLoad(run(SectionLoad.NoSource), onDevice = false, justArrived = false, hinted = false))
    }

    // ------------------------------------------------------------------ the press

    @Test
    fun `a section on the device opens, and one not here is asked for while the viewer stays`() {
        assertEquals(Press.Open, pressOf(onDevice = true, marked = true, arriving = false))
        assertEquals(Press.Fetch, pressOf(onDevice = false, marked = false, arriving = false))
    }

    /** Frame 5: the second press on a card on its way is not a second question. */
    @Test
    fun `a section on its way is not asked again, its card says when it opens`() {
        assertEquals(Press.Hint, pressOf(onDevice = false, marked = false, arriving = true))
    }

    /** A provider refresh forgets the marks and leaves the rows: open them, fetch behind them. */
    @Test
    fun `rows whose mark was forgotten open at once and are fetched again behind the screen`() {
        assertEquals(Press.OpenAndFetch, pressOf(onDevice = true, marked = false, arriving = false))
        assertEquals("and only once", Press.Open, pressOf(onDevice = true, marked = false, arriving = true))
    }

    /** Frame 7: a section being refreshed opens with what it has. */
    @Test
    fun `a section being refreshed opens`() {
        assertEquals(Press.Open, pressOf(onDevice = true, marked = true, arriving = true))
    }

    @Test
    fun `a section loaded and empty opens to its own empty state`() {
        assertEquals(Press.Open, pressOf(onDevice = true, marked = true, arriving = false))
    }

    // ------------------------------------------------------------------ the arrival

    @Test
    fun `a load that ends between two answers has arrived, and only then`() {
        val loading = mapOf(MediaKind.LIVE to run(SectionLoad.Loading(1, 2)))
        val done = mapOf(MediaKind.LIVE to run(SectionLoad.Done(2)))

        assertEquals(setOf(MediaKind.LIVE), arrivedBetween(loading, done))
        assertEquals("still done is not news", emptySet<MediaKind>(), arrivedBetween(done, done))
        assertEquals("nothing to done between two looks has arrived", setOf(MediaKind.LIVE), arrivedBetween(emptyMap(), done))
    }

    @Test
    fun `a failure or a section still on its way has not arrived`() {
        val before = mapOf(MediaKind.MOVIE to run(SectionLoad.Queued, elapsedMs = null))
        val after = mapOf(
            MediaKind.MOVIE to run(SectionLoad.Failed(AppError.TIMEOUT, retryable = true)),
            MediaKind.SERIES to run(SectionLoad.Loading(3, 9)),
        )

        assertEquals(emptySet<MediaKind>(), arrivedBetween(before, after))
    }

    // ------------------------------------------------- Live TV opens with its first category
    //
    // `design/mockups/channels-arriving.html`, and `home-loading.html` set f.

    @Test
    fun `only Live TV opens with its first category, for now`() {
        assertEquals(true, opensWithFirstCategory(MediaKind.LIVE))
        assertEquals(false, opensWithFirstCategory(MediaKind.MOVIE))
        assertEquals(false, opensWithFirstCategory(MediaKind.SERIES))
        assertEquals(false, opensWithFirstCategory(MediaKind.RADIO))
    }

    /**
     * A press during Live TV's first load waits until a category is listed -- whatever rows
     * are already in, which may belong to a category still being read -- and opens once one
     * is. It says nothing new while it waits.
     */
    @Test
    fun `a press during Live TV's first load waits for its first category, then opens`() {
        assertEquals(Press.Await, pressOf(MediaKind.LIVE, onDevice = false, marked = false, run = first(SectionLoad.Queued)))
        assertEquals(
            "rows of a category still being read",
            Press.Await,
            pressOf(MediaKind.LIVE, onDevice = true, marked = false, run = first(SectionLoad.Loading(items = 31, groups = 340))),
        )
        assertEquals(
            Press.Open,
            pressOf(MediaKind.LIVE, onDevice = true, marked = false, run = first(SectionLoad.Loading(items = 48, groups = 340, listed = 1))),
        )
    }

    /** The other sections, and Live TV's other loads, keep the presses they had. */
    @Test
    fun `every other press is the one it was`() {
        val arriving = SectionLoad.Loading(items = 48, groups = 340, listed = 1)
        assertEquals("films still say when they open", Press.Hint, pressOf(MediaKind.MOVIE, onDevice = false, marked = false, run = first(arriving)))
        assertEquals("a refresh over rows opens", Press.Open, pressOf(MediaKind.LIVE, onDevice = true, marked = true, run = run(arriving)))
        assertEquals(Press.Fetch, pressOf(MediaKind.LIVE, onDevice = false, marked = false, run = null))
        assertEquals(Press.Open, pressOf(MediaKind.LIVE, onDevice = true, marked = true, run = first(SectionLoad.Done(48))))
    }

    /**
     * Live TV's first load on its card: loading, as every first load says it, until its first
     * category is listed; then the count alone -- no word, no bar, no "loaded" after it.
     */
    @Test
    fun `Live TV's card loads until its first category, then shows its count alone`() {
        val before = first(SectionLoad.Loading(items = 31, groups = 340))
        val after = first(SectionLoad.Loading(items = 12_940, groups = 340, listed = 9))

        assertEquals(
            "rows on the device are not a refresh",
            CardLoad.Arriving(items = 31, percent = null, refresh = false),
            cardLoad(before, onDevice = true, justArrived = false, hinted = false, opensWithFirst = true),
        )
        assertEquals(CardLoad.Idle, cardLoad(after, onDevice = true, justArrived = false, hinted = false, opensWithFirst = true))
        assertEquals(
            "no loaded beat at the end",
            CardLoad.Idle,
            cardLoad(first(SectionLoad.Done(54_130)), onDevice = true, justArrived = true, hinted = false, opensWithFirst = true),
        )
    }

    /** It stopped partway: said as the section's load, not as a refresh, rows or not. */
    @Test
    fun `Live TV's first load that stopped says the channels could not be loaded`() {
        val failed = first(SectionLoad.Failed(AppError.NETWORK_UNAVAILABLE, retryable = true))

        assertEquals(CardLoad.Failed(refresh = false), cardLoad(failed, onDevice = true, justArrived = false, hinted = false, opensWithFirst = true))
    }

    /** The same answers for a section that does not open with its first category. */
    @Test
    fun `a first load of the other sections reads as it did`() {
        val after = first(SectionLoad.Loading(items = 12_940, groups = 340, listed = 9))

        assertEquals(
            CardLoad.Arriving(items = 12_940, percent = null, refresh = true),
            cardLoad(after, onDevice = true, justArrived = false, hinted = false, opensWithFirst = false),
        )
    }

    @Test
    fun `Home opens the section it waits on with its first category, or when its load ends`() {
        assertEquals(Await.Keep, awaitOf(MediaKind.LIVE, mapOf(MediaKind.LIVE to first(SectionLoad.Queued))))
        assertEquals(Await.Keep, awaitOf(MediaKind.LIVE, mapOf(MediaKind.LIVE to first(SectionLoad.Loading(31, 340)))))
        assertEquals(Await.Open, awaitOf(MediaKind.LIVE, mapOf(MediaKind.LIVE to first(SectionLoad.Loading(48, 340, listed = 1)))))
        assertEquals("a section with no categories opens with its rows", Await.Open, awaitOf(MediaKind.LIVE, mapOf(MediaKind.LIVE to first(SectionLoad.Done(48)))))
    }

    @Test
    fun `Home stops waiting when there is nothing to open`() {
        val failed = SectionLoad.Failed(AppError.TIMEOUT, retryable = true)
        assertEquals(Await.Drop, awaitOf(MediaKind.LIVE, mapOf(MediaKind.LIVE to first(failed))))
        assertEquals("another subscription forgot the load", Await.Drop, awaitOf(MediaKind.LIVE, emptyMap()))
    }

    private fun run(load: SectionLoad, elapsedMs: Long? = 1_000) = SectionRun(load, forced = false, elapsedMs = elapsedMs)

    private fun first(load: SectionLoad) = SectionRun(load, forced = false, elapsedMs = 1_000, first = true)
}
