package com.castivio.feature.home

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.domain.Channel
import com.castivio.domain.StreamQuality
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The channel row, composed with the real focus handling: the mark is the player's and the
 * remote does not move it.
 *
 * Three rows stand in for a list. What each one is told is the player's stream, as the board
 * hands it down; what moves the remote is the real focus manager, which is what the arrow
 * keys drive. Colours are not asserted -- the rule behind them is in [ChannelRowLookTest] --
 * but the mark is a node with a description, so where it is and whether it is there are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class ChannelRowTest {

    @get:Rule
    val compose = createComposeRule()

    private fun channel(id: String, title: String) = Channel(
        id = id,
        title = title,
        artworkUrl = null,
        number = null,
        groupId = null,
        streamUrl = "http://provider.tv/live/$id.ts",
        epgChannelId = null,
    )

    private val alpha = channel("1", "Alpha")
    private val beta = channel("2", "Beta")
    private val gamma = channel("3", "Gamma")

    /** The provider lists Alpha's stream a second time, as a different row. */
    private val twin = Channel(
        id = "4",
        title = "Twin",
        artworkUrl = null,
        number = null,
        groupId = null,
        streamUrl = alpha.streamUrl,
        epgChannelId = null,
    )

    private var playing by mutableStateOf<String?>(null)
    private var pressed by mutableStateOf<String?>(null)
    private var focus: FocusManager? = null
    private var mark = ""

    private fun show() {
        compose.setContent {
            CastivioTheme {
                focus = LocalFocusManager.current
                mark = stringResource(R.string.channels_now_playing)
                val m = channelsMetricsFor(tv = true, width = 960.dp, height = 540.dp)
                Column {
                    listOf(alpha, beta, gamma, twin).forEachIndexed { index, channel ->
                        ChannelRow(
                            channel = channel,
                            number = index + 1,
                            playing = isPlayingRow(channel.id, channel.streamUrl, pressed, playing),
                            m = m,
                            onClick = {},
                        )
                    }
                }
            }
        }
    }

    /** A channel pressed and playing: the player's stream, and the row that started it. */
    private fun setPlaying(channel: Channel?) {
        compose.runOnIdle {
            pressed = channel?.id
            playing = channel?.streamUrl
        }
        compose.waitForIdle()
    }

    private fun marks() = compose.onAllNodes(hasContentDescription(mark))

    private fun rowOf(name: String) = compose.onNode(hasText(name) and hasContentDescription(mark))

    @Test
    fun `with nothing playing no row carries the mark`() {
        show()
        marks().assertCountEquals(0)
    }

    @Test
    fun `only the playing row carries it`() {
        show()
        setPlaying(beta)

        marks().assertCountEquals(1)
        rowOf("Beta").assertExists()
    }

    @Test
    fun `the remote moving down the list does not move the mark`() {
        show()
        setPlaying(alpha)
        compose.onNodeWithText("Alpha").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()

        compose.runOnIdle { focus!!.moveFocus(FocusDirection.Down) }
        compose.waitForIdle()
        compose.onNodeWithText("Beta").assertIsFocused()
        marks().assertCountEquals(1)
        rowOf("Alpha").assertExists()

        compose.runOnIdle { focus!!.moveFocus(FocusDirection.Down) }
        compose.waitForIdle()
        compose.onNodeWithText("Gamma").assertIsFocused()
        rowOf("Alpha").assertExists()
    }

    @Test
    fun `the mark moves when another channel plays and goes when none does`() {
        show()
        setPlaying(alpha)
        rowOf("Alpha").assertExists()

        setPlaying(gamma)
        marks().assertCountEquals(1)
        rowOf("Gamma").assertExists()

        setPlaying(null)
        marks().assertCountEquals(0)
    }

    @Test
    fun `a focused row that is not playing has no mark`() {
        show()
        setPlaying(alpha)
        compose.onNodeWithText("Gamma").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()

        compose.onNodeWithText("Gamma").assertIsFocused()
        marks().assertCountEquals(1)
        rowOf("Alpha").assertExists()
    }

    @Test
    fun `the playing row and the focused row can be two different rows`() {
        show()
        setPlaying(alpha)
        compose.onNodeWithText("Gamma").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()

        compose.onNodeWithText("Gamma").assertIsFocused()
        marks().assertCountEquals(1)
        rowOf("Alpha").assertExists()
        compose.onNode(hasText("Gamma") and hasContentDescription(mark)).assertDoesNotExist()
    }

    @Test
    fun `a stream listed twice carries the mark once`() {
        show()
        setPlaying(alpha)

        marks().assertCountEquals(1)
        rowOf("Alpha").assertExists()

        setPlaying(twin)
        marks().assertCountEquals(1)
        rowOf("Twin").assertExists()
    }

    /* ------------------------------------------------------------------- the badge */

    private fun showWith(title: String, quality: StreamQuality?) {
        val row = channel("9", title)
        compose.setContent {
            CastivioTheme {
                val m = channelsMetricsFor(tv = true, width = 960.dp, height = 540.dp)
                ChannelRow(channel = row, number = 1, playing = false, m = m, onClick = {}, quality = quality)
            }
        }
    }

    @Test
    fun `a measured channel draws its class, and the name stays as the provider wrote it`() {
        showWith("Alpha UHD", StreamQuality.FHD)

        compose.onNode(hasText("FHD")).assertExists()
        compose.onNode(hasText("Alpha UHD")).assertExists()
    }

    @Test
    fun `the four classes are the four words`() {
        val classes = listOf(StreamQuality.SD to "SD", StreamQuality.HD to "HD", StreamQuality.FHD to "FHD", StreamQuality.UHD to "4K")
        compose.setContent {
            CastivioTheme {
                val m = channelsMetricsFor(tv = true, width = 960.dp, height = 540.dp)
                Column {
                    classes.forEachIndexed { index, (quality, _) ->
                        ChannelRow(
                            channel = channel("c$index", "Row $index"),
                            number = index + 1,
                            playing = false,
                            m = m,
                            onClick = {},
                            quality = quality,
                        )
                    }
                }
            }
        }

        for ((index, entry) in classes.withIndex()) {
            val word = entry.second
            compose.onNode(hasText("Row $index") and hasText(word)).assertExists()
        }
    }

    @Test
    fun `a channel never measured has no badge, whatever its name says`() {
        showWith("Alpha 4K UHD FHD HD", null)

        compose.onNode(hasText("Alpha 4K UHD FHD HD")).assertExists()
        for (word in listOf("SD", "HD", "FHD", "4K", "UHD")) compose.onNode(hasText(word)).assertDoesNotExist()
    }

    // ------------------------------------------------------------------------------ the favourite star

    private var star = ""

    /** Alpha a favourite and Beta not; [pick] is what both rows are told, as the picking mode tells them. */
    private fun showFavorites(pick: (Channel) -> RowPick? = { null }) {
        compose.setContent {
            CastivioTheme {
                star = stringResource(R.string.channels_favorites)
                val m = channelsMetricsFor(tv = true, width = 960.dp, height = 540.dp)
                Column {
                    listOf(alpha, beta).forEachIndexed { index, channel ->
                        ChannelRow(
                            channel = channel,
                            number = index + 1,
                            playing = false,
                            m = m,
                            onClick = {},
                            favorite = channel == alpha,
                            pick = pick(channel),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `a favourite's row carries the star, and another row does not`() {
        showFavorites()

        compose.onNode(hasText("Alpha") and hasContentDescription(star)).assertExists()
        compose.onNode(hasText("Beta") and hasContentDescription(star)).assertDoesNotExist()
        compose.onAllNodes(hasContentDescription(star)).assertCountEquals(1)
    }

    @Test
    fun `while picking favourites a favourite still has one star, not two`() {
        showFavorites { RowPick(picked = false, favorite = it == alpha) }

        compose.onAllNodes(hasContentDescription(star)).assertCountEquals(1)
        compose.onNode(hasText("Alpha") and hasContentDescription(star)).assertExists()
    }

    @Test
    fun `while choosing what to hide a favourite keeps its star beside the circle`() {
        showFavorites { RowPick(picked = false, favorite = false) }

        compose.onNode(hasText("Alpha") and hasContentDescription(star)).assertExists()
        compose.onAllNodes(hasContentDescription(star)).assertCountEquals(1)
    }
}
