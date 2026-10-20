package com.castivio.domain

/**
 * Removes a subscription from this device, and everything kept about it.
 *
 * ## Why it is more than `sources.delete`
 *
 * Because a subscription is kept in four places. The repository holds the row and the
 * catalogue imported for it; [SectionCatalogue] holds which of its sections have been
 * fetched; [ProviderStatusCatalogue] holds what its provider last said; [PlaylistCopies]
 * holds a playlist's download. Deleting from the first alone left the others behind, and
 * the marks are the ones that do harm: they say "on the device", so the same subscription
 * added again would open sections whose rows went with the deletion — empty, and never
 * fetched again, because the loader trusts a mark. The copy does a quieter harm: the
 * provider's whole file, credentials in every link, kept for a subscription the user
 * removed.
 *
 * Nothing here touches another subscription: every call is keyed on [invoke]'s id.
 *
 * Pure, like [RefreshProvider], and for the same reason.
 */
class RemoveSource(
    private val sources: SourceRepository,
    private val marks: SectionCatalogue,
    private val statuses: ProviderStatusCatalogue,
    /** Nullable for the tests that have no opinion about a playlist's copy. */
    private val copies: PlaylistCopies? = null,
) {

    suspend operator fun invoke(id: String) {
        // The marks before the rows. Killed in between, this order leaves a subscription
        // that is still saved and merely re-downloads a section it did not need to; the
        // other order would leave marks claiming rows that are gone, which is the empty
        // section this class exists to prevent. The copy goes with the marks, for the
        // same reason.
        marks.forget(id)
        copies?.discard(id)
        statuses.forget(id)
        sources.delete(id)
    }
}
