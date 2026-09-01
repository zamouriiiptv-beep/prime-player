package com.castivio.data.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The tally as it is actually kept, against real preferences.
 *
 * Robolectric rather than a fake store, because the claims worth making here are about
 * persistence and about the day rolling over — and a fake that held an `Int` in a field
 * would prove neither. What is being tested is that a number survives being written and
 * that yesterday's does not.
 */
@RunWith(RobolectricTestRunner::class)
class StoredSubtitleAllowanceTest {

    // From Robolectric itself, so this module needs no test dependency beyond the runner.
    private val context: android.content.Context get() = RuntimeEnvironment.getApplication()

    /** A fixed moment, so "today" means something. 2026-01-01T12:00:00Z. */
    private var now = 1_767_268_800_000L

    private fun allowance() = StoredSubtitleAllowance(context).apply { clock = { now } }

    /**
     * A fresh install has downloaded nothing, which is the state the defect denied.
     *
     * The player used to announce that the day's downloads were used up on a device that had
     * never downloaded one. Whatever else is true, this has to read zero.
     */
    @Test
    fun `nothing has been downloaded before anything is downloaded`() {
        val allowance = allowance()

        assertEquals(0, allowance.spentToday())
        assertFalse(allowance.spent())
    }

    /** One completed download is one, and it is still one after the object is rebuilt. */
    @Test
    fun `a download is counted and kept`() {
        allowance().recordDownload()

        assertEquals("the count did not survive being written", 1, allowance().spentToday())
    }

    /** They accumulate, and reaching the limit is what makes a day spent. */
    @Test
    fun `the day is spent only at the limit`() {
        val allowance = allowance()
        repeat(allowance.dailyLimit - 1) { allowance.recordDownload() }

        assertFalse("one short of the limit is not a spent day", allowance.spent())

        allowance.recordDownload()

        assertTrue(allowance.spent())
    }

    /**
     * Tomorrow starts at zero, and nothing had to run at midnight for that to be true.
     *
     * The reset is a comparison rather than a job: the day is stored beside the count, and a
     * read on a different day answers zero. So there is no scheduled work to miss, nothing
     * to do about a process that was not alive at midnight, and no way for yesterday's
     * number to survive by being read before something got round to clearing it.
     */
    @Test
    fun `the count starts again the next day`() {
        val allowance = allowance()
        repeat(allowance.dailyLimit) { allowance.recordDownload() }
        assertTrue(allowance.spent())

        now += DAY

        assertEquals("yesterday's downloads were charged to today", 0, allowance.spentToday())
        assertFalse(allowance.spent())
    }

    /** And a fresh count on the new day is a count of that day. */
    @Test
    fun `the new day counts its own downloads`() {
        allowance().recordDownload()
        now += DAY
        allowance().recordDownload()

        assertEquals(1, allowance().spentToday())
    }

    /**
     * The provider's word settles the day without a file arriving.
     *
     * The one thing allowed to move the number without a download, because a `406` on a
     * download request is OpenSubtitles stating a fact about an account that other devices
     * share. It is not an increment — it is the count being told what it is.
     */
    @Test
    fun `the provider can declare the day spent`() {
        val allowance = allowance()

        allowance.markSpent()

        assertEquals(allowance.dailyLimit, allowance.spentToday())
        assertTrue(allowance.spent())

        now += DAY

        assertEquals("a provider's refusal outlived the day it was about", 0, allowance().spentToday())
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
