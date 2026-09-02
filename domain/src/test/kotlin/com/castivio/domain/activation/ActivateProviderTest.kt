package com.castivio.domain.activation

import com.castivio.core.common.AppError
import com.castivio.core.common.Outcome
import com.castivio.domain.CatalogImporter
import com.castivio.domain.ImportProgress
import com.castivio.domain.MediaKind
import com.castivio.domain.PlaylistSource
import com.castivio.domain.ProviderSource
import com.castivio.domain.ProviderStatus
import com.castivio.domain.ProviderValidator
import com.castivio.domain.SourceKind
import com.castivio.domain.SourceRepository
import com.castivio.domain.SyncState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The journey this slice exists to prove: typed details in, a usable catalogue out —
 * and, on every other path, nothing broken.
 *
 * The failure halves matter more than the happy one. A provider that answers "renew",
 * a playlist that streams twelve thousand items and then dies, a user who presses back
 * halfway through: each has to leave the device exactly as usable as it was a minute
 * earlier, and each is a test below rather than a hope.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivateProviderTest {

    private val t0 = 1_772_323_200_000L
    private val day = 24L * 60 * 60 * 1000

    private val xtream = PlaylistSource.Xtream("http://line.example.com:8080", "bob", "hunter2")
    private val m3u = PlaylistSource.M3u("http://line.example.com/playlist.m3u")

    // --------------------------------------------------------------- the harness

    private class Sources : SourceRepository {
        val stored = linkedMapOf<String, ProviderSource>()
        var activeId: String? = null
        var deletions = 0
        var registrations = 0

        /** Seeds a provider that already has a working catalogue. */
        fun seed(id: String, itemCount: Int) {
            stored[id] = ProviderSource(
                id = id,
                kind = SourceKind.XTREAM,
                label = "Existing",
                url = "http://old.example.com",
                sync = SyncState(lastImportAtMs = 1L, itemCount = itemCount),
            )
        }

        override suspend fun register(source: PlaylistSource, label: String?): ProviderSource {
            registrations++
            val id = idOf(source)
            return stored.getOrPut(id) {
                ProviderSource(
                    id = id,
                    kind = SourceKind.XTREAM,
                    label = label ?: "New",
                    url = null,
                )
            }
        }

        override fun sources(): Flow<List<ProviderSource>> = throw UnsupportedOperationException()
        override fun active(): Flow<ProviderSource?> = throw UnsupportedOperationException()
        override suspend fun activeNow(): ProviderSource? = activeId?.let(stored::get)
        override suspend fun get(id: String): ProviderSource? = stored[id]

        override suspend fun save(source: ProviderSource) {
            stored[source.id] = source
        }

        override suspend fun setActive(id: String) {
            activeId = id
        }

        override suspend fun recordCatalogueImport(id: String, sync: SyncState) {
            stored[id] = stored.getValue(id).copy(sync = sync)
        }

        override suspend fun recordEpgImport(id: String, atMs: Long) = Unit

        override suspend fun delete(id: String) {
            stored.remove(id)
            deletions++
        }

        companion object {
            fun idOf(source: PlaylistSource): String = when (source) {
                is PlaylistSource.Xtream -> "xtream-1"
                is PlaylistSource.M3u -> "m3u-1"
                else -> "other"
            }
        }
    }

    private class Validator(var answer: Outcome<ProviderStatus>) : ProviderValidator {
        var calls = 0
        override suspend fun validate(source: PlaylistSource): Outcome<ProviderStatus> {
            calls++
            return answer
        }
    }

    private class Importer(private val script: List<ImportProgress>) : CatalogImporter {
        var started = 0
        override fun import(source: PlaylistSource): Flow<ImportProgress> = flow {
            started++
            script.forEach { emit(it) }
        }

        override suspend fun isUpToDate(source: PlaylistSource): Boolean = false
    }

    /** An import that stops where it is told and never finishes on its own. */
    private class StallingImporter : CatalogImporter {
        val reachedMiddle = CompletableDeferred<Unit>()
        private val release = CompletableDeferred<Unit>()

        override fun import(source: PlaylistSource): Flow<ImportProgress> = flow {
            emit(ImportProgress.Importing(12_000, 3, MediaKind.LIVE))
            reachedMiddle.complete(Unit)
            release.await()
            emit(ImportProgress.Done(90_000, 4_000))
        }

        override suspend fun isUpToDate(source: PlaylistSource): Boolean = false
    }

    private fun usable(
        expiresAtMs: Long? = null,
        activeConnections: Int = 0,
        maxConnections: Int = 0,
    ) = Outcome.Success(
        ProviderStatus(
            usable = true,
            expiresAtMs = expiresAtMs,
            activeConnections = activeConnections,
            maxConnections = maxConnections,
            statusLabel = "Active",
        ),
    )

    private fun activateProvider(
        validator: ProviderValidator,
        importer: CatalogImporter,
        sources: Sources,
    ) = ActivateProvider(validator, importer, sources)

    private fun importOf(vararg progress: ImportProgress) = Importer(progress.toList())

    // ------------------------------------------------------------- the happy path

    /**
     * Signing in to a panel is signing in, and nothing else happens.
     *
     * This used to import the catalogue here — every category of every kind, tens of
     * thousands of rows, before the user had said whether they came for football or
     * for films. Two phases now: checked, then in. What the user watches decides what
     * is fetched, and `CatalogSections` fetches it a section at a time.
     */
    @Test
    fun `signing in to a panel imports nothing`() = runTest {
        val sources = Sources()
        val importer = importOf(ImportProgress.Done(21_874, 9_000))

        val phases = activateProvider(
            Validator(usable(expiresAtMs = t0 + 300 * day)),
            importer,
            sources,
        ).activate(xtream, label = "Home", nowMs = t0).toList()

        assertEquals(
            listOf(
                ActivationPhase.Checking,
                ActivationPhase.Succeeded("xtream-1", 0, (usable(t0 + 300 * day)).value),
            ),
            phases,
        )
        assertEquals("the catalogue was imported at sign-in", 0, importer.started)
        assertEquals("xtream-1", sources.activeId)
        assertEquals(1, sources.registrations)
    }

    /**
     * And it is still the *checked* sign-in it always was.
     *
     * Skipping the import must not become skipping the validation: wrong password,
     * expired subscription and unreachable host still have to be three answers, and
     * they still have to arrive before anything is registered.
     */
    @Test
    fun `signing in still asks the provider first`() = runTest {
        val sources = Sources()
        val validator = Validator(usable())

        activateProvider(validator, importOf(), sources).activate(xtream, nowMs = t0).toList()

        assertEquals(1, validator.calls)
    }

    /**
     * A playlist is imported at sign-in, because there is no later moment that is
     * cheaper.
     *
     * An M3U is one file with no index — the only way to learn what is in it is to
     * read it — so the choice is not between now and later, it is between now and
     * never. This is the path the phases below still cover.
     */
    @Test
    fun `a playlist is still imported when it is added`() = runTest {
        val sources = Sources()
        val importer = importOf(
            ImportProgress.Importing(500, 1, MediaKind.LIVE),
            ImportProgress.Done(21_874, 9_000),
        )

        val phases = activateProvider(Validator(usable()), importer, sources)
            .activate(m3u, label = "Home", nowMs = t0)
            .toList()

        assertEquals(1, importer.started)
        assertEquals(ActivationPhase.Importing(500, 1), phases[1])
        assertEquals(
            ActivationPhase.Succeeded("m3u-1", 21_874, usable().value),
            phases.last(),
        )
    }

    @Test
    fun `a playlist url becomes a catalogue`() = runTest {
        val sources = Sources()
        val phases = activateProvider(
            Validator(usable()),
            importOf(
                ImportProgress.CheckingForChanges,
                ImportProgress.Importing(21_874, 0, MediaKind.LIVE),
                ImportProgress.Done(21_874, 12_000),
            ),
            sources,
        ).activate(m3u, nowMs = t0).toList()

        assertEquals(ActivationPhase.Importing(0, 0, checkingForChanges = true), phases[1])
        assertEquals(ActivationPhase.Importing(21_874, 0), phases[2])
        assertTrue("${phases.last()}", phases.last() is ActivationPhase.Succeeded)
        assertEquals("m3u-1", sources.activeId)
    }

    /**
     * The provider's own expiry, carried through untouched for the Home banner to
     * count down. Not interpreted here: turning it into a warning needs the clock, and
     * that is the shell's job.
     */
    @Test
    fun `the subscription expiry the provider stated survives to the caller`() = runTest {
        val expires = t0 + 45 * day
        val phases = activateProvider(
            Validator(usable(expiresAtMs = expires, activeConnections = 1, maxConnections = 3)),
            importOf(),
            Sources(),
        ).activate(xtream, nowMs = t0).toList()

        val done = phases.last() as ActivationPhase.Succeeded
        assertEquals(expires, done.subscriptionExpiresAtMs)
        assertEquals(3, done.status.maxConnections)
        assertEquals("Active", done.status.statusLabel)
    }

    /** The counter the loading screen shows never goes backwards. */
    @Test
    fun `the item count never decreases across kinds`() = runTest {
        val phases = activateProvider(
            Validator(usable()),
            importOf(
                ImportProgress.Importing(12_000, 4, MediaKind.LIVE),
                // A new kind starts its own count in some engines. The screen must not
                // appear to lose twelve thousand items.
                ImportProgress.Importing(300, 5, MediaKind.MOVIE),
                ImportProgress.Importing(13_500, 6, MediaKind.MOVIE),
                ImportProgress.Done(13_500, 8_000),
            ),
            Sources(),
        ).activate(m3u, nowMs = t0).toList()

        val counts = phases.filterIsInstance<ActivationPhase.Importing>().map { it.itemsFound }
        assertEquals(listOf(12_000, 12_000, 13_500), counts)
        assertEquals(counts.sorted(), counts)
    }

    // ------------------------------------------------------- refused before writing

    @Test
    fun `nothing is written when the provider refuses the credentials`() = runTest {
        val sources = Sources()
        val importer = importOf(ImportProgress.Done(1, 1))

        val phases = activateProvider(
            Validator(Outcome.Failure(AppError.UNAUTHORIZED)),
            importer,
            sources,
        ).activate(xtream, nowMs = t0).toList()

        assertEquals(
            listOf(ActivationPhase.Checking, ActivationPhase.Failed(ActivationFailure.REJECTED)),
            phases,
        )
        assertEquals(0, importer.started)
        assertEquals(0, sources.registrations)
        assertNull(sources.activeId)
    }

    /**
     * The distinction the validation step exists for. An expired subscription and a
     * wrong password are one sentence apart, and importing first would have made both
     * of them "no channels".
     */
    @Test
    fun `an expired subscription says renew, not wrong password`() = runTest {
        val expired = Outcome.Success(
            ProviderStatus(usable = false, expiresAtMs = t0 - day, statusLabel = "Expired"),
        )

        val phases = activateProvider(Validator(expired), importOf(), Sources())
            .activate(xtream, nowMs = t0)
            .toList()

        assertEquals(ActivationPhase.Failed(ActivationFailure.SUBSCRIPTION_ENDED), phases.last())
        assertFalse((phases.last() as ActivationPhase.Failed).retryable)
    }

    @Test
    fun `a banned line is refused rather than reported as expired`() = runTest {
        val banned = Outcome.Success(ProviderStatus(usable = false, statusLabel = "Banned"))

        val phases = activateProvider(Validator(banned), importOf(), Sources())
            .activate(xtream, nowMs = t0)
            .toList()

        assertEquals(ActivationPhase.Failed(ActivationFailure.PROVIDER_REFUSED), phases.last())
    }

    /** Every transport failure reaches the user as its own sentence. */
    @Test
    fun `each way of failing to reach a provider has its own answer`() = runTest {
        val expected = mapOf(
            AppError.NETWORK_UNAVAILABLE to ActivationFailure.UNREACHABLE,
            AppError.TIMEOUT to ActivationFailure.TIMED_OUT,
            AppError.UNAUTHORIZED to ActivationFailure.REJECTED,
            AppError.NOT_FOUND to ActivationFailure.NOT_FOUND,
            AppError.MALFORMED_PLAYLIST to ActivationFailure.UNREADABLE,
            AppError.SERVER_ERROR to ActivationFailure.PROVIDER_ERROR,
            AppError.NOT_CONFIGURED to ActivationFailure.UNSUPPORTED,
            AppError.UNKNOWN to ActivationFailure.UNKNOWN,
        )

        for ((error, failure) in expected) {
            val phases = activateProvider(Validator(Outcome.Failure(error)), importOf(), Sources())
                .activate(xtream, nowMs = t0)
                .toList()

            assertEquals("$error", ActivationPhase.Failed(failure), phases.last())
        }
    }

    /** Every AppError maps to something. A new one has to be given a sentence. */
    @Test
    fun `every error has an answer`() {
        for (error in AppError.entries) {
            ActivationFailure.of(error)
        }
    }

    @Test
    fun `only the transient failures offer a retry`() {
        val retryable = ActivationFailure.entries.filter { it.retryable }

        assertEquals(
            listOf(
                ActivationFailure.UNREACHABLE,
                ActivationFailure.TIMED_OUT,
                ActivationFailure.PROVIDER_ERROR,
                ActivationFailure.UNKNOWN,
            ),
            retryable,
        )
    }

    // --------------------------------------------------------- failing mid-import

    /**
     * The provider was reachable, the credentials worked, and the stream died at
     * twelve thousand items. How far it got is worth saying — "stopped after 12,000"
     * and "could not start" are different problems with different next steps.
     */
    @Test
    fun `a failure halfway reports how far it got`() = runTest {
        val sources = Sources()

        val phases = activateProvider(
            Validator(usable()),
            importOf(
                ImportProgress.Importing(12_000, 3, MediaKind.LIVE),
                ImportProgress.Failed(AppError.TIMEOUT),
            ),
            sources,
        ).activate(m3u, nowMs = t0).toList()

        assertEquals(ActivationPhase.Failed(ActivationFailure.TIMED_OUT, itemsFound = 12_000), phases.last())
        assertNull(sources.activeId)
    }

    /**
     * A first activation that failed leaves nothing behind. The registration existed
     * only so the import had somewhere to write; without a catalogue it is a provider
     * with no channels sitting in the user's settings for them to wonder about.
     */
    @Test
    fun `a failed first activation leaves no half-registered provider`() = runTest {
        val sources = Sources()

        activateProvider(
            Validator(usable()),
            importOf(ImportProgress.Failed(AppError.SERVER_ERROR)),
            sources,
        ).activate(m3u, nowMs = t0).toList()

        assertEquals(emptyMap<String, ProviderSource>(), sources.stored)
        assertEquals(1, sources.deletions)
    }

    /**
     * And the opposite: a *re-*activation that failed keeps everything. This is the
     * non-destructive guarantee at this level — the old catalogue is still committed,
     * still the active source, and the user has lost nothing by trying.
     */
    @Test
    fun `a failed re-activation keeps the catalogue that already worked`() = runTest {
        val sources = Sources().apply { seed("m3u-1", itemCount = 40_000) }
        sources.activeId = "m3u-1"

        activateProvider(
            Validator(usable()),
            importOf(ImportProgress.Failed(AppError.NETWORK_UNAVAILABLE)),
            sources,
        ).activate(m3u, nowMs = t0).toList()

        assertEquals(40_000, sources.stored.getValue("m3u-1").sync.itemCount)
        assertEquals("m3u-1", sources.activeId)
        assertEquals(0, sources.deletions)
    }

    // ------------------------------------------------------------------ zero items

    /**
     * An import that committed nothing is not a success, however cleanly it finished.
     * Landing on an empty app is the one outcome worse than a clear failure, and it is
     * the case where "the import worked" and "the user has something" disagree.
     */
    @Test
    fun `an import that commits nothing is a failure`() = runTest {
        val sources = Sources()

        val phases = activateProvider(
            Validator(usable()),
            importOf(ImportProgress.Done(0, 400)),
            sources,
        ).activate(m3u, nowMs = t0).toList()

        assertEquals(ActivationPhase.Failed(ActivationFailure.EMPTY), phases.last())
        assertNull(sources.activeId)
        assertEquals(emptyMap<String, ProviderSource>(), sources.stored)
    }

    @Test
    fun `an unchanged provider with nothing on the device is not a success either`() = runTest {
        val sources = Sources()

        val phases = activateProvider(
            Validator(usable()),
            importOf(ImportProgress.UpToDate),
            sources,
        ).activate(m3u, nowMs = t0).toList()

        assertEquals(ActivationPhase.Failed(ActivationFailure.EMPTY), phases.last())
        assertNull(sources.activeId)
    }

    /** But an unchanged provider that *does* have one is finished before it started. */
    @Test
    fun `an unchanged provider with a catalogue succeeds without importing`() = runTest {
        val sources = Sources().apply { seed("m3u-1", itemCount = 40_000) }

        val phases = activateProvider(
            Validator(usable()),
            importOf(ImportProgress.UpToDate),
            sources,
        ).activate(m3u, nowMs = t0).toList()

        assertEquals(
            ActivationPhase.Succeeded("m3u-1", 40_000, usable().value),
            phases.last(),
        )
        assertEquals("m3u-1", sources.activeId)
    }

    // ---------------------------------------------------------------- cancellation

    /**
     * The user presses back at twelve thousand items. Nothing is committed, the
     * scaffolding registration is removed, and no provider is made active — the app is
     * exactly where it was before they started typing.
     */
    @Test
    fun `cancelling midway leaves nothing behind`() = runTest {
        val sources = Sources()
        val importer = StallingImporter()
        val phases = mutableListOf<ActivationPhase>()

        val collecting = async {
            activateProvider(Validator(usable()), importer, sources)
                .activate(m3u, nowMs = t0)
                .collect { phases += it }
        }

        importer.reachedMiddle.await()
        yield()
        collecting.cancel()
        collecting.join()

        assertEquals(ActivationPhase.Importing(12_000, 3), phases.last())
        assertNull(sources.activeId)
        assertEquals(emptyMap<String, ProviderSource>(), sources.stored)
        assertEquals(1, sources.deletions)
    }

    /** Cancelling a re-activation is just as harmless, and keeps more. */
    @Test
    fun `cancelling a re-activation keeps the catalogue that already worked`() = runTest {
        val sources = Sources().apply { seed("m3u-1", itemCount = 40_000) }
        sources.activeId = "m3u-1"
        val importer = StallingImporter()

        val collecting = async {
            activateProvider(Validator(usable()), importer, sources)
                .activate(m3u, nowMs = t0)
                .collect { }
        }

        importer.reachedMiddle.await()
        yield()
        collecting.cancel()
        collecting.join()

        assertEquals(40_000, sources.stored.getValue("m3u-1").sync.itemCount)
        assertEquals("m3u-1", sources.activeId)
        assertEquals(0, sources.deletions)
    }

    /**
     * What the process being killed mid-import looks like from here: the flow simply
     * stops. Nothing was recorded, so the next launch sees a provider that needs a
     * first import — which is exactly what `RefreshPolicy.needsFirstImport` reports and
     * what `startDestination` sends back to activation.
     */
    @Test
    fun `an import that never finishes records no catalogue`() = runTest {
        val sources = Sources().apply { seed("m3u-1", itemCount = 0) }
        val importer = StallingImporter()

        val collecting = async {
            activateProvider(Validator(usable()), importer, sources)
                .activate(m3u, nowMs = t0)
                .collect { }
        }

        importer.reachedMiddle.await()
        yield()
        collecting.cancel()
        collecting.join()

        assertNull(sources.stored["m3u-1"]?.sync?.lastImportAtMs)
        assertNull(sources.activeId)
    }

    // -------------------------------------------------------------------- retrying

    /**
     * A retry is a second attempt at the same details, and it must be a clean one:
     * everything is asked again from the top, including the validation.
     */
    @Test
    fun `retrying after a transient failure runs the whole sequence again`() = runTest {
        val sources = Sources()
        val validator = Validator(Outcome.Failure(AppError.NETWORK_UNAVAILABLE))
        val importer = importOf(ImportProgress.Done(21_874, 9_000))
        val activate = activateProvider(validator, importer, sources)

        val first = activate.activate(m3u, nowMs = t0).toList()
        assertEquals(ActivationPhase.Failed(ActivationFailure.UNREACHABLE), first.last())
        assertTrue((first.last() as ActivationPhase.Failed).retryable)

        // The network comes back.
        validator.answer = usable()
        val second = activate.activate(m3u, nowMs = t0).toList()

        assertEquals(2, validator.calls)
        assertEquals(1, importer.started)
        assertTrue("${second.last()}", second.last() is ActivationPhase.Succeeded)
        assertEquals("m3u-1", sources.activeId)
    }

    @Test
    fun `activating twice does not register the provider twice`() = runTest {
        val sources = Sources()
        val activate = activateProvider(
            Validator(usable()),
            importOf(ImportProgress.Done(100, 100)),
            sources,
        )

        activate.activate(m3u, nowMs = t0).toList()
        activate.activate(m3u, nowMs = t0).toList()

        assertEquals(1, sources.stored.size)
    }

}
