package com.castivio.feature.home

import com.castivio.domain.FavoritesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A favourite is shown the moment it is marked, whether or not the store has said so yet -- the rows'
 * stars and the button are drawn from the same set, and on a device the store's answer came minutes
 * after the press.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoriteAtOnceTest {

    // ------------------------------------------------------------------------------- the rules

    @Test
    fun `no changes is the stored set as it is`() {
        val stored = setOf("a", "b")
        assertEquals(stored, stored.withChanges(emptyMap()))
    }

    @Test
    fun `a change marks or unmarks on top of the stored set`() {
        assertEquals(setOf("a", "b", "c"), setOf("a", "b").withChanges(mapOf("c" to true)))
        assertEquals(setOf("b"), setOf("a", "b").withChanges(mapOf("a" to false)))
        assertEquals(setOf("b", "c"), setOf("a", "b").withChanges(mapOf("a" to false, "c" to true)))
    }

    @Test
    fun `a change the store already agrees with is settled`() {
        val changes = mapOf("a" to true, "b" to false, "c" to true)

        assertEquals(mapOf("c" to true), changes.unsettledBy(stored = setOf("a")))
        assertEquals(emptyMap<String, Boolean>(), changes.unsettledBy(stored = setOf("a", "c")))
    }

    // ------------------------------------------------------------------------------- the holder

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** A store whose writes land at once but whose list only says so when [publish] is called. */
    private class SlowStore(initial: Set<String> = emptySet()) : FavoritesRepository {
        val saved = initial.toMutableSet()
        val listed = MutableStateFlow(initial)
        var failWrites = false

        fun publish() {
            listed.value = saved.toSet()
        }

        override fun isFavorite(mediaId: String): Flow<Boolean> = flowOf(mediaId in saved)
        override suspend fun toggle(mediaId: String): Boolean = error("not asked")
        override fun count(): Flow<Int> = flowOf(saved.size)
        override fun ids(): Flow<Set<String>> = listed

        override suspend fun set(mediaIds: Collection<String>, favorite: Boolean) {
            if (failWrites) error("disk full")
            if (favorite) saved += mediaIds else saved -= mediaIds.toSet()
        }
    }

    private fun kotlinx.coroutines.test.TestScope.watched(store: SlowStore): ChannelFavoritesViewModel {
        val model = ChannelFavoritesViewModel(store)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.ids.collect { } }
        runCurrent()
        return model
    }

    @Test
    fun `the button's press shows the star before the store says so`() = runTest(dispatcher) {
        val store = SlowStore()
        val model = watched(store)

        model.flip("c1"); runCurrent()

        assertTrue("c1" in model.ids.value)
        assertEquals(setOf("c1"), store.saved)
        assertEquals("the store has not said so yet", emptySet<String>(), store.listed.value)
    }

    @Test
    fun `the button pressed on a favourite takes the star away at once`() = runTest(dispatcher) {
        val store = SlowStore(initial = setOf("c1", "c2"))
        val model = watched(store)

        model.flip("c1"); runCurrent()

        assertEquals(setOf("c2"), model.ids.value)
    }

    @Test
    fun `when the store catches up nothing changes on screen`() = runTest(dispatcher) {
        val store = SlowStore()
        val model = watched(store)
        model.flip("c1"); runCurrent()

        store.publish(); runCurrent()

        assertEquals(setOf("c1"), model.ids.value)
    }

    @Test
    fun `a write that fails takes the star back`() = runTest(dispatcher) {
        val store = SlowStore().apply { failWrites = true }
        val model = watched(store)

        model.flip("c1"); runCurrent()

        assertFalse("c1" in model.ids.value)
    }

    @Test
    fun `picked channels are stars the moment they are added`() = runTest(dispatcher) {
        val store = SlowStore()
        val model = watched(store)
        model.begin()
        model.toggle("c1")
        model.toggle("c2")

        model.apply(); runCurrent()

        assertEquals(setOf("c1", "c2"), model.ids.value)
        assertEquals(setOf("c1", "c2"), store.saved)
    }

    @Test
    fun `picks that all are favourites are unmarked at once`() = runTest(dispatcher) {
        val store = SlowStore(initial = setOf("c1", "c2", "c3"))
        val model = watched(store)
        model.begin()
        model.toggle("c1")
        model.toggle("c2")

        model.apply(); runCurrent()

        assertEquals(setOf("c3"), model.ids.value)
    }
}
