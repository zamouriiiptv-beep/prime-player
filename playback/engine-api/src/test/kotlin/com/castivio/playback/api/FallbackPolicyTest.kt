package com.castivio.playback.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackPolicyTest {

    @Test
    fun `primary falls back on decoder init error`() {
        val next = FallbackPolicy.nextEngine(EngineId.PRIMARY, PlaybackError.DECODER_INIT)
        assertEquals(EngineId.BACKUP, next)
    }

    @Test
    fun `primary falls back on decoding error`() {
        val next = FallbackPolicy.nextEngine(EngineId.PRIMARY, PlaybackError.DECODING)
        assertEquals(EngineId.BACKUP, next)
    }

    @Test
    fun `primary falls back on unsupported format`() {
        val next = FallbackPolicy.nextEngine(EngineId.PRIMARY, PlaybackError.UNSUPPORTED_FORMAT)
        assertEquals(EngineId.BACKUP, next)
        assertTrue(FallbackPolicy.canBackupHelp(PlaybackError.UNSUPPORTED_FORMAT))
    }

    @Test
    fun `primary does not fall back on network error`() {
        val next = FallbackPolicy.nextEngine(EngineId.PRIMARY, PlaybackError.NETWORK)
        assertEquals(null, next)
        assertFalse(FallbackPolicy.canBackupHelp(PlaybackError.NETWORK))
    }

    @Test
    fun `primary does not fall back on drm error`() {
        val next = FallbackPolicy.nextEngine(EngineId.PRIMARY, PlaybackError.DRM)
        assertEquals(null, next)
        assertFalse(FallbackPolicy.canBackupHelp(PlaybackError.DRM))
    }

    @Test
    fun `backup never falls back`() {
        for (error in PlaybackError.entries) {
            val next = FallbackPolicy.nextEngine(EngineId.BACKUP, error)
            assertEquals(null, next)
        }
    }
}