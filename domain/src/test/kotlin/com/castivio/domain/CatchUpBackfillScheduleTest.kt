package com.castivio.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the backfill may run: only when the home screen is up and showing and nothing is loading or playing. */
class CatchUpBackfillScheduleTest {

    @Test
    fun `it may run only when everything is quiet`() {
        assertTrue(CatchUpBackfillSchedule.quiet(homeShown = true, homeLoaded = true, loadActive = false, playbackActive = false))
    }

    @Test
    fun `each thing that is going on holds it back`() {
        assertFalse("home is not on screen", CatchUpBackfillSchedule.quiet(false, true, false, false))
        assertFalse("home has not loaded", CatchUpBackfillSchedule.quiet(true, false, false, false))
        assertFalse("a section is queued or arriving", CatchUpBackfillSchedule.quiet(true, true, true, false))
        assertFalse("a stream is playing or opening", CatchUpBackfillSchedule.quiet(true, true, false, true))
    }

    @Test
    fun `the settle time is long enough to be a pause and short enough to happen`() {
        assertTrue(CatchUpBackfillSchedule.SETTLE_MS in 5_000L..30_000L)
    }

    @Test
    fun `the playback flag says what it was last told`() {
        val activity = PlaybackActivity()
        assertFalse(activity.active.value)

        activity.set(true)
        assertTrue(activity.active.value)

        activity.set(false)
        assertEquals(false, activity.active.value)
    }
}
