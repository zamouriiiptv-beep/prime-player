package com.castivio.feature.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.paging.PagingData
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.domain.CatalogPager
import com.castivio.domain.CatalogQuery
import com.castivio.domain.Episode
import com.castivio.domain.EpisodesLoad
import com.castivio.domain.EpisodesReady
import com.castivio.domain.FavoritesRepository
import com.castivio.domain.InProgressItem
import com.castivio.domain.MediaItem
import com.castivio.domain.PlaybackProgress
import com.castivio.domain.ProgressRepository
import com.castivio.domain.Season
import com.castivio.domain.SeriesDetails
import com.castivio.domain.SeriesDetailsStore
import com.castivio.domain.SeriesEpisodes
import com.castivio.domain.SeriesShell
import com.castivio.domain.SeriesSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A show's page composed over the real state holder, with the provider, the catalogue and the viewer's marks as fakes:
 * the rail names the parts that have something in them, the facts the provider sent are drawn and no others, Play and
 * an episode start what they say, the heart flips, and a similar show opens.
 *
 * What the holder does with the provider's reply is `ShowViewModelTest`'s; here the details are given and the page is
 * asked what it draws. A cut plot and the shapes the words take are the screenshot's: the test environment lays out
 * no text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class ShowScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun episode(season: Int, number: Int) = Episode(
        id = "s${season}e$number",
        title = "Episode $season-$number",
        artworkUrl = null,
        streamUrl = "http://example.invalid/series/$season/$number.mkv",
        seasonNumber = season,
        episodeNumber = number,
        durationMinutes = 42,
    )

    private val seasons = listOf(
        Season(1, listOf(episode(1, 1), episode(1, 2))),
        Season(2, listOf(episode(2, 1))),
    )

    private val said = SeriesDetails(
        rating = 8.0,
        plot = "An emotional thrill ride.",
        cast = listOf("Nick Gehlfuss", "Yaya DaCosta"),
        genres = listOf("Drama", "Mystery"),
        releaseYear = 2015,
    )

    private val others = listOf(SeriesSummary("show-2", "Chicago Fire", null, 0, 0), SeriesSummary("show-3", "Chicago PD", null, 0, 0))

    private class Pager(val seasons: List<Season>, val similar: List<SeriesSummary>) : CatalogPager {
        override fun items(query: CatalogQuery): Flow<PagingData<MediaItem>> = emptyFlow()
        override fun series(query: CatalogQuery): Flow<PagingData<SeriesSummary>> = emptyFlow()
        override fun seasons(seriesId: String): Flow<List<Season>> = MutableStateFlow(seasons)
        override fun favorites(): Flow<PagingData<MediaItem>> = emptyFlow()
        override fun continueWatching(): Flow<PagingData<InProgressItem>> = emptyFlow()
        override fun history(): Flow<PagingData<InProgressItem>> = emptyFlow()
        override suspend fun seriesShell(seriesId: String) =
            SeriesShell(sourceId = "src", providerRef = "551", title = "Chicago Med", groupId = "g1", mediaId = "shell-1")
        override suspend fun similarTo(seriesId: String, limit: Int): List<SeriesSummary> = similar
    }

    /** Answers at once and has nothing to add: the page has its episodes, as it would after the provider answered. */
    private object Answered : SeriesEpisodes {
        override suspend fun load(seriesId: String, onReady: (EpisodesReady) -> Unit): EpisodesLoad = EpisodesLoad.Loaded(3)
    }

    private object NoProgress : ProgressRepository {
        override suspend fun save(mediaId: String, positionMs: Long, durationMs: Long?) = Unit
        override suspend fun progress(mediaId: String): PlaybackProgress? = null
        override suspend fun clear(mediaId: String) = Unit
        override suspend fun clearAll() = Unit
        override suspend fun watched(mediaId: String) = Unit
    }

    private class Details(details: SeriesDetails?, plots: Map<String, String>) : SeriesDetailsStore {
        private val held = MutableStateFlow(details)
        private val plotted = MutableStateFlow(plots)
        override fun details(seriesId: String): Flow<SeriesDetails?> = held
        override fun episodePlots(seriesId: String): Flow<Map<String, String>> = plotted
        override suspend fun put(seriesId: String, details: SeriesDetails?, plots: Map<String, String>, now: Long) = Unit
    }

    private class Marks : FavoritesRepository {
        val marked = MutableStateFlow<Set<String>>(emptySet())
        override fun isFavorite(mediaId: String): Flow<Boolean> = marked.map { mediaId in it }
        override suspend fun toggle(mediaId: String): Boolean {
            marked.value = if (mediaId in marked.value) marked.value - mediaId else marked.value + mediaId
            return mediaId in marked.value
        }
        override fun count(): Flow<Int> = marked.map { it.size }
        override fun ids(): Flow<Set<String>> = marked
        override suspend fun set(mediaIds: Collection<String>, favorite: Boolean) = Unit
    }

    private val played = mutableListOf<CatalogSelection>()
    private val opened = mutableListOf<SeriesSummary>()
    private val marks = Marks()

    private fun show(
        details: SeriesDetails? = said,
        plots: Map<String, String> = mapOf("s1e1" to "A train crash."),
        similar: List<SeriesSummary> = others,
    ) {
        val model = ShowViewModel(Pager(seasons, similar), Answered, NoProgress, Details(details, plots), marks)
        compose.setContent {
            CastivioTheme {
                ShowScreen(
                    show = SeriesSummary("show-1", "Chicago Med", null, 3, 2),
                    onPlay = { played += it },
                    onBack = {},
                    onOpenShow = { opened += it },
                    model = model,
                )
            }
        }
        compose.waitForIdle()
    }

    // ----------------------------------------------------------------------------------------------- the rail

    @Test
    fun `the rail names the sections that have something in them`() {
        show()

        // A handset keeps the icons, which speak their names.
        compose.onNodeWithContentDescription("Overview").assertIsDisplayed()
        compose.onNodeWithContentDescription("Seasons").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cast").assertIsDisplayed()
        compose.onNodeWithContentDescription("Similar").assertIsDisplayed()
    }

    @Test
    fun `a show the provider said nothing of has an overview and its seasons, and no cast or similar`() {
        show(details = null, plots = emptyMap(), similar = emptyList())

        compose.onNodeWithContentDescription("Overview").assertIsDisplayed()
        compose.onNodeWithContentDescription("Seasons").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithContentDescription("Cast").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithContentDescription("Similar").fetchSemanticsNodes().isEmpty())
    }

    // ------------------------------------------------------------------------------------------------- the top

    @Test
    fun `the facts the provider sent are drawn, with the seasons the catalogue holds`() {
        show()

        // They are there, and a test environment that lays out no text cannot say where on the screen.
        compose.onNodeWithText("2015", substring = true).assertExists()
        compose.onNodeWithText("Drama | Mystery").assertExists()
        compose.onNodeWithText("8.0", substring = true).assertExists()
        compose.onNodeWithText("2 seasons").assertExists()
    }

    @Test
    fun `no fact is made up where the provider sent none`() {
        show(details = null, plots = emptyMap(), similar = emptyList())

        assertTrue(compose.onAllNodesWithText("2015").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("8.0").fetchSemanticsNodes().isEmpty())
        // What the catalogue holds is still there.
        compose.onNodeWithText("2 seasons").assertIsDisplayed()
    }

    @Test
    fun `Play starts the first episode of a show not begun, and says so`() {
        show()

        compose.onNodeWithText("Play S1 E1").performClick()
        compose.waitForIdle()

        assertEquals(listOf("http://example.invalid/series/1/1.mkv"), played.map { it.url })
    }

    @Test
    fun `the heart adds the show to the favourites, and the button then offers to remove it`() {
        show()

        compose.onNodeWithText("Add to Favorites").performClick()
        compose.waitForIdle()

        assertEquals(setOf("shell-1"), marks.marked.value)
        compose.onNodeWithText("Remove from Favorites").assertIsDisplayed()
    }

    // ---------------------------------------------------------------------------------------- seasons and episodes

    @Test
    fun `a season chip says how many episodes it holds`() {
        show()

        compose.onNodeWithText("Season 1").assertIsDisplayed()
        compose.onNodeWithText("2 episodes").assertIsDisplayed()
        compose.onNodeWithText("Season 2").assertIsDisplayed()
        compose.onNodeWithText("1 episode").assertIsDisplayed()
    }

    @Test
    fun `an episode shows its name, a line of its plot, and its running time`() {
        show()

        compose.onNodeWithText("Episode 1-1").assertIsDisplayed()
        compose.onNodeWithText("A train crash.").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("42m", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `a press on an episode plays it`() {
        show()

        compose.onNodeWithText("Episode 1-2").performClick()
        compose.waitForIdle()

        assertEquals(listOf("http://example.invalid/series/1/2.mkv"), played.map { it.url })
    }

    @Test
    fun `a press on another season lists that season's episodes`() {
        show()

        compose.onNodeWithText("Season 2").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Episode 2-1").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Episode 1-1").fetchSemanticsNodes().isEmpty())
    }

    // ------------------------------------------------------------------------------------ cast and similar shows

    @Test
    fun `the rail takes the page to the cast, which is names and nothing else`() {
        show()

        compose.onNodeWithContentDescription("Cast").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Nick Gehlfuss").assertIsDisplayed()
        compose.onNodeWithText("Yaya DaCosta").assertIsDisplayed()
    }

    @Test
    fun `a press on a similar show opens its page`() {
        show()

        compose.onNodeWithContentDescription("Similar").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Chicago PD", substring = true).performClick()
        compose.waitForIdle()

        assertEquals(listOf("show-3"), opened.map { it.seriesId })
    }
}
