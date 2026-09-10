package com.castivio.domain

import com.castivio.core.common.Outcome

/**
 * Ask the active provider again what it says about the subscription.
 *
 * ## What it is not
 *
 * It is not a catalogue refresh. Nothing here downloads a channel, and that is the
 * whole reason it can be a button on Home: a "refresh" that re-imported 180,000 films
 * would be a four-minute wait behind a control the user pressed to update a date.
 * Sections are still brought onto the device by the section that was opened, which is
 * where a fetch belongs and where its progress can be shown.
 *
 * What it does is re-run the one cheap question — the validator — and write the answer
 * down. On a screen that now states "Active, expires 27 June 2027", this is the only
 * thing that can move those two facts, because they were recorded when the provider
 * was added and nothing goes back to the network to keep them fresh.
 *
 * ## Three answers, and none of them is a lie
 *
 * [Refreshed.Updated] is what the provider said, whatever it said: a subscription that
 * has since expired reports as expired and the header turns red, because that is
 * useful and hiding it is not. [Refreshed.NoProvider] and [Refreshed.Unreachable] are
 * kept apart because they need opposite sentences — one is "add a subscription", the
 * other is "your provider did not answer, try again" — and because collapsing them
 * would let a network blip look like a missing subscription.
 *
 * A failure writes nothing. The previous answer stays, stale but true, rather than
 * being replaced by our inability to ask.
 */
sealed interface Refreshed {

    /** The provider answered; the answer has been recorded. */
    data class Updated(val status: ProviderStatus) : Refreshed

    /** There is no active provider, so there was nobody to ask. */
    data object NoProvider : Refreshed

    /** The provider could not be asked. Whatever was recorded before still stands. */
    data class Unreachable(val error: com.castivio.core.common.AppError) : Refreshed
}

/**
 * Pure: it takes the instant it should stamp the answer with, opens no clock, and
 * touches no Android — the same shape as [com.castivio.domain.activation.ActivateProvider],
 * for the same reason.
 */
class RefreshProvider(
    private val sources: SourceRepository,
    private val validator: ProviderValidator,
    private val statuses: ProviderStatusCatalogue,
) {

    suspend fun refresh(nowMs: Long): Refreshed {
        val active = sources.activeNow() ?: return Refreshed.NoProvider
        // A portal registration has no address to go back to, so it cannot be asked
        // again. Reported as "no provider to ask" rather than as a failure, because
        // nothing went wrong and a retry would reconstruct the same nothing.
        val source = active.asPlaylistSource() ?: return Refreshed.NoProvider

        return when (val answer = validator.validate(source)) {
            is Outcome.Failure -> Refreshed.Unreachable(answer.error)
            is Outcome.Success -> {
                statuses.record(active.id, answer.value, nowMs)
                Refreshed.Updated(answer.value)
            }
        }
    }
}
