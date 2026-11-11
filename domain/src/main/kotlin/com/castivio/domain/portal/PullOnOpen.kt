package com.castivio.domain.portal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The website's mailbox, asked without a press: each time the app opens or comes back to the
 * front.
 *
 * Refresh is the button for someone looking at the screen who wants it now. This is for the
 * owner who sent a subscription from the website, closed the app and opened it again, expecting
 * it to be there. It is the same question put to the same portal and answered by the same
 * [SentSubscriptionReceiver] — what is added here is *when* it is asked, and what must never go
 * wrong around the asking.
 *
 * ```
 * ask() ──► already running? ──► hand back that one
 *    │
 *    ├──► began a moment ago? ──► null: nothing new to wait for
 *    ├──► the portal said "not this key"? ──► null: asking again cannot change it
 *    └──► ask now, in the scope this object was given
 * ```
 *
 * ## What it guarantees
 *
 *  - **One ask at a time.** A second caller while one is in flight is handed that one. A launch
 *    asks from two places — the gate before the first screen, and the activity coming to the
 *    front — and two asks racing to add the same subscription would each try to make it the
 *    active one.
 *  - **Not again at once.** An ask that began less than [minGapMs] ago is not repeated. That is
 *    not a rate limit: it absorbs a screen that starts twice and nothing more, because the owner
 *    who sends a subscription and opens the app half a minute later is exactly the case this
 *    exists for.
 *  - **Never fatal.** Whatever the receiver does — an exception from the database, an answer it
 *    could not read — is an unreachable portal as far as any caller can tell. Asking is a
 *    convenience, and a convenience must not be the reason an app does not open.
 *  - **Not repeated against a lost cause.** A portal that knows this address under another key
 *    says so once ([Reception.NotRecognised]) and is not asked again by this object: another
 *    ask cannot change the answer, and the portal counts each one against the address's wrong
 *    keys, which lock the website out of the address too. Refresh, pressed by hand, still asks.
 *  - **Not tied to a screen.** The ask runs in [scope], which is the process's. A launch that is
 *    closed a second later still finishes adding what it found and acknowledges it, rather than
 *    leaving it half done for the next launch to repeat.
 *
 * Pure: the receiver, the scope and the clock are handed in, so the rules above are unit tests
 * on a virtual clock.
 *
 * @param scope what the ask runs in. A supervisor scope: one failed ask must not end the next.
 * @param elapsedMs milliseconds on a clock that only moves forward, whatever the wall clock is
 *   told. Used for nothing but [minGapMs].
 */
class PullOnOpen(
    private val receiver: SentSubscriptionReceiver,
    private val scope: CoroutineScope,
    private val elapsedMs: () -> Long,
    private val minGapMs: Long = MIN_GAP_MS,
) {

    /** Held only to decide whether to start an ask, never while one runs. */
    private val turn = Mutex()
    private var running: Deferred<Reception>? = null
    private var startedAtMs: Long? = null

    /**
     * Written by the ask, read by the next one. Volatile and not under [turn]: an ask that runs
     * on a dispatcher which starts it before [ask] has returned would otherwise wait for the
     * lock its own caller is holding.
     */
    @Volatile
    private var notRecognised = false

    /**
     * The ask in progress, a new one, or null when there is nothing new to wait for.
     *
     * Returns at once. What comes back is for a caller to wait on for as long as it can afford
     * — the gate gives it a few seconds, a screen already showing gives it as long as it takes —
     * and abandoning the wait leaves the ask going.
     */
    suspend fun ask(): Deferred<Reception>? = turn.withLock {
        running?.takeIf { it.isActive }?.let { return@withLock it }
        if (notRecognised) return@withLock null

        val now = elapsedMs()
        val last = startedAtMs
        // `in 0 until`: a reading that is *earlier* than the last one is a clock that was
        // not what it claimed to be, and is no reason to refuse to ask.
        if (last != null && now - last in 0 until minGapMs) return@withLock null

        startedAtMs = now
        scope.async { answer() }.also { running = it }
    }

    private suspend fun answer(): Reception {
        val reception = try {
            receiver.receive()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Reception.Unreachable
        }
        if (reception is Reception.NotRecognised) notRecognised = true
        return reception
    }

    companion object {
        /** Long enough to absorb one screen starting twice, short enough never to be noticed. */
        const val MIN_GAP_MS = 10_000L
    }
}
