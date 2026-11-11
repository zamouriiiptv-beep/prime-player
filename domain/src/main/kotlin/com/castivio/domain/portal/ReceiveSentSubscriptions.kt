package com.castivio.domain.portal

import com.castivio.core.common.AppError
import com.castivio.core.common.Outcome
import com.castivio.domain.PlaylistSource
import com.castivio.domain.activation.ActivateProvider
import com.castivio.domain.activation.ActivationPhase
import com.castivio.domain.provider.M3uFormCheck
import com.castivio.domain.provider.XtreamFormCheck
import com.castivio.domain.time.TrustedTime

/**
 * What the Refresh button asks for, as the screen sees it: two calls and a closed set of answers.
 *
 * An interface so that the view model that owns the button is proved without a portal and
 * without the whole sequence that adds a subscription — that sequence has its own tests, in
 * `ReceiveSentSubscriptionsTest` — and so the screen cannot reach past it for a detail it has
 * no business knowing.
 */
interface SentSubscriptionReceiver {

    /**
     * Tells the portal this device and its key exist, so the website recognises them.
     *
     * For the screen that shows the key to call as it opens: the owner reads the address
     * and the key off the television and types them on the website, which must already
     * know them by then. Best effort — a device with no connection simply is not known
     * yet, and [receive] announces again before it asks.
     */
    suspend fun announce()

    /** One press of Refresh: asks what was sent, adds it, and says how it ended. */
    suspend fun receive(): Reception
}

/**
 * What the Refresh button does: asks the portal what the owner sent from the website and
 * adds it, as if it had been typed on this device.
 *
 * ```
 * announce ──► waiting ──► add each, oldest first ──► acknowledge what was added
 * ```
 *
 * ## Added exactly as typed subscriptions are
 *
 * Each one goes through [ActivateProvider], the same sequence the add screens run, and
 * not around it. An Xtream line is asked whether its password is right and whether the
 * subscription has run out before anything is saved; a playlist is adopted and read where
 * the user is looking. The last one added becomes the subscription the app shows, which
 * is why the portal hands them over oldest first. Taking a shortcut here — saving the
 * credentials without asking — would let the website put a line on the device that the
 * device itself would have refused, and the screen would say it had worked.
 *
 * ## What is acknowledged
 *
 * Only what was added. A subscription that did not pass — a wrong password typed on the
 * website, a line that has expired — is left unacknowledged, so it stays "waiting" on the
 * website where its owner can see it and fix it, and is tried again on the next press.
 * An acknowledgement that does not get through changes nothing the user can see: the
 * subscription is already on the device, and the next press finds it waiting again and
 * adds it again. That does not duplicate it, because a subscription's identity is what it
 * *is* — the one cost is that it becomes the subscription shown again, which is what
 * pressing Refresh to get it meant in the first place.
 *
 * Pure: it takes its contracts and the clock, opens nothing, and touches no Android. The
 * whole sequence, including the awkward halves, is a unit test.
 */
class ReceiveSentSubscriptions(
    private val inbox: SubscriptionInbox,
    private val activate: ActivateProvider,
    private val clock: TrustedTime,
) : SentSubscriptionReceiver {

    override suspend fun announce() {
        inbox.announce()
    }

    override suspend fun receive(): Reception {
        // Announced again, every time: it is one small request, and it is the difference
        // between "nothing was sent" and "the portal has never heard of this device".
        when (val announced = inbox.announce()) {
            is Outcome.Failure -> return failed(announced.error)
            is Outcome.Success -> Unit
        }

        val waiting = when (val asked = inbox.waiting()) {
            is Outcome.Failure -> return failed(asked.error)
            is Outcome.Success -> asked.value
        }
        if (waiting.isEmpty()) return Reception.NothingSent

        val added = ArrayList<SentSubscription>(waiting.size)
        for (sent in waiting) {
            if (add(sent)) added += sent
        }
        if (added.isEmpty()) return Reception.Rejected(waiting.size)

        // Best effort, for the reason in the class comment.
        inbox.acknowledge(added)

        return Reception.Added(added = added.size, rejected = waiting.size - added.size)
    }

    /** Whether the subscription is now on the device. */
    private suspend fun add(sent: SentSubscription): Boolean {
        val (source, label) = sent.toSource() ?: return false

        var succeeded = false
        activate.activate(source, label, clock.nowMs(), fetchCatalogue = false).collect { phase ->
            if (phase is ActivationPhase.Succeeded) succeeded = true
        }
        return succeeded
    }

    private fun failed(error: AppError): Reception = when (error) {
        // The portal knows this address under another key. Retrying cannot fix it, and
        // retrying in a loop is exactly what the portal's limit on wrong keys punishes.
        AppError.UNAUTHORIZED -> Reception.NotRecognised
        else -> Reception.Unreachable
    }
}

/** How a press of Refresh ended. */
sealed interface Reception {

    /**
     * At least one subscription was added, and the last of them is the one the app shows.
     *
     * @param rejected how many were sent in the same batch and could not be added. They
     *   stay waiting on the website.
     */
    data class Added(val added: Int, val rejected: Int) : Reception

    /** The portal answered, and nothing was waiting: the ordinary answer of a new device. */
    data object NothingSent : Reception

    /** Something was sent and none of it could be added. All of it stays waiting. */
    data class Rejected(val count: Int) : Reception

    /** The portal could not be reached, or did not answer sensibly. Worth another press. */
    data object Unreachable : Reception

    /** The portal knows this address under a different key. Another press will not help. */
    data object NotRecognised : Reception
}

/**
 * The subscription as the device would have it typed, or null when the device's own rules
 * refuse it.
 *
 * Through the same form checks the add screens use, so that what is acceptable on the
 * website is exactly what is acceptable on the television — a server address is normalised
 * the same way, and a link that is not one is not added however it arrived.
 */
private fun SentSubscription.toSource(): Pair<PlaylistSource, String?>? = when (val kind = source) {
    is SentSource.Xtream -> XtreamFormCheck.of(name, kind.server, kind.username, kind.password)
        .let { check -> check.source?.let { it to check.label } }

    is SentSource.Playlist -> M3uFormCheck.of(name, kind.url)
        .let { check -> check.source?.let { it to check.label } }
}
