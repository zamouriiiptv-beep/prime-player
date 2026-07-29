package com.castivio.tv.gate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.domain.SourceRepository
import com.castivio.domain.entitlement.EntitlementRepository
import com.castivio.domain.entitlement.StartDestination
import com.castivio.domain.entitlement.startDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Where the app opens.
 *
 * Null while the two gates are still being asked, which is the only reason this is a
 * state at all — the decision itself is nine lines of pure code in
 * [startDestination], already tested against every crossing between the two gates.
 */
internal data class SplashState(val destination: StartDestination? = null)

/**
 * Asks the two gates once, in order, and reports the answer.
 *
 * `establish()` comes first because a device with no entitlement has to be given one
 * before it can be judged — in a debug build that is the local trial, and in a release
 * build it is a request to a licence server that does not exist yet, which fails closed
 * on purpose.
 *
 * It reads the active provider directly rather than observing it: this runs once, before
 * the first screen, and a flow that kept re-deciding where the app should be while the
 * user was already somewhere would be a navigation bug waiting for a slow import.
 */
@HiltViewModel
internal class SplashViewModel @Inject constructor(
    private val entitlement: EntitlementRepository,
    private val sources: SourceRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SplashState())
    val state: StateFlow<SplashState> = _state.asStateFlow()

    init {
        viewModelScope.launch { decide() }
    }

    /** Re-asks after activation finishes, so the app moves on for the right reason. */
    fun refresh() {
        viewModelScope.launch { decide() }
    }

    private suspend fun decide() {
        val licence = entitlement.establish()
        _state.value = SplashState(startDestination(licence, sources.activeNow()))
    }
}
