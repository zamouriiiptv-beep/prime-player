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
    /**
     * The marks that say which sections are already on the device.
     *
     * Cleared on a successful refresh — see [refresh]. Nullable so the two callers
     * that only want the status question, and the tests that only assert it, are not
     * made to supply a catalogue they have no opinion about.
     */
    private val sections: SectionCatalogue? = null,
) {

    /**
     * Ask the active provider, record the answer, and let the sections go stale.
     *
     * ## Why the active source is read here and never held
     *
     * `activeNow()` is a query, run inside this call, on every call. Nothing about the
     * provider is captured when this object is built, so the source it asks about is
     * whatever the repository says is active at the instant the button was pressed —
     * which is what makes switching playlists and then refreshing ask the *new* one.
     * The answer is then recorded under `active.id`, read in the same call, so a reply
     * can never be filed against a different subscription.
     *
     * ## Why a successful refresh forgets the section marks
     *
     * The marks are what let a section open instantly the second time: a section with
     * a mark is on the device and is not fetched again. That is right until the user
     * asks for fresh information, and then it is the reason pressing refresh changed a
     * date and nothing else — the catalogue underneath stayed whatever it was when it
     * was first imported.
     *
     * Forgetting the marks costs nothing now and re-fetches nothing now. It makes the
     * *next* visit to Movies, Series or Radio fetch, on the screen that already has a
     * progress indicator for exactly that. So refresh stays the cheap question it was
     * designed to be — no 180,000-row import behind a button — and the content still
     * catches up.
     *
     * Only on success, and only for the source that answered. A provider that could
     * not be reached has told us nothing about whether its catalogue moved, and
     * throwing away a working offline catalogue on the strength of a failed request
     * would be the opposite of useful.
     */
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
                sections?.forget(active.id)
                Refreshed.Updated(answer.value)
            }
        }
    }
}
