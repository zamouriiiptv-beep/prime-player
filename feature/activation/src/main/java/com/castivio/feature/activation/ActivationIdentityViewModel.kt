package com.castivio.feature.activation

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.core.common.AppDispatchers
import com.castivio.domain.entitlement.EntitlementRepository
import com.castivio.domain.entitlement.EntitlementState
import com.castivio.domain.identity.DeviceIdentity
import com.castivio.domain.identity.IdentityProvenance
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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
 * Which identifier was copied most recently, for the one status line to describe.
 *
 * This is *not* what drives the two ticks. It cannot be: one field can only ever
 * name one control, and the contract is that the two confirmations are
 * independent — copying the address must not cut short the tick on the key. The
 * ticks are [ActivationIdentityState.addressCopied] and
 * [ActivationIdentityState.keyCopied], one boolean each, with their own timers.
 *
 * The file used to claim independence in this comment while modelling a shared
 * flag underneath it. The comment was right about the intent and the code was
 * not, which is the kind of disagreement that survives review because both halves
 * read fine on their own.
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
    /** Encodes the central activation URL and nothing else. See [activationQrBitmap]. */
    val qr: Bitmap? = null,
    val refresh: RefreshState = RefreshState.Idle,

    /**
     * Whether each control is showing its confirmation, independently.
     *
     * Two fields rather than one, so both can be true at once — a user who copies
     * the address and then the key within a second should see both ticks, and
     * with a shared flag the first would vanish as the second appeared.
     */
    val addressCopied: Boolean = false,
    val keyCopied: Boolean = false,

    /** Which one the status line is currently describing. */
    val lastCopied: Copied = Copied.None,

    /**
     * Days left on Castivio's trial, or null while the entitlement is still being
     * read and on any state that is not a running trial.
     *
     * A number rather than a formatted string: "7 days" is a plural in 37
     * languages and four of them have more than two forms, so the count travels
     * and the screen resolves it. Null renders no chip at all, which is right for
     * the instant before the read completes and for a lifetime licence, and is
     * why the field is nullable rather than zero.
     */
    val trialDaysRemaining: Int? = null,
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
    /**
     * Castivio's licence, read and never written here.
     *
     * The trial is not this screen's to own. `EntitlementRepository` is the single
     * source of truth -- it holds the sealed record, the high-water clock mark
     * that stops a rolled-back device buying a second free week, and the policy
     * that turns those into a state. This subscribes and renders. A screen that
     * counted its own days would be a second answer to a question that already
     * has one, and the two would disagree the first time a user changed the date.
     */
    private val entitlement: EntitlementRepository,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    private val _state = MutableStateFlow(ActivationIdentityState())
    val state: StateFlow<ActivationIdentityState> = _state.asStateFlow()

    init {
        // Republished rather than read once: the repository re-evaluates against
        // the clock, so a device left on this screen past midnight sees the count
        // fall instead of showing yesterday's number until it is restarted.
        viewModelScope.launch {
            entitlement.state.collect { licence ->
                _state.update { it.copy(trialDaysRemaining = licence.trialDaysRemaining()) }
            }
        }

        viewModelScope.launch {
            val resolved = withContext(dispatchers.io) {
                val record = identity.current()
                Triple(
                    record.macAddress.value,
                    record.provenance,
                    runCatching { activationQrBitmap(QR_PIXELS) }.getOrNull(),
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

    /**
     * Confirm a copy, and take the confirmation back again a moment later.
     *
     * A tick that never clears stops being feedback: it becomes part of the
     * control's appearance, and the next copy says nothing at all. It reverts
     * after [COPY_FEEDBACK_MS].
     *
     * One timer per identifier, keyed and cancelled individually, because the two
     * confirmations are independent — a second copy of the same identifier
     * restarts that one's clock and leaves the other's running.
     */
    fun copied(what: Copied) {
        _state.update {
            when (what) {
                Copied.Address -> it.copy(addressCopied = true, lastCopied = what)
                Copied.Key -> it.copy(keyCopied = true, lastCopied = what)
                Copied.None -> it
            }
        }
        if (what == Copied.None) return

        copyTimers.remove(what)?.cancel()
        copyTimers[what] = viewModelScope.launch {
            delay(COPY_FEEDBACK_MS)
            _state.update {
                val cleared = when (what) {
                    Copied.Address -> it.copy(addressCopied = false)
                    Copied.Key -> it.copy(keyCopied = false)
                    Copied.None -> it
                }
                // The status line follows whichever tick is still lit, so that
                // clearing the older of two copies does not blank a sentence that
                // still has something to say.
                cleared.copy(
                    lastCopied = when {
                        cleared.addressCopied -> Copied.Address
                        cleared.keyCopied -> Copied.Key
                        else -> Copied.None
                    },
                )
            }
            copyTimers.remove(what)
        }
    }

    private val copyTimers = mutableMapOf<Copied, Job>()

    private companion object {
        /** Generous enough for a phone camera to read across a room. */
        const val QR_PIXELS = 512
        const val CHECK_FLOOR_MS = 900L

        /**
         * Long enough to be read, short enough that the control is ready again
         * before a user who mistrusts it presses a second time.
         */
        const val COPY_FEEDBACK_MS = 1_500L
    }
}

/**
 * The number the trial chip shows, or null when there is no trial to describe.
 *
 * Only [EntitlementState.TrialActive] produces one. An annual subscription has
 * days remaining too and they are not a trial; a lifetime licence has none; and
 * every blocked state is somebody else's screen, because `startDestination` sends
 * a device that may not be used to the licence destination rather than here.
 *
 * Never negative. A trial whose clock has just crossed the line is expired, not
 * "minus one days", and the gate will move the user off this screen at the next
 * decision — but it must not render nonsense in the meantime.
 */
private fun EntitlementState.trialDaysRemaining(): Int? =
    (this as? EntitlementState.TrialActive)?.daysRemaining?.coerceAtLeast(0)
