package com.castivio.feature.home

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onFirst
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
import com.castivio.domain.MediaKind
import com.castivio.domain.SectionLoads
import com.castivio.domain.SeriesSummary
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
 * The shows' screen, composed with the films' board over the holder's state: the band names the section and its field
 * narrows the shows, the rail lists the categories with their totals -- and none of the viewer's own lists, which a show
 * has not got -- and the wall carries what the provider sent, a poster and a name, and a press opens the show's page.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class SeriesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val drama = MediaGroup(id = "drama", name = "DRAMA", kind = MediaKind.SERIES, itemCount = 310)
    private val comedy = MediaGroup(id = "comedy", name = "COMEDY", kind = MediaKind.SERIES, itemCount = 95)

    private fun summary(id: String, title: String) = SeriesSummary(id, title, artworkUrl = null, episodeCount = 0, seasonCount = 0)

    /** A pager whose every list of shows is [shows], so the wall has something to draw. */
    private class WallPager(private val shows: List<SeriesSummary>) : CatalogPager by FakePager() {
        override fun series(query: CatalogQuery): Flow<PagingData<SeriesSummary>> = flowOf(PagingData.from(shows))
    }

    private fun holder(shows: List<SeriesSummary> = emptyList()): BrowseViewModel {
        val loads = SectionLoads(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            flowOf(null),
            { _, _ -> emptyFlow() },
            monotonicMs = { 0L },
        )
        val pager: CatalogPager = if (shows.isEmpty()) FakePager() else WallPager(shows)
        return BrowseViewModel(FakeCatalogue(listOf(drama, comedy)), pager, loads, FakeSources(), FakeOrder())
    }

    private val opened = mutableListOf<SeriesSummary>()

    private fun show(model: BrowseViewModel) {
        compose.setContent {
            CastivioTheme {
                SeriesScreen(onOpenShow = { opened += it }, model = model)
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the band names the section, and its field says what it searches`() {
        show(holder())

        compose.onNodeWithText("Series").assertIsDisplayed()
        compose.onNodeWithText("Search series").assertIsDisplayed()
    }

    @Test
    fun `the rail lists all the shows and every category with its total, and none of the viewer's own lists`() {
        show(holder())

        assertTrue(compose.onAllNodesWithText("All series").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("DRAMA").assertIsDisplayed()
        compose.onNodeWithText("Total: 310", substring = true).assertIsDisplayed()
        compose.onNodeWithText("COMEDY").assertIsDisplayed()
        // A favourite and a watch are of a film or a channel; an entry that opened nothing is not drawn.
        assertTrue(compose.onAllNodesWithText("Favorites").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Recently Watched").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a press on a category chooses it`() {
        val model = holder()
        show(model)

        compose.onNodeWithText("COMEDY").performClick()
        compose.waitForIdle()

        assertEquals("comedy", model.state.value.selectedGroup)
    }

    @Test
    fun `the band's field searches the shows as it is typed`() {
        val model = holder()
        show(model)

        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("dark")
        compose.waitForIdle()

        assertEquals("dark", model.searchText.value)
    }

    @Test
    fun `the band says the order the shows are in`() {
        show(holder())

        compose.onNodeWithText("Sort: Provider").assertIsDisplayed()
    }

    @Test
    fun `the wall draws each show's name, and a press opens its page`() {
        val dark = summary("s1", "Dark")
        show(holder(shows = listOf(dark, summary("s2", "Chernobyl"))))

        // A card says its name as its description, so that is what a reader -- and this test -- finds it by.
        compose.onNodeWithContentDescription("Dark").assertIsDisplayed()
        compose.onNodeWithContentDescription("Chernobyl").assertIsDisplayed()

        compose.onNodeWithContentDescription("Dark").performClick()
        compose.waitForIdle()

        assertEquals(listOf(dark), opened)
    }
}
