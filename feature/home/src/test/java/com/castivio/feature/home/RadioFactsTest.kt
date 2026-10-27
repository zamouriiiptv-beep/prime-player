package com.castivio.feature.home

import com.castivio.domain.Channel
import com.castivio.domain.RadioPhase
import com.castivio.domain.RadioStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the radio says, and what it leaves unsaid: pure, so no screen is needed to know. */
class RadioFactsTest {

    @Test
    fun `the timer offers a short list in ascending order`() {
        assertEquals(listOf(15, 30, 45, 60, 90), SLEEP_MINUTES)
    }

    @Test
    fun `a timer ends its minutes after it was set`() {
        assertEquals(1_000L + 30 * 60_000L, sleepDeadlineMs(nowMs = 1_000L, minutes = 30))
    }

    @Test
    fun `what is left of a timer is the distance to its end, and never negative`() {
        assertEquals(29_000L, sleepRemainingMs(deadlineMs = 30_000L, nowMs = 1_000L))
        assertEquals(0L, sleepRemainingMs(deadlineMs = 30_000L, nowMs = 31_000L))
        assertNull(sleepRemainingMs(deadlineMs = null, nowMs = 1_000L))
    }

    @Test
    fun `a remaining time reads as a clock, rounded up to the second`() {
        assertEquals("29:41", remainingText(29 * 60_000L + 41_000L))
        assertEquals("0:01", remainingText(500L))
        assertEquals("0:00", remainingText(0L))
        assertEquals("1:05:00", remainingText(65 * 60_000L))
        assertEquals("1:30:00", remainingText(90 * 60_000L))
        assertEquals("0:00", remainingText(-5_000L))
    }

    @Test
    fun `the station after and before are the neighbours in the list, and none past its ends`() {
        assertEquals(4, stationStep(index = 3, by = 1, count = 10))
        assertEquals(2, stationStep(index = 3, by = -1, count = 10))
        assertNull(stationStep(index = 9, by = 1, count = 10))
        assertNull(stationStep(index = 0, by = -1, count = 10))
        assertNull("a station that is not in this list has no neighbours", stationStep(index = -1, by = 1, count = 10))
    }

    @Test
    fun `a card names its category on the lists that mix them, and not inside one`() {
        assertTrue(BrowseState(selectedGroup = null).mixesCategories())
        assertFalse(BrowseState(selectedGroup = "maroc").mixesCategories())
        // The favourites and the stations played last have no category of their own either.
        assertTrue(BrowseState(selectedGroup = null, favorites = true).mixesCategories())
        assertTrue(BrowseState(selectedGroup = null, recent = true).mixesCategories())
    }

    @Test
    fun `a station is filed under the category the provider named, or under none`() {
        val names = mapOf("g1" to "MAROC RADIO")
        val station = Channel("c1", "Nova FM", null, 2, "g1", "http://x/1", null)
        assertEquals("MAROC RADIO", station.categoryIn(names))
        assertNull(station.copy(groupId = null).categoryIn(names))
        assertNull("an id the provider no longer lists says nothing", station.copy(groupId = "gone").categoryIn(names))
    }

    private val url = "http://x/nova"

    @Test
    fun `a station the player has not spoken of is on its way, or held if the viewer holds it`() {
        assertEquals(RadioPhase.OPENING, radioPhaseOf(url, RadioStatus(), paused = false))
        assertEquals(RadioPhase.OPENING, radioPhaseOf(url, RadioStatus("http://x/other", RadioPhase.PLAYING), paused = false))
        assertEquals(RadioPhase.PAUSED, radioPhaseOf(url, RadioStatus("http://x/other", RadioPhase.PLAYING), paused = true))
    }

    @Test
    fun `the viewer's hold is believed before the player has caught up with it`() {
        assertEquals(RadioPhase.PAUSED, radioPhaseOf(url, RadioStatus(url, RadioPhase.PLAYING), paused = true))
        assertEquals(RadioPhase.PLAYING, radioPhaseOf(url, RadioStatus(url, RadioPhase.PLAYING), paused = false))
    }

    @Test
    fun `a stream that failed is failed whatever the hold, so the control can ask again`() {
        assertEquals(RadioPhase.FAILED, radioPhaseOf(url, RadioStatus(url, RadioPhase.FAILED), paused = false))
        assertEquals(RadioPhase.FAILED, radioPhaseOf(url, RadioStatus(url, RadioPhase.FAILED), paused = true))
    }

    @Test
    fun `a stream being opened is opening`() {
        assertEquals(RadioPhase.OPENING, radioPhaseOf(url, RadioStatus(url, RadioPhase.OPENING), paused = false))
    }

    @Test
    fun `the neighbours of the station that is playing are the cards beside it in the open list`() {
        val ids = listOf("a", "b", "c")
        val at: (Int) -> String? = { ids.getOrNull(it) }

        assertEquals(2, neighbourOf("b", by = 1, count = 3, idAt = at))
        assertEquals(0, neighbourOf("b", by = -1, count = 3, idAt = at))
        assertNull(neighbourOf("c", by = 1, count = 3, idAt = at))
        assertNull(neighbourOf("a", by = -1, count = 3, idAt = at))
    }

    @Test
    fun `a station that is not in the open list has no neighbours`() {
        val ids = listOf("a", "b", "c")

        assertNull(neighbourOf("z", by = 1, count = 3, idAt = { ids.getOrNull(it) }))
        assertNull(neighbourOf("a", by = 1, count = 0, idAt = { null }))
    }
}
