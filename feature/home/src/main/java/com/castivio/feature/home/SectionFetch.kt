package com.castivio.feature.home

import com.castivio.core.common.AppError
import com.castivio.domain.FetchDiagnosis
import com.castivio.domain.FetchStage
import com.castivio.domain.SectionLoad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * One section's answer for the active subscription, and the one fetch allowed to be
 * asking for it.
 *
 * ## Why this is its own class
 *
 * Because the rules are where the bug was. A section screen tells its holder what it is
 * for every time it enters composition, and the holder answered every such call by
 * fetching again unless a fetch was already running. A failed fetch is not running. So
 * the board that drew a failure asked again on its first frame, the new fetch put the
 * loading screen back, the board left, the fetch failed, the board came back — about
 * twice a second for as long as the screen was open, every lap a full playlist request
 * to the provider, and the error with its retry button on screen for a frame at a time.
 *
 * Free of Android, so the rules are a unit test rather than a device session.
 *
 * ## The rules
 *
 *  1. **A failure stays until the user answers it.** [ensure] never retries one; only
 *     [retry] does, and only while nothing is running — one press is one attempt.
 *  2. **A success is asked about again.** That is cheap — the loader answers from a
 *     mark without touching the network — and it is how a refresh that forgot the marks
 *     reaches the section on its next visit.
 *  3. **An answer belongs to one subscription.** When the active one changes, the fetch
 *     for the previous one is cancelled and its answer forgotten, so the loading screen
 *     cannot present the old subscription's state as the new one's.
 *  4. **One measurement per subscription, and one per retry**, so the performance panel
 *     never shows a run that belongs to another subscription.
 *
 * Not thread-safe, and not meant to be: every call arrives on the main thread, from
 * composition or from the holder's own scope.
 */
internal class SectionFetch(
    private val scope: CoroutineScope,
    private val load: (force: Boolean) -> Flow<SectionLoad>,
    private val onRunStarted: () -> Unit,
    private val onAnswer: (SectionLoad) -> Unit,
) {

    private val answer = MutableStateFlow<SectionLoad?>(null)

    /** Null before anything was asked, and again after the answer was forgotten. */
    val state: StateFlow<SectionLoad?> = answer.asStateFlow()

    private var running: Job? = null
    private var measured = false

    /** The subscription [state] describes. Only meaningful once [known] is true. */
    private var source: String? = null
    private var known = false

    /**
     * The section is on screen while [activeSource] is the active subscription.
     *
     * Idempotent, because composition calls it for reasons that are not a navigation.
     */
    fun ensure(activeSource: String?) {
        sourceChanged(activeSource)
        if (running?.isActive == true) return
        if (answer.value is SectionLoad.Failed) return
        if (!measured) {
            measured = true
            onRunStarted()
        }
        start(force = false)
    }

    /**
     * The user asked again: exactly one new attempt, measured as a run of its own.
     *
     * Ignored while an attempt is running. A second press during the first is not a
     * second question, and answering it would put two requests on the provider at once.
     */
    fun retry(force: Boolean = false) {
        if (running?.isActive == true) return
        answer.value = null
        measured = true
        onRunStarted()
        start(force)
    }

    /**
     * [activeSource] is the active subscription now.
     *
     * The first call only records it. A later call naming a different one cancels what
     * was running for the previous subscription and forgets what it said.
     */
    fun sourceChanged(activeSource: String?) {
        if (known && activeSource == source) return
        if (known) forget()
        source = activeSource
        known = true
    }

    /** Nothing said so far applies any more: a new subscription, or a new section. */
    fun forget() {
        running?.cancel()
        running = null
        answer.value = null
        measured = false
    }

    private fun start(force: Boolean) {
        running = scope.launch {
            load(force)
                // A loader that throws is a failure the user can read and retry, not an
                // exception that takes the screen's scope down with it. It is named, too:
                // this is the one failure no importer reports, so without its class the
                // error screen would have nothing to say about it but "unknown".
                .catch { error ->
                    emit(
                        SectionLoad.Failed(
                            AppError.UNKNOWN,
                            retryable = true,
                            diagnosis = FetchDiagnosis(FetchStage.OTHER, exception = FetchDiagnosis.nameOf(error)),
                        ),
                    )
                }
                .collect { next ->
                    answer.value = next
                    onAnswer(next)
                }
        }
    }
}
