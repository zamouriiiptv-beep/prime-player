package com.castivio.domain.activation

import com.castivio.core.common.Outcome
import com.castivio.domain.CatalogImporter
import com.castivio.domain.ImportProgress
import com.castivio.domain.PlaylistSource
import com.castivio.domain.ProviderStatus
import com.castivio.domain.ProviderStatusCatalogue
import com.castivio.domain.SourceRepository
import com.castivio.domain.provider.ProviderHealth
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * From what the user typed to a catalogue on the device — or to a sentence explaining
 * why not, with nothing broken on the way.
 *
 * Two guarantees hold across every path through this file, and they are the reason it
 * exists as one place rather than as steps scattered through a view model:
 *
 *  1. **Ask before importing.** The provider is validated first, so a wrong password,
 *     an expired subscription and an unreachable host arrive as three different
 *     answers instead of as "no channels" thirty seconds later. Nothing is written
 *     until that check passes.
 *  2. **Nothing that already worked is lost.** A failure, a cancellation or a
 *     zero-item response leaves the previously committed catalogue exactly as it was —
 *     `ImportMode.REPLACE` prunes only after `finish()` commits, and the active source
 *     is only switched once there is something to switch to.
 *
 * Pure: it takes the instant it should judge by, opens no clock, and touches no
 * Android. The whole sequence is therefore a unit test, including the awkward halves —
 * cancelled midway, finished with nothing, provider says renew.
 */
