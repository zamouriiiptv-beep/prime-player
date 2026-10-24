package com.castivio.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The thumb's drag on a real list: a finger on a carried row that moves drags, and lifting it says where;
 * a finger on any other row still scrolls the list; a tap is still a tap; and with the drag off -- no
 * channel picked up -- every row scrolls as before.
 *
 * The rows are fifty units tall and the list ten rows high, so a row's place is easy to aim at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class DragToPlaceGestureTest {

    @get:Rule
    val compose = createComposeRule()

    private val drops = mutableListOf<Int>()
    private val moves = mutableListOf<Float?>()
    private val clicks = mutableListOf<Int>()
    private lateinit var listState: LazyListState

    private fun list(carried: Set<Int> = setOf(2), enabled: Boolean = true) {
        compose.setContent {
            listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .height(500.dp)
                    .fillMaxWidth()
                    .testTag("list")
                    .dragToPlace(
                        listState = listState,
                        enabled = enabled,
                        carried = { it in carried },
                        onMove = { moves += it },
                        onDrop = { drops += it },
                    ),
            ) {
                items(40) { index ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("row $index")
                            .clickable { clicks += index },
                    )
                }
            }
        }
    }

    private val scrolled: Boolean
        get() = listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0

    @Test
    fun `a carried row dragged down and let go goes before the row it was let go above`() {
        list()

        // From the middle of row 2 (two and a half rows down) to 5.3 rows down: above the middle of row 5.
        compose.onNodeWithTag("row 2").performTouchInput {
            down(center)
            moveBy(Offset(0f, height * 1.4f))
            moveBy(Offset(0f, height * 1.4f))
            up()
        }
        compose.waitForIdle()

        assertEquals(listOf(5), drops)
        assertTrue(clicks.isEmpty())
    }

    @Test
    fun `a carried row dragged to the top goes first`() {
        list()

        compose.onNodeWithTag("row 2").performTouchInput {
            down(center)
            moveBy(Offset(0f, -height * 1.2f))
            moveBy(Offset(0f, -height * 1.2f))
            up()
        }
        compose.waitForIdle()

        assertEquals(listOf(0), drops)
    }

    @Test
    fun `the list does not scroll under a drag`() {
        list()

        compose.onNodeWithTag("row 2").performTouchInput {
            down(center)
            moveBy(Offset(0f, -height * 1.5f))
            moveBy(Offset(0f, -height * 1.5f))
            up()
        }
        compose.waitForIdle()

        assertEquals(0, listState.firstVisibleItemIndex)
        assertEquals(0, listState.firstVisibleItemScrollOffset)
    }

    @Test
    fun `while it drags the finger is reported, and on letting go it is not`() {
        list()

        compose.onNodeWithTag("row 2").performTouchInput {
            down(center)
            moveBy(Offset(0f, height.toFloat()))
            moveBy(Offset(0f, height.toFloat()))
            up()
        }
        compose.waitForIdle()

        assertTrue(moves.filterNotNull().isNotEmpty())
        assertNull(moves.last())
    }

    @Test
    fun `a finger on a row that is not carried scrolls the list, and nothing is dropped`() {
        list()

        compose.onNodeWithTag("row 6").performTouchInput {
            down(center)
            repeat(6) { moveBy(Offset(0f, -height.toFloat())) }
            up()
        }
        compose.waitForIdle()

        assertTrue(drops.isEmpty())
        assertTrue(scrolled)
    }

    @Test
    fun `a tap on a carried row is still a tap`() {
        list()

        compose.onNodeWithTag("row 2").performClick()
        compose.waitForIdle()

        assertEquals(listOf(2), clicks)
        assertTrue(drops.isEmpty())
        assertTrue(moves.isEmpty())
    }

    @Test
    fun `with the drag off, a carried row scrolls the list like any other`() {
        list(enabled = false)

        compose.onNodeWithTag("row 2").performTouchInput {
            down(center)
            repeat(6) { moveBy(Offset(0f, -height.toFloat())) }
            up()
        }
        compose.waitForIdle()

        assertTrue(drops.isEmpty())
        assertTrue(scrolled)
    }
}
