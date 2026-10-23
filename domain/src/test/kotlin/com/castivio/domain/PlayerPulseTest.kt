package com.castivio.domain

import com.castivio.core.common.FrameSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerPulseTest {

    private val url = "http://provider.tv/live/1.ts"

    @Test
    fun `only a showing stream is playing`() {
        assertEquals(url, PlayerPulse(url, PulsePhase.SHOWING).playingUrl)
        assertNull("asked for is not playing", PlayerPulse(url, PulsePhase.OPENING).playingUrl)
        assertNull("stopped is not playing", PlayerPulse(url, PulsePhase.STOPPED).playingUrl)
    }

    @Test
    fun `the size does not decide whether it is playing`() {
        val frame = FrameSize(1920, 1080)
        assertNull(PlayerPulse(url, PulsePhase.OPENING, frame).playingUrl)
        assertEquals(url, PlayerPulse(url, PulsePhase.SHOWING, frame).playingUrl)
    }
}
