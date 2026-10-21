package com.castivio.domain

import com.castivio.core.common.AppError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The sections being brought onto the device, for the whole application, one at a time.
 *
 * ## Why it belongs to the application and not to a screen
 *
 * A section is asked for from Home, and the viewer stays on Home while it arrives -- or
 * leaves to watch a channel that is already here, and comes back. The load must not care
 * which. It used to belong to the section's own screen, behind a loading screen that held
 * the viewer until the last row; now Home draws it on the section's card, and the card has
 * to be right after a visit anywhere else, so the load lives here, as long as the app does.
 *
 * ## The rules
 *
 *  1. **One load at a time**, in the order they were asked for. A section pressed while
 *     another arrives waits as [SectionLoad.Queued]. Two imports at once share one
 *     connection, one database writer and, on a stick, one slow processor -- and both
 *     finish later than if they had gone one after the other.
 *  2. **Asking again changes nothing while a section waits or arrives.** A double press,
 *     or a remote's repeated key, is one question, not a second request to the provider.
 *  3. **A failure stays until somebody asks again.** Nothing here retries on its own: a
 *     failed section asked for again on every frame was once a request to the provider
 *     twice a second for as long as its screen was open.
 *  4. **An answer belongs to one subscription.** When the active one changes, the load
 *     running for the previous one is cancelled, the queue is emptied and every answer is
 *     forgotten, so no card can show the old subscription's state as the new one's.
 *
 * Pure, and therefore tested without a device. [scope] decides the thread the loads are
 * collected on; the state is guarded on its own, so [request] may come from any thread.
 *
 * @param activeSource the active subscription's id, null when there is none.
 * @param load one section's load, as [LoadSection.load] answers it.
 * @param monotonicMs a clock that never moves backwards, for [SectionRun.elapsedMs].
 * @param onRunStarted told as a load begins running, after it stopped waiting.
 * @param onAnswer told every answer of a load that still belongs to the subscription.
 */
class SectionLoads(
    private val scope: CoroutineScope,
    activeSource: Flow<String?>,
    private val load: (kind: MediaKind, force: Boolean) -> Flow<SectionLoad>,
    private val monotonicMs: () -> Long,
    private val onRunStarted: (MediaKind) -> Unit = {},
    private val onAnswer: (MediaKind, SectionLoad) -> Unit = { _, _ -> },
) {

    private val lock = Any()
    private val _runs = MutableStateFlow<Map<MediaKind, SectionRun>>(emptyMap())

    /**
     * Every section asked about for the active subscription, by kind: waiting, arriving,
     * or what its last load ended with. A kind nobody has asked about has no entry.
     */
    val runs: StateFlow<Map<MediaKind, SectionRun>> = _runs.asStateFlow()

    private val waiting = ArrayDeque<Ask>()
    private var running: Job? = null

    /** Bumped when the subscription changes, so a cancelled load's last answer is dropped. */
    private var generation = 0
    private var source: String? = null
    private var known = false

    init {
        scope.launch { activeSource.distinctUntilChanged().collect(::sourceChanged) }
    }

    /**
     * Bring [kind] onto the device, after whatever is arriving now.
     *
     * @param force fetch even though the section is marked as loaded: a refresh asked for
     *   deliberately. Without it a marked section answers `Ready` and downloads nothing.
     * @param first nothing of the section is on the device: this is its first load, and not
     *   a load over rows that are already here. The asker knows it, from the rows it counts;
     *   the queue only carries it, on [SectionRun.first].
     */
    fun request(kind: MediaKind, force: Boolean = false, first: Boolean = false) {
        synchronized(lock) {
            val current = _runs.value[kind]?.load
            if (current is SectionLoad.Queued || current is SectionLoad.Loading) return
            waiting.addLast(Ask(kind, force, first))
            _runs.update { it + (kind to SectionRun(SectionLoad.Queued, force, elapsedMs = null, first = first)) }
            if (running == null) startNext()
        }
    }

    /** Called holding [lock]. */
    private fun startNext() {
        val next = waiting.removeFirstOrNull()
        if (next == null) {
            running = null
            return
        }
        val asked = generation
        val job = scope.launch(start = CoroutineStart.LAZY) { run(next, asked) }
        running = job
        job.invokeOnCompletion {
            synchronized(lock) { if (running === job) startNext() }
        }
        job.start()
    }

    private suspend fun run(ask: Ask, asked: Int) {
        val startedAt = monotonicMs()
        onRunStarted(ask.kind)
        load(ask.kind, ask.force)
            // A loader that throws is a failure the viewer can read and ask again about,
            // not an exception that ends every load after it. It is named, too: no importer
            // reports this one, so without its class the card would have nothing to say.
            .catch { error ->
                emit(
                    SectionLoad.Failed(
                        AppError.UNKNOWN,
                        retryable = true,
                        diagnosis = FetchDiagnosis(FetchStage.OTHER, exception = FetchDiagnosis.nameOf(error)),
                    ),
                )
            }
            .collect { answer ->
                val current = synchronized(lock) {
                    if (asked != generation) {
                        false
                    } else {
                        val run = SectionRun(answer, ask.force, elapsedMs = monotonicMs() - startedAt, first = ask.first)
                        _runs.update { it + (ask.kind to run) }
                        true
                    }
                }
                if (current) onAnswer(ask.kind, answer)
            }
    }

    /**
     * The first subscription reported is only recorded: a section asked for before the
     * repository answered is a section of that subscription. A different one afterwards
     * starts everything over.
     */
    private fun sourceChanged(id: String?) {
        synchronized(lock) {
            if (known && id == source) return
            val first = !known
            known = true
            source = id
            if (first) return
            generation += 1
            waiting.clear()
            val previous = running
            running = null
            previous?.cancel()
            _runs.value = emptyMap()
        }
    }

    private data class Ask(val kind: MediaKind, val force: Boolean, val first: Boolean)
}

/**
 * One section's latest answer, and how long the load behind it had taken when it said it.
 */
data class SectionRun(
    val load: SectionLoad,
    /** A refresh asked for deliberately, rather than a section's first load. */
    val forced: Boolean,
    /**
     * From the moment the load began running -- not from when it was asked for, which
     * includes its wait behind another -- to its latest answer. Null while it waits.
     */
    val elapsedMs: Long?,
    /**
     * Asked for while nothing of the section was on the device: its first load, which the
     * viewer is waiting on, rather than a load over rows that are already here. Live TV opens
     * with the first category of its first load, and the rest of it arrives unannounced.
     */
    val first: Boolean = false,
)
