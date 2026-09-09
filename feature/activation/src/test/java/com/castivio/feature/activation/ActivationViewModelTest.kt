package com.castivio.feature.activation

import com.castivio.core.common.AppError
import com.castivio.core.common.Outcome
import com.castivio.domain.CatalogImporter
import com.castivio.domain.ImportProgress
import com.castivio.domain.PlaylistSource
import com.castivio.domain.ProviderSource
import com.castivio.domain.ProviderStatus
import com.castivio.domain.ProviderValidator
import com.castivio.domain.SourceKind
import com.castivio.domain.SourceRepository
import com.castivio.domain.SyncState
import com.castivio.domain.activation.ActivateProvider
import com.castivio.domain.activation.ActivationFailure
import com.castivio.domain.activation.ActivationForm
import com.castivio.domain.activation.ActivationPhase
import com.castivio.domain.time.TimeAnchorSource
import com.castivio.domain.time.TimeReading
import com.castivio.domain.time.TimeTrust
import com.castivio.domain.time.TrustedTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The thin part: typing, starting, stopping.
 *
 * Everything the sequence *decides* is proved in `ActivateProviderTest` without a
 * device. What is left to prove here is the part a view model is actually for — that
 * the running job belongs to the screen, that leaving cancels it, and that a repeated
 * keypress on a remote cannot start two checks of the same provider.
 *
 * Five of these asserted an import until sections became something the app fetches
 * when they are opened. They were not deleted for being inconvenient: each one still
 * describes a real guarantee, and each is rewritten against the step that guarantee
 * now hangs off — the provider check rather than the download. What the download does
 * is still asserted, in `ActivateProviderTest`, over the path that still performs one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivationViewModelTest {

    private val t0 = 1_772_323_200_000L

    // Installed as Main, which is what `viewModelScope` runs on. Built with no
    // scheduler on purpose: setMain makes runTest adopt this one, so the view model's
    // coroutines and the test share a clock.
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // --------------------------------------------------------------- the harness

    private class Sources : SourceRepository {
        val stored = linkedMapOf<String, ProviderSource>()
        var activeId: String? = null

        override suspend fun register(source: PlaylistSource, label: String?): ProviderSource =
            stored.getOrPut("src-1") {
                ProviderSource(id = "src-1", kind = SourceKind.XTREAM, label = label ?: "New", url = null)
            }

        override fun sources(): Flow<List<ProviderSource>> = throw UnsupportedOperationException()
        override fun active(): Flow<ProviderSource?> = throw UnsupportedOperationException()
        override suspend fun activeNow(): ProviderSource? = activeId?.let(stored::get)
        override suspend fun get(id: String): ProviderSource? = stored[id]
        override suspend fun save(source: ProviderSource) { stored[source.id] = source }
        override suspend fun setActive(id: String) { activeId = id }
        override suspend fun recordCatalogueImport(id: String, sync: SyncState) {
            stored[id] = stored.getValue(id).copy(sync = sync)
        }
        override suspend fun recordEpgImport(id: String, atMs: Long) = Unit
        override suspend fun delete(id: String) { stored.remove(id) }
    }

    private class Validator(
        private val answer: Outcome<ProviderStatus> = Outcome.Success(ProviderStatus(usable = true)),
    ) : ProviderValidator {
        override suspend fun validate(source: PlaylistSource): Outcome<ProviderStatus> = answer
    }

    private class Importer(private val script: List<ImportProgress>) : CatalogImporter {
        var starts = 0
        override fun import(source: PlaylistSource): Flow<ImportProgress> = flow {
            starts++
            script.forEach { emit(it) }
        }

        override suspend fun isUpToDate(source: PlaylistSource): Boolean = false
    }

    /**
     * A check that starts and never finishes.
     *
     * The stall moved here from the importer, and the move is the whole change in
     * this file. Activation no longer downloads anything, so "while it is busy" now
     * means "while the provider is being asked" — and a test that stalled the
     * importer to observe busy would be observing a step the screen no longer runs.
     */
    private class StallingValidator : ProviderValidator {
        var calls = 0
        val reached = CompletableDeferred<Unit>()
        private val never = CompletableDeferred<Unit>()

        override suspend fun validate(source: PlaylistSource): Outcome<ProviderStatus> {
            calls++
            reached.complete(Unit)
            never.await()
            return Outcome.Success(ProviderStatus(usable = true))
        }
    }

    /** Unreachable once, then fine. What a retry is actually for. */
    private class FlakyValidator : ProviderValidator {
        var calls = 0

        override suspend fun validate(source: PlaylistSource): Outcome<ProviderStatus> {
            calls++
            return if (calls == 1) {
                Outcome.Failure(AppError.NETWORK_UNAVAILABLE)
            } else {
                Outcome.Success(ProviderStatus(usable = true))
            }
        }
    }

    private class FixedClock(private val nowMs: Long) : TrustedTime {
        override fun now() = TimeReading(nowMs, TimeTrust.NETWORK)
        override fun anchor(epochMs: Long, source: TimeAnchorSource) = now()
    }

    private fun viewModel(
        importer: CatalogImporter = Importer(listOf(ImportProgress.Done(21_874, 9_000))),
        validator: ProviderValidator = Validator(),
        sources: Sources = Sources(),
    ) = ActivationViewModel(ActivateProvider(validator, importer, sources), FixedClock(t0))

    private fun ActivationViewModel.fillXtream() {
        serverUrl("line.example.com:8080")
        username("bob")
        password("hunter2")
    }

    // ---------------------------------------------------------------- the form

    @Test
    fun `the form starts empty and unsubmittable`() {
        val model = viewModel()

        assertTrue(model.state.value.form is ActivationForm.Xtream)
        assertFalse(model.state.value.canSubmit)
        assertEquals(ActivationPhase.Editing, model.state.value.phase)
    }

    @Test
    fun `typing makes the form submittable`() {
        val model = viewModel()

        model.fillXtream()

        assertTrue(model.state.value.canSubmit)
    }

    @Test
    fun `switching to a playlist url replaces the form`() {
        val model = viewModel()
        model.fillXtream()

        model.usePlaylistUrl()

        assertTrue(model.state.value.form is ActivationForm.Playlist)
        assertFalse(model.state.value.canSubmit)
    }

    /** The Slice 3 detection, finally reaching a user: an offer they accept, not a rewrite. */
    @Test
    fun `accepting a detected xtream link fills the xtream form`() {
        val model = viewModel()
        model.usePlaylistUrl()
        model.name("Home")
        model.playlistUrl("http://line.example.com:8080/get.php?username=bob&password=hunter2")

        model.acceptDetectedXtream()

        assertEquals(
            ActivationForm.Xtream("Home", "http://line.example.com:8080", "bob", "hunter2"),
            model.state.value.form,
        )
        assertTrue(model.state.value.canSubmit)
    }

    @Test
    fun `accepting nothing changes nothing`() {
        val model = viewModel()
        model.usePlaylistUrl()
        model.playlistUrl("http://line.example.com/playlist.m3u")

        model.acceptDetectedXtream()

        assertTrue(model.state.value.form is ActivationForm.Playlist)
    }

    // --------------------------------------------------------------- submitting

    /**
     * What activating means now: the credentials work, the provider is saved, and the
     * app opens.
     *
     * This asserted an item count until sections became something the app fetches when
     * they are opened. Downloading a provider's whole catalogue behind one progress bar
     * made everybody wait for the parts they do not use — 180,000 films for someone who
     * only watches television — so the wait moved to the sections, and the count this
     * used to assert is not a thing activation produces any more.
     *
     * The count is still asserted, in `ActivateProviderTest`, over the path that still
     * imports. Nothing about that path changed; what changed is which one this screen
     * asks for.
     */
    @Test
    fun `submitting checks the provider, saves it, and downloads nothing`() = runTest {
        val importer = Importer(listOf(ImportProgress.Done(21_874, 9_000)))
        val sources = Sources()
        val model = viewModel(importer, sources = sources)
        model.fillXtream()

        model.submit()
        advanceUntilIdle()

        val phase = model.state.value.phase
        assertTrue("$phase", phase is ActivationPhase.Succeeded)
        assertEquals("src-1", sources.activeId)
        assertEquals("activation started an import", 0, importer.starts)
    }

    @Test
    fun `an incomplete form does not start anything`() = runTest {
        val importer = Importer(listOf(ImportProgress.Done(1, 1)))
        val model = viewModel(importer)

        model.submit()
        advanceUntilIdle()

        assertEquals(0, importer.starts)
        assertEquals(ActivationPhase.Editing, model.state.value.phase)
    }

    /**
     * A television remote repeats a keypress more readily than a finger does, and two
     * imports of the same provider racing would interleave writes to the same rows.
     */
    @Test
    fun `pressing submit twice asks the provider once`() = runTest {
        val validator = StallingValidator()
        val model = viewModel(validator = validator)
        model.fillXtream()

        model.submit()
        validator.reached.await()
        model.submit()
        model.submit()
        advanceUntilIdle()

        assertEquals(1, validator.calls)
        model.cancel()
    }

    @Test
    fun `the fields are frozen while the check runs`() = runTest {
        val validator = StallingValidator()
        val model = viewModel(validator = validator)
        model.fillXtream()

        model.submit()
        validator.reached.await()
        model.username("someone-else")

        assertEquals("bob", (model.state.value.form as ActivationForm.Xtream).username)
        assertTrue(model.state.value.busy)
        model.cancel()
    }

    // -------------------------------------------------------------- cancellation

    /**
     * Leaving the screen is not tested here, and deliberately has no code behind it:
     * the import runs in `viewModelScope`, which the framework cancels when the view
     * model is cleared. Clearing one is `internal` to the library, so a test that
     * reached for it would be testing a stub rather than the guarantee.
     */
    @Test
    fun `cancelling stops the check and returns to the form`() = runTest {
        val validator = StallingValidator()
        val sources = Sources()
        val model = viewModel(validator = validator, sources = sources)
        model.fillXtream()

        model.submit()
        validator.reached.await()
        assertEquals(ActivationPhase.Checking, model.state.value.phase)

        model.cancel()
        advanceUntilIdle()

        assertEquals(ActivationPhase.Editing, model.state.value.phase)
        assertNull(sources.activeId)
        // The text the user typed is still there for them to try again.
        assertEquals("bob", (model.state.value.form as ActivationForm.Xtream).username)
    }

    // ------------------------------------------------------------------ retrying

    /**
     * The failure a retry is for, and it comes from the check now.
     *
     * It used to be scripted into the importer, which activation no longer runs — so
     * the same scenario is the same sentence with the unreachable host moved one step
     * earlier: the provider does not answer, the user presses again, it does.
     */
    @Test
    fun `a transient failure can be retried and can succeed`() = runTest {
        val validator = FlakyValidator()
        val model = viewModel(validator = validator)
        model.fillXtream()

        model.submit()
        advanceUntilIdle()
        assertEquals(
            ActivationPhase.Failed(ActivationFailure.UNREACHABLE),
            model.state.value.phase,
        )

        model.retry()
        advanceUntilIdle()

        assertEquals(2, validator.calls)
        assertTrue("${model.state.value.phase}", model.state.value.phase is ActivationPhase.Succeeded)
    }

    @Test
    fun `a failure that cannot be retried is not retried`() = runTest {
        val model = viewModel(
            validator = Validator(Outcome.Failure(AppError.UNAUTHORIZED)),
        )
        model.fillXtream()

        model.submit()
        advanceUntilIdle()
        val failed = model.state.value.phase as ActivationPhase.Failed
        assertFalse(failed.retryable)

        model.retry()
        advanceUntilIdle()

        assertEquals(failed, model.state.value.phase)
    }

    @Test
    fun `dismissing a failure returns to the form with the text intact`() = runTest {
        val model = viewModel(validator = Validator(Outcome.Failure(AppError.UNAUTHORIZED)))
        model.fillXtream()
        model.submit()
        advanceUntilIdle()

        model.dismissFailure()

        assertEquals(ActivationPhase.Editing, model.state.value.phase)
        assertEquals("hunter2", (model.state.value.form as ActivationForm.Xtream).password)
        assertTrue(model.state.value.canSubmit)
    }

    @Test
    fun `editing after a failure clears it`() = runTest {
        val model = viewModel(validator = Validator(Outcome.Failure(AppError.UNAUTHORIZED)))
        model.fillXtream()
        model.submit()
        advanceUntilIdle()

        model.usePlaylistUrl()

        assertEquals(ActivationPhase.Editing, model.state.value.phase)
    }
}
