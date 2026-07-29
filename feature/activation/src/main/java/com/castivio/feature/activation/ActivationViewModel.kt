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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 */
@HiltViewModel
class ActivationViewModel @Inject constructor(
    private val activate: ActivateProvider,
    private val clock: TrustedTime,
) : ViewModel() {

    private val _state = MutableStateFlow(ActivationUiState())
    val state: StateFlow<ActivationUiState> = _state.asStateFlow()

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
            activate.activate(source, current.form.label, clock.nowMs())
                .collect { phase -> _state.update { it.copy(phase = phase) } }
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

    override fun onCleared() {
        // Leaving the screen ends the import. An orphaned one would keep writing to a
        // database nobody is reading, on a box that needs its CPU for playback.
        running?.cancel()
        super.onCleared()
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
