package com.castivio.feature.home

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.paging.PagingData
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.domain.CatalogPager
import com.castivio.domain.CatalogQuery
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaItem
import com.castivio.domain.MediaKind
import com.castivio.domain.Movie
import com.castivio.domain.SectionLoads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
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
 * The films screen, composed with the real sidebar, controls and wall over the holder's own state: the
 * categories and their counts are listed, a press chooses one, the field searches, the Filter button opens
 * the same list as a page and a choice made there closes it, and the posters carry what the provider sent.
 *
 * What the lists hold, and in what order, is `BrowseSearchTest`'s and the data layer's.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class MoviesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun group(id: String, name: String, count: Int) =
        MediaGroup(id = id, name = name, kind = MediaKind.MOVIE, itemCount = count)

    private val action = group("action", "Action", 2_510)
    private val comedy = group("comedy", "Comedy", 1_920)

    /** A pager whose every list is [films], so the wall has something to draw. */
    private class WallPager(private val films: List<MediaItem>) : CatalogPager by FakePager() {
        override fun items(query: CatalogQuery): Flow<PagingData<MediaItem>> = flowOf(PagingData.from(films))
    }

    private fun holder(
        groups: List<MediaGroup> = listOf(action, comedy),
        films: List<MediaItem> = emptyList(),
    ): BrowseViewModel {
        val loads = SectionLoads(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            flowOf(null),
            { _, _ -> emptyFlow() },
            monotonicMs = { 0L },
        )
        val pager: CatalogPager = if (films.isEmpty()) FakePager() else WallPager(films)
        return BrowseViewModel(FakeCatalogue(groups), pager, loads, FakeSources(), FakeOrder())
    }

    private val opened = mutableListOf<Movie>()

    private fun show(model: BrowseViewModel) {
        compose.setContent {
            CastivioTheme {
                MoviesScreen(onOpenMovie = { opened += it }, model = model)
            }
        }
        compose.waitForIdle()
    }

    private fun film(id: String, title: String, minutes: Int? = null) = Movie(
        id = id,
        title = title,
        artworkUrl = null,
        streamUrl = "http://example.invalid/movie/u/p/$id.mp4",
        durationMinutes = minutes,
    )

    // --------------------------------------------------------------------------------- the sidebar

    @Test
    fun `the sidebar lists all the films, the favourites and every category with its count`() {
        show(holder())

        // "All movies" is the first entry and also the heading of the list it opens on.
        assertTrue(compose.onAllNodesWithText("All movies").fetchSemanticsNodes().size >= 1)
        compose.onNodeWithText("Favorites").assertIsDisplayed()
        compose.onNodeWithText("Action").assertIsDisplayed()
        compose.onNodeWithText("2,510").assertIsDisplayed()
        compose.onNodeWithText("Comedy").assertIsDisplayed()
        compose.onNodeWithText("1,920").assertIsDisplayed()
    }

    @Test
    fun `a press on a category chooses it`() {
        val model = holder()
        show(model)

        compose.onNodeWithText("Comedy").performClick()
        compose.waitForIdle()

        assertEquals("comedy", model.state.value.selectedGroup)
    }

    @Test
    fun `a press on the favourites opens them, and one on all the films leaves them`() {
        val model = holder()
        show(model)

        compose.onNodeWithText("Favorites").performClick()
        compose.waitForIdle()
        assertTrue(model.state.value.favorites)

        compose.onNodeWithText("All movies").performClick()
        compose.waitForIdle()
        assertTrue(!model.state.value.favorites)
        assertEquals(null, model.state.value.selectedGroup)
    }

    // ----------------------------------------------------------------------------------- the field

    @Test
    fun `the field searches the films as it is typed`() {
        val model = holder()
        show(model)

        compose.onNode(hasSetTextAction()).performTextInput("dune")
        compose.waitForIdle()

        assertEquals("dune", model.searchText.value)
    }

    // ---------------------------------------------------------------------------- the Filter page

    @Test
    fun `Filter opens the categories as a page, and a choice there closes it`() {
        val model = holder()
        show(model)

        compose.onNodeWithText("Filter").performClick()
        compose.waitForIdle()
        // The page is the list and nothing else: the field and the wall are not on the screen.
        assertTrue(compose.onAllNodesWithText("Sort: Provider").fetchSemanticsNodes().isEmpty())

        compose.onNodeWithText("Action").performClick()
        compose.waitForIdle()

        assertEquals("action", model.state.value.selectedGroup)
        compose.onNodeWithText("Sort: Provider").assertIsDisplayed()
    }

    // ------------------------------------------------------------------------------------ the wall

    @Test
    fun `the wall draws each film's name and running time, and a press opens its page`() {
        val dune = film("m1", "Dune: Part Two", minutes = 166)
        show(holder(films = listOf(dune, film("m2", "Oppenheimer", minutes = 180))))

        // A card says its name and its tag as one description, so that is what a reader -- and this test --
        // finds it by.
        compose.onNodeWithContentDescription("Dune: Part Two", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("2h 46m", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Oppenheimer", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("3h", substring = true).assertIsDisplayed()

        compose.onNodeWithContentDescription("Dune: Part Two", substring = true).performClick()
        compose.waitForIdle()

        assertEquals(listOf(dune), opened)
    }

    @Test
    fun `a film with no running time draws no tag`() {
        show(holder(films = listOf(film("m1", "Untimed"))))

        // Exactly the name: no ", 0m", no ", null", nothing the provider did not send.
        compose.onNodeWithContentDescription("Untimed").assertIsDisplayed()
    }
}
