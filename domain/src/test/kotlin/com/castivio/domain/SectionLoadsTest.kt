package com.castivio.domain

import com.castivio.core.common.AppError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules every section's load follows, now that Home asks and the application loads.
 *
 * Two of them are older than this class and were a user's video before they were rules:
 * a failure asked for again on every frame, and a double press that sent two requests.
 */
class SectionLoadsTest {

    private val failed = SectionLoad.Failed(AppError.NETWORK_UNAVAILABLE, retryable = true)

    // ------------------------------------------------------------------ one at a time

    @Test
    fun `a section asked for during another waits its turn, then loads`() = runTest {
        val live = CompletableDeferred<Unit>()
        val loader = Loader { kind, _ ->
            flow {
                emit(SectionLoad.Loading(0, 0))
                if (kind == MediaKind.LIVE) live.await()
                emit(SectionLoad.Done(10))
            }
        }
        val loads = loads(loader)

        loads.request(MediaKind.LIVE)
        loads.request(MediaKind.RADIO)
        runCurrent()

        assertEquals(SectionLoad.Loading(0, 0), loads.runs.value.getValue(MediaKind.LIVE).load)
        assertEquals(SectionLoad.Queued, loads.runs.value.getValue(MediaKind.RADIO).load)
        assertEquals("radio has not been asked of the provider yet", listOf(MediaKind.LIVE), loader.kinds)

        live.complete(Unit)
        runCurrent()

        assertEquals(SectionLoad.Done(10), loads.runs.value.getValue(MediaKind.LIVE).load)
        assertEquals(SectionLoad.Done(10), loads.runs.value.getValue(MediaKind.RADIO).load)
        assertEquals(listOf(MediaKind.LIVE, MediaKind.RADIO), loader.kinds)
    }

    @Test
    fun `sections load in the order they were asked for`() = runTest {
        val loader = Loader { _, _ -> flowOf(SectionLoad.Done(1)) }
        val loads = loads(loader)

        loads.request(MediaKind.SERIES)
        loads.request(MediaKind.LIVE)
        loads.request(MediaKind.MOVIE)
        runCurrent()

        assertEquals(listOf(MediaKind.SERIES, MediaKind.LIVE, MediaKind.MOVIE), loader.kinds)
    }

    @Test
    fun `asking again while a section waits or arrives is the same question`() = runTest {
        val loader = Loader { _, _ -> flow { emit(SectionLoad.Loading(0, 0)); awaitCancellation() } }
        val loads = loads(loader)

        loads.request(MediaKind.LIVE)
        loads.request(MediaKind.MOVIE)
        // A remote's repeated key on both cards, before and after the first has answered.
        loads.request(MediaKind.LIVE)
        loads.request(MediaKind.MOVIE)
        runCurrent()
        loads.request(MediaKind.LIVE)
        loads.request(MediaKind.MOVIE, force = true)
        runCurrent()

        assertEquals(listOf(MediaKind.LIVE), loader.kinds)
        assertEquals(SectionLoad.Queued, loads.runs.value.getValue(MediaKind.MOVIE).load)
    }

    // ------------------------------------------------------------------ failures

    @Test
    fun `a failure stays until somebody asks again, and is not retried on its own`() = runTest {
        val loader = Loader { _, _ -> flowOf(SectionLoad.Loading(0, 0), failed) }
        val loads = loads(loader)

        loads.request(MediaKind.MOVIE)
        runCurrent()
        // Another section loading and finishing must not wake the failed one up.
        loads.request(MediaKind.SERIES)
        runCurrent()

        assertEquals(failed, loads.runs.value.getValue(MediaKind.MOVIE).load)
        assertEquals(listOf(MediaKind.MOVIE, MediaKind.SERIES), loader.kinds)

        loads.request(MediaKind.MOVIE)
        runCurrent()

        assertEquals(listOf(MediaKind.MOVIE, MediaKind.SERIES, MediaKind.MOVIE), loader.kinds)
    }

