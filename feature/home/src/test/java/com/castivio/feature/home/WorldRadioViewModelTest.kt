package com.castivio.feature.home

import com.castivio.core.common.AppDispatchers
import com.castivio.core.common.PlayOutcome
import com.castivio.domain.DirectoryFailure
import com.castivio.domain.DirectoryResult
import com.castivio.domain.WorldCountry
import com.castivio.domain.WorldListing
import com.castivio.domain.WorldTag
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
import java.util.Locale

/**
 * The world radio's board, without a screen: which list is open and what was typed, what is asked of the directory for it, the rail it
 * draws, and what listening writes down.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorldRadioViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val directory = FakeWorldDirectory(
        countries = DirectoryResult.Fresh(listOf(WorldCountry("MA", "Morocco", 105), WorldCountry("FR", "France", 640))),
        tags = DirectoryResult.Fresh(listOf(WorldTag("news", 310), WorldTag("pop", 900))),
    )
    private val library = MemoryWorldLibrary()
    private val medi = worldStation("m1", name = "Medi 1 Radio")
    private val hit = worldStation("h1", name = "Hit Radio")

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val dispatchers = object : AppDispatchers {
        override val main: CoroutineDispatcher get() = dispatcher
        override val io: CoroutineDispatcher get() = dispatcher
        override val default: CoroutineDispatcher get() = dispatcher
    }

    private fun board(): WorldRadioViewModel = WorldRadioViewModel(directory, library, dispatchers).apply {
        locale = Locale.forLanguageTag("en-US")
        clock = { 2_000_000L }
    }

    /** Keeps [flow] collected for the test's length, so a state flow that shares only while watched is watched. */
    private fun <T> TestScope.watch(flow: kotlinx.coroutines.flow.Flow<T>): MutableList<T> {
        val seen = mutableListOf<T>()
        backgroundScope.launch { flow.collect { seen += it } }
        runCurrent()
        return seen
    }

    // ------------------------------------------------------------------------------------ the open list

    @Test
    fun `the radio opens on the top of the world`() = runTest(dispatcher) {
        val vm = board()
        val asked = watch(vm.listing)

        assertEquals(WorldChoice.Top, vm.choice.value)
        assertEquals(listOf<WorldListing?>(WorldListing.Top), asked)
    }

    @Test
    fun `a country and a genre are asked for by what they are`() = runTest(dispatcher) {
        val vm = board()
        val asked = watch(vm.listing)

        vm.choose(WorldChoice.Country("MA")); runCurrent()
        vm.choose(WorldChoice.Tag("news")); runCurrent()

        assertEquals(listOf<WorldListing?>(WorldListing.Top, WorldListing.OfCountry("MA"), WorldListing.OfTag("news")), asked)
    }

    @Test
    fun `the viewer's own lists are not the directory's`() = runTest(dispatcher) {
        val vm = board()
        val asked = watch(vm.listing)

        vm.choose(WorldChoice.Favorites); runCurrent()
        vm.choose(WorldChoice.Recents); runCurrent()

        assertEquals(listOf<WorldListing?>(WorldListing.Top, null), asked.distinct())
    }

    @Test
    fun `typing searches the directory for the word, once, where the viewer pauses`() = runTest(dispatcher) {
        val vm = board()
        val asked = watch(vm.listing)

        vm.search("m"); advanceTimeBy(50)
        vm.search("me"); advanceTimeBy(50)
        vm.search("med"); advanceTimeBy(119)
        runCurrent()
        assertEquals("nothing is asked before the pause", listOf<WorldListing?>(WorldListing.Top), asked)

        advanceTimeBy(2); runCurrent()
        assertEquals(listOf<WorldListing?>(WorldListing.Top, WorldListing.Search("med")), asked)
    }

    @Test
    fun `the field keeps exactly what was typed, at once`() = runTest(dispatcher) {
        val vm = board()

        vm.search("  Med ")

        assertEquals("  Med ", vm.typed.value)
    }

    @Test
    fun `capitals count as typed, spaces around the word do not`() = runTest(dispatcher) {
        val vm = board()
        val asked = watch(vm.listing)

        vm.search("  Medi  "); advanceTimeBy(200); runCurrent()

        assertEquals(WorldListing.Search("Medi"), asked.last())
    }

    @Test
    fun `emptying the field is the list that was open before, at once`() = runTest(dispatcher) {
        val vm = board()
        val asked = watch(vm.listing)
        vm.choose(WorldChoice.Country("MA")); runCurrent()
        vm.search("med"); advanceTimeBy(200); runCurrent()

        vm.search(""); runCurrent()

        assertEquals(WorldListing.OfCountry("MA"), asked.last())
    }

    @Test
    fun `choosing a list clears a search and does not ask for the old search on the way`() = runTest(dispatcher) {
        val vm = board()
        val asked = watch(vm.listing)
        vm.search("med"); advanceTimeBy(200); runCurrent()

        vm.choose(WorldChoice.Country("FR")); runCurrent()

        assertEquals("", vm.typed.value)
        assertEquals(WorldListing.OfCountry("FR"), asked.last())
        assertEquals("the search was asked once, and not again with the new list", 1, asked.count { it is WorldListing.Search })
    }

    // ------------------------------------------------------------------------------------ the rail

    @Test
    fun `the rail lists the countries in the reader's order with their counts`() = runTest(dispatcher) {
        val vm = board()
        val rails = watch(vm.rail)
        runCurrent()

        val ready = rails.last().load as RailLoad.Ready
        assertEquals(listOf("France", "Morocco"), ready.entries.map { it.label })
        assertEquals(listOf(640, 105), ready.entries.map { it.count })
        assertFalse(ready.fromSavedList)
    }

    @Test
    fun `the field narrows the rail, and the genres replace the countries when asked for`() = runTest(dispatcher) {
        val vm = board()
        val rails = watch(vm.rail)
        runCurrent()

        vm.filterRail("moro"); runCurrent()
        assertEquals(listOf("Morocco"), (rails.last().load as RailLoad.Ready).entries.map { it.label })

        vm.showTags(true); runCurrent()
        val tags = rails.last()
        assertTrue(tags.tags)
        assertEquals("what was typed over the countries is not a filter of the genres", "", tags.filter)
        assertEquals(listOf("News", "Pop"), (tags.load as RailLoad.Ready).entries.map { it.label })

        vm.showTags(false); runCurrent()
        assertFalse(rails.last().tags)
    }

    @Test
    fun `a rail the servers could not give is the saved one, said to be`() = runTest(dispatcher) {
        directory.countries = DirectoryResult.Stale(listOf(WorldCountry("MA", "Morocco", 105)), ageMs = 3 * 86_400_000L)
        val vm = board()
        val rails = watch(vm.rail)
        runCurrent()

        val ready = rails.last().load as RailLoad.Ready
        assertTrue(ready.fromSavedList)
        assertEquals(listOf("Morocco"), ready.entries.map { it.label })
    }

    @Test
    fun `a rail nobody could give says why, and asking again asks for fresh`() = runTest(dispatcher) {
        directory.countries = DirectoryResult.Unavailable(DirectoryFailure.OFFLINE)
        val vm = board()
        val rails = watch(vm.rail)
        runCurrent()
        assertEquals(RailLoad.Unavailable(DirectoryFailure.OFFLINE), rails.last().load)

        directory.countries = DirectoryResult.Fresh(listOf(WorldCountry("MA", "Morocco", 105)))
        vm.retryRail(); runCurrent()

        assertTrue(rails.last().load is RailLoad.Ready)
        assertEquals("the first ask used the saved copy, the second asked past it", listOf(false, true), directory.countryAsks)
    }

    // ------------------------------------------------------------------------------------ what is kept

    @Test
    fun `a favourite is kept, and pressed again is let go`() = runTest(dispatcher) {
        val vm = board()
        val ids = watch(vm.favoriteIds)

        vm.toggleFavorite(medi); runCurrent()
        assertEquals(setOf("m1"), ids.last())

        vm.toggleFavorite(medi); runCurrent()
        assertEquals(emptySet<String>(), ids.last())
    }

    @Test
    fun `a station that rests is a favourite still, and dimmed`() = runTest(dispatcher) {
        library.keep(medi, hit)
        library.recordsByUuid["h1"] = com.castivio.domain.StationRecord("h1", lastFailedAtMs = 1_999_000L, consecutiveFailures = 1)
        val vm = board()
        val kept = watch(vm.favorites)
        runCurrent()

        val saved = kept.last()!!
        assertEquals(listOf("m1", "h1"), saved.stations.map { it.uuid })
        assertEquals(setOf("h1"), saved.dimmed)
    }

    @Test
    fun `a station that sounded is the latest of the recents, and the directory is told`() = runTest(dispatcher) {
        val vm = board()
        val recent = watch(vm.recents)

        vm.listened(hit); runCurrent()
        vm.listened(medi); runCurrent()

        assertEquals(listOf("m1", "h1"), recent.last()!!.stations.map { it.uuid })
        assertEquals(listOf("h1", "m1"), directory.listens)
    }

    @Test
    fun `what a listen proved is written down`() = runTest(dispatcher) {
        val vm = board()

        vm.heard(medi, PlayOutcome.CONFIRMED)
        vm.heard(hit, PlayOutcome.UNSTABLE)
        vm.heard(medi, PlayOutcome.INCONCLUSIVE)
        runCurrent()

        assertEquals(
            listOf("m1" to PlayOutcome.CONFIRMED, "h1" to PlayOutcome.UNSTABLE, "m1" to PlayOutcome.INCONCLUSIVE),
            library.outcomes,
        )
    }

    @Test
    fun `a failure is the station's when it fails alone, and the network's when several fail together`() = runTest(dispatcher) {
        val vm = board()

        vm.heard(worldStation("a"), PlayOutcome.FAILED)
        vm.heard(worldStation("b"), PlayOutcome.FAILED)
        vm.heard(worldStation("c"), PlayOutcome.FAILED)
        runCurrent()

        assertEquals("the third in the same minutes is the network, held against nobody", listOf("a", "b"), library.outcomes.map { it.first })
    }

    @Test
    fun `the favourites are refreshed from the directory once a visit`() = runTest(dispatcher) {
        library.keep(medi)
        directory.byUuid = listOf(medi.copy(name = "Medi 1 (new name)"))
        val vm = board()

        vm.choose(WorldChoice.Favorites); runCurrent()
        vm.choose(WorldChoice.Top); runCurrent()
        vm.choose(WorldChoice.Favorites); runCurrent()

        assertEquals(listOf(listOf("m1")), directory.uuidAsks)
        assertEquals(listOf("m1"), library.refreshed)
    }

    @Test
    fun `nothing is asked for the favourites of a viewer who has none`() = runTest(dispatcher) {
        val vm = board()

        vm.choose(WorldChoice.Favorites); runCurrent()

        assertTrue(directory.uuidAsks.isEmpty())
    }
}
