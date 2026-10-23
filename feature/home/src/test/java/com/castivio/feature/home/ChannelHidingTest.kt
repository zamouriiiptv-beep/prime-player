package com.castivio.feature.home

import com.castivio.domain.HiddenItems
import com.castivio.domain.HiddenKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Choosing what to hide, as the holder runs it: bouquets and channels ticked in one mode, and Hide
 * selected writing each kind it has -- and only what it has.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelHidingTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Records each write as `kind:ids`, ids sorted. */
    private class FakeHidden : HiddenItems {
        val writes = mutableListOf<String>()
        var failOn: HiddenKind? = null

        override fun count(kind: HiddenKind): Flow<Int> = flowOf(0)

        override suspend fun hide(kind: HiddenKind, ids: Collection<String>) {
            if (kind == failOn) error("disk full")
            writes += "$kind:${ids.sorted().joinToString(",")}"
        }

        override suspend fun restoreAll(kind: HiddenKind) = Unit
    }

    @Test
    fun `beginning ticks nothing, of either kind`() = runTest(dispatcher) {
        val model = ChannelHidingViewModel(FakeHidden())

        model.begin()

        assertEquals(Hiding(), model.hiding.value)
    }

    @Test
    fun `bouquets and channels are ticked in the same mode, each in its own set`() = runTest(dispatcher) {
        val model = ChannelHidingViewModel(FakeHidden())
        model.begin()

        model.toggleBouquet("sports")
        model.toggleChannel("c1")
        model.toggleChannel("c2")
        model.toggleChannel("c1")

        assertEquals(Hiding(bouquets = setOf("sports"), channels = setOf("c2")), model.hiding.value)
    }

    @Test
    fun `a tick before the mode begins is nothing`() = runTest(dispatcher) {
        val model = ChannelHidingViewModel(FakeHidden())

        model.toggleChannel("c1")

        assertNull(model.hiding.value)
    }

    @Test
    fun `hide selected hides the ticked bouquets and the ticked channels, and leaves`() = runTest(dispatcher) {
        val hidden = FakeHidden()
        val model = ChannelHidingViewModel(hidden)
        model.begin()
        model.toggleBouquet("news")
        model.toggleChannel("c3")
        model.toggleChannel("c1")

        model.confirm(); runCurrent()

        assertEquals(listOf("BOUQUET:news", "CHANNEL:c1,c3"), hidden.writes)
        assertNull(model.hiding.value)
    }

    @Test
    fun `only channels ticked hides only channels`() = runTest(dispatcher) {
        val hidden = FakeHidden()
        val model = ChannelHidingViewModel(hidden)
        model.begin()
        model.toggleChannel("c1")

        model.confirm(); runCurrent()

        assertEquals(listOf("CHANNEL:c1"), hidden.writes)
    }

    @Test
    fun `nothing ticked leaves and writes nothing`() = runTest(dispatcher) {
        val hidden = FakeHidden()
        val model = ChannelHidingViewModel(hidden)
        model.begin()

        model.confirm(); runCurrent()

        assertEquals(emptyList<String>(), hidden.writes)
        assertNull(model.hiding.value)
    }

    @Test
    fun `cancel leaves and writes nothing`() = runTest(dispatcher) {
        val hidden = FakeHidden()
        val model = ChannelHidingViewModel(hidden)
        model.begin()
        model.toggleBouquet("news")
        model.toggleChannel("c1")

        model.cancel(); runCurrent()

        assertEquals(emptyList<String>(), hidden.writes)
        assertNull(model.hiding.value)
    }

    @Test
    fun `what could not be written stays ticked, and what was written does not`() = runTest(dispatcher) {
        val hidden = FakeHidden().apply { failOn = HiddenKind.CHANNEL }
        val model = ChannelHidingViewModel(hidden)
        model.begin()
        model.toggleBouquet("news")
        model.toggleChannel("c1")

        model.confirm(); runCurrent()

        assertEquals(listOf("BOUQUET:news"), hidden.writes)
        assertEquals(Hiding(channels = setOf("c1")), model.hiding.value)
    }
}
