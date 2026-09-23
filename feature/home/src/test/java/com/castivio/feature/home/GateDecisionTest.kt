package com.castivio.feature.home

import com.castivio.core.common.AppError
import com.castivio.domain.SectionLoad
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When a section is worth drawing, and when the viewer is still asked to wait.
 *
 * ## Why this is the piece worth testing without a device
 *
 * Because both ways of getting it wrong are silent. Draw too early and the viewer lands
 * on an empty list that fills under their thumb; draw too late and they stare at a
 * spinner in front of a database that already holds what they asked for. Neither shows
 * up as a crash, and the second one shipped: a first import held the screen for 836
 * categories — 112.9s measured on the owner's device — while `XtreamImportEngine`
 * committed rows the whole way through.
 *
 * So the decision is a function of two values, and these are the six cases it has.
 */
class GateDecisionTest {

    /**
     * **Importing with nothing committed is a wait.**
     *
     * The case that keeps the fix honest. The engine writes the categories before the
     * first request goes out, so something always exists early — but rows are what a
     * list is made of, and until one is committed there is nothing to draw.
     */
    @Test
    fun `an import that has committed no rows still waits`() {
        val importing = SectionLoad.Loading(items = 0, groups = 836)

        assertEquals(GateDecision.Wait, gateDecision(CatalogSection.Live, importing, 0))
    }

    /**
     * **And with rows committed it opens, mid-import.**
     *
     * This is the whole change. The loader still says `Loading` and the importer is
     * still running; the difference is that the device now holds something to show.
     */
    @Test
    fun `an import with committed rows opens the section`() {
        assertEquals(
            GateDecision.Content,
            gateDecision(
                CatalogSection.Live,
                SectionLoad.Loading(items = 60, groups = 836, groupsDone = 1),
                60,
            ),
        )
    }

    /**
     * **One row is enough, and the number is the only input.**
     *
     * Deliberately not a threshold. A provider whose first category holds a single
     * channel has a section with a channel in it, and inventing a minimum would be this
     * screen having an opinion about somebody else's catalogue. The page asks for sixty
     * and draws what comes back.
     */
    @Test
    fun `one committed row is enough`() {
        val lonely = SectionLoad.Loading(1, 836)

        assertEquals(GateDecision.Content, gateDecision(CatalogSection.Live, lonely, 1))
    }

    /**
     * **A finished import draws, as it always did.**
     *
     * Included because the change must not alter the path everybody already has: a
     * section that reaches `Done` or answers `Ready` from its mark is drawn whatever
     * the count says, and the count is not consulted.
     */
    @Test
    fun `a settled section is drawn whatever the count says`() {
        val live = CatalogSection.Live

        assertEquals(GateDecision.Content, gateDecision(live, SectionLoad.Done(54_628), 54_628))
        assertEquals(GateDecision.Content, gateDecision(live, SectionLoad.Ready, 0))
        assertEquals(GateDecision.Content, gateDecision(live, SectionLoad.NoSource, 0))
    }

    /**
     * **A failure is the screen's to explain, not the gate's to hide behind.**
     *
     * `ChannelsScreen` draws a wall when it failed with no rows and a one-line notice
     * when it failed with rows on the device. Holding the gate over either would replace
     * a message that says what happened with a spinner that says nothing.
     */
    @Test
    fun `a failure reaches the screen with or without rows`() {
        val failed = SectionLoad.Failed(AppError.TIMEOUT, retryable = true)

        assertEquals(GateDecision.Content, gateDecision(CatalogSection.Live, failed, 0))
        assertEquals(GateDecision.Content, gateDecision(CatalogSection.Live, failed, 5_000))
    }

    /**
     * **Not asked yet is not the same as nothing there.**
     *
     * Before the loader has answered, the honest answer is neither. Treating it as empty
     * is what once let a section draw its own first frame and have the gate replace it a
     * frame later; treating it as ready would draw a list before the query that fills it
     * had been made. The screen holds a blank for one grace period instead.
     */
    @Test
    fun `no answer yet is undecided, not empty`() {
        assertEquals(GateDecision.Undecided, gateDecision(CatalogSection.Live, null, 0))
        assertEquals(GateDecision.Undecided, gateDecision(CatalogSection.Live, null, 54_628))
    }

    /**
     * **Only Channels opens early, and that is the point of the experiment.**
     *
     * The three other sections keep the behaviour they have: an import in progress holds
     * the gate however many rows are down. Not because the argument for opening early
     * stops applying to them — it does not, and Radio re-imports the whole live catalogue
     * so it would gain the most — but because one change measured on four screens is four
     * changes measured on none.
     *
     * Asserted rather than left to the source, because "we will widen it later" is the
     * sentence a silent behaviour change hides behind.
     */
    @Test
    fun `the other sections keep waiting for their import`() {
        val importing = SectionLoad.Loading(items = 5_000, groups = 836, groupsDone = 100)

        val others = listOf(CatalogSection.Movies, CatalogSection.Series, CatalogSection.Radio)

        for (section in others) {
            assertEquals(section.name, GateDecision.Wait, gateDecision(section, importing, 5_000))
        }
    }

    /**
     * **And everything that is not an import in progress is untouched, in every section.**
     *
     * The narrowing is on one branch only. A settled, absent or failed section answers
     * the same for Movies as it does for Channels, which is what makes this a change to
     * when Channels opens rather than a change to how the gate works.
     */
    @Test
    fun `the narrowing touches only the loading branch`() {
        val failed = SectionLoad.Failed(AppError.TIMEOUT, retryable = true)

        for (section in CatalogSection.entries) {
            val where = section.name

            assertEquals(where, GateDecision.Content, gateDecision(section, SectionLoad.Ready, 0))
            assertEquals(where, GateDecision.Content, gateDecision(section, SectionLoad.Done(9), 9))
            assertEquals(where, GateDecision.Content, gateDecision(section, failed, 0))
            assertEquals(where, GateDecision.Undecided, gateDecision(section, null, 9))
        }
    }
}
