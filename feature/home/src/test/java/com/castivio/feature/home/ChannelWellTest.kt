package com.castivio.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.domain.Channel
import com.castivio.domain.NowNext
import com.castivio.domain.PlayerPulse
import com.castivio.domain.Programme
import com.castivio.domain.PulsePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The well is sized against the column it is in, which is not always the board's.
 *
 * A band that comes and goes over the three columns -- the reorder bar, a failed refresh's notice
 * -- takes its height from them. The picture used to be sized for the full column anyway, so it
 * kept its height and the facts under it lost theirs: in reorder mode on a handset the channel's
 * name ran into what was on, and what was next fell off the panel.
 *
 * Geometry only: the picture's frame is a fixed figure, and where it ends is Compose's answer
 * whatever a harness does to the size of text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w891dp-h411dp-land")
class ChannelWellTest {

    @get:Rule
    val compose = createComposeRule()

    /** The owner's handset, where the picture is bounded by the column rather than by its share. */
    private val m = channelsMetricsFor(tv = false, width = 891.dp, height = 411.dp)

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

    private fun show(height: Dp) {
        compose.setContent {
            CastivioTheme {
                ChannelWell(
                    shown = ChannelPreview(
                        channel = alpha,
                        guide = NowNext(
                            Programme("1", "Coup de foudre au marché de Noël", null, 0, 5_400_000),
                            Programme("1", "Star Academy, la quotidienne", null, 5_400_000, 9_000_000),
                        ),
                    ),
                    m = m,
                    pulse = PlayerPulse(url = alpha.streamUrl, phase = PulsePhase.SHOWING),
                    qualities = emptyMap(),
                    preview = player,
                    onOpenGuide = {},
                    onFavorite = {},
                    arranging = false,
                    onArrange = {},
                    onExpand = {},
                    modifier = Modifier.width(m.player).height(height),
                )
            }
        }
        compose.waitForIdle()
    }

    private val picture get() = compose.onNodeWithTag(PLAYER).getUnclippedBoundsInRoot()

    @Test
    fun `with nothing over the columns the picture is the board's`() {
        show(height = m.column)

        assertEquals(m.wellPicture.value, (picture.bottom - picture.top).value, DUST)
        assertEquals(m.wellPictureWidth.value, (picture.right - picture.left).value, DUST)
    }

    @Test
    fun `a band over the columns takes its height from the picture`() {
        val well = m.column - BAND
        val yielded = m.copy(column = well)
        assertTrue(
            "the band has to cost the picture something at this geometry for the test to say anything",
            yielded.wellPicture < m.wellPicture,
        )

        show(height = well)

        assertEquals(yielded.wellPicture.value, (picture.bottom - picture.top).value, DUST)
    }

    @Test
    fun `and not from the facts under it`() {
        val well = m.column - BAND

        show(height = well)

        val facts = well - (picture.bottom - picture.top) - m.strip - m.guideGap * 2
        assertTrue(
            "the facts block keeps $facts of a $well well, under $CHANNELS_FACTS_MIN",
            facts.value >= CHANNELS_FACTS_MIN.value - DUST,
        )
    }

    @Test
    fun `the picture keeps its shape when it yields`() {
        show(height = m.column - BAND)

        assertEquals(
            CHANNELS_PREVIEW_ASPECT,
            (picture.right - picture.left) / (picture.bottom - picture.top),
            ASPECT_DUST,
        )
    }

    private companion object {
        const val PLAYER = "test.player"

        /** About the reorder bar's height at this geometry: a label, a line of hint and its padding. */
        val BAND = 48.dp

        /** A pixel at the harness's density, which is where Compose rounds a Dp to. */
        const val DUST = 1f

        const val ASPECT_DUST = 0.02f
    }
}