    @Test
    fun `a loader that throws is a failure that can be asked again, not the end of the queue`() = runTest {
        val loader = Loader { kind, _ ->
            if (kind == MediaKind.LIVE) flow { throw IllegalStateException("boom") } else flowOf(SectionLoad.Done(4))
        }
        val loads = loads(loader)

        loads.request(MediaKind.LIVE)
        loads.request(MediaKind.RADIO)
        runCurrent()

        val live = loads.runs.value.getValue(MediaKind.LIVE).load as SectionLoad.Failed
        assertTrue(live.retryable)
        assertEquals("IllegalStateException", live.diagnosis?.exception)
        assertEquals(SectionLoad.Done(4), loads.runs.value.getValue(MediaKind.RADIO).load)
    }

    // ------------------------------------------------------------------ refresh

    @Test
    fun `a refresh asks the loader to fetch whatever the marks say, and says it was one`() = runTest {
        val loader = Loader { _, _ -> flowOf(SectionLoad.Done(2)) }
        val loads = loads(loader)

        loads.request(MediaKind.LIVE)
        runCurrent()
        loads.request(MediaKind.LIVE, force = true)
        runCurrent()

        assertEquals(listOf(false, true), loader.forced)
        assertTrue(loads.runs.value.getValue(MediaKind.LIVE).forced)
    }

    // ------------------------------------------------------------------ a first load

    /**
     * A section asked for with nothing of it on the device says so on every answer of its
     * run -- waiting, arriving, done -- and a load over rows that are already here does not.
     */
    @Test
    fun `a first load says so on every answer, and a load over rows does not`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val loader = Loader { kind, _ ->
            flow {
                if (kind == MediaKind.LIVE) gate.await()
                emit(SectionLoad.Loading(1, 2))
                emit(SectionLoad.Done(2))
            }
        }
        val loads = loads(loader)
        val seen = mutableListOf<Pair<MediaKind, Boolean>>()
        backgroundScope.launch { loads.runs.collect { runs -> runs.forEach { (kind, run) -> seen += kind to run.first } } }

