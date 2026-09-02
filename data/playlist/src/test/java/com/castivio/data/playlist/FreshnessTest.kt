package com.castivio.data.playlist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a stored section is too old to serve without asking again.
 *
 * This is the rule that decides how often the app talks to a provider at all, which
 * makes it the rule most likely to be quietly changed to "always" by a bug fix. Both
 * ways of getting it wrong are here: never refreshing leaves a category wrong until
 * the app is reinstalled, and always refreshing turns "load when opened" into "load
 * every time it is opened" — which is the defect this whole flow removes, reappearing
 * one screen at a time.
 */
class FreshnessTest {

    private val now = 1_772_323_200_000L
    private val hour = 60L * 60 * 1000

    /** Never fetched. There is nothing stored to serve, so there is nothing to weigh. */
    @Test
    fun `a section that was never loaded is always stale`() {
        assertTrue(Freshness.stale(null, now))
    }

    @Test
    fun `a section loaded a moment ago is not fetched again`() {
        assertTrue("browsing must not re-request", !Freshness.stale(now, now))
        assertFalse(Freshness.stale(now - hour, now))
        assertFalse(Freshness.stale(now - 11 * hour, now))
    }

    /**
     * And it does expire. A provider adds channels; a category that can never be
     * refreshed is a category that is wrong for as long as the app is installed.
     */
    @Test
    fun `a section goes stale at the window`() {
        assertTrue(Freshness.stale(now - Freshness.MAX_AGE_MS, now))
        assertTrue(Freshness.stale(now - 24 * hour, now))
    }

    /**
     * A clock that moved backwards must not pin a section as fresh forever.
     *
     * Not hypothetical on the hardware this runs on: a TV box with no battery-backed
     * clock boots at the epoch and jumps forward when it reaches the network, and a
     * timezone set during setup moves it too. A stored time in the future is not
     * evidence of freshness — it is evidence the clock changed.
     */
    @Test
    fun `a stored time in the future is treated as stale rather than as fresh`() {
        assertTrue(Freshness.stale(now + hour, now))
        assertTrue(Freshness.stale(now + 365 * 24 * hour, now))
    }

    /** The window is the catalogue refresh policy's, stated once rather than guessed. */
    @Test
    fun `the window is twelve hours`() {
        assertTrue(Freshness.MAX_AGE_MS == 12 * hour)
    }
}
