package com.castivio.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class FrameSizeTest {

    @Test
    fun `a decoded size has both sides`() {
        assertEquals(FrameSize(1920, 1080), FrameSize.of(1920, 1080))
    }

    @Test
    fun `a size with a zero in it is nothing decoded yet`() {
        assertNull(FrameSize.of(0, 1080))
        assertNull(FrameSize.of(1920, 0))
        assertNull(FrameSize.of(-1, -1))
        assertNull(FrameSize.of(0, 0))
    }

    @Test
    fun `a frame cannot be built with a side that is not a size`() {
        try {
            FrameSize(0, 720)
            fail("a frame of no width was accepted")
        } catch (expected: IllegalArgumentException) {
            // The decoder not having said is a null where one of these is wanted.
        }
    }
}
