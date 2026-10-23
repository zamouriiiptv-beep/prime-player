package com.castivio.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.domain.Channel
import com.castivio.domain.PlayerPulse
import com.castivio.domain.PulsePhase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The preview plate says what is true of the channel: nothing of it is shown until the player has
 * drawn it, and it does not claim to be live before then.
 *
 * The plate used to be the page's near-black with the channel's name in a corner and a `LIVE`
 * badge over it -- a player that had gone black, saying the channel was working. Now the product's
 * own ground and the channel's mark stand in until a frame of *this* stream arrives, the badge is
 * made only then, and a stream that failed is left to the player's own card.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class WellPictureTest {

    @get:Rule
    val compose = createComposeRule()

    private val alpha = Channel(
        id = "1",
        title = "Alpha",
        artworkUrl = null,
        number = null,
        groupId = null,
        streamUrl = "http://provider.tv/live/1.ts",
        epgChannelId = null,
    )

    private val player: @Composable () -> Unit = { Box(Modifier.fillMaxSize().testTag(PLAYER)) }

    private fun show(shown: Channel?, pulse: PlayerPulse?, preview: (@Composable () -> Unit)?) {
        compose.setContent {
            CastivioTheme {
                val m = channelsMetricsFor(tv = true, width = 960.dp, height = 540.dp)
                WellPicture(
                    shown = ChannelPreview(channel = shown),
                    m = m,
                    pulse = pulse,
                    preview = preview,
                    modifier = Modifier.width(320.dp).height(180.dp),
                )
            }
        }
        compose.waitForIdle()
    }

    private val ground get() = compose.onNode(hasTestTag(PLATE_GROUND_TAG))
    private val live get() = compose.onNode(hasText("LIVE"))
    private val spinner get() = compose.onNode(hasContentDescription("Working"))

    private fun pulse(phase: PulsePhase, url: String = alpha.streamUrl, waiting: Boolean = false) =
        PlayerPulse(url = url, phase = phase, waiting = waiting)

    @Test
    fun `with nothing chosen the plate says what it waits for`() {
        show(shown = null, pulse = null, preview = null)

        compose.onNode(hasText("Press a channel to play it")).assertExists()
        ground.assertDoesNotExist()
        live.assertDoesNotExist()
    }

    @Test
    fun `a channel chosen but not playing is its mark on the product's ground, not live`() {
        show(shown = alpha, pulse = null, preview = null)

        ground.assertExists()
        live.assertDoesNotExist()
    }

    @Test
    fun `while the channel opens the ground stands over the player and it is not live`() {
        show(shown = alpha, pulse = pulse(PulsePhase.OPENING), preview = player)

        compose.onNode(hasTestTag(PLAYER)).assertExists()
        ground.assertExists()
        live.assertDoesNotExist()
        spinner.assertDoesNotExist()
    }

    @Test
    fun `an opening that has been silent says it is still working`() {
        show(shown = alpha, pulse = pulse(PulsePhase.OPENING, waiting = true), preview = player)

        ground.assertExists()
        spinner.assertExists()
    }

    @Test
    fun `the first frame takes the ground away and makes the claim`() {
        show(shown = alpha, pulse = pulse(PulsePhase.SHOWING), preview = player)

        ground.assertDoesNotExist()
        live.assertExists()
        spinner.assertDoesNotExist()
    }

    @Test
    fun `a stream that failed is the player's card to read, and is not live`() {
        show(shown = alpha, pulse = pulse(PulsePhase.STOPPED), preview = player)

        compose.onNode(hasTestTag(PLAYER)).assertExists()
        ground.assertDoesNotExist()
        live.assertDoesNotExist()
    }

    @Test
    fun `another channel's picture does not make this one live`() {
        show(shown = alpha, pulse = pulse(PulsePhase.SHOWING, url = "http://provider.tv/live/2.ts"), preview = player)

        live.assertDoesNotExist()
    }

    private companion object {
        const val PLAYER = "test.player"
    }
}
