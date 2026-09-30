package com.castivio.feature.activation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
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
        // The mark appears on the active one, and not on the other.
        compose.onNodeWithText("Active now").assertIsDisplayed()
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

    // ------------------------------------------------------- renaming and deleting

    /**
     * The row carries no connection details, and this is the assertion that keeps it
     * that way. The addresses are in the fixtures precisely so their absence on screen
     * is a claim about the screen rather than about the data it was given.
     */
    @Test
    fun `a row shows no address and no username`() {
        compose.show(SavedSourcesState.Ready(saved = twoSources(), activeId = "b"))

        compose.onNodeWithText("http://one.example", substring = true).assertDoesNotExist()
        compose.onNodeWithText("http://two.example", substring = true).assertDoesNotExist()
    }

    /** Edit opens the rename dialog, and the dialog opens on the name it will change. */
    @Test
    fun `edit opens the rename dialog on that subscription's name`() {
        compose.show(SavedSourcesState.Ready(saved = twoSources(), activeId = "b"))

        compose.onAllNodesWithTag(ActivationTags.SAVED_EDIT)[0].performClick()

        compose.onNodeWithTag(ActivationTags.SAVED_RENAME_DIALOG).assertIsDisplayed()
        // **The unmerged tree.** A text field merges its label, its value and its
        // decoration into one semantics node for a screen reader, which is right and
        // which means a tag on anything inside it is not in the merged tree at all.
        // Asked for it there, the finder reports no such node — and the assertion it
        // was standing in front of reports that as "not displayed", which reads like a
        // layout fault and is not one.
        compose
            .onNodeWithTag(ActivationTags.SAVED_RENAME_FIELD, useUnmergedTree = true)
            .assertIsDisplayed()
    }

    /**
     * Deleting a subscription that is merely saved asks the ordinary question.
     *
     * The two titles are asserted apart because they are the whole of the requirement:
     * one is a tidy-up and the other changes what the app shows next time it opens.
     */
    @Test
    fun `deleting a saved subscription asks the ordinary question`() {
        compose.show(SavedSourcesState.Ready(saved = twoSources(), activeId = "b"))

        compose.onAllNodesWithTag(ActivationTags.SAVED_DELETE)[0].performClick()

        compose.onNodeWithTag(ActivationTags.SAVED_DELETE_DIALOG).assertIsDisplayed()
        compose.onNodeWithText("Delete this subscription?").assertIsDisplayed()
    }

    /** Deleting the one in use says so, rather than refusing the press. */
    @Test
    fun `deleting the subscription in use warns that it is the one in use`() {
        compose.show(SavedSourcesState.Ready(saved = twoSources(), activeId = "a"))

        compose.onAllNodesWithTag(ActivationTags.SAVED_DELETE)[0].performClick()

        compose.onNodeWithText("This is the subscription in use").assertIsDisplayed()
        compose.onNodeWithText("Delete this subscription?").assertDoesNotExist()
    }

    /** Confirming reports the id, and the screen writes nothing itself. */
    @Test
    fun `confirming a delete reports that subscription`() {
        val deleted = mutableListOf<String>()
        compose.show(
            state = SavedSourcesState.Ready(saved = twoSources(), activeId = "b"),
            onDelete = { deleted += it },
        )

        compose.onAllNodesWithTag(ActivationTags.SAVED_DELETE)[0].performClick()
        // Every row draws the word "Delete" under its bin, so the one inside the
        // dialog has to be named as such rather than found by its text alone.
        compose.onNode(
            hasText("Delete") and
                hasClickAction() and
                hasAnyAncestor(hasTestTag(ActivationTags.SAVED_DELETE_DIALOG)),
        ).performClick()

        assertEquals(listOf("a"), deleted)
    }

    /* -------------------------------------------------------------------------- */

    private fun twoSources() = listOf(
        ProviderSource(id = "a", kind = SourceKind.XTREAM, label = "Home", url = "http://one.example"),
        ProviderSource(id = "b", kind = SourceKind.M3U_URL, label = "Cabin", url = "http://two.example"),
    )

    private fun ComposeContentTestRule.show(
        state: SavedSourcesState,
        onChoose: (String) -> Unit = {},
        onRename: (String, String) -> Unit = { _, _ -> },
        onDelete: (String) -> Unit = {},
        onAddXtream: () -> Unit = {},
        onAddPlaylist: () -> Unit = {},
    ) = setContent {
        CastivioTheme {
            CompositionLocalProvider(LocalDeviceClass provides DeviceClass.Expanded) {
                Stage {
                    SavedSourcesScreen(
                        state = state,
                        onChoose = onChoose,
                        onRename = onRename,
                        onDelete = onDelete,
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
