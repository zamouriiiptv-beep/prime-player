package com.castivio.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The reading of an archive listing's depth: numbers, and nothing that could identify an account or a stream. */
class CatchUpDepthTest {

    private val hour = CatchUpPolicy.HOUR_MS
    private val now = 1_784_980_800_000L

    @Test
    fun `the counts and the oldest and newest start are read against now`() {
        val received = listOf(now - 190 * hour, now - 100 * hour, now + 31 * hour)
        val archived = listOf(now - 168 * hour, now - 100 * hour)
        val replayable = listOf(now - 168 * hour, now - 100 * hour)

        val depth = CatchUpDepth.of(received, archived, replayable, windowHours = 168, nowMs = now)

        assertEquals(3, depth.received)
        assertEquals(2, depth.archived)
        assertEquals(2, depth.replayable)
        assertEquals(190 * hour, depth.receivedOldestAgoMs)
        assertEquals(-31 * hour, depth.receivedNewestAgoMs)
        assertEquals(168 * hour, depth.archivedOldestAgoMs)
        assertEquals(168 * hour, depth.replayableOldestAgoMs)
    }

    @Test
    fun `the line is one line, in hours to a tenth, past and future told apart`() {
        val depth = CatchUpDepth.of(
            receivedStarts = listOf(now - 190 * hour, now + 31 * hour + hour / 2),
            archivedStarts = listOf(now - 168 * hour),
            replayableStarts = listOf(now - 168 * hour),
            windowHours = 168,
            nowMs = now,
        )

        assertEquals(
            "catch-up archive: received=2 archived=1 replayable=1 window=168h " +
                "received[oldest=190.0h ago newest=31.5h ahead] archived[oldest=168.0h ago newest=168.0h ago] " +
                "replayable[oldest=168.0h ago]",
            depth.format(),
        )
        assertFalse(depth.format().contains('\n'))
    }

    @Test
    fun `an empty listing says so with dashes`() {
        val depth = CatchUpDepth.of(emptyList(), emptyList(), emptyList(), windowHours = 72, nowMs = now)

        assertNull(depth.receivedOldestAgoMs)
        assertEquals(
            "catch-up archive: received=0 archived=0 replayable=0 window=72h " +
                "received[oldest=- newest=-] archived[oldest=- newest=-] replayable[oldest=-]",
            depth.format(),
        )
    }
}
