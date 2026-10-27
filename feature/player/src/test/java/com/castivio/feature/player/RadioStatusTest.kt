package com.castivio.feature.player

import com.castivio.domain.RadioPhase
import com.castivio.domain.RadioStatus
import com.castivio.playback.api.MediaKind
import com.castivio.playback.api.PlaybackError
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the radio's screen is told about the sound, from the one player's state. */
class RadioStatusTest {

    private val request = PlayerRequest(url = "http://x/nova", title = "Nova FM", kind = MediaKind.LIVE)

    private fun status(picture: Picture, playRequested: Boolean? = null) =
        radioStatusOf(PlayerState(request = request, picture = picture, playRequested = playRequested))

    @Test
    fun `no player is no radio`() {
        assertEquals(RadioStatus(), radioStatusOf(null))
    }

    @Test
    fun `a stream being asked for is opening, and so is one being asked for again`() {
        assertEquals(RadioStatus(request.url, RadioPhase.OPENING), status(Picture.Opening))
        assertEquals(RadioStatus(request.url, RadioPhase.OPENING), status(Picture.Reconnecting(attempt = 1, of = 3)))
    }

    @Test
    fun `a stall is still sounding, because the viewer has not stopped it`() {
        assertEquals(RadioStatus(request.url, RadioPhase.PLAYING), status(Picture.Playing))
        assertEquals(RadioStatus(request.url, RadioPhase.PLAYING), status(Picture.Buffering))
    }

    @Test
    fun `a stream the viewer holds is paused, whatever the engine last announced`() {
        assertEquals(RadioStatus(request.url, RadioPhase.PAUSED), status(Picture.Paused))
        // The announcement can lag the intent: a held stream that still reads as playing is held.
        assertEquals(RadioStatus(request.url, RadioPhase.PAUSED), status(Picture.Playing, playRequested = false))
        assertEquals(RadioStatus(request.url, RadioPhase.PAUSED), status(Picture.Buffering, playRequested = false))
        assertEquals(RadioStatus(request.url, RadioPhase.PLAYING), status(Picture.Playing, playRequested = true))
    }

    @Test
    fun `a stream that failed or ended is silence the viewer did not choose`() {
        assertEquals(
            RadioStatus(request.url, RadioPhase.FAILED),
            status(Picture.Failed(PlaybackError.UNKNOWN, canTryBackup = false)),
        )
        assertEquals(RadioStatus(request.url, RadioPhase.FAILED), status(Picture.Ended))
    }
}
