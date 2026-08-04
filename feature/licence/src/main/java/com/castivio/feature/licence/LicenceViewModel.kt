package com.castivio.feature.licence

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.core.common.AppDispatchers
import com.castivio.core.common.Outcome
import com.castivio.core.common.config.ActivationDestination
import com.castivio.domain.entitlement.EntitlementRepository
import com.castivio.domain.entitlement.EntitlementState
import com.castivio.domain.entitlement.PlanOffer
import com.castivio.domain.entitlement.PricingDefaults
import com.castivio.domain.identity.DeviceIdentity
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
 * Everything the licence screen renders, as one value.
 *
 * `licence` is null only while the sealed record is being read — that is the
 * Loading state, and it is a fact about this screen rather than about the
 * entitlement, which is why it is a nullable field here and not a tenth case in
 * `EntitlementState`.
 */
internal data class LicenceUiState(
    val licence: EntitlementState? = null,
    val address: String? = null,
    val deviceKey: String? = null,
    val qr: android.graphics.Bitmap? = null,

    /** The plans on offer, from `PricingConfig` and never from a literal. */
    val plans: List<PlanOffer> = emptyList(),

    /** The plan whose portal is being opened, if any. Drives the Working state. */
    val opening: String? = null,

    /** A handoff or a refresh that did not work. Cleared by the next attempt. */
    val failed: Boolean = false,

    val addressCopied: Boolean = false,
    val keyCopied: Boolean = false,
    val lastCopied: Copied = Copied.None,
)

/** Which identifier the status line is currently describing. */
internal enum class Copied { None, Address, Key }

/**
 * The licence screen's state holder.
 *
 * ## What it does not do
 *
 * It does not decide whether the device may be used, it does not count days, it
 * does not know what a trial costs and it never writes an entitlement. All four
 * belong to `EntitlementRepository` and the policy behind it, which holds the
 * sealed record and the high-water clock mark that stops a rolled-back device
 * buying a second free week. This subscribes and renders.
 *
 * It does not take money either. Castivio is portal-first on every platform: the
 * app presents the plans and hands the user to the portal, which owns
 * authentication, payment, licence creation and MAC binding. `redeem()` stays the
 * single integration point for whatever proof comes back, so adding Play Billing
 * later changes nothing above this line.
 */
@HiltViewModel
internal class LicenceViewModel @Inject constructor(
    private val entitlement: EntitlementRepository,
    private val identity: DeviceIdentity,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    private val _state = MutableStateFlow(
        // The plans are known before anything is read: they are configuration,
        // not state. `purchasable` filters out the trial and anything a region
        // does not sell, so the screen renders what the list says and never a
        // hardcoded pair.
        LicenceUiState(plans = PricingDefaults.config.purchasable),
    )
    val state: StateFlow<LicenceUiState> = _state.asStateFlow()

    init {
        // Collected rather than read once: the repository re-evaluates against
        // the clock, so a device left on this screen past midnight sees the trial
        // count fall instead of showing yesterday's number.
        viewModelScope.launch {
            entitlement.state.collect { licence ->
                _state.update { it.copy(licence = licence) }
            }
        }

        viewModelScope.launch {
            val resolved = withContext(dispatchers.io) {
                val record = identity.current()
                // The QR carries the bare portal address and nothing else -- no
                // MAC, no device key. See LicenceQrTest, which decodes it.
                record.macAddress.value to runCatching { licenceQrBitmap(QR_PIXELS) }.getOrNull()
            }
            _state.update {
                it.copy(
                    address = resolved.first,
                    qr = resolved.second,
                    deviceKey = DebugFixtures.deviceKey(),
                )
            }
        }
    }

    /**
     * The address to open for a plan, and the state change that goes with it.
     *
     * The screen performs the navigation — an `Intent` is `:app`'s and the
     * platform's business, not a view model's — so this returns the link and
     * records that a handoff is in flight.
     */
    fun portalFor(plan: PlanOffer): String {
        val id = plan.plan.name.lowercase()
        _state.update { it.copy(opening = id, failed = false) }

        // The portal is a browser away, and a user who never completes the
        // purchase must not be left staring at a spinner. The Working state
        // clears itself; returning to the screen re-reads the entitlement.
        openTimer?.cancel()
        openTimer = viewModelScope.launch {
            delay(HANDOFF_MS)
            _state.update { if (it.opening == id) it.copy(opening = null) else it }
        }
        return ActivationDestination.portalUrl(plan = id, macAddress = _state.value.address)
    }

    /** No browser, no portal, nothing happened. Say so rather than spinning. */
    fun handoffFailed() {
        openTimer?.cancel()
        _state.update { it.copy(opening = null, failed = true) }
    }

    /** Ask the licence server again. The only action the blocked states offer. */
    fun refresh() {
        if (_state.value.opening != null) return
        viewModelScope.launch {
            _state.update { it.copy(opening = REFRESHING, failed = false) }
            val outcome = entitlement.refresh()
            _state.update {
                it.copy(opening = null, failed = outcome.isFailure())
            }
        }
    }

    /**
     * Confirm a copy, and take the confirmation back a moment later.
     *
     * One timer per identifier, cancelled individually, because the two
     * confirmations are independent: copying the address must not cut short the
     * tick on the key.
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

    private var openTimer: Job? = null
    private val copyTimers = mutableMapOf<Copied, Job>()

    private companion object {
        const val QR_PIXELS = 512
        const val COPY_FEEDBACK_MS = 1_500L

        /**
         * How long the handoff spinner lives before the screen gives the user
         * their controls back.
         *
         * Not a guess about how long paying takes -- the app is backgrounded for
         * that and re-reads the entitlement on return. This is how long the
         * *launch* is allowed to look like it is happening.
         */
        const val HANDOFF_MS = 4_000L

        /** A sentinel for the busy state that is not a plan. */
        const val REFRESHING = "refresh"
    }
}

/** `Outcome` has no `isFailure`; this keeps the call site readable. */
private fun <T> Outcome<T>.isFailure(): Boolean = this !is Outcome.Success
