package com.castivio.feature.home

import com.castivio.domain.EpgRepository
import com.castivio.domain.Programme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the guide page decides that what it holds is worth drawing without asking again.
 *
 * ## Why this is the part worth testing without a device
 *
 * Because getting it wrong is silent. The page draws whatever storage gave it either way,
 * so a test that is too easy to pass leaves a channel showing four rows for ever and
 * nothing on screen says a request was skipped. It was wrong in exactly that way: the
 * decision borrowed [EpgRepository.MINIMUM_HORIZON_MS], the six hours *now/next* runs on,
 * and a channel whose four stored rows happened to span longer than that was never asked
 * for the rest of its day. The defect scaled backwards — the longer a channel's
 * programmes, the less of its guide appeared — which is the shape of bug a screenshot
 * makes look like a provider's fault.
 *
 * So the figures below are the ones measured on a device rather than invented here.
 */
class GuideDepthTest {

    private val hour = 60 * 60 * 1000L
    private val day = 24 * hour

    /** 2026-09-23 18:27 local, the instant the defect was photographed at. */
    private val now = 1_790_620_020_000L

    /**
     * **The case that was broken, and the reason this test exists.**
     *
     * TV BREIZH: four rows of three-hour programmes, 17:00 through 05:00, reaching
     * 10h33m past the moment the page was opened. That cleared six hours comfortably, so
     * the request never went out and the page drew four rows of a day it never asked for.
     * A day is the page's floor, ten and a half hours is less than a day, and this must
     * ask.
     */
    @Test
    fun `four long rows spanning half a day are still short of a page`() {
        val stored = listOf(
            programme("17:00", now - 87 * 60 * 1000L, 3 * hour),
            programme("20:00", now + 93 * 60 * 1000L, 3 * hour),
            programme("23:00", now + 273 * 60 * 1000L, 3 * hour),
            programme("02:00", now + 453 * 60 * 1000L, 3 * hour),
        )

        // The figure that produced the defect, stated so the regression is visible: this
        // storage passes the row's question and fails the page's.
        assertTrue(
            "the six-hour figure is what let this through",
            stored.maxOf { it.stopMs } >= now + EpgRepository.MINIMUM_HORIZON_MS,
        )
        assertFalse("ten and a half hours is not a day", coversAPage(stored, now))
    }

    /** A day ahead is what the page set out to show, so there is nothing to ask for. */
    @Test
    fun `storage reaching a full day is drawn without asking`() {
        val stored = listOf(programme("all day", now, day))

        assertTrue(coversAPage(stored, now))
    }

    /**
     * Exactly a day passes, one millisecond less does not.
     *
     * The boundary is stated rather than left to a comparison operator, because "at
     * least" and "more than" differ by one request per channel per half hour and by
     * nothing a reader would notice in the source.
     */
    @Test
    fun `the boundary is inclusive`() {
        assertTrue(coversAPage(listOf(programme("exact", now, day)), now))
        assertFalse(coversAPage(listOf(programme("short", now, day - 1)), now))
    }

    /**
     * **Nothing stored is always worth asking.**
     *
     * A channel the app has never fetched has no last stop at all, and the page must not
     * read that as satisfaction. This is the one input where returning the wrong answer
     * would mean a channel could never gain a guide.
     */
    @Test
    fun `an empty store is never enough`() {
        assertFalse(coversAPage(emptyList(), now))
    }

    /**
     * **Depth, not count.**
     *
     * Twenty rows of ten minutes are twenty rows and three hours. The page asks how far
     * the schedule reaches, never how many entries it has, because a provider's
     * programme length is not something to have an opinion about.
     */
    @Test
    fun `many short rows are judged by where they end, not how many there are`() {
        val slot = 10 * 60 * 1000L
        val stored = (0 until 20).map { programme("slot $it", now + it * slot, slot) }

        assertFalse(coversAPage(stored, now))
    }

    /**
     * **The past does not count towards the future.**
     *
     * Yesterday's schedule is kept for catch-up, and a channel holding a day of it plus
     * an hour ahead has an hour ahead. Measured from `now`, not from the earliest row.
     */
    @Test
    fun `a day of history plus an hour ahead is still an hour ahead`() {
        val stored = listOf(
            programme("yesterday", now - day, day - hour),
            programme("on now", now - hour, 2 * hour),
        )

        assertFalse(coversAPage(stored, now))
    }

    private fun programme(title: String, startMs: Long, durationMs: Long) =
        Programme("c", title, null, startMs, startMs + durationMs)
}