class ActivateProvider(
    private val validator: com.castivio.domain.ProviderValidator,
    private val importer: CatalogImporter,
    private val sources: SourceRepository,
    /**
     * Where the provider's answer is kept once it has been given.
     *
     * Optional, and null in the tests that are about something else, because a use
     * case that cannot be constructed without a store it does not exercise is a use
     * case whose tests grow a fixture per collaborator. What it must not be is
     * *forgotten*: every field of [ProviderStatus] was read once and dropped here
     * until this parameter existed, which is why a dashboard could not say when a
     * subscription runs out.
     */
    private val statuses: ProviderStatusCatalogue? = null,
) {

    /**
     * @param nowMs from the app's trusted clock. It tells an expired subscription
     *   from a refused one — see [ProviderHealth] — and stamps the answer that is
     *   recorded, so both use the same instant.
     */
    fun activate(
        source: PlaylistSource,
        label: String? = null,
        nowMs: Long,
        /**
         * Whether to bring the catalogue down as part of activating.
         *
         * False is what the app does now, and it changes what activation *means*:
         * the credentials are checked, the provider is saved and made active, and
         * the app opens. Each section is fetched when it is first opened, by
         * [com.castivio.domain.LoadSection].
         *
         * The reason is a number. A provider with 180,000 films makes someone who
         * only watches television wait four minutes for a library they will never
         * open, on this box and on the next one. Fetching per section turns one
         * unavoidable wait into three optional ones, two of which are never taken.
         *
         * True keeps the original sequence, which is still what a caller that
         * genuinely wants everything now should ask for.
         */
        fetchCatalogue: Boolean = true,
    ): Flow<ActivationPhase> = flow {
        emit(ActivationPhase.Checking)

        val status = when (val checked = validator.validate(source)) {
            is Outcome.Failure -> {
                emit(ActivationPhase.Failed(ActivationFailure.of(checked.error)))
                return@flow
            }

            is Outcome.Success -> checked.value
        }

        if (!status.usable) {
            emit(ActivationPhase.Failed(refusal(status, nowMs)))
            return@flow
        }

        if (fetchCatalogue) {
            importCatalogue(source, label, status, nowMs)
        } else {
            connect(source, label, status, nowMs)
        }
    }

    /**
     * Registered, active, and nothing downloaded.
     *
     * The two guarantees at the top of this file survive it. Nothing that already
     * worked is lost, because registering a provider writes no catalogue rows and
     * `register` preserves the sync state of a source that was already there. And
     * the provider was asked first, so this cannot save credentials that do not
     * work — which is the whole reason it is a separate branch here rather than a
     * screen skipping the use case.
     *
     * The `finally` clause the importing path needs has no counterpart, and that is
     * deliberate rather than an omission: there is no import to be cancelled halfway,
     * so the registration is never scaffolding for something that did not happen.
     */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<ActivationPhase>.connect(
        source: PlaylistSource,
        label: String?,
        status: ProviderStatus,
        nowMs: Long,
    ) {
        val registered = sources.register(source, label)
        succeed(registered.id, registered.sync.itemCount, status, nowMs)
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ActivationPhase>.importCatalogue(
        source: PlaylistSource,
        label: String?,
        status: ProviderStatus,
        nowMs: Long,
    ) {
        // Registered before the import so that the sync state written at the end has a
        // row to land on, and so a resumed import can find its validators. Whether it
        // *stays* registered depends on how this ends.
        val registered = sources.register(source, label)
        val hadCatalogue = registered.sync.itemCount > 0

        var settled = false
        var found = 0

        try {
            importer.import(source).collect { progress ->
                when (progress) {
                    is ImportProgress.CheckingForChanges ->
                        emit(ActivationPhase.Importing(found, groupsReady = 0, checkingForChanges = true))

                    is ImportProgress.Importing -> {
                        // Never backwards. An import that spans several kinds reports
                        // per-kind progress, and a counter that resets to 300 after
                        // reaching 12,000 reads as a fault in the app rather than as a
                        // change of section.
                        found = maxOf(found, progress.itemsImported)
                        emit(ActivationPhase.Importing(found, progress.groupsReady))
                    }

                    // The provider says nothing has changed, so nothing was downloaded
                    // or parsed. What is already on the device is the answer.
                    is ImportProgress.UpToDate -> if (hadCatalogue) {
                        settled = true
                        succeed(registered.id, registered.sync.itemCount, status, nowMs)
                    } else {
                        emit(ActivationPhase.Failed(ActivationFailure.EMPTY, found))
                    }

                    is ImportProgress.Done -> if (progress.totalItems > 0) {
                        settled = true
                        succeed(registered.id, progress.totalItems, status, nowMs)
                    } else {
                        // An import that committed nothing is not a success. Landing on
                        // an empty app is the one outcome worse than a clear failure.
                        emit(ActivationPhase.Failed(ActivationFailure.EMPTY, found))
                    }

                    is ImportProgress.Failed ->
                        emit(ActivationPhase.Failed(ActivationFailure.of(progress.error), found))
                }
            }
        } finally {
            // Cancelled or failed, and this provider had nothing here before: the
            // registration was only ever scaffolding for an import that did not happen,
            // and leaving it behind would put a provider with no channels in the user's
            // settings for them to wonder about.
            //
            // NonCancellable because the common way to reach here is cancellation, and
            // a cleanup that is itself cancelled is not a cleanup.
            if (!settled && !hadCatalogue) {
                withContext(NonCancellable) { sources.delete(registered.id) }
            }
        }
    }

    /**
     * The last writes, in this order: keep what the provider said, then make this the
     * source the app opens.
     *
     * Switching last is the whole of the non-destructive guarantee at this level. Until
     * that line runs, whatever the user had before is still what the app shows — so the
     * status is recorded first, against the id it belongs to, and a failure to switch
     * cannot leave an answer attached to a provider that never became active.
     *
     * `nowMs` and not a clock read here: this file is pure and judges by the instant it
     * was handed, and a recorded answer stamped with a different instant from the one
     * the expiry was judged against would be two clocks in one decision.
     */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<ActivationPhase>.succeed(
        sourceId: String,
        itemCount: Int,
        status: ProviderStatus,
        nowMs: Long,
    ) {
        statuses?.record(sourceId, status, nowMs)
        sources.setActive(sourceId)
        emit(ActivationPhase.Succeeded(sourceId, itemCount, status))
    }

    /**
     * Why a provider that answered said no.
     *
     * Routed through [ProviderHealth] rather than reimplemented, because it already
     * settles the precedence — a date in the past outranks a panel still claiming to be
     * active — and it does it against the trusted clock rather than the provider's
     * optimism.
     */
    private fun refusal(status: ProviderStatus, nowMs: Long): ActivationFailure =
        when (ProviderHealth.of(Outcome.Success(status), nowMs)) {
            is ProviderHealth.Expired -> ActivationFailure.SUBSCRIPTION_ENDED
            else -> ActivationFailure.PROVIDER_REFUSED
        }
}
