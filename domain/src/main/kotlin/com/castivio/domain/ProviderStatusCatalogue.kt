package com.castivio.domain

import kotlinx.coroutines.flow.Flow

/**
 * What the provider last said about the subscription, kept so a screen can say it.
 *
 * ## Why this exists at all
 *
 * [ProviderStatus] is what a validator returns: whether the subscription is usable,
 * when it runs out, what the panel calls it. Until now every field of it was read
 * once — to decide whether activation could proceed — and then dropped on the floor.
 * A user who wanted to know when their subscription expires had to go and ask their
 * provider, on a screen whose whole job is to answer that.
 *
 * ## It is not the licence, and the two are never merged
 *
 * Castivio's own entitlement is a separate system with the opposite consequence: a
 * lapsed provider still opens the app and shows an empty catalogue, a lapsed licence
 * does not open the app at all. `EntitlementState` answers one and this answers the
 * other, and nothing converts between them — a single "status" covering both is the
 * first step toward a screen that says "expired" without the user being able to tell
 * which thing expired.
 *
 * ## Recorded, not polled
 *
 * The provider is asked when a subscription is added, because that is when the app
 * has a reason to ask. Nothing here goes back to the network to keep the number
 * fresh, so [Recorded.atMs] is part of the answer rather than bookkeeping: what is
 * shown is "this is what your provider said, on this date", which is honest about
 * its own age. A screen that needs it fresher asks for a refresh, which re-validates.
 */
data class Recorded(
    /** The provider's own verdict at the time it was asked. */
    val usable: Boolean,
    /** When the subscription runs out, where the provider states one. */
    val expiresAtMs: Long?,
    /** What the panel called it — "Active", "Expired", "Banned" — untranslated. */
    val label: String?,
    /** When this answer was received, which is what makes it readable rather than stale. */
    val atMs: Long,
)

/**
 * Where a provider's last answer is kept.
 *
 * Keyed by source, because a box can carry three subscriptions and "expires in May"
 * is true of exactly one of them; [forget] exists so that removing a provider does
 * not leave an answer behind for the next registration of the same id to inherit.
 */
interface ProviderStatusCatalogue {

    /** Stores what a validator just returned for this source. */
    suspend fun record(sourceId: String, status: ProviderStatus, atMs: Long)

    /** The last answer for this source, or null where none has been recorded. */
    fun of(sourceId: String): Flow<Recorded?>

    /** Drops the answer, for a provider that is being removed. */
    suspend fun forget(sourceId: String)
}
