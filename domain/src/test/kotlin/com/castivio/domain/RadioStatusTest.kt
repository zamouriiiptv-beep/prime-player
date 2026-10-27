package com.castivio.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioStatusTest {

    @Test
    fun `only a station that is sounding marks its card`() {
        assertEquals("u", RadioStatus("u", RadioPhase.PLAYING).soundingUrl)
        assertNull(RadioStatus("u", RadioPhase.OPENING).soundingUrl)
        assertNull(RadioStatus("u", RadioPhase.PAUSED).soundingUrl)
        assertNull(RadioStatus("u", RadioPhase.FAILED).soundingUrl)
        assertNull(RadioStatus().soundingUrl)
    }

    @Test
    fun `a station is active from the press until the sound is let go`() {
        assertTrue(RadioStatus("u", RadioPhase.OPENING).active)
        assertTrue(RadioStatus("u", RadioPhase.PAUSED).active)
        assertTrue(RadioStatus("u", RadioPhase.FAILED).active)
        assertFalse(RadioStatus().active)
        assertFalse("a phase with no stream is nothing", RadioStatus(null, RadioPhase.PLAYING).active)
    }
}
