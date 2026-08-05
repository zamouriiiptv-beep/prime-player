package com.castivio.feature.licence

import com.castivio.domain.entitlement.EntitlementState
import com.castivio.domain.entitlement.ServiceFault

/**
 * What the licence screen shows, derived from the entitlement and nothing else.
 *
 * ## Why this is a function and not a state holder
 *
 * `EntitlementState` is the state model. This screen renders it; it does not keep
 * a second copy, does not cache a flattened version of it, and does not decide
 * anything the domain has already decided. Everything below is a total function
 * of one sealed value, which is why it is testable in microseconds and why
 * `LicenceViewTest` can prove every case is covered by asking the compiler.
 *
 * The nine entitlement cases collapse to fewer *screens* than states, but never
 * by conflating two that mean different things — §2 of `design/licence-spec.md`
 * is the table this implements, and the three copy rules in §8.1 are the reason
 * some of these branches look redundant. They are not:
 *
 *  - [EntitlementState.VerificationUnavailable] must never say "expired". It is
 *    a device that could not reach us for a fortnight, not a lapsed licence, and
 *    telling a paying customer on holiday that they have none is a support call.
 *  - [ServiceFault.STORAGE_UNREADABLE] must never say "no licence". That device
 *    *had* one and lost the key to it.
 *  - [EntitlementState.Unknown] is the only case that may say "no licence yet".
 */
internal data class LicenceView(
    /** The header chip's sentence. */
    val chip: Int,
    val tone: LicenceTone,
    /**
     * The reserved status line, or null when the state has nothing to add.
     *
     * Null for a running trial, whose line is a plural with a day count in it
     * rather than a fixed sentence — see [countsDays].
     */
    val status: Int?,
    /**
     * Whether the plans are offered.
     *
     * False wherever a price is the wrong thing to show, which is two different
     * situations rather than one:
     *
     *  - **A purchase would not fix it.** Withdrawn, unreadable, or a licence
     *    server we cannot reach. Selling a licence for a problem money does not
     *    solve is taking money for nothing.
     *  - **There is nothing to sell.** A lifetime licence, an active annual one,
     *    or a device whose licence merely has not been confirmed. Showing a
     *    price to somebody who has already paid reads as a bill.
     */
    val offersPlans: Boolean,
    /** The one action offered when the plans are not. */
    val action: LicenceAction? = null,
    /**
     * Whether the status line is the trial countdown.
     *
     * Only a running trial counts down. An annual subscription has days
     * remaining too and they are not a trial — a licence somebody paid for
     * should not display a countdown to its own expiry every time it is opened.
     *
     * It used to be the chip that carried the number, which read as
     * "Trial · 3 days left" across two elements neither of which was a sentence.
     * The chip now names the condition and the line says what it means, which is
     * what each of them is for.
     */
    val countsDays: Boolean = false,
)

/**
 * The four registers, and they mean the same things they mean on the frozen
 * sibling screen — a colour that meant one thing on one screen and another on
 * the next would mean nothing on either.
 */
internal enum class LicenceTone {
    /** Nothing to report. */
    Neutral,

    /** A licence is active. */
    Good,

    /**
     * Something is missing or unconfirmed and the user can act on it.
     *
     * Expiry lives here, not in [Bad]. A trial that ended is not a fault —
     * nothing broke, there is simply a decision to make — and painting it red
     * tells somebody their working app is broken.
     */
    Warning,

    /** A genuine fault: withdrawn, or a record that will not open. */
    Bad,
}

internal enum class LicenceAction {
    /** The licence server could not be reached. Ask again. */
    Retry,

    /** Nothing the app can do. The user needs a person. */
    Support,
}

/**
 * The whole mapping, exhaustively.
 *
 * `when` over a sealed interface with no `else`: adding a tenth entitlement case
 * makes this stop compiling, which is the point. A screen that silently rendered
 * a new state as "unknown" is how a user ends up looking at a sentence nobody
 * wrote for them.
 */
