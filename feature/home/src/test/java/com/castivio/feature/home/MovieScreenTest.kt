package com.castivio.feature.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.domain.FavoritesRepository
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaKind
import com.castivio.domain.Movie
import com.castivio.domain.MovieDetails
import com.castivio.domain.MovieDetailsRepository
import com.castivio.domain.SectionLoads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A film's page, composed with the real buttons over the holder's state: the name, what the provider sent
 * about the film and nothing it did not, a Play that hands the player exactly this film, and a favourite
 * that is shown from the press.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class MovieScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val action = MediaGroup(id = "action", name = "Action", kind = MediaKind.MOVIE, itemCount = 12)

    private val dune = Movie(
        id = "m1",
        title = "Dune: Part Two",
        artworkUrl = null,
        streamUrl = "http://example.invalid/movie/u/p/1.mp4",
        year = 2024,
        durationMinutes = 166,
        groupId = "action",
    )

    /** The favourites, written at once and listed as written. */
    private class Store(initial: Set<String> = emptySet()) : FavoritesRepository {
        val saved = initial.toMutableSet()
        private val listed = MutableStateFlow(initial)

        override fun isFavorite(mediaId: String): Flow<Boolean> = flowOf(mediaId in saved)
        override suspend fun toggle(mediaId: String): Boolean = error("not asked")
        override fun count(): Flow<Int> = flowOf(saved.size)
        override fun ids(): Flow<Set<String>> = listed

        override suspend fun set(mediaIds: Collection<String>, favorite: Boolean) {
            if (favorite) saved += mediaIds else saved -= mediaIds.toSet()
            listed.value = saved.toSet()
        }
    }

    private val played = mutableListOf<CatalogSelection>()
    private var backs = 0

    private fun show(movie: Movie = dune, store: Store = Store(), details: MovieDetailsRepository = NoDetails) {
        val loads = SectionLoads(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            flowOf(null),
            { _, _ -> emptyFlow() },
            monotonicMs = { 0L },
        )
        val holder = BrowseViewModel(FakeCatalogue(listOf(action)), FakePager(), loads, FakeSources(), FakeOrder())
        compose.setContent {
            CastivioTheme {
                MovieScreen(
                    movie = movie,
                    onPlay = { played += it },
                    onBack = { backs++ },
                    model = holder,
                    favorites = ChannelFavoritesViewModel(store),
                    detailsModel = MovieDetailsViewModel(details),
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the page names the film and what the provider sent about it`() {
        show()

        compose.onNodeWithText("Dune: Part Two").assertIsDisplayed()
        // A figure is kept whole in a right-to-left line by direction marks around it, so it is found inside.
        compose.onNodeWithText("2024", substring = true).assertIsDisplayed()
        compose.onNodeWithText("2h 46m", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Action").assertIsDisplayed()
    }

    @Test
    fun `a film the provider told nothing about is a name and two buttons, with no facts made up`() {
        show(movie = dune.copy(year = null, durationMinutes = null, groupId = null))

        compose.onNodeWithText("Dune: Part Two").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Action").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Play movie").assertIsDisplayed()
        compose.onNodeWithText("Add to favourites").assertIsDisplayed()
    }

    @Test
    fun `Play hands the player exactly this film`() {
        show()

        compose.onNodeWithText("Play movie").performClick()
        compose.waitForIdle()

        val selection = played.single()
        assertEquals(dune.streamUrl, selection.url)
        assertEquals("Dune: Part Two", selection.title)
        assertEquals(false, selection.live)
    }

    @Test
    fun `a film that is not a favourite offers to add it, and shows it added from the press`() {
        val store = Store()
        show(store = store)

        compose.onNodeWithText("Add to favourites").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Remove from favourites").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Remove from favourites").assertIsDisplayed()
    }

    @Test
    fun `a film that is a favourite offers to remove it`() {
        show(store = Store(initial = setOf("m1")))

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Remove from favourites").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Remove from favourites").assertIsDisplayed()
    }

    @Test
    fun `the back button leaves the page`() {
        show()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()

        assertEquals(1, backs)
    }

    @Test
    fun `a plot that fits is shown whole, with no link to more`() {
        show(details = WithPlot("A short plot."))

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("A short plot.").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("A short plot.").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Show all").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a plot the page has to cut offers the whole of it in a dialog, which closes and leaves the page where it was`() {
        show(details = WithPlot(LONG_PLOT))

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Show all").fetchSemanticsNodes().isNotEmpty()
        }
        // The page's own plot is the only node that says it, until the dialog says it again.
        assertEquals(1, compose.onAllNodesWithText(LONG_PLOT).fetchSemanticsNodes().size)

        compose.onNodeWithText("Show all").performClick()
        compose.waitForIdle()
        assertEquals(2, compose.onAllNodesWithText(LONG_PLOT).fetchSemanticsNodes().size)
        compose.onNodeWithText("Close").assertIsDisplayed()

        compose.onNodeWithText("Close").performClick()
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodesWithText(LONG_PLOT).fetchSemanticsNodes().size)
        assertTrue(compose.onAllNodesWithText("Close").fetchSemanticsNodes().isEmpty())
        // Closing the dialog is not leaving the page.
        assertEquals(0, backs)
    }

    /** A provider that gives a plot and nothing else. */
    private class WithPlot(private val plot: String) : MovieDetailsRepository {
        override suspend fun details(movie: Movie): MovieDetails? = MovieDetails(plot = plot)
    }

    /** A provider that gives no details: the page then draws only what the list row carried. */
    private object NoDetails : MovieDetailsRepository {
        override suspend fun details(movie: Movie): MovieDetails? = null
    }

    private companion object {
        /** Far longer than the page can hold: about thirty lines at any width the page is drawn at. */
        val LONG_PLOT = "The long plot goes on and on. ".repeat(90).trim()
    }
}
