package com.castivio.domain.activation

import com.castivio.core.common.Outcome
import com.castivio.domain.CatalogImporter
import com.castivio.domain.ImportProgress
import com.castivio.domain.PlaylistSource
import com.castivio.domain.ProviderStatus
import com.castivio.domain.SourceRepository
import com.castivio.domain.isOnDemand
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
) {

    /**
     * @param nowMs from the app's trusted clock, used only to tell an expired
     *   subscription from a refused one. See [ProviderHealth].
     */
    fun activate(
        source: PlaylistSource,
        label: String? = null,
        nowMs: Long,
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

        // Signing in is signing in, and for a provider that can be read a section at a
        // time that is the whole of it.
        //
        // This used to import the catalogue here: every category of every kind, tens of
        // thousands of rows, before the user had said what they wanted to watch. It is
        // the wrong moment for that work twice over. It is minutes on a weak box for
        // data most of which is never opened, and it is spent before the app has any
        // idea whether this person came for football or for films. `CatalogSections`
        // fetches a section when a section is asked for, which costs one request and
        // arrives while the screen is still drawing.
        //
        // Nothing is skipped for a playlist, because nothing *can* be: an M3U is one
        // file with no index, so reading it is the only way to learn what is in it, and
        // no later moment makes that cheaper. See `PlaylistSource.isOnDemand`.
        if (source.isOnDemand) {
            signIn(source, label, status)
            return@flow
        }

        importCatalogue(source, label, status)
    }

    /**
     * Registers the provider, makes it the active one, and stops.
     *
     * `itemCount = 0` is honest rather than unfortunate: nothing has been fetched, and a
     * screen that reported a number here would be reporting one it invented. What the
     * user gets instead is Home, immediately.
     */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<ActivationPhase>.signIn(
        source: PlaylistSource,
        label: String?,
        status: ProviderStatus,
    ) {
        val registered = sources.register(source, label)
        sources.setActive(registered.id)
        emit(ActivationPhase.Succeeded(registered.id, registered.sync.itemCount, status))
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ActivationPhase>.importCatalogue(
        source: PlaylistSource,
        label: String?,
        status: ProviderStatus,
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
                        succeed(registered.id, registered.sync.itemCount, status)
                    } else {
                        emit(ActivationPhase.Failed(ActivationFailure.EMPTY, found))
                    }

                    is ImportProgress.Done -> if (progress.totalItems > 0) {
                        settled = true
                        succeed(registered.id, progress.totalItems, status)
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
     * The last two writes, in this order: record the catalogue, then make it the one
     * the app opens.
     *
     * Switching last is the whole of the non-destructive guarantee at this level. Until
     * this line runs, whatever the user had before is still what the app shows.
     */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<ActivationPhase>.succeed(
        sourceId: String,
        itemCount: Int,
        status: ProviderStatus,
    ) {
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
