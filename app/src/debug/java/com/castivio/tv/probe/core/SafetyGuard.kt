package com.castivio.tv.probe.core

/**
 * What makes the session stop by itself.
 *
 * The owner's rule: **a 429, a 403 or any 5xx ends the experiment at once and shows what was measured.** These are
 * the statuses a provider answers with when it is being asked too much or has had enough, and carrying on past one
 * is how an address gets blocked for the application's own use.
 */
object SafetyGuard {

    fun stopsSession(status: Int): Boolean = status == 403 || status == 429 || status in 500..599
}

/** Why the session ended before its last step. Words and numbers only; never an address. */
sealed interface StopReason {

    /** A request was answered with a status that ends the session. */
    data class ProviderStatus(val status: Int, val step: String) : StopReason

    /** The list of categories or the one reply was refused: there is nothing to compare. */
    data class Configuration(val status: Int, val step: String) : StopReason

    object ByUser : StopReason

    data class HardBudget(val receivedBytes: Long, val announcedBytes: Long) : StopReason

    data class ConsecutiveInvalid(val count: Int) : StopReason

    /** An error nobody expected, by the name of its class. */
    data class Unexpected(val kind: String) : StopReason
}
