package com.castivio.feature.home

import com.castivio.core.common.AppError
import com.castivio.domain.FetchDiagnosis
import com.castivio.domain.FetchStage
import com.castivio.domain.SectionLoad
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules a section's fetch follows, without a device.
 *
 * The first two are the defect a user filmed: a failed playlist that the board asked for
 * again on every frame it was composed, so the screen flicked between loading and the
 * board about twice a second and the provider received a request each time.
 */
class SectionFetchTest {

    private val failed = SectionLoad.Failed(AppError.UNAUTHORIZED, retryable = false)

    @Test
    fun `a failure is not asked again when the board composes again`() = runTest {
        val loader = Loader { flowOf(SectionLoad.Loading(0, 0), failed) }
        val fetch = fetch(loader)

        fetch.ensure("m3u")
        runCurrent()
        // What the board's own `LaunchedEffect` did on each of its frames.
        repeat(5) { fetch.ensure("m3u") }
        runCurrent()

        assertEquals("one request, not one per frame", 1, loader.calls)
        assertEquals("the failure stays on screen", failed, fetch.state.value)
    }

    @Test
    fun `one press of retry is one attempt, however many times it lands`() = runTest {
        val loader = Loader { attempt ->
            if (attempt == 1) flowOf(failed) else flow {
                emit(SectionLoad.Loading(0, 0))
                awaitCancellation()
            }
        }
        val fetch = fetch(loader)
        fetch.ensure("m3u")
        runCurrent()

        // A double press, or a remote's repeated key, before the first one has answered.
        fetch.retry()
        fetch.retry()
        fetch.retry()
        runCurrent()

        assertEquals(2, loader.calls)
        assertEquals(SectionLoad.Loading(0, 0), fetch.state.value)
    }

    @Test
    fun `a retry that fails again waits for the next press`() = runTest {
        val loader = Loader { flowOf(failed) }
        val fetch = fetch(loader)
        fetch.ensure("m3u")
        runCurrent()

        fetch.retry()
        runCurrent()
        repeat(3) { fetch.ensure("m3u") }
        runCurrent()

        assertEquals(2, loader.calls)
        assertEquals(failed, fetch.state.value)
    }

    @Test
    fun `retry clears the failure while it asks`() = runTest {
        val loader = Loader { attempt ->
            if (attempt == 1) flowOf(failed) else flow { awaitCancellation() }
        }
        val fetch = fetch(loader)
        fetch.ensure("m3u")
        runCurrent()

        fetch.retry()

        assertNull("the loading screen, not the failure, while the retry runs", fetch.state.value)
    }

    @Test
    fun `asking while a fetch runs does not start a second one`() = runTest {
        val loader = Loader { flow { emit(SectionLoad.Loading(0, 0)); awaitCancellation() } }
        val fetch = fetch(loader)

        fetch.ensure("xtream")
        fetch.ensure("xtream")
        runCurrent()
        fetch.ensure("xtream")
        runCurrent()

        assertEquals(1, loader.calls)
    }

    @Test
    fun `a section on the device is asked about again on the next visit`() = runTest {
        // Cheap -- the loader answers Ready from a mark -- and it is how a refresh that
        // forgot the marks reaches the section the next time it is opened.
        val loader = Loader { flowOf(SectionLoad.Done(3)) }
        val fetch = fetch(loader)

        fetch.ensure("xtream")
        runCurrent()
        fetch.ensure("xtream")
        runCurrent()

        assertEquals(2, loader.calls)
    }

