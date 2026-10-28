package com.castivio.feature.home

import androidx.paging.PagingData
import com.castivio.domain.CatalogPager
import com.castivio.domain.CatalogQuery
import com.castivio.domain.Episode
import com.castivio.domain.EpisodesLoad
import com.castivio.domain.FavoritesRepository
import com.castivio.domain.InProgressItem
import com.castivio.domain.MediaItem
import com.castivio.domain.PlaybackProgress
import com.castivio.domain.ProgressRepository
import com.castivio.domain.RequestTimes
import com.castivio.domain.Season
import com.castivio.domain.SeriesDetails
import com.castivio.domain.SeriesDetailsStore
import com.castivio.domain.SeriesEpisodes
import com.castivio.domain.SeriesShell
import com.castivio.domain.SeriesSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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
 * A show's page: what it says while the episodes are asked for, once they are held, and when the provider could not
 * be asked -- and where Play continues from.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShowViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun episode(season: Int, number: Int) = Episode(
        id = "s${season}e$number",
        title = "Episode $number",
        artworkUrl = null,
        streamUrl = "http://example.invalid/series/$season/$number.mkv",
        seasonNumber = season,
        episodeNumber = number,
    )

    private val held = listOf(Season(1, listOf(episode(1, 1), episode(1, 2))), Season(2, listOf(episode(2, 1))))

    private class FakePager(initial: List<Season>) : CatalogPager {
        val seasons = MutableStateFlow(initial)

        /** The seasons of a show that has a flow of its own: another show's page is not handed the same ones. */
        val byShow = mutableMapOf<String, Flow<List<Season>>>()
        override fun items(query: CatalogQuery): Flow<PagingData<MediaItem>> = emptyFlow()
        override fun series(query: CatalogQuery): Flow<PagingData<SeriesSummary>> = emptyFlow()
        override fun seasons(seriesId: String): Flow<List<Season>> = byShow[seriesId] ?: seasons
        override fun favorites(): Flow<PagingData<MediaItem>> = emptyFlow()
        override fun continueWatching(): Flow<PagingData<InProgressItem>> = emptyFlow()
        override fun history(): Flow<PagingData<InProgressItem>> = emptyFlow()
        override suspend fun seriesShell(seriesId: String): SeriesShell? =
            SeriesShell(sourceId = "src", providerRef = "551", title = "Dark Harbor", groupId = null, mediaId = "shell-1")
        var others: List<SeriesSummary> = emptyList()
        override suspend fun similarTo(seriesId: String, limit: Int): List<SeriesSummary> = others.take(limit)
    }

    /** What the provider said, as the store holds it: the test sets it and the page reads it as it is set. */
    private class FakeDetails : SeriesDetailsStore {
        val details = MutableStateFlow<SeriesDetails?>(null)
        val plots = MutableStateFlow<Map<String, String>>(emptyMap())
        override fun details(seriesId: String): Flow<SeriesDetails?> = details
        override fun episodePlots(seriesId: String): Flow<Map<String, String>> = plots
        override suspend fun put(seriesId: String, details: SeriesDetails?, plots: Map<String, String>, now: Long) = Unit
    }

    /** The marks, written at once and read as written. */
    private class FakeFavorites : FavoritesRepository {
        val marked = MutableStateFlow<Set<String>>(emptySet())
        val toggled = mutableListOf<String>()
        override fun isFavorite(mediaId: String): Flow<Boolean> = marked.map { mediaId in it }
        override suspend fun toggle(mediaId: String): Boolean {
            toggled += mediaId
            marked.value = if (mediaId in marked.value) marked.value - mediaId else marked.value + mediaId
            return mediaId in marked.value
        }
        override fun count(): Flow<Int> = marked.map { it.size }
        override fun ids(): Flow<Set<String>> = marked
        override suspend fun set(mediaIds: Collection<String>, favorite: Boolean) = Unit
    }

    /** Answers what the test says, when the test lets it: a show is asked for by its id, and every ask is kept. */
    private class FakeEpisodes : SeriesEpisodes {
        val asked = mutableListOf<String>()
        var answer = CompletableDeferred<EpisodesLoad>()
        override suspend fun load(seriesId: String): EpisodesLoad {
            asked += seriesId
            return answer.await()
        }
    }

    /** Answers one after another as it is asked, and the last one again after that. */
    private class SequencedEpisodes(private vararg val answers: EpisodesLoad) : SeriesEpisodes {
        val asked = mutableListOf<String>()
        override suspend fun load(seriesId: String): EpisodesLoad {
            asked += seriesId
            return answers[minOf(asked.size, answers.size) - 1]
        }
    }

    private class FakeProgress(val positions: Map<String, PlaybackProgress> = emptyMap()) : ProgressRepository {
        val watched = mutableListOf<String>()
        override suspend fun save(mediaId: String, positionMs: Long, durationMs: Long?) = Unit
        override suspend fun progress(mediaId: String): PlaybackProgress? = positions[mediaId]
        override suspend fun clear(mediaId: String) = Unit
        override suspend fun clearAll() = Unit
        override suspend fun watched(mediaId: String) {
            watched += mediaId
        }
    }

    private fun TestScope.model(
        pager: FakePager,
        episodes: SeriesEpisodes,
        progress: FakeProgress = FakeProgress(),
        details: FakeDetails = FakeDetails(),
        favorites: FakeFavorites = FakeFavorites(),
    ): ShowViewModel {
        val model = ShowViewModel(pager, episodes, progress, details, favorites)
        // The page is what collects the state, and the state is only live while someone does.
        backgroundScope.launch { model.state.collect { } }
        return model
    }

    @Test
    fun `a show with no episode held says it is loading until the provider answers`() = runTest(dispatcher) {
        val episodes = FakeEpisodes()
        val model = model(FakePager(emptyList()), episodes)

        model.open("show-1")
        advanceUntilIdle()
        assertEquals(ShowLoad.Loading, model.state.value.load)
        assertEquals(listOf("show-1"), episodes.asked)

        episodes.answer.complete(EpisodesLoad.Loaded(0))
        advanceUntilIdle()
        // The provider answered and lists none: the page says so, it does not go on loading.
        assertEquals(ShowLoad.Ready, model.state.value.load)
        assertEquals(emptyList<Season>(), model.state.value.seasons)
    }

    @Test
    fun `a failure with nothing held is shown, and asking again can end in the episodes`() = runTest(dispatcher) {
        val pager = FakePager(emptyList())
        val episodes = FakeEpisodes()
        val model = model(pager, episodes)

        model.open("show-1")
        episodes.answer.complete(EpisodesLoad.Failed(java.io.IOException("down")))
        advanceUntilIdle()
        assertEquals(ShowLoad.Failed, model.state.value.load)

        episodes.answer = CompletableDeferred()
        model.retry()
        advanceUntilIdle()
        assertEquals(ShowLoad.Loading, model.state.value.load)

        pager.seasons.value = held
        episodes.answer.complete(EpisodesLoad.Loaded(3))
        advanceUntilIdle()
        assertEquals(ShowLoad.Ready, model.state.value.load)
        assertEquals(2, model.state.value.seasons.size)
    }

    @Test
    fun `episodes already held are shown at once, and a failure behind them costs the page nothing`() = runTest(dispatcher) {
        val episodes = FakeEpisodes()
        val model = model(FakePager(held), episodes)

        model.open("show-1")
        advanceUntilIdle()
        // Still being asked of the provider, and the page has what it holds.
        assertEquals(ShowLoad.Ready, model.state.value.load)

        episodes.answer.complete(EpisodesLoad.Failed(java.io.IOException("down")))
        advanceUntilIdle()
        assertEquals(ShowLoad.Ready, model.state.value.load)
        assertEquals(2, model.state.value.seasons.size)
    }

    @Test
    fun `Play starts the first episode of a show not begun`() = runTest(dispatcher) {
        val model = model(FakePager(held), FakeEpisodes())

        model.open("show-1")
        advanceUntilIdle()

        assertEquals("s1e1", model.state.value.target?.episode?.id)
        assertEquals(false, model.state.value.target?.resume)
    }

    @Test
    fun `Play continues from the episode last started`() = runTest(dispatcher) {
        val started = PlaybackProgress("s1e2", positionMs = 0, durationMs = null, updatedAtEpochMs = 50)
        val model = model(FakePager(held), FakeEpisodes(), FakeProgress(mapOf("s1e2" to started)))

        model.open("show-1")
        advanceUntilIdle()

        assertEquals("s1e2", model.state.value.target?.episode?.id)
        assertEquals(true, model.state.value.target?.resume)
    }

    @Test
    fun `an episode started from the page is recorded as watched`() = runTest(dispatcher) {
        val progress = FakeProgress()
        val model = model(FakePager(held), FakeEpisodes(), progress)

        model.started("s2e1")
        advanceUntilIdle()

        assertEquals(listOf("s2e1"), progress.watched)
    }

    @Test
    fun `nothing is asked until a show is opened`() = runTest(dispatcher) {
        val episodes = FakeEpisodes()
        val model = model(FakePager(held), episodes)
        advanceUntilIdle()

        assertEquals(emptyList<String>(), episodes.asked)
        assertNull(model.state.value.target)
    }

    @Test
    fun `what the provider said of the show and of its episodes reaches the page as it is written`() = runTest(dispatcher) {
        val details = FakeDetails()
        val model = model(FakePager(held), FakeEpisodes(), details = details)
        model.open("show-1")
        advanceUntilIdle()
        assertNull(model.state.value.details)
        assertEquals(emptyMap<String, String>(), model.state.value.plots)

        details.details.value = SeriesDetails(plot = "A harbour town.", releaseYear = 2019)
        details.plots.value = mapOf("s1e1" to "A storm closes the port.")
        advanceUntilIdle()

        assertEquals("A harbour town.", model.state.value.details?.plot)
        assertEquals(2019, model.state.value.details?.releaseYear)
        assertEquals("A storm closes the port.", model.state.value.plots["s1e1"])
    }

    @Test
    fun `the heart follows the mark on the show's own row, and a press flips it`() = runTest(dispatcher) {
        val favorites = FakeFavorites()
        val model = model(FakePager(held), FakeEpisodes(), favorites = favorites)
        model.open("show-1")
        advanceUntilIdle()
        assertEquals(false, model.state.value.favorite)

        model.toggleFavorite()
        advanceUntilIdle()
        assertEquals(listOf("shell-1"), favorites.toggled)
        assertEquals(true, model.state.value.favorite)

        model.toggleFavorite()
        advanceUntilIdle()
        assertEquals(false, model.state.value.favorite)
    }

    @Test
    fun `a show already marked opens with its heart lit`() = runTest(dispatcher) {
        val favorites = FakeFavorites().apply { marked.value = setOf("shell-1") }
        val model = model(FakePager(held), FakeEpisodes(), favorites = favorites)

        model.open("show-1")
        advanceUntilIdle()

        assertEquals(true, model.state.value.favorite)
    }

    @Test
    fun `nothing is marked before a show is opened`() = runTest(dispatcher) {
        val favorites = FakeFavorites()
        val model = model(FakePager(held), FakeEpisodes(), favorites = favorites)

        model.toggleFavorite()
        advanceUntilIdle()

        assertEquals(emptyList<String>(), favorites.toggled)
    }

    @Test
    fun `other shows of the category are offered, and are not the show itself`() = runTest(dispatcher) {
        val pager = FakePager(held).apply {
            others = listOf(
                SeriesSummary("show-2", "Chernobyl", null, 5, 1),
                SeriesSummary("show-3", "Dark", null, 26, 3),
            )
        }
        val model = model(pager, FakeEpisodes())

        model.open("show-1")
        advanceUntilIdle()

        assertEquals(listOf("show-2", "show-3"), model.state.value.similar.map { it.seriesId })
    }

    @Test
    fun `the page is asking while the provider is, and not after it answers`() = runTest(dispatcher) {
        val episodes = FakeEpisodes()
        val model = model(FakePager(held), episodes)

        model.open("show-1")
        advanceUntilIdle()
        // The episodes are held, and the provider is still being asked behind them: what it says may yet come.
        assertEquals(ShowLoad.Ready, model.state.value.load)
        assertEquals(true, model.state.value.asking)

        episodes.answer.complete(EpisodesLoad.Loaded(3))
        advanceUntilIdle()
        assertEquals(false, model.state.value.asking)
    }

    @Test
    fun `a failure says why, and asking again clears it`() = runTest(dispatcher) {
        val episodes = FakeEpisodes()
        val model = model(FakePager(emptyList()), episodes)

        model.open("show-1")
        episodes.answer.complete(EpisodesLoad.Failed(java.io.IOException("HTTP 404")))
        advanceUntilIdle()
        assertEquals(ShowLoad.Failed, model.state.value.load)
        assertEquals("IOException: HTTP 404", model.state.value.failure)

        episodes.answer = CompletableDeferred()
        model.retry()
        advanceUntilIdle()
        assertNull(model.state.value.failure)
    }

    @Test
    fun `a show whose episodes loaded has no failure`() = runTest(dispatcher) {
        val episodes = FakeEpisodes()
        val model = model(FakePager(held), episodes)

        model.open("show-1")
        episodes.answer.complete(EpisodesLoad.Loaded(3))
        advanceUntilIdle()

        assertNull(model.state.value.failure)
    }

    // ---------------------------------------------------------- an empty answer is not believed at once

    @Test
    fun `an empty answer is asked for again after two, four and seven seconds, and believed the fourth time`() = runTest(dispatcher) {
        val episodes = SequencedEpisodes(EpisodesLoad.Loaded(0))
        val model = model(FakePager(emptyList()), episodes)

        model.open("show-1")
        runCurrent()
        assertEquals(1, episodes.asked.size)
        // Not "no episodes": the provider may not have built the show yet, and the page says it is being prepared.
        assertEquals(ShowLoad.Loading, model.state.value.load)
        assertEquals(true, model.state.value.preparing)

        advanceTimeBy(1_900)
        runCurrent()
        assertEquals(1, episodes.asked.size)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(2, episodes.asked.size)
        advanceTimeBy(4_100)
        runCurrent()
        assertEquals(3, episodes.asked.size)
        advanceTimeBy(7_100)
        runCurrent()

        assertEquals(4, episodes.asked.size)
        assertEquals(ShowLoad.Ready, model.state.value.load)
        assertEquals(false, model.state.value.preparing)
        assertEquals(emptyList<Season>(), model.state.value.seasons)
    }

    @Test
    fun `an empty answer followed by the episodes ends with the episodes, and nothing is asked again`() = runTest(dispatcher) {
        val pager = FakePager(emptyList())
        val episodes = SequencedEpisodes(EpisodesLoad.Loaded(0), EpisodesLoad.Loaded(3, RequestTimes(askMs = 900, readMs = 100)))
        val model = model(pager, episodes)

        model.open("show-1")
        runCurrent()
        assertEquals(true, model.state.value.preparing)

        // What the provider built is written by the second asking, and the page reads it as it is written.
        pager.seasons.value = held
        advanceTimeBy(2_100)
        runCurrent()

        assertEquals(2, episodes.asked.size)
        assertEquals(ShowLoad.Ready, model.state.value.load)
        assertEquals(false, model.state.value.preparing)
        assertEquals(2, model.state.value.seasons.size)
        advanceUntilIdle()
        assertEquals(2, episodes.asked.size)
        // The figures say which asking they are of: the second.
        assertEquals(2, model.state.value.times?.tries)
    }

    @Test
    fun `an empty answer behind episodes already held is not asked for again`() = runTest(dispatcher) {
        val episodes = SequencedEpisodes(EpisodesLoad.Loaded(0))
        val model = model(FakePager(held), episodes)

        model.open("show-1")
        advanceUntilIdle()

        assertEquals(1, episodes.asked.size)
        assertEquals(ShowLoad.Ready, model.state.value.load)
        assertEquals(false, model.state.value.preparing)
    }

    @Test
    fun `opening another show ends the waiting for the first`() = runTest(dispatcher) {
        val episodes = SequencedEpisodes(EpisodesLoad.Loaded(0))
        val model = model(FakePager(emptyList()), episodes)

        model.open("show-1")
        runCurrent()
        model.open("show-2")
        advanceUntilIdle()

        // Once for the first show, which the page left; the second is asked for to the end.
        assertEquals(1, episodes.asked.count { it == "show-1" })
        assertEquals(4, episodes.asked.count { it == "show-2" })
    }

    @Test
    fun `a show that ended empty is asked for again by a press, and by opening it again`() = runTest(dispatcher) {
        val episodes = SequencedEpisodes(EpisodesLoad.Loaded(0))
        val model = model(FakePager(emptyList()), episodes)
        model.open("show-1")
        advanceUntilIdle()
        assertEquals(4, episodes.asked.size)

        model.retry()
        runCurrent()
        assertEquals(5, episodes.asked.size)
        assertEquals(ShowLoad.Loading, model.state.value.load)
        advanceUntilIdle()
        assertEquals(8, episodes.asked.size)

        // Coming back to the same show is the same wish: it does not stay as it was left.
        model.open("show-1")
        runCurrent()
        assertEquals(9, episodes.asked.size)
    }

    @Test
    fun `a show that was asked for and found is not asked for again by being opened again`() = runTest(dispatcher) {
        val episodes = SequencedEpisodes(EpisodesLoad.Loaded(3))
        val model = model(FakePager(held), episodes)
        model.open("show-1")
        advanceUntilIdle()

        model.open("show-1")
        advanceUntilIdle()

        assertEquals(1, episodes.asked.size)
    }

    @Test
    fun `what the request cost reaches the page, with how many times the provider was asked`() = runTest(dispatcher) {
        val cost = RequestTimes(askMs = 6_100, readMs = 400, saveMs = 200)
        val model = model(FakePager(held), SequencedEpisodes(EpisodesLoad.Loaded(3, cost)))

        model.open("show-1")
        advanceUntilIdle()

        assertEquals(cost.copy(tries = 1), model.state.value.times)
        assertEquals("ask 6.1 s · read 0.4 s · save 0.2 s · try 1", model.state.value.times?.line())
    }

    // ---------------------------------------------------------- the state is of one show

    @Test
    fun `the state says which show it is of, and the show opened next is not given the one before`() = runTest(dispatcher) {
        val pager = FakePager(held)
        val model = model(pager, SequencedEpisodes(EpisodesLoad.Loaded(3)))
        model.open("show-1")
        advanceUntilIdle()
        assertEquals("show-1", model.state.value.seriesId)
        assertEquals(2, model.state.value.seasons.size)

        // The next show's seasons have not been read yet: what the model holds is still the first show's, and says so.
        val next = kotlinx.coroutines.flow.MutableSharedFlow<List<Season>>()
        pager.byShow["show-2"] = next
        model.open("show-2")
        advanceUntilIdle()
        assertEquals("show-1", model.state.value.seriesId)

        next.emit(emptyList())
        advanceUntilIdle()
        assertEquals("show-2", model.state.value.seriesId)
        assertEquals(emptyList<Season>(), model.state.value.seasons)
    }
}
