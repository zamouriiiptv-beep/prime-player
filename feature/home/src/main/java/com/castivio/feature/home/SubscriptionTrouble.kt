package com.castivio.feature.home

import com.castivio.core.common.AppError
import com.castivio.domain.Recorded

/**
 * What is wrong with the subscription, said on Home's last line while it lasts.
 *
 * Four causes, because they need four different things done: a server that does not
 * answer is the provider's to explain, a device with no connection is the viewer's to
 * fix, a subscription that ran out is renewed, and one the provider refused is taken up
 * with the provider. Each section's card already says that it could not load; none of
 * them can say why, and "why" is what tells a viewer whom to call.
 *
 * The header's subscription card keeps its date through all of this. A viewer whose
 * subscription runs until next year and whose channels will not open needs both facts at
 * once: that it is paid for, and that the trouble is the provider's.
 */
enum class SubscriptionTrouble {

    /** The provider's server did not answer, and this device is on a network. */
    Unreachable,

    /** The provider could not be asked because this device is on no network. */
    Offline,

    /** The provider answered that the subscription has run out. */
    Expired,

    /** The provider answered, and refused: banned, disabled, or credentials it rejects. */
    Refused,
}

/** The last time Home asked the provider about the account, and how it went. */
internal sealed interface Contact {

    /** The provider answered. What it said is the recorded answer. */
    data object Answered : Contact

    /**
     * The provider could not be asked, or refused to be.
     *
     * @param online whether this device was on a network when it failed, read at that
     *   moment: the network can come back before anybody looks at the screen, and the
     *   question is why *that* attempt failed.
     */
    data class Failed(val error: AppError, val online: Boolean) : Contact
}

/**
 * The trouble to say, if there is one.
 *
 * A failed contact speaks first: it is the newest fact, and the answer recorded before it
 * says nothing about whether the server answers now. A refusal of the credentials is the
 * provider saying no, not the provider being away. Otherwise the recorded answer decides:
 * an account the provider said is unusable is expired or refused, as the header's card
 * says beside it.
 *
 * @param contact null when Home has not asked in this visit -- or there is nothing to ask,
 *   a playlist with no account behind it, which has no server to be in trouble with.
 */
internal fun subscriptionTrouble(contact: Contact?, recorded: Recorded?): SubscriptionTrouble? = when {
    contact is Contact.Failed && contact.error == AppError.UNAUTHORIZED -> SubscriptionTrouble.Refused
    contact is Contact.Failed && !contact.online -> SubscriptionTrouble.Offline
    contact is Contact.Failed -> SubscriptionTrouble.Unreachable
    recorded == null || recorded.usable -> null
    recorded.ranOut -> SubscriptionTrouble.Expired
    else -> SubscriptionTrouble.Refused
}

/** The sentence each trouble is said in: what happened, and what to do about it. */
internal fun troubleSentence(trouble: SubscriptionTrouble): Int = when (trouble) {
    SubscriptionTrouble.Unreachable -> R.string.home_trouble_unreachable
    SubscriptionTrouble.Offline -> R.string.home_trouble_offline
    SubscriptionTrouble.Expired -> R.string.home_trouble_expired
    SubscriptionTrouble.Refused -> R.string.home_trouble_refused
}

/**
 * Whether the answer was that the subscription ran out: its date had passed when the
 * provider was asked, or the panel said so in its own word.
 *
 * "Passed" is the validator's own rule, a date strictly before the moment of asking,
 * so a panel that still says "Active" over a date gone by is called what the validator
 * called it: expired, which is why it was refused.
 */
internal val Recorded.ranOut: Boolean
    get() {
        // Bound to locals first: these are properties of a class in another module, so
        // the compiler will not carry a null check across the dot however plainly it reads.
        val at = expiresAtMs
        val word = label
        return (at != null && at < atMs) || word.equals(PANEL_EXPIRED, ignoreCase = true)
    }

/** The word an Xtream panel's `user_info.status` uses for a subscription that ran out. */
private const val PANEL_EXPIRED = "Expired"
