package com.castivio.feature.activation

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.core.common.AppDispatchers
import com.castivio.domain.identity.DeviceIdentity
import com.castivio.domain.identity.IdentityProvenance
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * This device's address, and a code that carries it.
 *
 * @param address null only for the instant before the first read resolves. Deriving it
 *   is a hash and a preferences read, so that instant is short — but it is off the main
 *   thread anyway, because a keystore-backed file on a cheap stick is not something to
 *   find out about on the first frame.
 * @param qr null when the code could not be drawn. The address above it is the thing
 *   that matters; the code is a convenience, and a screen that refuses to render
 *   because a convenience failed is worse than one without it.
 */
internal data class MacIdentityState(
    val address: String? = null,
    val provenance: IdentityProvenance? = null,
    val qr: Bitmap? = null,
)

/**
 * Reads the identity once and holds it.
 *
 * Separate from `ActivationViewModel` on purpose: this screen shows who the device is,
 * that one shepherds an import, and joining them would give the form machinery a reason
 * to exist on a screen that has no form.
 */
@HiltViewModel
internal class MacIdentityViewModel @Inject constructor(
    private val identity: DeviceIdentity,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    private val _state = MutableStateFlow(MacIdentityState())
    val state: StateFlow<MacIdentityState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val resolved = withContext(dispatchers.io) {
                val record = identity.current()
                val code = runCatching { qrBitmap(record.macAddress.value, QR_PIXELS) }.getOrNull()
                MacIdentityState(record.macAddress.value, record.provenance, code)
            }
            _state.value = resolved
        }
    }

    private companion object {
        /**
         * Generous enough that a phone camera reads it off a television across a room,
         * which is the whole point of putting it on the screen.
         */
        const val QR_PIXELS = 512
    }
}
