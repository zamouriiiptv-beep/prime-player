package com.castivio.domain

import com.castivio.core.common.FrameSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The four words, and what a decoded picture has to be to earn each.
 *
 * Measured from the picture and never from a name: these cases are the frames providers
 * actually ship, including the letterboxed ones a lines-only rule would mislabel.
 */
class StreamQualityTest {

    private fun quality(width: Int, height: Int) = StreamQuality.of(FrameSize(width, height))

    @Test
    fun `the four classes are measured by lines`() {
        assertEquals(StreamQuality.UHD, quality(3840, 2160))
        assertEquals(StreamQuality.FHD, quality(1920, 1080))
        assertEquals(StreamQuality.HD, quality(1280, 720))
        assertEquals(StreamQuality.SD, quality(720, 576))
    }

    @Test
    fun `a letterboxed picture is its width's class`() {
        assertEquals("1920x800 is a full-HD channel", StreamQuality.FHD, quality(1920, 800))
        assertEquals("1280x544 is an HD channel", StreamQuality.HD, quality(1280, 544))
        assertEquals("3840x1600 is 4K", StreamQuality.UHD, quality(3840, 1600))
    }

    @Test
    fun `an anamorphic or odd picture still lands in a class`() {
        assertEquals(StreamQuality.FHD, quality(1440, 1080))
        assertEquals(StreamQuality.FHD, quality(2560, 1440))
        assertEquals(StreamQuality.HD, quality(1366, 768))
        assertEquals(StreamQuality.SD, quality(960, 540))
        assertEquals(StreamQuality.SD, quality(704, 396))
    }

    @Test
    fun `the boundaries are inclusive at the line count`() {
        assertEquals(StreamQuality.UHD, quality(3840, 2160))
        assertEquals(StreamQuality.FHD, quality(3000, 2159 * 1))
        assertEquals(StreamQuality.FHD, quality(1920, 1080))
        assertEquals(StreamQuality.HD, quality(1920 - 8, 1079))
        assertEquals(StreamQuality.HD, quality(1280, 720))
        assertEquals(StreamQuality.SD, quality(1270, 719))
    }

    @Test
    fun `the labels are the four the board uses`() {
        assertEquals(listOf("SD", "HD", "FHD", "4K"), StreamQuality.entries.map { it.label })
    }

    @Test
    fun `a stored name comes back, and one this build does not know is nothing`() {
        for (quality in StreamQuality.entries) assertEquals(quality, StreamQuality.named(quality.name))
        assertNull(StreamQuality.named("ULTRA"))
        assertNull(StreamQuality.named(null))
        assertNull(StreamQuality.named(""))
    }
}