internal fun licenceView(state: EntitlementState): LicenceView = when (state) {

    is EntitlementState.TrialActive -> LicenceView(
        chip = R.string.licence_chip_trial,
        tone = LicenceTone.Neutral,
        // The line is the plural, composed with the day count.
        status = null,
        offersPlans = true,
        countsDays = true,
    )

    is EntitlementState.AnnualActive -> LicenceView(
        chip = R.string.licence_chip_active,
        tone = LicenceTone.Good,
        status = R.string.licence_status_active,
        // Nothing offered. This was the one state that still showed prices to a
        // paying customer, on the argument that an annual holder might move to
        // lifetime — which is a real thing they might want and the wrong place
        // to ask for it. A screen whose job is "are you licensed" answering
        // "yes, and here is what else you could buy" reads as a bill, and a user
        // who has just paid reads it as one that did not go through.
        //
        // Upgrading is the portal's, reached from Settings, where a price is
        // something the user went looking for.
        offersPlans = false,
    )

    EntitlementState.Lifetime -> LicenceView(
        chip = R.string.licence_chip_active,
        tone = LicenceTone.Good,
        status = R.string.licence_status_lifetime,
        // Nothing left to sell. A lifetime licence is the end of this screen's
        // job, and a plan card here would be asking for money twice.
        offersPlans = false,
    )

    EntitlementState.TrialExpired -> LicenceView(
        chip = R.string.licence_chip_trial_ended,
        tone = LicenceTone.Warning,
        status = R.string.licence_status_trial_ended,
        offersPlans = true,
    )

    EntitlementState.AnnualExpired -> LicenceView(
        chip = R.string.licence_chip_expired,
        tone = LicenceTone.Warning,
        status = R.string.licence_status_expired,
        offersPlans = true,
    )

    EntitlementState.Unknown -> LicenceView(
        chip = R.string.licence_chip_none,
        tone = LicenceTone.Warning,
        status = R.string.licence_status_none,
        offersPlans = true,
    )

    // "We could not check", never "it expired". Reached only after the offline
    // grace in PricingConfig has run out, so the device has been away a fortnight
    // -- a holiday, a broken router, an outage of ours.
    //
    // Support, not the plans and not Retry. The plans were on offer here on the
    // argument that buying is one of the ways back; they are not, because this
    // device may well hold a valid licence that simply has not been confirmed,
    // and selling a second one to somebody who already paid is the worst outcome
    // this screen can produce. Retry is not it either: the device has been
    // failing to verify for a fortnight, so another attempt is a button that has
    // already been pressed by the clock.
    is EntitlementState.VerificationUnavailable -> LicenceView(
        chip = R.string.licence_chip_verify,
        tone = LicenceTone.Warning,
        status = R.string.licence_status_verify,
        offersPlans = false,
        action = LicenceAction.Support,
    )

    is EntitlementState.ServiceUnavailable -> when (state.fault) {
        // No licence server bound to this build. Ours to fix, and the sentence
        // says so rather than implying the user did something.
        ServiceFault.NOT_CONFIGURED -> LicenceView(
            chip = R.string.licence_chip_unavailable,
            tone = LicenceTone.Warning,
            status = R.string.licence_status_unavailable,
            offersPlans = false,
            action = LicenceAction.Retry,
        )
        // Something is stored and will not open: an edited blob, or a keystore
        // reset. This device *had* a licence. Offering to sell it another one is
        // the wrong answer and so is telling it that it never had one.
        ServiceFault.STORAGE_UNREADABLE -> LicenceView(
            chip = R.string.licence_chip_unreadable,
            tone = LicenceTone.Bad,
            status = R.string.licence_status_unreadable,
            offersPlans = false,
            action = LicenceAction.Support,
        )
    }

    is EntitlementState.Revoked -> LicenceView(
        chip = R.string.licence_chip_revoked,
        tone = LicenceTone.Bad,
        status = R.string.licence_status_revoked,
        offersPlans = false,
        action = LicenceAction.Support,
    )
}