        loads.request(MediaKind.LIVE, first = true)
        loads.request(MediaKind.MOVIE)
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertTrue("every live answer says first", seen.filter { it.first == MediaKind.LIVE }.all { it.second })
        assertTrue("no film answer says first", seen.filter { it.first == MediaKind.MOVIE }.none { it.second })
        assertEquals(SectionLoad.Done(2), loads.runs.value.getValue(MediaKind.LIVE).load)
        assertTrue(loads.runs.value.getValue(MediaKind.LIVE).first)
    }

    // ------------------------------------------------------------------ subscriptions

    @Test
    fun `a new subscription cancels the load, empties the queue and forgets every answer`() = runTest {
        var cancelled = false
        val loader = Loader { kind, _ ->
            if (kind == MediaKind.LIVE) {
                flow {
                    emit(SectionLoad.Loading(5, 10))
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
            } else {
                flowOf(SectionLoad.Done(1))
            }
        }
        val active = MutableStateFlow<String?>("first")
        val loads = loads(loader, active)
        runCurrent()

        loads.request(MediaKind.MOVIE)
        loads.request(MediaKind.LIVE)
        loads.request(MediaKind.RADIO)
        runCurrent()
        active.value = "second"
        runCurrent()

        assertTrue(cancelled)
        assertEquals(emptyMap<MediaKind, SectionRun>(), loads.runs.value)
        assertEquals("radio was never asked for the old subscription", listOf(MediaKind.MOVIE, MediaKind.LIVE), loader.kinds)
    }

    @Test
    fun `the first subscription reported does not cancel a section asked for before it`() = runTest {
        val loader = Loader { _, _ -> flow { emit(SectionLoad.Loading(1, 2)); awaitCancellation() } }
        val active = MutableStateFlow<String?>(null)
        // Asked before the repository has said anything: the queue's own collector has not run.
        val loads = loads(loader, active)
        loads.request(MediaKind.LIVE)
        active.value = "xtream"
        runCurrent()

        assertEquals(SectionLoad.Loading(1, 2), loads.runs.value.getValue(MediaKind.LIVE).load)
    }

    @Test
    fun `the same subscription reported again changes nothing`() = runTest {
        val loader = Loader { _, _ -> flow { emit(SectionLoad.Loading(1, 2)); awaitCancellation() } }
        val active = MutableStateFlow<String?>("xtream")
        val loads = loads(loader, active)
        runCurrent()

        loads.request(MediaKind.LIVE)
        runCurrent()
        active.value = "other"
        active.value = "xtream"
        runCurrent()

        // A conflated flow may deliver only the last value; either way the load is the
        // subscription's own and must still be on show.
        assertEquals(1, loader.kinds.size)
    }

    // ------------------------------------------------------------------ what is told

    @Test
    fun `the time is measured from when a load starts running, not from when it was asked`() = runTest {
        var now = 1_000L
        val first = CompletableDeferred<Unit>()
        val loader = Loader { kind, _ ->
            flow {
                if (kind == MediaKind.LIVE) first.await()
                now += 7_000
                emit(SectionLoad.Done(3))
            }
        }
        val loads = SectionLoads(backgroundScope, MutableStateFlow("xtream"), loader::invoke, monotonicMs = { now })

        loads.request(MediaKind.LIVE)
        loads.request(MediaKind.RADIO)
        runCurrent()
        assertNull("waiting has no time yet", loads.runs.value.getValue(MediaKind.RADIO).elapsedMs)
        now += 30_000
        first.complete(Unit)
        runCurrent()

        assertEquals(37_000L, loads.runs.value.getValue(MediaKind.LIVE).elapsedMs)
        assertEquals("radio waited thirty seconds and loaded in seven", 7_000L, loads.runs.value.getValue(MediaKind.RADIO).elapsedMs)
    }

    @Test
    fun `each load is told as it starts and every answer after, in order`() = runTest {
        val told = mutableListOf<String>()
        val loader = Loader { _, _ -> flowOf(SectionLoad.Loading(1, 1), SectionLoad.Done(1)) }
        val loads = SectionLoads(
            backgroundScope,
            MutableStateFlow("xtream"),
            loader::invoke,
            monotonicMs = { 0L },
            onRunStarted = { told += "start $it" },
            onAnswer = { kind, load -> told += "$kind ${load::class.simpleName}" },
        )

        loads.request(MediaKind.LIVE)
        loads.request(MediaKind.MOVIE)
        runCurrent()

        assertEquals(
            listOf("start LIVE", "LIVE Loading", "LIVE Done", "start MOVIE", "MOVIE Loading", "MOVIE Done"),
            told,
        )
    }

    @Test
    fun `a cancelled load's answers are not told`() = runTest {
        val told = mutableListOf<SectionLoad>()
        val loader = Loader { _, _ -> flow { emit(SectionLoad.Loading(1, 1)); awaitCancellation() } }
        val active = MutableStateFlow<String?>("first")
        val loads = SectionLoads(backgroundScope, active, loader::invoke, monotonicMs = { 0L }, onAnswer = { _, load -> told += load })
        runCurrent()

        loads.request(MediaKind.LIVE)
        runCurrent()
        active.value = "second"
        runCurrent()

        assertEquals(listOf<SectionLoad>(SectionLoad.Loading(1, 1)), told)
    }

    // ------------------------------------------------------------------ helpers

    private fun TestScope.loads(loader: Loader, active: MutableStateFlow<String?> = MutableStateFlow("xtream")) =
        SectionLoads(backgroundScope, active, loader::invoke, monotonicMs = { 0L })

    private class Loader(private val answer: (MediaKind, Boolean) -> Flow<SectionLoad>) {
        val kinds = mutableListOf<MediaKind>()
        val forced = mutableListOf<Boolean>()

        fun invoke(kind: MediaKind, force: Boolean): Flow<SectionLoad> {
            kinds += kind
            forced += force
            return answer(kind, force)
        }
    }
}
