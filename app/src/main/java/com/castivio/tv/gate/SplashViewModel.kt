package com.castivio.tv.gate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.domain.entitlement.StartDestination
import com.castivio.domain.portal.AppOpening
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Where the app opens.
 *
 * Null while the gates are still being asked, which is the only reason this is a state at all —
 * the decision itself is nine lines of pure code in `startDestination`, already tested against
 * every crossing between the two gates, and the rules about asking the website first are in
 * [AppOpening], tested without a device.
 */
internal data class SplashState(val destination: StartDestination? = null)

/**
 * Hands the gate's question to [AppOpening] and keeps its answers.
 *
 * `establish()` still comes first, inside [AppOpening], because a device with no entitlement has
 * to be given one before it can be judged — in a debug build that is the local trial, and in a
 * release build it is a request to a licence server that does not exist yet, which fails closed
 * on purpose.
 *
 * **Without a press.** What the owner sent from the website is asked for as the app opens, and
 * again whenever it comes back to the front: a device with nothing on it waits a moment for the
 * answer and opens on Home with the subscription, a device with one opens on Home at once and
 * is given the new one behind it. The ask is the process's and not this view model's, so
 * closing the app mid-way does not leave a subscription half added.
 *
 * It reads the active provider directly rather than observing it: this decides once, before the
 * first screen, and a flow that kept re-deciding where the app should be while the user was
 * already somewhere would be a navigation bug waiting for a slow import. The only later moves
 * are the two it is told about — an activation that finished, and an arrival that changes the
 * screen the user is on.
 */
@HiltViewModel
internal class SplashViewModel @Inject constructor(
    private val opening: AppOpening,
) : ViewModel() {

    private val _state = MutableStateFlow(SplashState())
    val state: StateFlow<SplashState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // One destination as a rule; a second only when a slow answer from the website
            // puts a subscription on a device that had none, after the first screen was up.
            opening.opening().collect { _state.value = SplashState(it) }
        }
    }

    /** Re-asks after activation finishes, so the app moves on for the right reason. */
    fun refresh() {
        viewModelScope.launch { _state.value = SplashState(opening.settled()) }
    }

    /**
     * The app is in front again — opened, or returned to after another one — and something may
     * have been sent meanwhile. The first call of a launch comes while the opening is still
     * deciding and has nothing to add to it.
     */
    fun cameForward() {
        val shown = _state.value.destination ?: return
        viewModelScope.launch {
            opening.cameForward(shown)?.let { _state.value = SplashState(it) }
        }
    }
}
