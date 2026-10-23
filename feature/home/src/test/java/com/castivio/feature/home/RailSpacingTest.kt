package com.castivio.feature.home

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rail's rhythm: the one-line entries are spaced as one family, and the total is gone.
 *
 * "Favorites" and "Recently watched" stood two lines apart because every entry took the
 * height of a bouquet, which carries a name *and* a count. Drawn at the sizes the board has on a
 * phone and on a television, so the figures are the device's and not a convenient one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class RailSpacingTest {

    @get:Rule
    val compose = createComposeRule()

    private val groups = listOf("Sport" to 170, "News" to 36, "Kids" to 40).mapIndexed { i, (name, count) ->
        MediaGroup(id = "g$i", name = name, kind = MediaKind.LIVE, itemCount = count)
    }

    private fun show(tv: Boolean, width: Dp, height: Dp) {
        compose.setContent {
            CastivioTheme {
                val m = channelsMetricsFor(tv = tv, width = width, height = height)
                CategoryRail(
                    state = BrowseState(groups = groups, shownGroups = groups, sectionTotal = 18_779, loading = false),
                    m = m,
                    onChoose = {},
                    onFilter = {},
                    onRefresh = {},
                    modifier = Modifier.width(m.rail).height(height),
                )
            }
        }
    }

    private fun top(label: String) = compose.onNodeWithText(label).getUnclippedBoundsInRoot().top
    private fun height(label: String): Dp = compose.onNodeWithText(label).getUnclippedBoundsInRoot().let { it.bottom - it.top }

    private fun assertEven(tv: Boolean, width: Dp, height: Dp) {
        show(tv, width, height)
        val all = "All Channels"
        val favourites = "Favorites"
        val recent = "Recently Watched"

        val one = listOf(all, favourites, recent).map { height(it) }
        assertEquals("the three one-line entries are one height", one[0].value, one[1].value, 0.01f)
        assertEquals(one[1].value, one[2].value, 0.01f)

        val first = top(all)
        val second = top(favourites)
        val third = top(recent)
        assertEquals(
            "the same distance from all to favourites as from favourites to recent",
            (second - first).value,
            (third - second).value,
            0.01f,
        )

        val bouquet = height("Sport")
        assertTrue("a bouquet, with its count, is taller than a one-line entry: $bouquet against ${one[0]}", bouquet > one[0])
    }

    @Test
    fun `the one-line entries are evenly spaced on a phone`() = assertEven(tv = false, width = 883.dp, height = 408.dp)

    @Test
    fun `the one-line entries are evenly spaced on a television`() = assertEven(tv = true, width = 960.dp, height = 540.dp)

    @Test
    fun `the one-line entries are evenly spaced on a large television`() = assertEven(tv = true, width = 1280.dp, height = 720.dp)

    @Test
    fun `the total is not on the all entry and the bouquets keep theirs`() {
        show(tv = false, width = 883.dp, height = 408.dp)

        compose.onNodeWithText("Total: 18,779").assertDoesNotExist()
        compose.onNodeWithText("Total: 170").assertExists()
        compose.onNodeWithText("Total: 36").assertExists()
        assertFalse("no figure is left under the all entry", compose.onNodeWithText("All Channels").getUnclippedBoundsInRoot().let { it.bottom - it.top } > height("Favorites"))
    }
}
