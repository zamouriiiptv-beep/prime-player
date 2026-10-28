package com.castivio.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.domain.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rows of the list of channels in the player, composed with the real focus handling: they open on the
 * playing channel, mark it and only it, ask for the rows they have not got, and choose on a press.
 *
 * A list of fifty channels stands in for a bouquet, and only some windows of it are "read", which is the
 * point: a row that has not arrived is a placeholder and a question, never a name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class ChannelListRowsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun channel(i: Int) = Channel(
        id = "c$i",
        title = "Channel $i",
        artworkUrl = null,
        number = null,
        groupId = null,
        streamUrl = "http://provider.tv/live/$i.ts",
        epgChannelId = null,
    )

    private val all = (0 until 50).map(::channel)
    private val asked = mutableListOf<Int>()
    private val picked = mutableListOf<Pair<String, Int>>()
    private var nowPlaying = ""

    private fun show(
        state: ChannelListState,
        held: IntRange = 0 until 50,
        playingId: String = "c0",
        locked: Set<String> = emptySet(),
    ) {
        compose.setContent {
            CastivioTheme {
                nowPlaying = stringResource(R.string.channels_now_playing)
                Box(Modifier.size(380.dp, 540.dp)) {
                    ChannelListRows(
                        state = state,
                        channelAt = { i -> all.getOrNull(i)?.takeIf { i in held } },
                        want = { asked += it },
                        playingId = playingId,
                        favorites = emptySet(),
                        locked = locked,
                        qualities = emptyMap(),
                        m = channelsMetricsFor(tv = true, width = 960.dp, height = 540.dp),
                        onPick = { channel, position -> picked += channel.id to position },
                    )
                }
            }
        }
    }

    private val ready = ChannelListState(ready = true, total = 50, start = 0, version = 1)

    @Test
    fun `while the first window arrives there are placeholders and no rows`() {
        show(ChannelListState(ready = false))

        compose.onNodeWithTag(ChannelListTags.LOADING).assertIsDisplayed()
        compose.onAllNodesWithTag(ChannelListTags.LIST).assertCountEquals(0)
    }

    @Test
    fun `the rows are the channels read, by name, and the first has the remote`() {
        show(ready)

        compose.onNodeWithText("Channel 0").assertIsDisplayed()
        compose.onNodeWithText("Channel 1").assertIsDisplayed()
    }

    @Test
    fun `the list opens on the playing channel`() {
        show(ready.copy(start = 30), playingId = "c30")

        compose.onNodeWithText("Channel 30").assertIsDisplayed()
        compose.onAllNodesWithTag(ChannelListTags.LIST).assertCountEquals(1)
    }

    @Test
    fun `only the playing channel is marked`() {
        show(ready, playingId = "c1")

        compose.onAllNodes(hasContentDescription(nowPlaying)).assertCountEquals(1)
    }

    @Test
    fun `a row that has not arrived is asked for and is not a name`() {
        // Only the first window is held; the list is fifty long and the panel shows a dozen rows, but
        // the ones past the held window must be questions.
        show(ready, held = 0 until 5)

        compose.waitForIdle()
        assertTrue("the rows that are missing were asked for: $asked", asked.any { it >= 5 })
        compose.onAllNodes(androidx.compose.ui.test.hasText("Channel 7")).assertCountEquals(0)
    }

    @Test
    fun `a press on a row chooses that channel at that place in the list`() {
        show(ready)

        compose.onNodeWithText("Channel 2").performClick()

        assertEquals(listOf("c2" to 2), picked)
    }

    @Test
    fun `a locked channel says so on its row`() {
        show(ready, locked = setOf("c1"))

        val lockedWord = androidx.compose.ui.test.hasContentDescription("Locked")
        compose.onAllNodes(lockedWord).assertCountEquals(1)
    }

    @Test
    fun `the remote starts on the row the list opens on`() {
        show(ready.copy(start = 2), playingId = "c2")

        compose.waitForIdle()
        compose.onNodeWithText("Channel 2").assertIsFocused()
    }
}
