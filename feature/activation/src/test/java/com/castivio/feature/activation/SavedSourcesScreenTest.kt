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
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
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
     * It says so, and it keeps the way back — which is now the only way to add one. The
     * two add buttons that used to sit under the list are gone deliberately; see the
     * assertion below that keeps them gone.
     */
    @Test
    fun `with nothing saved it says so and keeps the way back`() {
        compose.show(SavedSourcesState.Ready(saved = emptyList(), activeId = null))

        compose.onNodeWithTag(ActivationTags.SAVED_EMPTY).assertIsDisplayed()
        compose.onNodeWithTag(ActivationTags.SAVED_BACK).assertIsDisplayed()
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
     * **This screen does not add subscriptions, and that is the assertion.**
     *
     * It used to, with two buttons under the list that called the same two flows the
     * source choice calls. The replacement for that test is this one rather than
     * nothing, because the reason they went is a decision and not an oversight: the
     * chooser one step back is where a subscription is added, and this screen is the
     * fourth card on it. Two places offering the same two options is one decision asked
     * twice, and the first edit to either is where they start disagreeing.
     *
     * Asserted on the empty state, because that is where the temptation to put them
     * back will come from — a list with nothing in it and no control to fix that. The
     * way out is Back, which the test above holds.
     */
    @Test
    fun `it offers no way to add a subscription`() {
        compose.show(SavedSourcesState.Ready(saved = emptyList(), activeId = null))

        compose.onNodeWithText("Add Xtream").assertDoesNotExist()
        compose.onNodeWithText("Add M3U").assertDoesNotExist()
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

    // --------------------------------------------------------------- the row's columns

    /**
     * The column that is usually empty still holds its share.
     *
     * The row lays its facts out in columns so the width is carried by them rather than
     * by a void between the name and the two actions. Every one of those columns is the
     * same share of every row, and the one that tests that is the "in use" mark: it is
     * on one row and absent from the others, so if the kind badge after it starts at the
     * same place on both, the empty slot kept its place and every column after it is
     * aligned too.
     *
     * A slot that collapsed when it was empty would put the kind in a different place on
     * every line, which is the one way this layout can quietly stop being a layout.
     */
    @Test
    fun `the kind badge starts at the same place whether or not the row is in use`() {
        compose.show(SavedSourcesState.Ready(saved = twoSources(), activeId = "a"))

        val kinds = compose.onAllNodesWithTag(ActivationTags.SAVED_KIND)
        val active = kinds[0].getUnclippedBoundsInRoot()
        val plain = kinds[1].getUnclippedBoundsInRoot()

        assertEquals(
            "the mark's column collapsed: the kind moved by ${plain.left - active.left}",
            active.left.value,
            plain.left.value,
            1f,
        )
    }

    // ------------------------------------------------------- the keyboard's surface

    /**
     * The rename dialog on what a keyboard leaves behind.
     *
     * Measured on the frame this ships to: a landscape handset is 393dp tall and the
     * keyboard takes 212 of them. What the dialog must show measures 178dp and what it
     * measures with its sentence in it is 210, so on a surface this short the sentence
     * is the thing that goes — and the field the dialog was opened for, with both
     * buttons that answer it, are the things that do not.
     *
     * The surface is shortened rather than a keyboard raised, because that is the same
     * claim without an emulator: the panel asks how much room there is, not whether an
     * IME is up, and a test that shortens the room tests the rule rather than the cause.
     */
    @Test
    fun `on a surface a keyboard has shortened the rename dialog drops only its sentence`() {
        compose.show(SavedSourcesState.Ready(saved = twoSources(), activeId = "b"), height = 181.dp)

        compose.onAllNodesWithTag(ActivationTags.SAVED_EDIT)[0].performClick()

        compose.onNodeWithText("The name you see in", substring = true).assertDoesNotExist()
        compose
            .onNodeWithTag(ActivationTags.SAVED_RENAME_FIELD, useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    /** And with room for it, it is still there. A sentence dropped always is a sentence cut. */
    @Test
    fun `with room the rename dialog keeps its sentence`() {
        compose.show(SavedSourcesState.Ready(saved = twoSources(), activeId = "b"))

        compose.onAllNodesWithTag(ActivationTags.SAVED_EDIT)[0].performClick()

        compose.onNodeWithText("The name you see in", substring = true).assertIsDisplayed()
    }

    /**
     * A confirmation keeps its question on any surface.
     *
     * The rule is "the dialog that asks for a value drops its sentence", not "a short
     * dialog drops its sentence", and the difference matters: a confirmation's message
     * *is* the question, and a dialog with no field never has a keyboard in front of it
     * to be shortened by. This is the assertion that keeps the two apart.
     */
    @Test
    fun `a confirmation keeps its question even on a short surface`() {
        compose.show(SavedSourcesState.Ready(saved = twoSources(), activeId = "b"), height = 181.dp)

        compose.onAllNodesWithTag(ActivationTags.SAVED_DELETE)[0].performClick()

        compose.onNodeWithText("It will be removed from this device only.").assertIsDisplayed()
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
        height: Dp = 393.dp,
    ) = setContent {
        CastivioTheme {
            CompositionLocalProvider(LocalDeviceClass provides DeviceClass.Expanded) {
                Stage(height) {
                    SavedSourcesScreen(
                        state = state,
                        onChoose = onChoose,
                        onRename = onRename,
                        onDelete = onDelete,
                        onBack = {},
                    )
                }
            }
        }
    }

    /**
     * The reporter's frame, which is the tightest this screen is drawn at.
     *
     * The height is a parameter because one claim on this screen is about what is left
     * of the frame rather than about the frame: a keyboard takes 212 of a handset's
     * 393dp, and shortening the stage is how that is asserted without an emulator.
     */
    @Composable
    private fun Stage(height: Dp = 393.dp, content: @Composable () -> Unit) {
        Box(Modifier.requiredSize(827.dp, height)) { content() }
    }
}
