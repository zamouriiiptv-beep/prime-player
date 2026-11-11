package com.castivio.domain.portal

import com.castivio.core.common.Outcome

/**
 * What the owner sent to this device from the website, and the three things the device
 * does about it.
 *
 * The data flows one way, and always on the device's own initiative. The owner types a
 * subscription on the website and it waits there; the device asks for what is waiting
 * when its Refresh button is pressed, adds it, and says which it took. Nothing is ever
 * pushed to a device, which is why there is no background work behind this and why a
 * device that is switched off loses nothing.
 *
 * Every call proves the device with its address and its key. The address is not a secret
 * and the key is only six digits, so the portal limits wrong attempts per address; a
 * caller that is refused is told so as [com.castivio.core.common.AppError.UNAUTHORIZED]
 * and must not retry in a loop.
 *
 * An interface, because the transport is an implementation: OkHttp today, whatever the
 * next platform has tomorrow.
 */
interface SubscriptionInbox {

    /**
     * Tells the portal this device exists, and which key it shows.
     *
     * Only the device can do this, and it is what lets the website recognise the address
     * and key a visitor types: a device the portal has never heard from is unknown to it,
     * whatever the website is told. Idempotent, so it is safe to call on every launch of
     * the screen that shows the key. The first announcement of an address fixes its key;
     * announcing another key for it is refused as
     * [com.castivio.core.common.AppError.UNAUTHORIZED].
     */
    suspend fun announce(): Outcome<Unit>

    /**
     * What was sent and not yet taken, oldest first, so that the last of them is the one
     * the app ends up showing.
     *
     * Empty is not a failure: it is the ordinary answer of a device nothing was sent to.
     * Something the app cannot read — a kind of subscription it has no form for — is left
     * out, and stays waiting on the website.
     */
    suspend fun waiting(): Outcome<List<SentSubscription>>

    /**
     * Says that these were added, so the website can say they arrived and the portal
     * does not hand them over again.
     *
     * What is acknowledged is the exact version that was taken. One the owner has edited
     * in the meantime stays waiting.
     */
    suspend fun acknowledge(taken: List<SentSubscription>): Outcome<Unit>
}

/** A subscription the owner sent to this device. */
data class SentSubscription(
    /** The portal's own, with [version] what the device acknowledges. Not shown to anyone. */
    val id: String,
    val version: String,
    /** What the owner called it on the website. Blank when they did not. */
    val name: String,
    val source: SentSource,
)

/**
 * What a sent subscription is made of, as typed on the website and not yet checked.
 *
 * Unchecked on purpose: turning these into a [com.castivio.domain.PlaylistSource] is the
 * form's job — it knows what a server address is allowed to look like, and the person who
 * typed this on a website is held to exactly the rule a person typing on the television is.
 */
sealed interface SentSource {

    data class Xtream(val server: String, val username: String, val password: String) : SentSource

    data class Playlist(val url: String) : SentSource
}
