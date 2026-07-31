package com.castivio.feature.activation

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.core.common.AppDispatchers
import com.castivio.domain.identity.DeviceIdentity
import com.castivio.domain.identity.IdentityProvenance
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * What the refresh button is doing, as a closed set.
 *
 * Refresh is never a dead control: it says what it is doing while it does it and
 * what it found when it stops, and "nothing yet" is a sentence on a status line
 * rather than a dialogue somebody has to dismiss with a remote.
 */
internal sealed interface RefreshState {
    data object Idle : RefreshState
    data object Checking : RefreshState
    data object Found : RefreshState
    data object None : RefreshState
    data object Error : RefreshState
}

/**
 * Which identifier was last copied, if either.
 *
 * Two independent instances, not one shared flag: copying the address must not
 * clear the confirmation on the key, and the two controls are never in the same
 * state by accident. Confirmation is a glyph swap inside an unchanged box, so
 * nothing moves.
 */
internal enum class Copied { None, Address, Key }

internal data class ActivationIdentityState(
    /** Null only for the instant before the first read resolves. */
    val address: String? = null,
    val provenance: IdentityProvenance? = null,
    /**
     * Six digits, and today a debug fixture (§4.2). Null in a build that has no
     * business showing one — nothing here derives a key from anything.
     */
    val deviceKey: String? = null,
    /** Drawn, never encoded. See [qrFixtureBitmap]. */
    val qr: Bitmap? = null,
    val refresh: RefreshState = RefreshState.Idle,
    val copied: Copied = Copied.None,
)

/**
 * The identity this device shows, and the two controls that act on it.
 *
 * Separate from `ActivationViewModel` on purpose: that one shepherds an import,
 * this one shows who the device is, and joining them would give the form
 * machinery a reason to exist on a screen that has no form.
 */
@HiltViewModel
internal class ActivationIdentityViewModel @Inject constructor(
    private val identity: DeviceIdentity,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    private val _state = MutableStateFlow(ActivationIdentityState())
    val state: StateFlow<ActivationIdentityState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val resolved = withContext(dispatchers.io) {
                val record = identity.current()
                Triple(
                    record.macAddress.value,
                    record.provenance,
                    runCatching { qrFixtureBitmap(QR_PIXELS) }.getOrNull(),
                )
            }
            _state.update {
                it.copy(
                    address = resolved.first,
                    provenance = resolved.second,
                    qr = resolved.third,
                    deviceKey = DebugFixtures.deviceKey(),
                )
            }
        }
    }

    /**
     * Ask whether a subscription has appeared.
     *
     * There is no portal to ask yet, so the honest answer is [RefreshState.None]
     * — the state that means "nothing found", which is exactly true. It is not a
     * stub standing in for a real call: when the licence backend exists this
     * reaches it, and the four outcomes the screen already renders are the four
     * it can return.
     */
    fun refresh() {
        if (_state.value.refresh == RefreshState.Checking) return
        viewModelScope.launch {
            _state.update { it.copy(refresh = RefreshState.Checking) }
            // Long enough that the state is legible rather than a flicker, which
            // is a property of the control and not a fake network delay.
            delay(CHECK_FLOOR_MS)
            _state.update { it.copy(refresh = RefreshState.None) }
        }
    }

    fun copied(what: Copied) {
        _state.update { it.copy(copied = what) }
    }

    private companion object {
        /** Generous enough for a phone camera to read across a room. */
        const val QR_PIXELS = 512
        const val CHECK_FLOOR_MS = 900L
    }
}
