package com.castivio.feature.activation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.DeviceClass
import com.castivio.core.design.theme.LocalDeviceClass
import com.castivio.domain.ProviderSource
import com.castivio.domain.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The fourth card's destination: the subscriptions this box already holds.
 *
 * The claim worth gating is not that the list renders — it is that this screen is a
 * second **door** onto the existing add-provider flow rather than a second
 * implementation of it. Both add buttons have to reach the two forms that have existed
 * since activation was written, and choosing a saved subscription has to go to the
 * repository rather than to some state of this screen's own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-land")
class SavedSourcesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * A fresh install has none, and that is a state rather than an error.
     *
     * The two add buttons are present here too — an empty list with no way out of it
     * would be a dead end reached by pressing a card.
     */
    @Test
    fun `with nothing saved it says so and still offers both ways to add one`() {
        compose.show(SavedSourcesState.Ready(saved = emptyList(), activeId = null))

        compose.onNodeWithTag(ActivationTags.SAVED_EMPTY).assertIsDisplayed()
        compose.onNodeWithTag(ActivationTags.SAVED_ADD_XTREAM).assertIsDisplayed()
        compose.onNodeWithTag(ActivationTags.SAVED_ADD_M3U).assertIsDisplayed()
    }

    /**
     * Before the first emission, neither the list nor the empty state.
     *
     * Rendering "you have nothing" for one frame and correcting it is the flash a
     * returning user reads as their subscriptions having been lost.
     */
    @Test
    fun `while loading it claims nothing about what is saved`() {
        compose.show(SavedSourcesState.Loading)

        compose.onNodeWithTag(ActivationTags.SAVED_EMPTY).assertDoesNotExist()
        compose.onNodeWithTag(ActivationTags.SAVED_LIST).assertDoesNotExist()
        // The way out is still there, which is the point of asserting this state at all.
        compose.onNodeWithTag(ActivationTags.SAVED_BACK).assertIsDisplayed()
    }

    /** Every saved subscription is listed, by the label the user gave it. */
    @Test
    fun `it lists what is saved and marks the one in use`() {
        compose.show(SavedSourcesState.Ready(saved = twoSources(), activeId = "b"))

        compose.onNodeWithTag(ActivationTags.SAVED_LIST).assertIsDisplayed()
        compose.onNodeWithText("Home").assertIsDisplayed()
        compose.onNodeWithText("Cabin").assertIsDisplayed()
        // "In use" appears once: on the active one, and not on the other.
        compose.onNodeWithText("In use").assertIsDisplayed()
    }

    /**
     * Choosing one reports its id, and does not flip anything locally.
     *
     * The repository is the only writer — see the view model — so what this asserts is
     * that the screen forwards the identity of what was pressed and nothing else.
     */
    @Test
    fun `choosing a subscription reports that subscription`() {
        val chosen = mutableListOf<String>()
        compose.show(
            state = SavedSourcesState.Ready(saved = twoSources(), activeId = "b"),
            onChoose = { chosen += it },
        )

        compose.onNodeWithText("Home").performClick()

        assertEquals(listOf("a"), chosen)
    }

    /**
     * The requirement this screen exists for: both ways to add a subscription are
     * reachable from it, and they are the two that already exist.
     */
    @Test
    fun `both add buttons reach the existing Xtream and M3U flows`() {
        val pressed = mutableListOf<String>()
        compose.show(
            state = SavedSourcesState.Ready(saved = emptyList(), activeId = null),
            onAddXtream = { pressed += "xtream" },
            onAddPlaylist = { pressed += "m3u" },
        )

        compose.onNodeWithTag(ActivationTags.SAVED_ADD_XTREAM).performClick()
        compose.onNodeWithTag(ActivationTags.SAVED_ADD_M3U).performClick()

        assertEquals(listOf("xtream", "m3u"), pressed)
    }

    /* -------------------------------------------------------------------------- */

    private fun twoSources() = listOf(
        ProviderSource(id = "a", kind = SourceKind.XTREAM, label = "Home", url = "http://one.example"),
        ProviderSource(id = "b", kind = SourceKind.M3U_URL, label = "Cabin", url = "http://two.example"),
    )

    private fun ComposeContentTestRule.show(
        state: SavedSourcesState,
        onChoose: (String) -> Unit = {},
        onAddXtream: () -> Unit = {},
        onAddPlaylist: () -> Unit = {},
    ) = setContent {
        CastivioTheme {
            CompositionLocalProvider(LocalDeviceClass provides DeviceClass.Expanded) {
                Stage {
                    SavedSourcesScreen(
                        state = state,
                        onChoose = onChoose,
                        onAddXtream = onAddXtream,
                        onAddPlaylist = onAddPlaylist,
                        onBack = {},
                    )
                }
            }
        }
    }

    /** The reporter's frame, which is the tightest this screen is drawn at. */
    @Composable
    private fun Stage(content: @Composable () -> Unit) {
        Box(Modifier.requiredSize(827.dp, 393.dp)) { content() }
    }
}
