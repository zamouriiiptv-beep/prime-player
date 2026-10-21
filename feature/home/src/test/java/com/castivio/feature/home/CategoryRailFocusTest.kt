package com.castivio.feature.home

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The remote does not lose its place when categories arrive.
 *
 * Live TV opens with its first category and the rest join the rail while the viewer
 * browses it. A viewer who has arrowed to "Sport" must still be on "Sport" when "Kids"
 * is inserted above or below it -- not thrown back to the top, not moved off the row,
 * and not left unable to reach what just arrived. The real rail is composed, with the
 * real focus handling, and the categories are replaced the way the view model replaces
 * them: a new list, same ids for the ones already there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class CategoryRailFocusTest {

    @get:Rule
    val compose = createComposeRule()

    private fun group(id: String, name: String) =
        MediaGroup(id = id, name = name, kind = MediaKind.LIVE, itemCount = 10)

    private val sport = group("sport", "Sport")
    private val news = group("news", "News")

    private var state by mutableStateOf(BrowseState())

    /** The focus manager the remote's directional keys drive, captured from inside the rail's own tree. */
    private var focus: FocusManager? = null

    private fun arrive(vararg groups: MediaGroup) {
        compose.runOnIdle {
            state = state.copy(groups = groups.toList(), shownGroups = groups.toList())
        }
        compose.waitForIdle()
    }

    private fun show(vararg groups: MediaGroup) {
        state = BrowseState(groups = groups.toList(), shownGroups = groups.toList(), loading = false)
        compose.setContent {
            CastivioTheme {
                focus = LocalFocusManager.current
                val m = channelsMetricsFor(tv = true, width = 960.dp, height = 540.dp)
                CategoryRail(
                    state = state,
                    m = m,
                    onChoose = {},
                    onFilter = {},
                    onRefresh = {},
                    modifier = Modifier.width(m.rail).height(540.dp),
                )
            }
        }
    }

    private fun focusSport() {
        compose.onNodeWithText("Sport").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("Sport").assertIsFocused()
    }

    @Test
    fun `focus stays on the category when others arrive below it`() {
        show(sport, news)
        focusSport()
        val before = compose.onNodeWithText("Sport").getUnclippedBoundsInRoot()

        arrive(sport, news, group("kids", "Kids"), group("docs", "Docs"))

        compose.onNodeWithText("Sport").assertIsFocused()
        assertEquals("the row did not move under the remote", before, compose.onNodeWithText("Sport").getUnclippedBoundsInRoot())
    }

    @Test
    fun `focus stays on the category when one arrives above it`() {
        show(sport, news)
        focusSport()

        arrive(group("kids", "Kids"), sport, news)

        compose.onNodeWithText("Sport").assertIsFocused()
    }

    @Test
    fun `a category that arrived is reached by the remote`() {
        show(sport)
        focusSport()

        arrive(sport, group("kids", "Kids"))
        // What the remote's Down key does: the focus search the platform runs for it.
        compose.runOnIdle { focus!!.moveFocus(FocusDirection.Down) }

        compose.onNodeWithText("Kids").assertIsFocused()
    }
}