    @Test
    fun `a new subscription cancels the previous fetch and forgets its answer`() = runTest {
        var cancelled = false
        val loader = Loader { attempt ->
            if (attempt == 1) {
                flow {
                    emit(SectionLoad.Loading(items = 5_330, groups = 835, groupsDone = 53))
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
            } else {
                flowOf(SectionLoad.Done(2))
            }
        }
        val fetch = fetch(loader)
        fetch.ensure("xtream")
        runCurrent()

        fetch.sourceChanged("m3u")
        runCurrent()

        assertTrue("the old subscription's fetch is cancelled", cancelled)
        assertNull("and its progress is not shown as the new one's", fetch.state.value)

        fetch.ensure("m3u")
        runCurrent()
        assertEquals(2, loader.calls)
        assertEquals(SectionLoad.Done(2), fetch.state.value)
    }

    @Test
    fun `a failure belongs to the subscription it happened on`() = runTest {
        val loader = Loader { attempt -> if (attempt == 1) flowOf(failed) else flowOf(SectionLoad.Done(4)) }
        val fetch = fetch(loader)
        fetch.ensure("m3u")
        runCurrent()

        fetch.sourceChanged("xtream")
        fetch.ensure("xtream")
        runCurrent()

        assertEquals("no retry press needed for a different subscription", 2, loader.calls)
        assertEquals(SectionLoad.Done(4), fetch.state.value)
    }

    @Test
    fun `a screen that sees the switch before the watcher still asks about the new one`() =
        runTest {
            val loader = Loader { flowOf(failed) }
            val fetch = fetch(loader)
            fetch.ensure("m3u")
            runCurrent()

            fetch.ensure("xtream")
            runCurrent()

            assertEquals(2, loader.calls)
        }

    @Test
    fun `the same subscription reported again changes nothing`() = runTest {
        val loader = Loader { flowOf(SectionLoad.Done(7)) }
        val fetch = fetch(loader)
        fetch.ensure("xtream")
        runCurrent()

        fetch.sourceChanged("xtream")

        assertEquals(SectionLoad.Done(7), fetch.state.value)
    }

    @Test
    fun `one measurement per subscription, and one per retry`() = runTest {
        var runs = 0
        val loader = Loader { attempt -> if (attempt <= 2) flowOf(failed) else flowOf(SectionLoad.Done(1)) }
        val fetch = fetch(loader, onRunStarted = { runs++ })

        fetch.ensure("m3u")
        runCurrent()
        fetch.ensure("m3u")
        runCurrent()
        assertEquals("composing again is not a new open", 1, runs)

        fetch.retry()
        runCurrent()
        assertEquals("a deliberate retry is its own run", 2, runs)

        fetch.sourceChanged("xtream")
        fetch.ensure("xtream")
        runCurrent()
        fetch.ensure("xtream")
        runCurrent()
        assertEquals("a new subscription is a new run, measured once", 3, runs)
    }

    @Test
    fun `every answer reaches the stopwatch in order`() = runTest {
        val answers = mutableListOf<SectionLoad>()
        val loader = Loader { flowOf(SectionLoad.Loading(0, 0), SectionLoad.Loading(1_000, 3), failed) }
        val fetch = fetch(loader, onAnswer = { answers += it })

        fetch.ensure("m3u")
        runCurrent()

        assertEquals(
            listOf(SectionLoad.Loading(0, 0), SectionLoad.Loading(1_000, 3), failed),
            answers,
        )
    }

    @Test
    fun `a loader that throws is a failure the user can retry, not a crash`() = runTest {
        val loader = Loader { flow { throw IllegalStateException("writer exploded") } }
        val fetch = fetch(loader)

        fetch.ensure("m3u")
        runCurrent()

        assertEquals(
            SectionLoad.Failed(
                AppError.UNKNOWN,
                retryable = true,
                // Named, and only by its class: "writer exploded" stays out of it.
                diagnosis = FetchDiagnosis(FetchStage.OTHER, exception = "IllegalStateException"),
            ),
            fetch.state.value,
        )
    }

    @Test
    fun `forgetting a section cancels its fetch and starts the next one fresh`() = runTest {
        var runs = 0
        val loader = Loader { attempt ->
            if (attempt == 1) flow { awaitCancellation() } else flowOf(SectionLoad.Ready)
        }
        val fetch = fetch(loader, onRunStarted = { runs++ })
        fetch.ensure("xtream")
        runCurrent()

        fetch.forget()
        fetch.ensure("xtream")
        runCurrent()

        assertEquals(SectionLoad.Ready, fetch.state.value)
        assertEquals(2, runs)
    }

    // ------------------------------------------------------------------ fixtures

    private fun TestScope.fetch(
        loader: Loader,
        onRunStarted: () -> Unit = {},
        onAnswer: (SectionLoad) -> Unit = {},
    ) = SectionFetch(
        // The background scope, because some loaders here never finish -- the way a
        // stalled download does not -- and the test must not wait for them.
        scope = backgroundScope,
        load = loader::load,
        onRunStarted = onRunStarted,
        onAnswer = onAnswer,
    )

    /** Counts the fetches it is asked for and answers each with [answer]. */
    private class Loader(private val answer: (attempt: Int) -> Flow<SectionLoad>) {
        var calls = 0
            private set

        fun load(@Suppress("UNUSED_PARAMETER") force: Boolean): Flow<SectionLoad> {
            calls += 1
            return answer(calls)
        }
    }
}
