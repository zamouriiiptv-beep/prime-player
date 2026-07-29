package com.castivio.domain.provider

import com.castivio.core.common.AppError
import com.castivio.core.common.Outcome
import com.castivio.domain.ProviderStatus
import com.castivio.domain.time.DAY_MS
import com.castivio.domain.time.daysRemaining

/**
 * What is wrong with the user's provider, if anything — and it is never a reason to
 * stop the app.
 *
 * This is the provider's subscription, bought from a third party, not Castivio's own
 * licence. The two are separate systems with separate consequences, and the difference
 * is the one settled in `UI_ARCHITECTURE.md`:
 *
 *  - The **app licence** decides whether Castivio may be used at all. When it lapses,
 *    the app locks. That is [com.castivio.domain.entitlement.EntitlementState].
 *  - The **provider subscription** decides whether new streams will play. When it
 *    lapses, the catalogue that was already imported is still on the device, so the
 *    app opens Home exactly as it always does and *says* something. That is this file.
 *
 * `startDestination` is not given a [ProviderHealth] and must never be. The rule holds
 * because the function cannot see the value, which is stronger than the rule being
 * written down — including here.
 *
 * One state at a time. A banner that stacks three warnings is a banner nobody reads, so
 * [of] resolves a precedence rather than returning a list, and the order it resolves in
 * is "what stops the user watching something right now" before "what will stop them
 * next week".
 */
sealed interface ProviderHealth {

    val severity: HealthSeverity

    /** True when a stream started now is likely to fail. Lets the player warn first. */
    val playbackLikelyFails: Boolean

    /** Nothing to say. The overwhelmingly common case, and it renders nothing. */
    data object Healthy : ProviderHealth {
        override val severity: HealthSeverity get() = HealthSeverity.NONE
        override val playbackLikelyFails: Boolean get() = false
    }

    /** The provider has never been asked, or its answer is not yet in. */
    data object Unknown : ProviderHealth {
        override val severity: HealthSeverity get() = HealthSeverity.NONE
        override val playbackLikelyFails: Boolean get() = false
    }

    /** The subscription ends soon enough that the user should be told now. */
    data class ExpiringSoon(
        val expiresAtMs: Long,
        val daysRemaining: Int,
    ) : ProviderHealth {
        override val severity: HealthSeverity get() = HealthSeverity.NOTICE
        override val playbackLikelyFails: Boolean get() = false
    }

    /**
     * The provider says the subscription has ended.
     *
     * The catalogue stays. Deleting a library because a subscription lapsed would
     * destroy the user's favourites and history over a renewal they may make an hour
     * later, and it is not ours to delete.
     */
    data class Expired(val expiresAtMs: Long?) : ProviderHealth {
        override val severity: HealthSeverity get() = HealthSeverity.PROBLEM
        override val playbackLikelyFails: Boolean get() = true
    }

    /**
     * Every connection the plan allows is already in use — usually another device in
     * the house, sometimes a stream that did not close cleanly.
     *
     * Ranked above an expiry warning because it is happening now: the next thing the
     * user presses will fail, and the reason is not one they could guess.
     */
    data class ConnectionLimit(val active: Int, val max: Int) : ProviderHealth {
        override val severity: HealthSeverity get() = HealthSeverity.PROBLEM
        override val playbackLikelyFails: Boolean get() = true
    }

    /**
     * The provider answered and refused: wrong credentials, a banned line, a disabled
     * account. [label] is whatever the panel called it, for the screen to show verbatim
     * — a provider's own word for it is more useful to the user than ours.
     */
    data class Rejected(val label: String?) : ProviderHealth {
        override val severity: HealthSeverity get() = HealthSeverity.PROBLEM
        override val playbackLikelyFails: Boolean get() = true
    }

    /**
     * The provider could not be reached. Says nothing about the subscription, which is
     * why it is its own state rather than an expiry — a hotel wifi is not a lapse.
     */
    data class Unreachable(val error: AppError) : ProviderHealth {
        override val severity: HealthSeverity get() = HealthSeverity.NOTICE
        override val playbackLikelyFails: Boolean get() = true
    }

    companion object {

        /**
         * @param status the last validation attempt, or null if none has been made.
         * @param nowMs from the app's trusted clock.
         * @param warnWithinMs how far ahead an expiry starts being worth mentioning.
         */
        fun of(
            status: Outcome<ProviderStatus>?,
            nowMs: Long,
            warnWithinMs: Long = ProviderHealthPolicy.WARN_WITHIN_MS,
        ): ProviderHealth = when (status) {
            null -> Unknown

            is Outcome.Failure -> when (status.error) {
                // The provider answered; it just said no. That is a fact about the
                // subscription, not about the network, and the two want different
                // sentences on screen.
                AppError.UNAUTHORIZED -> Rejected(null)
                else -> Unreachable(status.error)
            }

            is Outcome.Success -> settled(status.value, nowMs, warnWithinMs)
        }

        private fun settled(
            status: ProviderStatus,
            nowMs: Long,
            warnWithinMs: Long,
        ): ProviderHealth {
            val expiry = status.expiresAtMs

            // 1. Refusals first, because nothing will play and no other notice matters.
            if (!status.usable) {
                return if (expiry != null && nowMs >= expiry) Expired(expiry) else Rejected(status.statusLabel)
            }

            // 2. A stated expiry that has passed outranks the panel's own optimism: a
            //    line marked active with yesterday's date is a panel that has not caught
            //    up, and the date is the more specific fact.
            if (expiry != null && nowMs >= expiry) return Expired(expiry)

            // 3. Happening now beats happening later.
            if (status.atConnectionLimit) return ConnectionLimit(status.activeConnections, status.maxConnections)

            // 4. And only then, the warning.
            if (expiry != null && expiry - nowMs <= warnWithinMs) {
                return ExpiringSoon(expiry, daysRemaining(nowMs, expiry))
            }

            return Healthy
        }
    }
}

/**
 * How loudly to say it.
 *
 * Three levels rather than a colour, because the shell decides what a level looks like
 * and `:domain` must not know that a warning is amber.
 */
enum class HealthSeverity {
    /** Render nothing. */
    NONE,

    /** A quiet line the user can ignore. */
    NOTICE,

    /** Something will not work. Worth interrupting for once, not every launch. */
    PROBLEM,
}

/** The one place the windows are chosen. */
object ProviderHealthPolicy {

    /**
     * Three days. Long enough to renew without rushing, short enough that the notice
     * still means something when it appears — a warning that runs for a month is
     * furniture, and the user stops seeing it long before it matters.
     */
    const val WARN_WITHIN_MS: Long = 3 * DAY_MS
}
