package com.castivio.feature.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.domain.FavoritesRepository
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaKind
import com.castivio.domain.Movie
import com.castivio.domain.MovieDetails
import com.castivio.domain.MovieDetailsRepository
import com.castivio.domain.RequestTimes
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
@OptIn(ExperimentalTestApi::class)
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

    /** [direction] is the reader's, where a test needs the page turned round; the harness's own (left to right) where it is null. */
    private fun show(
        movie: Movie = dune,
        store: Store = Store(),
        details: MovieDetailsRepository = NoDetails,
        direction: LayoutDirection? = null,
    ) {
        val loads = SectionLoads(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            flowOf(null),
            { _, _ -> emptyFlow() },
            monotonicMs = { 0L },
        )
        val holder = BrowseViewModel(FakeCatalogue(listOf(action)), FakePager(), loads, FakeSources(), FakeOrder())
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides (direction ?: LocalLayoutDirection.current)) {
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

    // The other half -- a plot the page has to cut offers "Show all", which opens the whole of it in a dialog --
    // is not asserted here: it depends on the text being laid out and found longer than the lines it was given,
    // and Robolectric does not lay text out (see ActivationBudgetTest), so the link would never appear in this
    // harness. It is checked on a device, where the plot is real. The credit lines are cut and offer their own
    // "Show all" by the same rule, and are checked the same way.

    @Test
    fun `a director and a cast that fit are shown whole, with no link to more`() {
        show(details = WithCredits(director = "Denis Villeneuve", cast = listOf("Timothée Chalamet", "Zendaya")))

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Denis Villeneuve", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Denis Villeneuve", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Timothée Chalamet, Zendaya", substring = true).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Show all").fetchSemanticsNodes().isEmpty())
    }

    /**
     * The page can carry three of these links, the plot's and the credits', and a reader that met "Show all" three times
     * would not know which was which: the one at the end of a credit line says what it is the whole of.
     */
    @Test
    fun `the link to the whole of a credit says which credit it is, and a press on it is heard`() {
        var pressed = 0
        compose.setContent {
            CastivioTheme {
                PlotMore(catalogMetricsFor(tv = false, width = 833.dp, height = 385.dp), onClick = { pressed++ }, subject = "Cast")
            }
        }

        compose.onNodeWithContentDescription("Show all, Cast").performClick()

        assertEquals(1, pressed)
    }

    @Test
    fun `the plot's own link keeps its plain name`() {
        compose.setContent {
            CastivioTheme {
                PlotMore(catalogMetricsFor(tv = false, width = 833.dp, height = 385.dp), onClick = {})
            }
        }

        compose.onNodeWithText("Show all").assertIsDisplayed()
    }

    // ---------------------------------------------------------------------------------------- the direction

    /** Where each part of the page begins, measured from the left of the screen: the name, the way back and the two buttons. */
    private fun lefts() = mapOf(
        "name" to compose.onNodeWithText("Dune: Part Two").getUnclippedBoundsInRoot().left.value,
        "back" to compose.onNodeWithContentDescription("Back").getUnclippedBoundsInRoot().left.value,
        "play" to compose.onNodeWithText("Play movie").getUnclippedBoundsInRoot().left.value,
        "favourite" to compose.onNodeWithText("Add to favourites").getUnclippedBoundsInRoot().left.value,
    )

    @Test
    fun `left to right the name begins at the left, the way back ends its row, and Play is the first button`() {
        show(direction = LayoutDirection.Ltr)

        val at = lefts()
        assertTrue("the way back is to the right of the name's start: $at", at.getValue("back") > at.getValue("name"))
        assertTrue("Play comes before the favourite, from the left: $at", at.getValue("play") < at.getValue("favourite"))
    }

    /**
     * The page used to be drawn left to right in every language, inside a direction of its own. It follows the reader's now:
     * turned round, the name begins at the right and the way back ends its row at the left, and Play -- the first button --
     * is the one furthest to the right. Under the old forced direction every one of these would have read as left to right.
     */
    @Test
    fun `right to left the page is turned round, the way back at the left and Play the furthest to the right`() {
        show(direction = LayoutDirection.Rtl)

        val at = lefts()
        assertTrue("the way back is to the left of the name's start: $at", at.getValue("back") < at.getValue("name"))
        assertTrue("Play comes before the favourite, from the right: $at", at.getValue("play") > at.getValue("favourite"))
    }

    @Test
    fun `right to left a director and a cast that fit are listed from the label outwards, each name whole`() {
        show(
            details = WithCredits(director = "Denis Villeneuve", cast = listOf("Timothée Chalamet", "Zendaya")),
            direction = LayoutDirection.Rtl,
        )

        // Each name is a unit the line breaks only between -- its spaces do not break -- parted by the language's comma:
        // see `creditNames`.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Denis\u00A0Villeneuve", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Timothée\u00A0Chalamet", substring = true).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Show all").fetchSemanticsNodes().isEmpty())
    }

    /** A provider that gives a director and a cast and nothing else. */
    private class WithCredits(private val director: String, private val cast: List<String>) : MovieDetailsRepository {
        override suspend fun details(movie: Movie, onTimes: (RequestTimes) -> Unit): MovieDetails? =
            MovieDetails(director = director, cast = cast)
    }

    /** A provider that gives a plot and nothing else. */
    private class WithPlot(private val plot: String) : MovieDetailsRepository {
        override suspend fun details(movie: Movie, onTimes: (RequestTimes) -> Unit): MovieDetails? = MovieDetails(plot = plot)
    }

    /** A provider that gives no details: the page then draws only what the list row carried. */
    private object NoDetails : MovieDetailsRepository {
        override suspend fun details(movie: Movie, onTimes: (RequestTimes) -> Unit): MovieDetails? = null
    }
}
