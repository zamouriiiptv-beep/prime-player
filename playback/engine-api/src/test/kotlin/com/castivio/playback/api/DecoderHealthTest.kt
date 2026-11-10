package com.castivio.playback.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the decoder's events add up to, and when they say something is wrong. */
class DecoderHealthTest {

    @Test
    fun `a log with nothing in it reads as a decoder with nothing wrong`() {
        val health = DecoderHealthLog().snapshot(rendered = 560, dropped = 0, skipped = 0, toKeyFrame = 0, longestRun = 0)

        assertTrue(health.available)
        assertEquals(560, health.rendered)
        assertFalse(health.troubled)
        assertEquals(0, health.dropReports)
        assertNull(health.lastCodecError)
    }

    @Test
    fun `dropped frames are counted by report and by frame`() {
        val log = DecoderHealthLog()
        log.dropped(4)
        log.dropped(0)
        log.dropped(-3)
        log.dropped(8)

        val health = log.snapshot(rendered = 100, dropped = 12, skipped = 0, toKeyFrame = 1, longestRun = 8)

        assertEquals("a report of nothing is not a report", 2, health.dropReports)
        assertEquals(12, health.droppedReported)
        assertEquals(12, health.dropped)
        assertEquals(8, health.longestDropRun)
        assertEquals(1, health.droppedToKeyFrame)
        assertTrue(health.troubled)
    }

    @Test
    fun `an error is kept by the name of its class and the last one wins`() {
        val log = DecoderHealthLog()
        log.codecError("CodecException")
        log.codecError("MediaCodecDecoderException")

        val health = log.snapshot(0, 0, 0, 0, 0)

        assertEquals(2, health.codecErrors)
        assertEquals("MediaCodecDecoderException", health.lastCodecError)
        assertTrue(health.troubled)
    }

    @Test
    fun `a size is counted when it changes and not when it is told again`() {
        val log = DecoderHealthLog()
        log.sized(1920, 1080)
        log.sized(1920, 1080)
        log.sized(0, 0)
        log.sized(1440, 1080)

        val health = log.snapshot(0, 0, 0, 0, 0)

        assertEquals(2, health.sizeChanges)
        assertEquals(1440, health.lastWidth)
        assertEquals(1080, health.lastHeight)
        assertTrue("a picture that changed its size more than once is worth a look", health.troubled)
    }

    @Test
    fun `the size a picture begins with is a change once and is not trouble`() {
        val log = DecoderHealthLog()
        log.sized(1920, 1080)

        val health = log.snapshot(10, 0, 0, 0, 0)

        assertEquals(1, health.sizeChanges)
        assertFalse(health.troubled)
    }

    @Test
    fun `frames skipped without being drawn are trouble`() {
        assertTrue(DecoderHealthLog().snapshot(100, 0, 3, 0, 0).troubled)
    }

    @Test
    fun `events alone, before the decoder has counters, say they are not the whole account`() {
        val log = DecoderHealthLog()
        log.codecError("CodecException")

        val health = log.snapshotEvents()

        assertFalse(health.available)
        assertEquals(1, health.codecErrors)
    }
}
