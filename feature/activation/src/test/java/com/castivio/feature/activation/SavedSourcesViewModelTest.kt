package com.castivio.feature.activation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.castivio.domain.MediaKind
import com.castivio.domain.PlaylistSource
import com.castivio.domain.ProviderSource
import com.castivio.domain.ProviderStatus
import com.castivio.domain.ProviderStatusCatalogue
import com.castivio.domain.Recorded
import com.castivio.domain.RemoveSource
import com.castivio.domain.SectionCatalogue
import com.castivio.domain.SourceKind
import com.castivio.domain.SourceRepository
import com.castivio.domain.SyncState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
 * What a press of "Delete" does to the list of subscriptions, and what leaving the screen does not undo.
 *
 * On a device the row stayed on screen for seconds after "Delete", and only its expiry went; and the removal ran in
 * this holder's scope, so closing the app from its own exit cancelled it half-way. Here the repository does not
 * answer until a test lets it -- the write held behind another, as on the device -- so each test can look at the
 * screen while the removal is still running.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SavedSourcesViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a deleted subscription leaves the list at the press, before its removal has finished`() =
        runTest(dispatcher) {
            val sources = Sources(subscription("a", active = true), subscription("b"))
            val screen = screen(sources)

            screen.model.delete("a"); runCurrent()

            assertEquals(listOf("b"), screen.ready.saved.map { it.id })
            assertEquals("the tick moves to the one that takes its place", "b", screen.ready.activeId)
            assertTrue("while the repository is still removing it", sources.removing)
            assertFalse(sources.removed)
        }

    @Test
    fun `the only subscription deleted leaves no tick`() = runTest(dispatcher) {
        val sources = Sources(subscription("a", active = true))
        val screen = screen(sources)

        screen.model.delete("a"); runCurrent()

        assertTrue(screen.ready.isEmpty)
        assertNull(screen.ready.activeId)
    }

    @Test
    fun `deleting one leaves the others and the tick where they were`() = runTest(dispatcher) {
        val sources = Sources(subscription("a", active = true), subscription("b"))
        val screen = screen(sources)

        screen.model.delete("b"); runCurrent()

        assertEquals(listOf("a"), screen.ready.saved.map { it.id })
        assertEquals("a", screen.ready.activeId)
    }

    @Test
    fun `the removal finishes after the screen has gone`() = runTest(dispatcher) {
        val sources = Sources(subscription("a", active = true), subscription("b"))
        val store = ViewModelStore()
        val screen = screen(sources, store)
        screen.model.delete("a"); runCurrent()

        // What the app's own exit does to this holder: it ends, and its scope with it.
        store.clear()
        sources.answer(); runCurrent()

        assertTrue("cancelled, the removal was rolled back and the subscription stayed", sources.removed)
        assertEquals(listOf("b"), sources.saved.map { it.id })
    }

    @Test
    fun `a subscription added again after its removal is listed again`() = runTest(dispatcher) {
        val sources = Sources(subscription("a"), subscription("b"))
        val screen = screen(sources)
        screen.model.delete("a"); runCurrent()
        sources.answer(); runCurrent()

        sources.add(subscription("a")); runCurrent()

        assertEquals(listOf("b", "a"), screen.ready.saved.map { it.id })
    }

    // ------------------------------------------------------------------------------ scaffolding

    private val marks = Marks()
    private val statuses = Statuses()

    private class Screen(val model: SavedSourcesViewModel) {
        val states = mutableListOf<SavedSourcesState>()
        val ready: SavedSourcesState.Ready get() = states.last() as SavedSourcesState.Ready
    }

    private fun TestScope.screen(sources: Sources, store: ViewModelStore? = null): Screen {
        val build = { SavedSourcesViewModel(sources, statuses, RemoveSource(sources, marks, statuses)) }
        val model = if (store == null) {
            build()
        } else {
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = build() as T
            }
            ViewModelProvider(store, factory)["saved", SavedSourcesViewModel::class.java]
        }
        val screen = Screen(model)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect { screen.states += it } }
        runCurrent()
        return screen
    }

    private fun subscription(id: String, active: Boolean = false) = ProviderSource(
        id = id,
        kind = SourceKind.M3U_URL,
        label = id,
        url = "http://host/$id.m3u",
        isActive = active,
    )

    /** The saved subscriptions, whose removal answers only when [answer] is called. */
    private class Sources(vararg initial: ProviderSource) : SourceRepository {
        private val rows = MutableStateFlow(initial.toList())
        private val gate = CompletableDeferred<Unit>()

        val saved: List<ProviderSource> get() = rows.value
        var removing = false
            private set
        var removed = false
            private set

        /** The write ahead of the removal is done, and the removal goes through. */
        fun answer() {
            gate.complete(Unit)
        }

        fun add(source: ProviderSource) = rows.update { it + source }

        override fun sources(): Flow<List<ProviderSource>> = rows
        override fun active(): Flow<ProviderSource?> = rows.map { all -> all.firstOrNull { it.isActive } }
        override suspend fun delete(id: String) {
            removing = true
            gate.await()
            rows.update { all -> all.filterNot { it.id == id } }
            removed = true
        }

        override suspend fun register(source: PlaylistSource, label: String?): ProviderSource = error("not asked")
        override suspend fun activeNow(): ProviderSource? = rows.value.firstOrNull { it.isActive }
        override suspend fun get(id: String): ProviderSource? = rows.value.firstOrNull { it.id == id }
        override suspend fun save(source: ProviderSource) = error("not asked")
        override suspend fun setActive(id: String) = error("not asked")
        override suspend fun recordCatalogueImport(id: String, sync: SyncState) = error("not asked")
        override suspend fun recordEpgImport(id: String, atMs: Long) = error("not asked")
    }

    /** What else is kept about a subscription; what removing does to it is `RemoveSourceTest`'s. */
    private class Marks : SectionCatalogue {
        override suspend fun loadedAt(sourceId: String, kind: MediaKind): Long? = null
        override fun loaded(sourceId: String): Flow<Map<MediaKind, Long>> = flowOf(emptyMap())
        override suspend fun markLoaded(sourceId: String, kind: MediaKind, atMs: Long) = Unit
        override suspend fun forget(sourceId: String) = Unit
    }

    private class Statuses : ProviderStatusCatalogue {
        override suspend fun record(sourceId: String, status: ProviderStatus, atMs: Long) = Unit
        override fun of(sourceId: String): Flow<Recorded?> = flowOf(null)
        override suspend fun forget(sourceId: String) = Unit
    }
}
