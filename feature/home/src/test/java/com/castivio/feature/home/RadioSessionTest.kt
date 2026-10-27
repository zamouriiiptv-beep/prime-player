package com.castivio.feature.home

import com.castivio.domain.Channel
import com.castivio.domain.RadioPhase
import com.castivio.domain.RadioStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The listening's decisions, without a decoder or a screen. */
class RadioSessionTest {

    private val nova = Channel("c1", "Nova FM", "http://x/nova.png", 2, "g1", "http://x/nova", null)
    private val atlas = Channel("c2", "Radio Atlas", null, 1, "g1", "http://x/atlas", null)

    @Test
    fun `nothing is asked for before the first press`() {
        val session = RadioSession()

        assertNull(session.station)
        assertFalse(session.paused)
        assertEquals(RadioStatus(), session.status)
    }

    @Test
    fun `a press starts the station and lets go of what was held`() {
        val session = RadioSession()
        session.play(nova)
        session.toggle()
        assertTrue(session.paused)

        session.play(atlas)

        assertEquals(atlas, session.station)
        assertFalse("a new station is asked to play, not held", session.paused)
    }

    @Test
    fun `the play control holds the sound and lets it go again`() {
        val session = RadioSession()
        session.play(nova)
        session.heard(RadioStatus(nova.streamUrl, RadioPhase.PLAYING))

        session.toggle()
        assertTrue(session.paused)
        session.toggle()
        assertFalse(session.paused)
    }

    @Test
    fun `the play control on a station that failed asks for it again and does not hold it`() {
        val session = RadioSession()
        session.play(nova)
        session.heard(RadioStatus(nova.streamUrl, RadioPhase.FAILED))

        session.toggle()

        assertEquals(1, session.retries)
        assertFalse(session.paused)
    }

    @Test
    fun `the play control does nothing before there is a station`() {
        val session = RadioSession()

        session.toggle()

        assertFalse(session.paused)
        assertEquals(0, session.retries)
    }

    @Test
    fun `what the player says is what is drawn`() {
        val session = RadioSession()
        session.play(nova)
        session.heard(RadioStatus(nova.streamUrl, RadioPhase.OPENING))
        assertEquals(RadioPhase.OPENING, session.status.phase)

        session.heard(RadioStatus(nova.streamUrl, RadioPhase.PLAYING))
        assertEquals(nova.streamUrl, session.status.soundingUrl)
    }

    @Test
    fun `a timer ends at its deadline and holds the sound as a press would`() {
        val session = RadioSession()
        session.play(nova)
        session.sleepIn(minutes = 30, nowMs = 10_000L)
        assertEquals(10_000L + 30 * 60_000L, session.sleepAtMs)
        assertEquals(30, session.sleepMinutes)

        session.sleepEnded()

        assertTrue("the station stays on the plate, held", session.paused)
        assertEquals(nova, session.station)
        assertNull("a timer is over once it has acted", session.sleepAtMs)
        assertNull(session.sleepMinutes)
    }

    @Test
    fun `a timer set to nothing stops running`() {
        val session = RadioSession()
        session.sleepIn(minutes = 15, nowMs = 0L)

        session.sleepIn(minutes = null, nowMs = 5_000L)

        assertNull(session.sleepAtMs)
        assertNull(session.sleepMinutes)
    }

    @Test
    fun `letting the sound go leaves the screen as it was when first opened`() {
        val session = RadioSession()
        session.play(nova)
        session.heard(RadioStatus(nova.streamUrl, RadioPhase.PLAYING))
        session.sleepIn(minutes = 15, nowMs = 0L)

        session.release()

        assertNull(session.station)
        assertFalse(session.paused)
        assertNull(session.sleepAtMs)
        assertEquals(RadioStatus(), session.status)
    }
}
