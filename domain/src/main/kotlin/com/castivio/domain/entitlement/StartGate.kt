package com.castivio.domain.entitlement

import com.castivio.domain.ProviderSource
import com.castivio.domain.RefreshPolicy

/**
 * Where the app starts, decided by two independent gates in a fixed order.
 *
 * ```
 * App entitlement ──(not allowed)──→ Licence
 *        │ allowed
 *        ▼
 * Provider catalogue ──(none usable)──→ Activation
 *        │ usable
 *        ▼
 *      Home
 * ```
 *
 * Deliberately expressed as a domain decision rather than a navigation one: it is a
 * statement about what the user is entitled to and what they have, and `:app` maps the
 * answer onto a `Route`. That keeps `:core:navigation` free of business types, exactly
 * as it is free of media kinds today.
 *
 * The whole contract in `UI_ARCHITECTURE.md` reduces to the nine lines of
 * [startDestination]. It reads no clock, opens no database and touches no network, so
 * every branch of it — including the awkward crossings, like a lapsed provider under a
 * valid licence — is answerable by a unit test in microseconds.
 */
sealed interface StartDestination {

    /** The app may not be used yet. [reason] chooses the wording, never the routing. */
    data class Licence(val reason: LicenceReason) : StartDestination

    /** Entitled, but there is nothing to watch yet. */
    data object Activation : StartDestination

    /** Entitled, with a complete catalogue already on the device. */
    data object Home : StartDestination
}

/**
 * Why the licence screen is being shown.
 *
 * Separate from [EntitlementState] because the screen needs one of four sentences,
 * not one of six states, and because a new plan should not force the screen to change.
 */
enum class LicenceReason {
    /** No entitlement has ever been established on this device. */
    NOT_ESTABLISHED,

    TRIAL_EXPIRED,

    SUBSCRIPTION_EXPIRED,

    /** A cached entitlement outlived its offline grace. Not an accusation. */
    VERIFICATION_REQUIRED,
}

/**
 * @param entitlement the app licence, from [EntitlementPolicy.evaluate].
 * @param source the active provider, or null before one is configured.
 */
fun startDestination(
    entitlement: EntitlementState,
    source: ProviderSource?,
): StartDestination = when {
    // Gate 1. The licence decides whether the app may be used at all.
    !entitlement.allowsUse -> StartDestination.Licence(licenceReason(entitlement))

    // Gate 2. A usable catalogue decides whether there is anything to show.
    //
    // Note what is *not* consulted here: whether the provider is reachable, whether
    // its subscription has lapsed, or when it was last refreshed. An expired provider
    // does not make a committed catalogue disappear, so it does not change where the
    // app starts — it changes what Home says. Keeping the entry rule free of those
    // exceptions is what keeps it a rule.
    source == null || RefreshPolicy.needsFirstImport(source) -> StartDestination.Activation

    else -> StartDestination.Home
}

/**
 * The sentence the licence screen should lead with.
 *
 * Total over the denying states; the permissive ones cannot reach here because
 * [startDestination] has already sent them past gate one. [EntitlementState.Unknown]
 * is the honest default for a state that somehow denies use without saying why.
 */
fun licenceReason(state: EntitlementState): LicenceReason = when (state) {
    is EntitlementState.TrialExpired -> LicenceReason.TRIAL_EXPIRED
    is EntitlementState.AnnualExpired -> LicenceReason.SUBSCRIPTION_EXPIRED
    is EntitlementState.VerificationUnavailable -> LicenceReason.VERIFICATION_REQUIRED
    is EntitlementState.Unknown -> LicenceReason.NOT_ESTABLISHED
    is EntitlementState.TrialActive,
    is EntitlementState.AnnualActive,
    is EntitlementState.Lifetime,
    -> LicenceReason.NOT_ESTABLISHED
}
