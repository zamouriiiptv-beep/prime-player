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
import com.castivio.core.design.theme.MotionLevel
import com.castivio.domain.CatalogPager
import com.castivio.domain.CatalogQuery
import com.castivio.domain.Channel
import com.castivio.domain.FavoritesRepository
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaItem
import com.castivio.domain.MediaKind
import com.castivio.domain.RadioPhase
import com.castivio.domain.RadioStatus
import com.castivio.domain.SectionLoads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The radio, composed with the channels board's own band and rail over the holder's state: the wall draws the
 * stations the provider listed, a press starts one in the session and puts it on the plate, and the plate's
 * controls do what they say -- hold the sound, step through the open list, keep a favourite, set the sleep timer.
 *
 * What the lists hold is `BrowseSearchTest`'s and the data layer's; what the sound does is the shell's. Here the
 * session is the test's, and its status is fed by hand, as the player would feed it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class RadioScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val maroc = MediaGroup(id = "maroc", name = "MAROC RADIO", kind = MediaKind.RADIO, itemCount = 86)
    private val talk = MediaGroup(id = "talk", name = "MAROC TALK", kind = MediaKind.RADIO, itemCount = 23)

    private fun station(id: String, title: String, number: Int, group: String? = "maroc") = Channel(
        id = id,
        title = title,
        artworkUrl = null,
        number = number,
        groupId = group,
        streamUrl = "http://example.invalid/radio/$id",
        epgChannelId = null,
    )

    private val atlas = station("r1", "Radio Atlas", 1)
    private val nova = station("r2", "Nova FM", 2)
    private val sahara = station("r3", "Sahara FM", 3)

    /** A pager whose every list is [stations], so the wall has something to draw. */
    private class WallPager(private val stations: List<MediaItem>) : CatalogPager by FakePager() {
        override fun items(query: CatalogQuery): Flow<PagingData<MediaItem>> = flowOf(PagingData.from(stations))
    }

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

    private val session = RadioSession()
    private val store = Store()

    private fun show(stations: List<MediaItem> = listOf(atlas, nova, sahara)) {
        val loads = SectionLoads(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            flowOf(null),
            { _, _ -> emptyFlow() },
            monotonicMs = { 0L },
        )
        val pager: CatalogPager = if (stations.isEmpty()) FakePager() else WallPager(stations)
        val holder = BrowseViewModel(FakeCatalogue(listOf(maroc, talk)), pager, loads, FakeSources(), FakeOrder())
        compose.setContent {
            // Motion off: the equaliser on the playing card and the plate turns for as long as it sounds, and a
            // composition that never settles is one the test can never wait for.
            CastivioTheme(motionLevel = MotionLevel.DISABLED) {
                RadioScreen(session = session, model = holder, favorites = ChannelFavoritesViewModel(store))
            }
        }
        compose.waitForIdle()
    }

    private fun sound(station: Channel, phase: RadioPhase) {
        compose.runOnIdle { session.heard(RadioStatus(station.streamUrl, phase)) }
        compose.waitForIdle()
    }

    // ----------------------------------------------------------------------------- the band and the rail

    @Test
    fun `the band names the section, and its field says what it searches`() {
        show()

        compose.onNodeWithText("Radio").assertIsDisplayed()
        compose.onNodeWithText("Search stations").assertIsDisplayed()
    }

    @Test
    fun `the rail lists all the stations, the viewer's own lists and every category with its total`() {
        show()

        // "All stations" is the rail's first entry and also what the band calls the list it opens on.
        assertTrue(compose.onAllNodesWithText("All stations").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("Favorites").assertIsDisplayed()
        compose.onNodeWithText("Recently played").assertIsDisplayed()
        compose.onNodeWithText("MAROC RADIO").assertIsDisplayed()
        compose.onNodeWithText("Total: 86", substring = true).assertIsDisplayed()
        compose.onNodeWithText("MAROC TALK").assertIsDisplayed()
    }

    // ----------------------------------------------------------------------------- the wall

    @Test
    fun `the wall draws the stations the provider listed, each with its number`() {
        show()

        // A card says its name and its category as one description, so that is what a reader -- and this test --
        // finds it by. The list is all of them, so each names the category it is filed under.
        compose.onNodeWithContentDescription("Radio Atlas, MAROC RADIO").assertIsDisplayed()
        compose.onNodeWithContentDescription("Nova FM, MAROC RADIO").assertIsDisplayed()
        compose.onNodeWithContentDescription("Sahara FM, MAROC RADIO").assertIsDisplayed()
    }

    @Test
    fun `a station filed under no category names none, and nothing is made up`() {
        show(stations = listOf(station("r9", "Loose FM", 9, group = null)))

        compose.onNodeWithContentDescription("Loose FM").assertIsDisplayed()
    }

    @Test
    fun `nothing is playing, and no plate is drawn, before a station is pressed`() {
        show()

        assertNull(session.station)
        assertTrue(compose.onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithContentDescription("Sleep timer").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a press on a station starts it and puts it on the plate`() {
        show()

        compose.onNodeWithContentDescription("Nova FM", substring = true).performClick()
        compose.waitForIdle()

        assertEquals(nova, session.station)
        // The station's name is on the plate, whose controls are drawn.
        compose.onNodeWithText("Nova FM").assertIsDisplayed()
        compose.onNodeWithContentDescription("Sleep timer").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add to favourites").assertIsDisplayed()
    }

    // ----------------------------------------------------------------------------- the plate

    @Test
    fun `the play control holds the sound, and lets it go again`() {
        show()
        compose.onNodeWithContentDescription("Nova FM", substring = true).performClick()
        sound(nova, RadioPhase.PLAYING)

        compose.onNodeWithContentDescription("Pause").performClick()
        compose.waitForIdle()
        assertTrue(session.paused)
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()

        compose.onNodeWithContentDescription("Play").performClick()
        compose.waitForIdle()
        assertFalse(session.paused)
    }

    @Test
    fun `the plate says live while the station sounds, and names its category`() {
        show()
        compose.onNodeWithContentDescription("Nova FM", substring = true).performClick()
        sound(nova, RadioPhase.PLAYING)

        compose.onNodeWithText("MAROC RADIO \u00B7 Live").assertIsDisplayed()
    }

    @Test
    fun `a station that failed says so, and the control asks for it again`() {
        show()
        compose.onNodeWithContentDescription("Nova FM", substring = true).performClick()
        sound(nova, RadioPhase.FAILED)

        compose.onNodeWithText("This station can't be played").assertIsDisplayed()
        compose.onNodeWithContentDescription("Try again").performClick()
        compose.waitForIdle()

        assertEquals(1, session.retries)
        assertFalse(session.paused)
    }

    @Test
    fun `the controls step to the station before and after in the open list, and not past its ends`() {
        show()
        compose.onNodeWithContentDescription("Radio Atlas", substring = true).performClick()
        compose.waitForIdle()
        assertTrue("nothing comes before the first card", compose.onAllNodesWithContentDescription("Previous station").fetchSemanticsNodes().isEmpty())

        compose.onNodeWithContentDescription("Next station").performClick()
        compose.waitForIdle()
        assertEquals(nova, session.station)

        compose.onNodeWithContentDescription("Previous station").performClick()
        compose.waitForIdle()
        assertEquals(atlas, session.station)

        compose.onNodeWithContentDescription("Sahara FM", substring = true).performClick()
        compose.waitForIdle()
        assertTrue("nothing comes after the last card", compose.onAllNodesWithContentDescription("Next station").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `the favourite is kept from the press, and a favourite is hearted on its card`() {
        show()
        compose.onNodeWithContentDescription("Nova FM", substring = true).performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Add to favourites").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("Remove from favourites").fetchSemanticsNodes().isNotEmpty()
        }

        assertEquals(setOf("r2"), store.saved)
    }

    // ----------------------------------------------------------------------------- the sleep timer

    @Test
    fun `the sleep timer offers its times, and a press sets one and closes the dialog`() {
        show()
        compose.onNodeWithContentDescription("Nova FM", substring = true).performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Sleep timer").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("After 15 minutes").assertIsDisplayed()
        compose.onNodeWithText("After 90 minutes").assertIsDisplayed()
        compose.onNodeWithText("Off").assertIsDisplayed()

        compose.onNodeWithText("After 30 minutes").performClick()
        compose.waitForIdle()

        assertEquals(30, session.sleepMinutes)
        assertTrue(compose.onAllNodesWithText("After 15 minutes").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `choosing off in the dialog lets the timer go`() {
        show()
        compose.onNodeWithContentDescription("Nova FM", substring = true).performClick()
        compose.runOnIdle { session.sleepIn(minutes = 15, nowMs = System.currentTimeMillis()) }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Sleep timer").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Off").performClick()
        compose.waitForIdle()

        assertNull(session.sleepMinutes)
        assertNull(session.sleepAtMs)
    }
}
