package com.castivio.feature.home

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.castivio.core.common.format.CastivioDates
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.domain.Programme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Under the picture: what is on -- its title, over the word that says so, its hours and its
 * bar -- and then what is next, on one line: the word, its hours and its title. The words and
 * the hours are cells that line up from one line to the other.
 *
 * Positions, not sizes: where each cell is relative to the others is Compose's answer
 * whatever a harness does to the size of text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class ProgrammeTableTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = Programme(
        channelId = "1",
        title = "Coup de foudre au marché de Noël",
        description = "Not drawn here",
        startMs = 0,
        stopMs = 5_400_000,
    )
    private val next = Programme(
        channelId = "1",
        title = "Star Academy, la quotidienne",
        description = null,
        startMs = 5_400_000,
        stopMs = 9_000_000,
    )

    private fun show(now: Programme?, next: Programme?) {
        compose.setContent {
            CastivioTheme {
                val m = channelsMetricsFor(tv = true, width = 960.dp, height = 540.dp)
                ProgrammeTable(now = now, next = next, progress = 0.4f, m = m, modifier = Modifier.width(m.player))
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(text: String) = compose.onNodeWithText(text).getUnclippedBoundsInRoot()

    /** As the table prints them: twenty-four hours, Western digits, in the device's zone. */
    private fun hours(programme: Programme) =
        "${CastivioDates.time(programme.startMs)} – ${CastivioDates.time(programme.stopMs)}"

    @Test
    fun `what is on comes first and what is next under it`() {
        show(now, next)

        assertTrue(bounds("NOW").top < bounds("NEXT").top)
        assertTrue(bounds(now.title).top < bounds(next.title).top)
        assertTrue("what is next starts under what is on", bounds("NEXT").top >= bounds("NOW").bottom)
    }

    @Test
    fun `the title of what is on is over the word that says so`() {
        show(now, next)

        assertTrue("the title ends above the word", bounds(now.title).bottom <= bounds("NOW").top)
    }

    @Test
    fun `what is on is told by its word then its hours, and what is next by its word then its title`() {
        show(now, next)

        assertTrue("NOW comes before its hours", bounds("NOW").right <= bounds(hours(now)).left)
        assertTrue("NEXT comes before its hours", bounds("NEXT").right <= bounds(hours(next)).left)
        assertTrue("the hours come before the title", bounds(hours(next)).right <= bounds(next.title).left)
    }

    @Test
    fun `the hours of both lines start on one edge`() {
        show(now, next)

        assertEquals(bounds(hours(now)).left, bounds(hours(next)).left)
    }

    @Test
    fun `the words of both lines start on one edge`() {
        show(now, next)

        assertEquals(bounds("NOW").left, bounds("NEXT").left)
    }

    @Test
    fun `the synopsis is not drawn under the picture`() {
        show(now, next)

        compose.onNodeWithText("Not drawn here").assertDoesNotExist()
    }

    @Test
    fun `with nothing on the line of what is next stands alone`() {
        show(now = null, next = next)

        compose.onNodeWithText("NOW").assertDoesNotExist()
        compose.onNodeWithText("NEXT").assertExists()
        compose.onNodeWithText(next.title).assertExists()
    }

    @Test
    fun `with nothing next the line of what is on stands alone`() {
        show(now = now, next = null)

        compose.onNodeWithText("NOW").assertExists()
        compose.onNodeWithText("NEXT").assertDoesNotExist()
    }
}
