package com.castivio.feature.player

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.playback.api.EngineId
import com.castivio.playback.api.OpeningMarks
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The panel over the big player draws what [OpeningDiagnostics] holds, when it is on, and nothing otherwise.
 *
 * Whether the lines say the right thing is [OpeningReadingTest]'s; this is only that they reach the screen, and that
 * an instrument that is off or has nothing to say leaves the picture alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-land")
class OpeningPanelTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        OpeningDiagnostics.reset()
        OpeningDiagnostics.enable(true)
    }

    @After
    fun tearDown() {
        OpeningDiagnostics.reset()
        OpeningDiagnostics.enable(false)
    }

    @Test
    fun `before the first opening the panel draws nothing`() {
        compose.setContent { CastivioTheme { OpeningPanel() } }

        compose.onAllNodesWithText("Al Aoula", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("last", substring = true).assertCountEquals(0)
    }

    @Test
    fun `switched off, the panel draws nothing however much it holds`() {
        OpeningDiagnostics.publish(reading(OpeningStatus.SHOWN))
        OpeningDiagnostics.enable(false)

        compose.setContent { CastivioTheme { OpeningPanel() } }

        compose.onAllNodesWithText("Al Aoula", substring = true).assertCountEquals(0)
    }

    @Test
    fun `an opening is drawn with the table of the last ones beneath it`() {
        OpeningDiagnostics.publish(reading(OpeningStatus.SHOWN))

        compose.setContent { CastivioTheme { OpeningPanel() } }

        // The opening's own line, and its row in the table beneath.
        compose.onAllNodesWithText("MEDIA3 · engine reused", substring = true).assertCountEquals(2)
        compose.onNodeWithText("last 1:", substring = true).assertIsDisplayed()
        compose.onNodeWithText("main thread (from open)", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a failure is drawn as one`() {
        OpeningDiagnostics.publish(reading(OpeningStatus.FAILED, failure = "TIMEOUT"))

        compose.setContent { CastivioTheme { OpeningPanel() } }

        compose.onAllNodesWithText("FAILED TIMEOUT", substring = true)[0].assertIsDisplayed()
    }

    private fun reading(status: OpeningStatus, failure: String? = null) = OpeningReading(
        id = 1,
        attempt = 3,
        channel = "Al Aoula",
        case = OpeningCase.REUSED,
        status = status,
        totalMs = 270,
        timing = OpeningTiming(
            engine = EngineId.PRIMARY, fallback = false, reused = true,
            pressToOpenMs = 3, releaseMs = 12, setupMs = 40, prepareMs = 60,
            marks = OpeningMarks(serverAnsweredMs = 40, firstFrameMs = 210),
            firstFrameMs = 270,
        ),
        stalls = MainThreadStalls(),
        failure = failure,
    )
}
