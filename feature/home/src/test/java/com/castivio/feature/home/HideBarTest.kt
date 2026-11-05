package com.castivio.feature.home

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The band over the board while it is choosing what to hide: the one-row band the reorder mode's is too ([ModeBand]), its one
 * sentence, and its two buttons -- Hide selected dark until something is ticked.
 *
 * What the holder does with a press is `HidingUiTest`'s; this is what the band shows and that its buttons reach it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class HideBarTest {

    @get:Rule
    val compose = createComposeRule()

    private var confirmed = 0
    private var cancelled = 0

    private fun showBar(hiding: Hiding, width: Int = 960, height: Int = 540) {
        val m = channelsMetricsFor(tv = width >= 960, width = width.dp, height = height.dp)
        compose.setContent {
            CastivioTheme {
                HideBar(hiding = hiding, m = m, onConfirm = { confirmed++ }, onCancel = { cancelled++ })
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the band says the word, one sentence, and nothing about pressing a button that is beside it`() {
        showBar(Hiding())

        compose.onNodeWithText("Hide bouquets and channels").assertExists()
        compose.onNode(hasText(HINT)).assertExists()
        compose.onNode(hasText("Tick what to hide, then press Hide selected.", substring = true)).assertDoesNotExist()
    }

    @Test
    fun `the band is laid out like the board, the word first, the hint, then the two buttons at the end`() {
        showBar(Hiding())

        val word = compose.onNodeWithText("Hide bouquets and channels").getUnclippedBoundsInRoot().left.value
        val hint = compose.onNode(hasText(HINT)).getUnclippedBoundsInRoot().left.value
        val cancel = compose.onNodeWithText("Cancel").getUnclippedBoundsInRoot().left.value
        val hide = compose.onNodeWithText("Hide selected").getUnclippedBoundsInRoot().left.value

        assertTrue("word $word, hint $hint, cancel $cancel, hide $hide", word < hint && hint < cancel && cancel < hide)
    }

    @Test
    fun `hide selected has nothing to do until something is ticked`() {
        showBar(Hiding())

        compose.onNodeWithText("Hide selected").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsEnabled()
    }

    @Test
    fun `with a bouquet ticked hide selected is live and does what it says`() {
        showBar(Hiding(bouquets = setOf("sport")))

        compose.onNodeWithText("Hide selected").assertIsEnabled()
        compose.onNodeWithText("Hide selected").performClick()
        compose.onNodeWithText("Cancel").performClick()

        assertEquals(1, confirmed)
        assertEquals(1, cancelled)
    }

    @Test
    fun `the hint stays on one line however narrow the room`() {
        val m = channelsMetricsFor(tv = false, width = 360.dp, height = 640.dp)
        compose.setContent {
            CastivioTheme {
                HideBar(hiding = Hiding(), m = m, onConfirm = {}, onCancel = {})
            }
        }
        compose.waitForIdle()

        // Two lines of type are over twice its size; one is under. The sentence is longer than 360dp has room for.
        val high = compose.onNode(hasText(HINT)).getUnclippedBoundsInRoot().height.value
        assertTrue("the hint is $high dp high with a type size of ${m.frame.fsBody.value}", high < m.frame.fsBody.value * 2f)
    }

    private companion object {
        const val HINT = "Tick what to hide. Nothing is deleted; Settings brings it back."
    }
}
