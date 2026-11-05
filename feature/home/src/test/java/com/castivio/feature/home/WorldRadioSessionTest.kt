package com.castivio.feature.home

import com.castivio.domain.RadioPhase
import com.castivio.domain.RadioStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The world radio's listening decisions, without a decoder or a screen. */
class WorldRadioSessionTest {

    private val medi = worldStation("m1", url = "http://a.invalid/medi", resolved = "https://b.invalid/medi.mp3")
    private val hit = worldStation("h1", url = "http://a.invalid/hit")

    private fun heard(session: WorldRadioSession, phase: RadioPhase, url: String? = session.url) =
        session.heard(RadioStatus(url = url, phase = phase))

    @Test
    fun `nothing is asked for before the first press`() {
        val session = WorldRadioSession()

        assertNull(session.station)
        assertNull(session.url)
        assertFalse(session.paused)
        assertEquals(RadioStatus(), session.status)
    }

    @Test
    fun `a press asks for the resolved address and lets go of what was held`() {
        val session = WorldRadioSession()
        session.play(hit)
        session.toggle()
        assertTrue(session.paused)

        session.play(medi)

        assertEquals(medi, session.station)
        assertEquals("https://b.invalid/medi.mp3", session.url)
        assertFalse("a new station is asked to play, not held", session.paused)
    }

    @Test
    fun `every stream asked for is a listen of its own`() {
        val session = WorldRadioSession()
        val before = session.listen

        session.play(hit)
        session.play(medi)

        assertEquals(before + 2, session.listen)
    }

    @Test
    fun `the same station pressed again is not asked for again`() {
        val session = WorldRadioSession()
        session.play(hit)
        heard(session, RadioPhase.PLAYING)
        val listen = session.listen

        session.play(hit)

        assertEquals(listen, session.listen)
        assertEquals(RadioPhase.PLAYING, session.status.phase)
    }

    @Test
    fun `the play control holds the sound and lets it go again`() {
        val session = WorldRadioSession()
        session.play(hit)
        heard(session, RadioPhase.PLAYING)

        session.toggle()
        assertTrue(session.paused)
        session.toggle()
        assertFalse(session.paused)
    }

    @Test
    fun `a report about another stream is the previous station's and is dropped`() {
        val session = WorldRadioSession()
        session.play(hit)
        heard(session, RadioPhase.PLAYING)

        session.play(medi)
        session.heard(RadioStatus(url = hit.streamUrl, phase = RadioPhase.PLAYING))

        assertEquals("the plate does not say the new station sounds on the old one's word", RadioStatus(), session.status)
    }

    @Test
    fun `a report with no stream is dropped while a station is asked for`() {
        val session = WorldRadioSession()
        session.play(hit)
        heard(session, RadioPhase.PLAYING)

        session.heard(RadioStatus())

        assertEquals(RadioPhase.PLAYING, session.status.phase)
    }

    @Test
    fun `a report is applied as the player says it`() {
        val session = WorldRadioSession()
        session.play(hit)

        heard(session, RadioPhase.OPENING)
        assertEquals(RadioPhase.OPENING, session.status.phase)
        heard(session, RadioPhase.PLAYING)
        assertEquals(hit.streamUrl, session.status.soundingUrl)
    }

    @Test
    fun `the first failure of a station with another address asks for the other, and says nothing`() {
        val session = WorldRadioSession()
        session.play(medi)
        val listen = session.listen

        heard(session, RadioPhase.FAILED)

        assertEquals("http://a.invalid/medi", session.url)
        assertEquals("the failure of the first address is not shown", RadioStatus(), session.status)
        assertEquals("and the other address is a listen of its own", listen + 1, session.listen)
    }

    @Test
    fun `when the other address fails too the station has failed`() {
        val session = WorldRadioSession()
        session.play(medi)
        heard(session, RadioPhase.FAILED)

        heard(session, RadioPhase.FAILED)

        assertEquals("http://a.invalid/medi", session.url)
        assertEquals(RadioPhase.FAILED, session.status.phase)
    }

    @Test
    fun `a station with one address fails at once`() {
        val session = WorldRadioSession()
        session.play(hit)

        heard(session, RadioPhase.FAILED)

        assertEquals(RadioPhase.FAILED, session.status.phase)
        assertEquals(hit.streamUrl, session.url)
    }

    @Test
    fun `asking again after a failure on the same address asks the player again`() {
        val session = WorldRadioSession()
        session.play(hit)
        heard(session, RadioPhase.FAILED)
        val listen = session.listen

        session.toggle()

        assertEquals(1, session.retries)
        assertFalse(session.paused)
        assertEquals(listen + 1, session.listen)
    }

    @Test
    fun `asking again after the other address failed goes back to the first, as a new request and not a retry`() {
        val session = WorldRadioSession()
        session.play(medi)
        heard(session, RadioPhase.FAILED)
        heard(session, RadioPhase.FAILED)

        session.toggle()

        assertEquals("https://b.invalid/medi.mp3", session.url)
        assertEquals("the new request is opened by the route; a retry as well would open it twice", 0, session.retries)
        assertEquals(RadioStatus(), session.status)
        // and the other address is in hand again
        heard(session, RadioPhase.FAILED)
        assertEquals("http://a.invalid/medi", session.url)
    }

    @Test
    fun `the same station pressed after it failed asks again`() {
        val session = WorldRadioSession()
        session.play(hit)
        heard(session, RadioPhase.FAILED)

        session.play(hit)

        assertEquals(1, session.retries)
    }

    @Test
    fun `letting the sound go forgets everything but the library's`() {
        val session = WorldRadioSession()
        session.play(medi)
        session.sleepIn(15, nowMs = 0)
        heard(session, RadioPhase.PLAYING)

        session.release()

        assertNull(session.station)
        assertNull(session.url)
        assertNull(session.sleep.atMs)
        assertEquals(RadioStatus(), session.status)
        session.heard(RadioStatus(url = medi.streamUrl, phase = RadioPhase.PLAYING))
        assertEquals("a report that arrives after the release is nobody's", RadioStatus(), session.status)
    }

    @Test
    fun `the sleep timer ends by holding the sound, and is over`() {
        val session = WorldRadioSession()
        session.play(hit)
        session.sleepIn(30, nowMs = 1_000)
        assertEquals(1_000 + 30 * 60_000L, session.sleep.atMs)

        session.sleepEnded()

        assertTrue(session.paused)
        assertNull(session.sleep.atMs)
        assertNull(session.sleep.minutes)
    }

    @Test
    fun `a timer that ends with nothing asked for holds nothing`() {
        val session = WorldRadioSession()
        session.sleepIn(15, nowMs = 0)

        session.sleepEnded()

        assertFalse(session.paused)
    }
}
