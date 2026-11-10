package com.castivio.feature.player

import com.castivio.playback.api.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * What a channel brings to its loading screen must never make a press a different one.
 *
 * Pressing the channel that is already playing builds an equal request, and the player does nothing; what is on changes while it
 * plays, so a request that compared the programme would reopen a playing channel.
 */
class OpeningFaceTest {

    private fun request(face: OpeningFace?) =
        PlayerRequest(url = "http://example.invalid/live/1.ts", title = "M6", kind = MediaKind.LIVE, face = face)

    @Test
    fun `two requests that differ only in the face are the same request`() {
        val before = request(OpeningFace("http://example.invalid/m6.png", "Programmes de la nuit", "00:55 \u2013 05:00"))
        val after = request(OpeningFace("http://example.invalid/m6.png", "Scènes de ménages", "05:00 \u2013 07:05"))

        assertEquals(before, after)
        assertEquals(before.hashCode(), after.hashCode())
        assertEquals(request(null), before.copy(face = null))
    }

    @Test
    fun `a different address is still a different request`() {
        val face = OpeningFace(null, null, null)

        assertNotEquals(request(face), request(face).copy(url = "http://example.invalid/live/2.ts"))
    }
}
