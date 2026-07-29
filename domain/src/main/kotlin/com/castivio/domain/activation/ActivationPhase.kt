package com.castivio.domain.activation

import com.castivio.core.common.AppError
import com.castivio.domain.ProviderStatus

/**
 * How far an activation attempt has got.
 *
 * Deliberately not `ScreenState<T>`: activation is not a load, it is a sequence with a
 * running count in the middle, and flattening it to loading/error/content would throw
 * away the only number the user is actually watching.
 */
sealed interface ActivationPhase {

    /** Nothing is running. The user is typing, or has just dismissed a failure. */
    data object Editing : ActivationPhase

    /**
     * Asking the provider whether these details work, before importing anything.
     *
     * Its own phase because it is the step that turns "no channels" into one of four
     * answers a user can act on — wrong password, expired subscription, every
     * connection in use, unreachable host.
     */
    data object Checking : ActivationPhase

    /**
     * @param itemsFound what to put on screen. **"Items", not "channels"** — an M3U
     *   cannot always be classified while it is streaming, and a count that promises a
     *   classification the data layer has not made yet is a count that jumps.
     *   It never decreases; see [ActivateProvider].
     * @param checkingForChanges true for the brief moment before the first byte, when
     *   the provider is being asked whether anything changed at all.
     */
    data class Importing(
        val itemsFound: Int,
        val groupsReady: Int,
        val checkingForChanges: Boolean = false,
    ) : ActivationPhase

    /**
     * A catalogue is on the device and this provider is now the active one.
     *
     * @param status what the provider said about the subscription, verbatim — the
     *   expiry among it. Carried rather than interpreted: turning it into a banner
     *   needs the trusted clock, and this use case reads no clock of its own beyond
     *   the instant it was given.
     */
    data class Succeeded(
        val sourceId: String,
        val itemCount: Int,
        val status: ProviderStatus,
    ) : ActivationPhase {
        /** Non-null when the provider states one. What the Home banner counts down. */
        val subscriptionExpiresAtMs: Long? get() = status.expiresAtMs
    }

    /**
     * @param itemsFound how far it got before failing. Worth showing: "stopped after
     *   12,000 items" and "could not start" are different problems.
     */
    data class Failed(
        val reason: ActivationFailure,
        val itemsFound: Int = 0,
    ) : ActivationPhase {
        val retryable: Boolean get() = reason.retryable
    }
}

/**
 * Why an activation did not finish.
 *
 * A small closed set, because each entry is a sentence the activation screen has to
 * write and a translation in every language. They are grouped by what the user can do,
 * which is the only thing the distinction is for.
 */
enum class ActivationFailure {

    /** The host could not be reached at all. Try again later. */
    UNREACHABLE,

    /** It answered too slowly. Same shape as [UNREACHABLE], different sentence. */
    TIMED_OUT,

    /** The provider answered and refused the credentials. Edit them. */
    REJECTED,

    /** The provider says the subscription has ended. Renew it with them, not with us. */
    SUBSCRIPTION_ENDED,

    /** Answered, refused, and gave no date — a banned or disabled line. */
    PROVIDER_REFUSED,

    /** Nothing at that address. Almost always a typo in a URL. */
    NOT_FOUND,

    /** It answered with something that is not a playlist. */
    UNREADABLE,

    /** The provider's own server had a problem. Try again later. */
    PROVIDER_ERROR,

    /**
     * The import finished and committed nothing.
     *
     * Treated as a failure rather than a success, because landing on an empty app
     * reads as broken. It is the one case where "the import worked" and "the user has
     * something" disagree.
     */
    EMPTY,

    /** This build cannot activate that kind of source. */
    UNSUPPORTED,

    UNKNOWN,
    ;

    /**
     * Whether pressing the same button again could plausibly work.
     *
     * Only the transient ones. Offering a retry for a rejected password is offering
     * the user a way to waste their own time, and it teaches them that the button
     * means nothing.
     */
    val retryable: Boolean
        get() = when (this) {
            UNREACHABLE, TIMED_OUT, PROVIDER_ERROR, UNKNOWN -> true
            REJECTED, SUBSCRIPTION_ENDED, PROVIDER_REFUSED, NOT_FOUND, UNREADABLE, EMPTY, UNSUPPORTED -> false
        }

    companion object {
        fun of(error: AppError): ActivationFailure = when (error) {
            AppError.NETWORK_UNAVAILABLE -> UNREACHABLE
            AppError.TIMEOUT -> TIMED_OUT
            AppError.UNAUTHORIZED -> REJECTED
            AppError.NOT_FOUND -> NOT_FOUND
            AppError.MALFORMED_PLAYLIST -> UNREADABLE
            AppError.SERVER_ERROR -> PROVIDER_ERROR
            AppError.NOT_CONFIGURED -> UNSUPPORTED
            AppError.UNKNOWN -> UNKNOWN
        }
    }
}

/**
 * Everything the activation screen renders, as one value.
 *
 * Pure, and living in `:domain` for that reason: the whole flow — typing, validating,
 * importing, failing, retrying — is then a JVM test rather than something that needs a
 * device to observe. The view model that owns it adds a coroutine scope and nothing
 * else.
 */
data class ActivationUiState(
    val form: ActivationForm = ActivationForm.Xtream(),
    val phase: ActivationPhase = ActivationPhase.Editing,
) {
    /** True while something is running, which is when every field should be read-only. */
    val busy: Boolean
        get() = phase is ActivationPhase.Checking || phase is ActivationPhase.Importing

    /** The submit button is live only when the form is complete and nothing is running. */
    val canSubmit: Boolean get() = form.canSubmit && !busy

    /** Cancelling is only offered once there is something to cancel. */
    val canCancel: Boolean get() = busy
}
