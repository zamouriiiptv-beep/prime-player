package com.castivio.feature.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.domain.Channel
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reorder mode on the screen: the way in, what the rail and the rows look like and do while it is
 * on, the arrow keys on what is held, and the bar with its two ways out.
 *
 * The real rail, the real rows and the real bar are composed with the real focus handling.
 * What the holder does with what they report is `BrowseArrangeTest`'s.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class ReorderModeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun group(id: String, name: String) =
        MediaGroup(id = id, name = name, kind = MediaKind.LIVE, itemCount = 10)

    private val sport = group("sport", "Sport")
    private val news = group("news", "News")
    private val kids = group("kids", "Kids")

    private var state by mutableStateOf(BrowseState())
    private val chosen = mutableListOf<String?>()
    private val pressed = mutableListOf<Held>()
    private val moves = mutableListOf<Boolean>()
    private var arranged = 0

    /** The arrow presses that reached an ancestor, which is every one the row under it did not take. */
    private val bubbled = mutableListOf<Key>()

    private fun Modifier.watchingKeys() = onKeyEvent {
        if (it.type == KeyEventType.KeyDown) bubbled += it.key
        false
    }

    private fun mode(held: Held? = null, channelsOf: String? = "sport") = Arranging(
        bouquets = listOf("sport", "news", "kids"),
        initial = listOf("sport", "news", "kids"),
        channelsOf = channelsOf,
        held = held,
    )

    private fun showRail(arranging: Arranging? = null) {
        val groups = listOf(sport, news, kids)
        state = BrowseState(groups = groups, shownGroups = groups, arranging = arranging, loading = false)
        compose.setContent {
            CastivioTheme {
                val m = channelsMetricsFor(tv = true, width = 960.dp, height = 540.dp)
                CategoryRail(
                    state = state,
                    m = m,
                    onChoose = { chosen += it },
                    onFilter = {},
                    onRefresh = {},
                    onPress = { pressed += it },
                    onMove = { moves += it },
                    modifier = Modifier.width(m.rail).height(540.dp).watchingKeys(),
                )
            }
        }
        compose.waitForIdle()
    }

    // ------------------------------------------------------------------------------ the way in

    private fun showStrip(arranging: Boolean) {
        compose.setContent {
            CastivioTheme {
                val m = channelsMetricsFor(tv = true, width = 960.dp, height = 540.dp)
                ActionStrip(
                    shown = ChannelPreview(),
                    m = m,
                    onOpenGuide = {},
                    onFavorite = {},
                    arranging = arranging,
                    onArrange = { arranged++ },
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the way in is the strip's last button, and pressing it asks for the mode`() {
        showStrip(arranging = false)

        compose.onNodeWithText("Reorder").performClick()

        assertEquals(1, arranged)
    }

    @Test
    fun `the way in needs no channel in the picture`() {
        // `ChannelPreview()` has no channel: the guide, favourite, lock and hide are dark.
        showStrip(arranging = false)

        compose.onNodeWithText("Reorder").assertIsEnabled()
    }

    @Test
    fun `in the mode the strip's button is not pressed again`() {
        showStrip(arranging = true)

        compose.onNodeWithText("Reorder").assertIsNotEnabled()
    }

    @Test
    fun `the rail is as it was, with no row for the mode`() {
        showRail()

        compose.onNodeWithText("All Channels").assertExists()
        compose.onNodeWithText("Sport").assertExists()
        compose.onNode(hasSetTextAction()).assertExists()
        compose.onNodeWithText("Reorder").assertDoesNotExist()
    }

    // ------------------------------------------------------------------------- the rail in the mode

    @Test
    fun `in the mode the rail is the bouquets and nothing else`() {
        showRail(mode())

        compose.onNodeWithText("Sport").assertExists()
        compose.onNodeWithText("News").assertExists()
        compose.onNodeWithText("Kids").assertExists()
        compose.onNodeWithText("Reorder").assertDoesNotExist()
        compose.onNodeWithText("All Channels").assertDoesNotExist()
        compose.onNodeWithText("Favorites").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test
    fun `pressing a bouquet in the mode picks it up and does not open it`() {
        showRail(mode())

        compose.onNodeWithText("News").performClick()

        assertEquals(listOf<Held>(Held.Bouquet("news")), pressed)
        assertTrue("no bouquet was opened", chosen.isEmpty())
    }

    @Test
    fun `only the held bouquet shows the two taps`() {
        showRail(mode(held = Held.Bouquet("news")))

        // One pair, on the held row.
        compose.onAllNodes(hasContentDescription("Move up")).assertCountEquals(1)
        compose.onAllNodes(hasContentDescription("Move down")).assertCountEquals(1)
    }

    @Test
    fun `nothing held means no taps`() {
        showRail(mode())

        compose.onAllNodes(hasContentDescription("Move up")).assertCountEquals(0)
    }

    @Test
    fun `the taps move what is held`() {
        showRail(mode(held = Held.Bouquet("news")))

        compose.onNode(hasContentDescription("Move up")).performClick()
        compose.onNode(hasContentDescription("Move down")).performClick()

        assertEquals(listOf(true, false), moves)
    }

    @Test
    fun `the arrow keys move the held bouquet and the focus stays on it`() {
        showRail(mode(held = Held.Bouquet("news")))
        compose.onNodeWithText("News").requestFocus()

        compose.onNodeWithText("News").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("News").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("News").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()

        assertEquals(listOf(true, false, false), moves)
        compose.onNodeWithText("News").assertIsFocused()
        assertTrue("the held row took the keys: none reached the rail around it", bubbled.isEmpty())
    }

    @Test
    fun `without anything held the arrow keys move nothing`() {
        showRail(mode())
        compose.onNodeWithText("Sport").requestFocus()

        compose.onNodeWithText("Sport").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()

        // Not consumed: the key is the platform's, as it is on every row that is not held.
        assertTrue(moves.isEmpty())
        assertEquals(listOf(Key.DirectionDown), bubbled)
    }

    // ----------------------------------------------------------------------------------- the rows

    private fun channel(id: String, title: String) = Channel(
        id = id, title = title, artworkUrl = null, number = null, groupId = "sport",
        streamUrl = "http://provider.tv/live/$id.ts", epgChannelId = null,
    )

    private fun showRows(held: String?) {
        compose.setContent {
            CastivioTheme {
                val m = channelsMetricsFor(tv = true, width = 960.dp, height = 540.dp)
                Column(Modifier.watchingKeys()) {
                    for ((index, c) in listOf(channel("1", "Alpha"), channel("2", "Beta"), channel("3", "Gamma")).withIndex()) {
                        ChannelRow(
                            channel = c,
                            number = index + 1,
                            playing = false,
                            m = m,
                            onClick = { pressed += Held.Channel(c.id) },
                            arrange = RowArrange(held = held == c.id, onMove = { moves += it }),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `a channel row in the mode is pressed to be picked up`() {
        showRows(held = null)

        compose.onNodeWithText("Beta").performClick()

        assertEquals(listOf<Held>(Held.Channel("2")), pressed)
    }

    @Test
    fun `only the held channel shows the two taps`() {
        showRows(held = "2")

        compose.onAllNodes(hasContentDescription("Move up")).assertCountEquals(1)
    }

    @Test
    fun `the arrow keys move the held channel and are not taken from a row that is not held`() {
        showRows(held = "2")
        compose.onNodeWithText("Beta").requestFocus()

        compose.onNodeWithText("Beta").performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitForIdle()

        assertEquals(listOf(true), moves)
        compose.onNodeWithText("Beta").assertIsFocused()
        assertTrue("the held row took the key", bubbled.isEmpty())

        compose.onNodeWithText("Gamma").requestFocus()
        compose.onNodeWithText("Gamma").performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitForIdle()

        assertEquals("a row that is not held moves nothing", listOf(true), moves)
        assertEquals("and leaves the key to the platform", listOf(Key.DirectionUp), bubbled)
    }

    @Test
    fun `a row outside the mode is not told, and draws no handle`() {
        compose.setContent {
            CastivioTheme {
                val m = channelsMetricsFor(tv = true, width = 960.dp, height = 540.dp)
                ChannelRow(channel = channel("1", "Alpha"), number = 1, playing = false, m = m, onClick = {})
            }
        }

        compose.onAllNodes(hasContentDescription("Move up")).assertCountEquals(0)
    }

    // ------------------------------------------------------------------------------------ the bar

    private var saved = 0
    private var cancelled = 0

    private fun showBar(mode: Arranging, direction: LayoutDirection = LayoutDirection.Ltr) {
        compose.setContent {
            CastivioTheme {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    val m = channelsMetricsFor(tv = true, width = 960.dp, height = 540.dp)
                    ReorderBar(mode = mode, m = m, onSave = { saved++ }, onCancel = { cancelled++ })
                }
            }
        }
        compose.waitForIdle()
    }

    private fun barOrder(): List<Float> = listOf(
        compose.onNodeWithText("Press OK on a category or a channel to pick it up.").getUnclippedBoundsInRoot().left.value,
        compose.onNodeWithText("Cancel").getUnclippedBoundsInRoot().left.value,
        compose.onNodeWithText("Save").getUnclippedBoundsInRoot().left.value,
    )

    @Test
    fun `the bar is laid out like the board, the hint first and save at the end`() {
        showBar(mode())

        val (hint, cancel, save) = barOrder()
        assertTrue("hint $hint, cancel $cancel, save $save", hint < cancel && cancel < save)
    }

    @Test
    fun `in a right-to-left language the bar does not turn round`() {
        showBar(mode(), LayoutDirection.Ltr)
        val ltr = barOrder()

        showBar(mode(), LayoutDirection.Rtl)
        val rtl = barOrder()

        // The board does not mirror, and the bar is part of it: the same places in both.
        assertEquals(ltr, rtl)
    }

    @Test
    fun `with nothing held the bar says what to pick up`() {
        showBar(mode())

        compose.onNode(hasText("Press OK on a category or a channel to pick it up.")).assertExists()
        compose.onNode(hasText("Open a category first to reorder its channels.")).assertDoesNotExist()
    }

    @Test
    fun `with something held the bar says how to move it`() {
        showBar(mode(held = Held.Channel("c1")))

        compose.onNode(hasText("Move it with ↑ and ↓, then press OK to put it down.")).assertExists()
        compose.onNode(hasText("Press OK on a category or a channel to pick it up.")).assertDoesNotExist()
    }

    @Test
    fun `with all open the bar says the channels need a category`() {
        showBar(mode(channelsOf = null))

        compose.onNode(hasText("Open a category first to reorder its channels.")).assertExists()
    }

    @Test
    fun `save and cancel are on the bar and do what they say`() {
        showBar(mode())

        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Cancel").performClick()

        assertEquals(1, saved)
        assertEquals(1, cancelled)
    }

    @Test
    fun `while it is being saved cancel is not offered`() {
        showBar(mode().copy(saving = true))

        compose.onNodeWithText("Cancel").assertIsNotEnabled()
    }

    @Test
    fun `a save that failed is said on the bar and the buttons are live again`() {
        showBar(mode().copy(failed = true))

        compose.onNode(hasText("Couldn't save the order. Try again.")).assertExists()
        compose.onNodeWithText("Cancel").assertIsEnabled()
        compose.onNodeWithText("Save").assertIsEnabled()
    }
}
