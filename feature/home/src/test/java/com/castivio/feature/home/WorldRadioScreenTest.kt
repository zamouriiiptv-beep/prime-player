package com.castivio.feature.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.castivio.core.common.AppDispatchers
import com.castivio.core.common.PlayOutcome
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.MotionLevel
import com.castivio.domain.DirectoryFailure
import com.castivio.domain.DirectoryResult
import com.castivio.domain.RadioPhase
import com.castivio.domain.RadioStatus
import com.castivio.domain.WorldCountry
import com.castivio.domain.WorldListing
import com.castivio.domain.WorldStation
import com.castivio.domain.WorldTag
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The world radio, composed with the subscription radio's own band, rail and plate over a directory the test gives it: the rail lists the
 * lists and the countries, a press on a station plays it in the world's session and puts it on the plate, and what listening says reaches
 * the store -- all without a network, a decoder or a subscription.
 *
 * **The band's second line -- "World · Morocco" -- is asserted to exist, not to be displayed.** Robolectric lays every `Text` out 35dp tall
 * whatever its style, and the band's cell holds two of them in a header 38dp high at this size, so the second is cut by its own cell here and
 * would not be on a device; the other screens' tests ask the same of that line (`onAllNodesWithText("All movies")`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class WorldRadioScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val medi = worldStation("m1", name = "Medi 1 Radio", resolved = "https://b.invalid/medi.mp3")
    private val hit = worldStation("h1", name = "Hit Radio")
    private val rfi = worldStation("f1", name = "RFI Monde", countryCode = "FR", country = "France")

    private val directory = FakeWorldDirectory(
        stationsOf = { listing ->
            when (listing) {
                WorldListing.Top -> listOf(medi, hit, rfi)
                is WorldListing.OfCountry -> if (listing.code == "FR") listOf(rfi) else listOf(medi, hit)
                else -> emptyList()
            }
        },
        countries = DirectoryResult.Fresh(listOf(WorldCountry("MA", "Morocco", 105), WorldCountry("FR", "France", 640))),
        tags = DirectoryResult.Fresh(listOf(WorldTag("news", 310), WorldTag("pop", 900))),
    )
    private val library = MemoryWorldLibrary()
    private val session = WorldRadioSession()
    private val switched = mutableListOf<RadioSource>()

    private val unconfined = object : AppDispatchers {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private fun show() {
        val model = WorldRadioViewModel(directory, library, unconfined).apply { locale = Locale.forLanguageTag("en-US") }
        compose.setContent {
            // Motion off: the equaliser on the plate turns for as long as it sounds, and a composition that never settles is one
            // the test can never wait for.
            CastivioTheme(motionLevel = MotionLevel.DISABLED) {
                WorldRadioScreen(session = session, onSource = { switched += it }, model = model)
            }
        }
        compose.waitForIdle()
    }

    /** Waits for the list's first page, which a pager reads off the test's thread. */
    private fun awaitStation(name: String) {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun sound(station: WorldStation, phase: RadioPhase, evidence: PlayOutcome? = null) {
        compose.runOnIdle { session.heard(RadioStatus(url = session.url ?: station.streamUrl, phase = phase, evidence = evidence)) }
        compose.waitForIdle()
    }

    // ----------------------------------------------------------------------------- the band and the rail

    @Test
    fun `the band names the section and the world, and its field says what it searches`() {
        show()

        compose.onNodeWithText("Radio").assertIsDisplayed()
        compose.onNodeWithText("World · Most listened").assertExists()
        compose.onNodeWithText("Search the world's stations").assertIsDisplayed()
    }

    @Test
    fun `the rail offers the two sources, the viewer's lists and every country with its total`() {
        show()

        compose.onNodeWithText("Subscription").assertIsDisplayed()
        compose.onNodeWithText("World").assertIsDisplayed()
        compose.onNodeWithText("Most listened").assertIsDisplayed()
        compose.onNodeWithText("Favorites").assertIsDisplayed()
        compose.onNodeWithText("Recently played").assertIsDisplayed()
        compose.onNodeWithText("Genres").assertIsDisplayed()
        compose.onNode(hasText("Morocco") and hasText("Total: 105", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("France") and hasText("Total: 640", substring = true)).assertIsDisplayed()
        // The directory's name is drawn inside a left-to-right isolate, so it is found by the name and not by the whole line.
        compose.onNodeWithText("radio-browser.info", substring = true).assertExists()
    }

    @Test
    fun `the other source is one press away`() {
        show()

        compose.onNodeWithText("Subscription").performClick()
        compose.waitForIdle()

        assertEquals(listOf(RadioSource.Subscription), switched)
    }

    @Test
    fun `the genres replace the countries, and the countries come back from them`() {
        show()

        compose.onNodeWithText("Genres").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("News") and hasText("Total: 310", substring = true)).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("France").fetchSemanticsNodes().isEmpty())

        compose.onNodeWithText("Countries").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("France") and hasText("Total: 640", substring = true)).assertIsDisplayed()
    }

    // ----------------------------------------------------------------------------- the list

    @Test
    fun `the list draws the stations of the top of the world, each with what it plays at`() {
        show()
        awaitStation("Medi 1 Radio")

        compose.onNodeWithText("Hit Radio").assertIsDisplayed()
        compose.onNodeWithText("RFI Monde").assertIsDisplayed()
        // The country on the lists that mix them, and the format the directory said.
        assertTrue(compose.onAllNodesWithText("France · 128 kbps · MP3").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `a country opens its own stations, and the band says which`() {
        show()
        awaitStation("Medi 1 Radio")

        compose.onNode(hasText("France") and hasText("Total: 640", substring = true)).performClick()
        awaitStation("RFI Monde")

        compose.onNodeWithText("World · France").assertExists()
        assertEquals(WorldListing.OfCountry("FR"), directory.pageAsks.last().first)
        assertTrue("the stations of another country are not on it", compose.onAllNodesWithText("Hit Radio").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a directory that cannot be reached says so, and has a press to ask again`() {
        directory.pageFailure = DirectoryFailure.OFFLINE
        show()

        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("No connection").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Check your internet connection and try again.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsDisplayed()

        directory.pageFailure = null
        compose.onNodeWithText("Try again").performClick()
        awaitStation("Medi 1 Radio")
    }

    @Test
    fun `a viewer with no favourites is told how to have one`() {
        show()

        compose.onNodeWithText("Favorites").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("No favourite stations yet").assertIsDisplayed()
        compose.onNodeWithText("World · Favorites").assertExists()
    }

    // ----------------------------------------------------------------------------- the plate

    @Test
    fun `nothing is playing, and no plate is drawn, before a station is pressed`() {
        show()
        awaitStation("Medi 1 Radio")

        assertNull(session.station)
        compose.onNodeWithText("Choose a station").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a press asks for the station's resolved address and puts it on the plate`() {
        show()
        awaitStation("Medi 1 Radio")

        compose.onNodeWithText("Medi 1 Radio").performClick()
        compose.waitForIdle()

        assertEquals(medi, session.station)
        assertEquals("https://b.invalid/medi.mp3", session.url)
        assertEquals(2, compose.onAllNodesWithText("Medi 1 Radio").fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("Sleep timer").assertIsDisplayed()
    }

    @Test
    fun `the plate says live while the station sounds, and names its country`() {
        show()
        awaitStation("Medi 1 Radio")
        compose.onNodeWithText("Medi 1 Radio").performClick()
        sound(medi, RadioPhase.PLAYING)

        compose.onNodeWithText("Morocco · Live").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
    }

    @Test
    fun `the controls step through the open list`() {
        show()
        awaitStation("Medi 1 Radio")
        compose.onNodeWithText("Medi 1 Radio").performClick()
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithContentDescription("Previous station").fetchSemanticsNodes().isEmpty())

        compose.onNodeWithContentDescription("Next station").performClick()
        compose.waitForIdle()

        assertEquals(hit, session.station)
    }

    @Test
    fun `a station that sounded is a recent, and the directory is told it was listened to`() {
        show()
        awaitStation("Medi 1 Radio")
        compose.onNodeWithText("Medi 1 Radio").performClick()
        sound(medi, RadioPhase.PLAYING)

        compose.waitUntil(timeoutMillis = 5_000) { directory.listens.isNotEmpty() }

        assertEquals(listOf("m1"), directory.listens)
        assertEquals(listOf("m1"), library.listenedUuids)
        compose.onNodeWithText("Recently played").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("World \u00B7 Recently played").assertExists()
    }

    @Test
    fun `what a listen proved is written against the station`() {
        show()
        awaitStation("Medi 1 Radio")
        compose.onNodeWithText("Medi 1 Radio").performClick()
        sound(medi, RadioPhase.PLAYING, evidence = PlayOutcome.CONFIRMED)

        compose.waitUntil(timeoutMillis = 5_000) { library.outcomes.isNotEmpty() }

        assertEquals(listOf("m1" to PlayOutcome.CONFIRMED), library.outcomes)
    }

    @Test
    fun `the favourite is kept from the plate, and is on the favourites list`() {
        show()
        awaitStation("Medi 1 Radio")
        compose.onNodeWithText("Medi 1 Radio").performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Add to favourites").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("Remove from favourites").fetchSemanticsNodes().isNotEmpty()
        }

        assertEquals(listOf("m1"), library.keptUuids)
        compose.onNodeWithText("Favorites").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("World \u00B7 Favorites").assertExists()
        assertFalse(compose.onAllNodesWithText("No favourite stations yet").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `a station with another address is asked for at it before the viewer is told it failed`() {
        show()
        awaitStation("Medi 1 Radio")
        compose.onNodeWithText("Medi 1 Radio").performClick()
        compose.waitForIdle()

        sound(medi, RadioPhase.FAILED)

        assertEquals("http://a.invalid/m1", session.url)
        assertTrue(compose.onAllNodesWithText("This station can't be played").fetchSemanticsNodes().isEmpty())

        sound(medi, RadioPhase.FAILED)
        compose.onNodeWithText("This station can't be played").assertIsDisplayed()
    }
}
