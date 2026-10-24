package com.castivio.feature.home

import com.castivio.domain.Channel
import com.castivio.domain.HiddenItems
import com.castivio.domain.HiddenKind
import com.castivio.domain.HiddenNow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
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
 * What is hidden leaves the board on the press: the rail's bouquets and the list's rows are filtered by
 * what was just hidden, before the store has been written or read again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HideAtOnceTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Hiding that the store has not answered yet: only [recent] moves. */
    private class Unanswered : HiddenItems {
        val now = MutableStateFlow(HiddenNow())
        override val recent: StateFlow<HiddenNow> = now
        override fun count(kind: HiddenKind): Flow<Int> = flowOf(0)
        override suspend fun hide(kind: HiddenKind, ids: Collection<String>) {
            now.value = now.value.with(kind, ids)
        }
        override suspend fun restoreAll(kind: HiddenKind) {
            now.value = now.value.cleared(kind)
        }
    }

    private fun channel(id: String, group: String?) = Channel(
        id = id,
        title = id,
        artworkUrl = null,
        number = null,
        groupId = group,
        streamUrl = "http://provider.tv/live/$id.ts",
        epgChannelId = null,
    )

    // ------------------------------------------------------------------------------- the rows

    @Test
    fun `a channel hidden by itself is left out, and its neighbours are not`() {
        val now = HiddenNow().with(HiddenKind.CHANNEL, listOf("c1"))

        assertTrue(now.hides(channel("c1", "sports")))
        assertFalse(now.hides(channel("c2", "sports")))
    }

    @Test
    fun `every channel of a hidden bouquet is left out`() {
        val now = HiddenNow().with(HiddenKind.BOUQUET, listOf("sports"))

        assertTrue(now.hides(channel("c1", "sports")))
        assertTrue(now.hides(channel("c2", "sports")))
        assertFalse(now.hides(channel("c3", "news")))
    }

    // ------------------------------------------------------------------------------- the rail

    @Test
    fun `a bouquet hidden from the board leaves the rail before the store answers`() = runTest(dispatcher) {
        val hidden = Unanswered()
        val board = browseBoard(hidden = hidden)
        assertEquals(listOf("sports", "news", "kids"), board.bouquets)

        hidden.hide(HiddenKind.BOUQUET, listOf("news")); runCurrent()

        assertEquals(listOf("sports", "kids"), board.bouquets)
    }

    @Test
    fun `a hidden channel leaves every bouquet in the rail`() = runTest(dispatcher) {
        val hidden = Unanswered()
        val board = browseBoard(hidden = hidden)

        hidden.hide(HiddenKind.CHANNEL, listOf("c1")); runCurrent()

        assertEquals(listOf("sports", "news", "kids"), board.bouquets)
    }

    @Test
    fun `the open bouquet hidden sends the board back to all of its channels`() = runTest(dispatcher) {
        val hidden = Unanswered()
        val board = browseBoard(hidden = hidden)
        board.model.choose("news"); runCurrent()

        hidden.hide(HiddenKind.BOUQUET, listOf("news")); runCurrent()

        assertNull(board.state.selectedGroup)
    }

    @Test
    fun `bouquets brought back from Settings return to the rail`() = runTest(dispatcher) {
        val hidden = Unanswered()
        val board = browseBoard(hidden = hidden)
        hidden.hide(HiddenKind.BOUQUET, listOf("news")); runCurrent()

        hidden.restoreAll(HiddenKind.BOUQUET); runCurrent()

        assertEquals(listOf("sports", "news", "kids"), board.bouquets)
    }
}
