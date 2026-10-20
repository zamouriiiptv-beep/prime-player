package com.castivio.feature.home

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The refresh card's four looks, and the sentence a failure is read out as.
 *
 * The card is the only place a refresh says how it went -- the notice that floated over
 * the footer is gone -- so a look that is wrong is a press with no visible outcome, which
 * is the defect the card's states exist to prevent.
 */
class RefreshLookTest {

    @Test
    fun `a card nobody pressed is at rest`() {
        assertEquals(RefreshLook.Idle, refreshLook(HomeState()))
    }

    @Test
    fun `a press in flight is asking`() {
        assertEquals(RefreshLook.Busy, refreshLook(HomeState(refreshing = true)))
    }

    /** A failure from before is being retried: the newest fact is the press. */
    @Test
    fun `a press in flight outranks the failure it retries`() {
        val state = HomeState(refreshing = true, refreshFault = RefreshFault.Unreachable)

        assertEquals(RefreshLook.Busy, refreshLook(state))
    }

    @Test
    fun `a failed press says so, with its reason`() {
        assertEquals(RefreshLook.Failed(RefreshFault.Unreachable), refreshLook(HomeState(refreshFault = RefreshFault.Unreachable)))
        assertEquals(RefreshLook.Failed(RefreshFault.NoProvider), refreshLook(HomeState(refreshFault = RefreshFault.NoProvider)))
    }

    @Test
    fun `an answered press says so`() {
        assertEquals(RefreshLook.Answered, refreshLook(HomeState(refreshed = true)))
    }

    /** One press never sets both; if two did, the one a viewer must not miss is the failure. */
    @Test
    fun `a failure outranks an answer`() {
        val state = HomeState(refreshFault = RefreshFault.Unreachable, refreshed = true)

        assertEquals(RefreshLook.Failed(RefreshFault.Unreachable), refreshLook(state))
    }

    /** The two failures need opposite advice, so they are read out as different sentences. */
    @Test
    fun `each failure is read out as its own sentence`() {
        assertEquals(R.string.home_refresh_unreachable, refreshSentence(RefreshFault.Unreachable))
        assertEquals(R.string.home_refresh_no_provider, refreshSentence(RefreshFault.NoProvider))
    }
}
