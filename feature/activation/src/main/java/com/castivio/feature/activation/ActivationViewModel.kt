package com.castivio.feature.activation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.domain.activation.ActivateProvider
import com.castivio.domain.activation.ActivationForm
import com.castivio.domain.activation.ActivationPhase
import com.castivio.domain.activation.ActivationUiState
import com.castivio.domain.activation.asForm
import com.castivio.domain.time.TrustedTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The activation screen's state holder — and deliberately almost nothing else.
 *
 * Every decision this screen makes lives in `:domain`: what a valid server URL is, what
 * a failure means, whether a retry is worth offering, what happens to the previous
 * catalogue when an import dies halfway. All of that is pure and unit-tested without a
 * device. What is left here is a coroutine scope, a `StateFlow` and the one thing a
 * view model is actually for: owning the lifetime of a running job so that leaving the
 * screen cancels the import rather than orphaning it.
 *
 * Holding the split this way is the difference between a hundred activation cases
 * proven in a second on a laptop and a handful of them poked at by hand on a
 * television.
 *
 * Leaving the screen needs no code here. The import runs in [viewModelScope], which the
 * framework cancels when this view model is cleared, and an `onCleared` that cancelled
 * the same job again would be a line of ceremony that can only be tested by reaching
 * for API the library keeps internal.
 */
@HiltViewModel
class ActivationViewModel @Inject constructor(
    private val activate: ActivateProvider,
    private val clock: TrustedTime,
) : ViewModel() {

    private val _state = MutableStateFlow(ActivationUiState())
    val state: StateFlow<ActivationUiState> = _state.asStateFlow()

    /**
     * **An import finished. Said once, to whoever is listening at the time.**
     *
     * `ActivationPhase.Succeeded` is a state, and this view model outlives the screen
     * that reads it: `hiltViewModel()` resolves to the activity's store, so the phase
     * survives the flow closing, Home being shown, and the flow being opened again. And
     * nothing takes the phase back out of `Succeeded` — `cancel`, `retry`,
     * `dismissFailure` and `editing` all return to `Editing`, but there is no path from
     * a success to anything else, because a success is not something a user undoes.
     *
     * So a screen that watched the phase and reported "activated" on seeing `Succeeded`
     * reported it again every time it was composed. In the shell that seam means "close
     * me", and the subscription flow closed itself a quarter of a second after it opened
     * — for the rest of the process, after any one successful import. From Home the two
     * doors simply did not open.
     *
     * A [Channel] rather than a `StateFlow` because that is the actual shape of the
     * thing: a completion happens once, is delivered once, and is gone. A later
     * composition finds nothing to re-consume, which is the property the phase could
     * never have.
     *
     * Buffered, not conflated and not dropping: an event must not be lost across the
     * recomposition that follows it. In practice one cannot queue up — leaving the flow
     * while an import runs goes through [cancel], which stops the job before it can
     * finish — but a buffer is what makes that an observation rather than a requirement.
     */
    private val _activated = Channel<ActivationPhase.Succeeded>(Channel.BUFFERED)
    val activated: Flow<ActivationPhase.Succeeded> = _activated.receiveAsFlow()

    private var running: Job? = null

    // ------------------------------------------------------------- choosing a form

    /** "Add manually" from the activation screen; Xtream is the form it opens on. */
    fun useXtream() = editing { ActivationForm.Xtream() }

    fun usePlaylistUrl() = editing { ActivationForm.Playlist() }

    /**
     * The user accepted the offer to read their playlist link as Xtream.
     *
     * Only ever reached from a link that was already detected, so the fields are filled
     * from what they pasted rather than asked for again.
     */
    fun acceptDetectedXtream() = editing { current ->
        val playlist = current as? ActivationForm.Playlist ?: return@editing current
        playlist.detectedXtream?.asForm(playlist.name) ?: current
    }

    // -------------------------------------------------------------------- typing

    fun name(value: String) = editForm { form ->
        when (form) {
            is ActivationForm.Xtream -> form.copy(name = value)
            is ActivationForm.Playlist -> form.copy(name = value)
        }
    }

    fun serverUrl(value: String) = editXtream { it.copy(serverUrl = value) }

    fun username(value: String) = editXtream { it.copy(username = value) }

    fun password(value: String) = editXtream { it.copy(password = value) }

    fun playlistUrl(value: String) = editForm { form ->
        (form as? ActivationForm.Playlist)?.copy(url = value) ?: form
    }

    // --------------------------------------------------------------- the attempt

    /**
     * Starts an activation, or does nothing when the form is incomplete or one is
     * already running.
     *
     * Guarded rather than trusted: a television remote repeats a keypress more readily
     * than a finger does, and two imports of the same provider racing each other would
     * interleave writes to the same rows.
     */
    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        val source = current.form.source ?: return

        running = viewModelScope.launch {
            // Checked and saved, not downloaded.
            //
            // Activation used to bring the whole catalogue with it, and on a real
            // provider that is a four-minute wait before the app opens — most of it
            // spent on a film library the user may never open. Each section is
            // fetched when it is first opened instead, by `LoadSection`, so what this
            // screen now proves is only that the credentials work.
            activate.activate(source, current.form.label, clock.nowMs(), fetchCatalogue = false)
                .collect { phase ->
                    _state.update { it.copy(phase = phase) }
                    // Announced where it happens, once, rather than inferred later from
                    // the phase it leaves behind. See [activated].
                    if (phase is ActivationPhase.Succeeded) _activated.send(phase)
                }
        }
    }

    /** Same details, second attempt. Offered only for the failures that can pass later. */
    fun retry() {
        val failed = _state.value.phase as? ActivationPhase.Failed ?: return
        if (!failed.retryable) return
        _state.update { it.copy(phase = ActivationPhase.Editing) }
        submit()
    }

    /**
     * Stops an import in flight.
     *
     * Nothing is committed by a cancellation and nothing already committed is lost:
     * `ActivateProvider` removes the scaffolding registration, and a `REPLACE` import
     * prunes only after it commits. The user is exactly where they were.
     */
    fun cancel() {
        running?.cancel()
        running = null
        _state.update { it.copy(phase = ActivationPhase.Editing) }
    }

    /** Dismisses a failure and returns to the form with the text still in it. */
    fun dismissFailure() {
        if (_state.value.phase !is ActivationPhase.Failed) return
        _state.update { it.copy(phase = ActivationPhase.Editing) }
    }

    // -------------------------------------------------------------------- plumbing

    private fun editing(change: (ActivationForm) -> ActivationForm) {
        if (_state.value.busy) return
        _state.update { it.copy(form = change(it.form), phase = ActivationPhase.Editing) }
    }

    private fun editForm(change: (ActivationForm) -> ActivationForm) {
        // Typing while an import runs is ignored rather than queued: the fields are
        // read-only on screen, and accepting an edit that the running attempt would not
        // use is a state the user cannot reason about.
        if (_state.value.busy) return
        _state.update { it.copy(form = change(it.form)) }
    }

    private fun editXtream(change: (ActivationForm.Xtream) -> ActivationForm.Xtream) = editForm { form ->
        (form as? ActivationForm.Xtream)?.let(change) ?: form
    }
}
