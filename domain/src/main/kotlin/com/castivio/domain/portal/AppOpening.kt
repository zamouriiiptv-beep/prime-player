package com.castivio.domain.portal

import com.castivio.domain.SourceRepository
import com.castivio.domain.entitlement.EntitlementRepository
import com.castivio.domain.entitlement.StartDestination
import com.castivio.domain.entitlement.startDestination
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How the app opens: the two gates of [startDestination], with the website asked what was sent
 * while the app was closed.
 *
 * ```
 *  licence says no ───────────────────────────────► Licence        (nothing is asked)
 *  licence says yes
 *    a subscription is already here ──────────────► Home           (asked behind it)
 *    nothing here ── asked, waited for a moment ──► Home if something arrived
 *                                                   Activation otherwise
 *                                                   …and Home later, if the answer was slow
 * ```
 *
 * ## Why the wait is only where nothing is on the device
 *
 * A device with a subscription opens on Home at once, as it always did: a cold start does not
 * wait on a website. Whatever the website has for it is added behind Home, and Home shows it
 * when it lands. A device with *nothing* is the one whose next screen depends on the answer — it
 * is either the activation screen it would have seen anyway, or Home with the subscription its
 * owner just sent — so that is the only place a wait is worth having, and it is bounded: an
 * offline television reaches the activation screen, which works offline, after [PATIENCE_MS]
 * and no later.
 *
 * ## Why the licence is asked first
 *
 * A device that may not use Castivio is not told about subscriptions. There is nowhere to show
 * them, and adding what cannot be used would be a side effect of a screen that says "no".
 *
 * The decision itself stays [startDestination], unchanged and still free of any wait or
 * network. This class only feeds it, and only decides how long to wait before doing so.
 */
class AppOpening(
    private val entitlement: EntitlementRepository,
    private val sources: SourceRepository,
    private val pull: PullOnOpen,
) {

    /**
     * Where the app is when nothing remote is involved: after a subscription was added on this
     * device, by hand, and the gate has to move on for the right reason.
     */
    suspend fun settled(): StartDestination = startDestination(entitlement.establish(), sources.activeNow())

    /**
     * Where the app opens: the first destination as soon as it can be known, and a second one
     * only if a slow answer from the website changes it.
     *
     * Completes after the first when there is nothing more to wait for. Collecting it in a scope
     * that is later cancelled abandons the wait and nothing else: the ask belongs to
     * [PullOnOpen], which finishes it either way.
     *
     * @param patienceMs how long a device with nothing on it waits for the website before it
     *   shows the activation screen.
     */
    fun opening(patienceMs: Long = PATIENCE_MS): Flow<StartDestination> = flow {
        val licence = entitlement.establish()
        if (!licence.allowsUse) {
            emit(startDestination(licence, source = null))
            return@flow
        }

        val source = sources.activeNow()
        val asked = pull.ask()

        // Something to show, or nothing new to wait for: this is the destination.
        if (source != null || asked == null) {
            emit(startDestination(licence, source))
            return@flow
        }

        val answer = withTimeoutOrNull(patienceMs) { asked.await() }
        emit(startDestination(licence, sources.activeNow()))

        // The answer was slow. The first screen is already up; if the late answer put a
        // subscription on the device, the app moves on to it, as it would from Refresh.
        if (answer == null && asked.await() is Reception.Added) {
            emit(startDestination(licence, sources.activeNow()))
        }
    }

    /**
     * The app came back to the front after being away — a press of the Home button, another
     * app — and may have been sent something meanwhile.
     *
     * @param shown where the app is now.
     * @return the destination it should move to, or null when it should stay where it is: nothing
     *   was waiting, nothing could be added, the answer is not in yet, or what was added changes
     *   nothing about the screen (Home stays Home; it shows the new subscription by itself).
     */
    suspend fun cameForward(shown: StartDestination): StartDestination? {
        if (shown is StartDestination.Licence) return null

        // `current`, not `establish`: this re-reads the licence against the clock and grants
        // nothing. A licence that lapsed while the app was away is neither told about
        // subscriptions nor a reason to move the user — the launch judges it, as it always did.
        if (!entitlement.current().allowsUse) return null

        val asked = pull.ask() ?: return null
        if (asked.await() !is Reception.Added) return null

        val now = startDestination(entitlement.current(), sources.activeNow())
        return now.takeIf { it != shown }
    }

    companion object {
        /**
         * What a device with nothing on it waits for the website before showing the activation
         * screen.
         *
         * The ask is two small requests, then — when something was sent — the subscription's own
         * panel is asked whether it works, then one more small request says it arrived. On a
         * television's Wi-Fi that is a second or two. A figure well above that costs nothing where
         * the portal answers; where it does not, the figure is the delay before the one screen
         * that works without a connection, so it is kept short.
         */
        const val PATIENCE_MS = 5_000L
    }
}
