package com.castivio.feature.player

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    fun `an opening begins as a card of two lines, and the table is not drawn`() {
        OpeningDiagnostics.publish(reading(OpeningStatus.SHOWN))

        compose.setContent { CastivioTheme { OpeningPanel() } }

        // How it stands, and how it was opened and ended -- the channel is on the screen, and the rest is under the press.
        compose.onNodeWithText("#3 · MEDIA3 · 0.27 s", substring = true).assertIsDisplayed()
        compose.onNodeWithText("engine reused · first frame", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("last 1:", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("main thread (from open)", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a press opens it to every line, with the table of the last ones beneath`() {
        OpeningDiagnostics.publish(reading(OpeningStatus.SHOWN))
        compose.setContent { CastivioTheme { OpeningPanel() } }

        compose.onNodeWithText("#3 · MEDIA3 · 0.27 s", substring = true).performClick()
        compose.waitForIdle()

        // The opening's own line, and its row in the table beneath.
        compose.onAllNodesWithText("MEDIA3 · engine reused", substring = true).assertCountEquals(2)
        compose.onNodeWithText("last 1:", substring = true).assertIsDisplayed()
        compose.onNodeWithText("main thread (from open)", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the mark at the corner of the opened card closes it again`() {
        OpeningDiagnostics.publish(reading(OpeningStatus.SHOWN))
        compose.setContent { CastivioTheme { OpeningPanel() } }
        compose.onNodeWithTag(OPENING_OPEN_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("last 1:", substring = true).assertIsDisplayed()

        compose.onNodeWithTag(OPENING_CLOSE_TAG).performClick()
        compose.waitForIdle()

        compose.onAllNodesWithText("last 1:", substring = true).assertCountEquals(0)
        compose.onNodeWithText("#3 · MEDIA3 · 0.27 s", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a failure is drawn as one, in the card as it is opened`() {
        OpeningDiagnostics.publish(reading(OpeningStatus.FAILED, failure = "TIMEOUT"))

        compose.setContent { CastivioTheme { OpeningPanel() } }

        compose.onNodeWithText("FAILED TIMEOUT", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(OPENING_OPEN_TAG).performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("FAILED TIMEOUT", substring = true)[0].assertIsDisplayed()
    }

    @Test
    fun `the hide mark takes the card away for that opening, and the next opening brings it back`() {
        OpeningDiagnostics.publish(reading(OpeningStatus.SHOWN))
        compose.setContent { CastivioTheme { OpeningPanel() } }

        compose.onNodeWithTag(OPENING_HIDE_TAG).performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("#3 · MEDIA3", substring = true).assertCountEquals(0)

        OpeningDiagnostics.publish(reading(OpeningStatus.SHOWN, id = 2, attempt = 4))
        compose.waitForIdle()

        compose.onNodeWithText("#4 · MEDIA3 · 0.27 s", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("last 2:", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a new opening brings the card back collapsed, even if the last one was opened`() {
        OpeningDiagnostics.publish(reading(OpeningStatus.SHOWN))
        compose.setContent { CastivioTheme { OpeningPanel() } }
        compose.onNodeWithTag(OPENING_OPEN_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("last 1:", substring = true).assertIsDisplayed()

        OpeningDiagnostics.publish(reading(OpeningStatus.SHOWN, id = 2, attempt = 4))
        compose.waitForIdle()

        compose.onNodeWithText("#4 · MEDIA3 · 0.27 s", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("last 2:", substring = true).assertCountEquals(0)
    }

    @Test
    fun `it goes by itself after the opening ends, unless it is open`() {
        OpeningDiagnostics.publish(reading(OpeningStatus.SHOWN))
        compose.setContent { CastivioTheme { OpeningPanel() } }
        compose.onNodeWithText("#3 · MEDIA3", substring = true).assertIsDisplayed()

        compose.mainClock.advanceTimeBy(LINGER_MS + 100)
        compose.waitForIdle()

        compose.onAllNodesWithText("#3 · MEDIA3", substring = true).assertCountEquals(0)
    }

    @Test
    fun `an open card stays however long it is left, and an opening that runs keeps its card`() {
        OpeningDiagnostics.publish(reading(OpeningStatus.SHOWN))
        compose.setContent { CastivioTheme { OpeningPanel() } }
        compose.onNodeWithTag(OPENING_OPEN_TAG).performClick()
        compose.waitForIdle()

        compose.mainClock.advanceTimeBy(LINGER_MS * 2)
        compose.waitForIdle()

        compose.onNodeWithText("last 1:", substring = true).assertIsDisplayed()

        OpeningDiagnostics.publish(reading(OpeningStatus.WAITING, id = 2, attempt = 4))
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(LINGER_MS * 2)
        compose.waitForIdle()

        compose.onNodeWithText("#4 · MEDIA3", substring = true).assertIsDisplayed()
    }

    private fun reading(status: OpeningStatus, failure: String? = null, id: Int = 1, attempt: Int = 3) = OpeningReading(
        id = id,
        attempt = attempt,
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
