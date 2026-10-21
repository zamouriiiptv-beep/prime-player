package com.castivio.feature.home

import com.castivio.core.common.AppError
import com.castivio.domain.MediaKind
import com.castivio.domain.SectionLoad
import com.castivio.domain.SectionRun
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Home opening Live TV by itself, and the ways that stops.
 *
 * Each case is a viewer who pressed Live TV with nothing of it on the device, and then either
 * waited on Home or did not. The queue's answers are written by hand, one at a time, so every
 * answer the opener can be shown is shown -- including the ones that must open nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SectionOpenerTest {

    private val runs = MutableStateFlow<Map<MediaKind, SectionRun>>(emptyMap())

    private fun answer(load: SectionLoad) {
        runs.value = mapOf(MediaKind.LIVE to SectionRun(load, forced = false, elapsedMs = 0, first = true))
    }

    private fun arriving(listed: Int) = SectionLoad.Loading(items = 2_400 + listed * 300, groups = 40, listed = listed)

    /** What Home is told to open, collected as Home collects it while it is on screen. */
    private fun TestScope.opened(opener: SectionOpener): List<CatalogSection> {
        val seen = mutableListOf<CatalogSection>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { opener.opens.collect { seen += it } }
        return seen
    }

    /** The viewer's press, as `HomeViewModel.press` makes it: asked, then awaited. */
    private fun TestScope.pressed(): SectionOpener {
        val opener = SectionOpener(backgroundScope, runs)
        answer(SectionLoad.Queued)
        opener.await(MediaKind.LIVE)
        runCurrent()
        return opener
    }

    @Test
    fun `Live TV opens by itself once its first category is listed, and only once`() = runTest {
        val opener = pressed()
        val seen = opened(opener)

        answer(arriving(listed = 0))
        runCurrent()
        assertEquals("rows, but no category complete", emptyList<CatalogSection>(), seen)

        answer(arriving(listed = 1))
        runCurrent()
        assertEquals(listOf(CatalogSection.Live), seen)

        // The rest of the section arrives behind the screen that opened.
        answer(arriving(listed = 7))
        runCurrent()
        answer(SectionLoad.Done(items = 12_940))
        runCurrent()
        assertEquals("opened once", listOf(CatalogSection.Live), seen)
    }

    /** A playlist with no categories at all lists none, and opens with its rows when it ends. */
    @Test
    fun `a section that lists no category opens when its load ends`() = runTest {
        val opener = pressed()
        val seen = opened(opener)

        answer(arriving(listed = 0))
        runCurrent()
        answer(SectionLoad.Done(items = 840))
        runCurrent()

        assertEquals(listOf(CatalogSection.Live), seen)
    }

    /** Both are `HomeViewModel.leftHome` and the first thing any press does. */
    @Test
    fun `leaving Home or pressing another card ends the wait, and nothing opens after it`() = runTest {
        val opener = pressed()
        val seen = opened(opener)
        answer(arriving(listed = 0))
        runCurrent()

        opener.stop()
        answer(arriving(listed = 1))
        runCurrent()
        answer(SectionLoad.Done(items = 12_940))
        runCurrent()

        assertEquals(emptyList<CatalogSection>(), seen)
    }

    /**
     * The answer that must not open anything, though it would have.
     *
     * The viewer's press lands between the queue's answer and the opener reading it -- on a
     * television both are messages on the one main thread, in whatever order they came. The
     * answer says a category is listed, the wait is already over by the time it is read, and
     * the viewer is not taken anywhere: not to Live TV over the card they pressed instead.
     *
     * The `launch` is what puts the press there: it runs after the answer is on its way to the
     * opener and before the opener has read it.
     */
    @Test
    fun `an answer read after the viewer moved on opens nothing`() = runTest {
        val opener = pressed()
        val seen = opened(opener)

        answer(arriving(listed = 1))
        launch { opener.stop() }
        runCurrent()

        assertEquals(emptyList<CatalogSection>(), seen)
    }

    /**
     * The card says the failure, and a press asks again. A later load of the section -- a
     * refresh, another press -- is answering that press, so it opens nothing for this one.
     */
    @Test
    fun `a failed load ends the wait, and a later load of the section opens nothing`() = runTest {
        val opener = pressed()
        val seen = opened(opener)

        answer(arriving(listed = 0))
        runCurrent()
        answer(SectionLoad.Failed(AppError.TIMEOUT, retryable = true))
        runCurrent()

        answer(SectionLoad.Queued)
        runCurrent()
        answer(arriving(listed = 1))
        runCurrent()

        assertEquals(emptyList<CatalogSection>(), seen)
    }

    @Test
    fun `pressed again after a failure, it waits again and opens`() = runTest {
        val opener = pressed()
        val seen = opened(opener)
        answer(SectionLoad.Failed(AppError.TIMEOUT, retryable = true))
        runCurrent()

        answer(SectionLoad.Queued)
        opener.await(MediaKind.LIVE)
        runCurrent()
        answer(arriving(listed = 1))
        runCurrent()

        assertEquals(listOf(CatalogSection.Live), seen)
    }

    /** The queue forgets every answer when the subscription changes; so does the wait. */
    @Test
    fun `another subscription ends the wait`() = runTest {
        val opener = pressed()
        val seen = opened(opener)
        answer(arriving(listed = 0))
        runCurrent()

        runs.value = emptyMap()
        runCurrent()
        answer(arriving(listed = 1))
        runCurrent()

        assertEquals(emptyList<CatalogSection>(), seen)
    }

    /** Nothing is kept for later: a Home that comes back is not sent anywhere it did not ask. */
    @Test
    fun `nothing is kept for a Home that is not listening`() = runTest {
        val opener = pressed()

        answer(arriving(listed = 1))
        runCurrent()
        val seen = opened(opener)
        answer(SectionLoad.Done(items = 12_940))
        runCurrent()

        assertEquals(emptyList<CatalogSection>(), seen)
    }
}
