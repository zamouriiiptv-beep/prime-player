package com.castivio.feature.home

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The header's field, and what the list does around it: it takes text, it leaves the remote
 * alone, and the list neither takes the remote from it nor leaves a viewer with a blank where
 * the channels were.
 *
 * What the text *finds* is not here -- the holder's part is `BrowseSearchTest` and the index's
 * is `CatalogQueriesTest`. This is the half a viewer touches: the field, the focus, and the
 * words a search that matched nothing says.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class HeaderSearchTest {

    @get:Rule
    val compose = createComposeRule()

    private var text by mutableStateOf("")
    private var typed = mutableListOf<String>()
    private var focusReports = mutableListOf<Boolean>()
    private var focus: FocusManager? = null

    /** The field over one row that can take focus, which is the list as far as the remote goes. */
    private fun show() {
        compose.setContent {
            CastivioTheme {
                val m = channelsMetricsFor(tv = true, width = 960.dp, height = 540.dp)
                focus = LocalFocusManager.current
                Column {
                    HeaderSearchField(
                        text = text,
                        m = m,
                        onType = { typed += it; text = it },
                        onFocusChange = { focusReports += it },
                    )
                    Box(Modifier.size(40.dp).testTag(ROW).focusable())
                }
            }
        }
        compose.waitForIdle()
    }

    private val field get() = compose.onNode(hasSetTextAction())

    @Test
    fun `an empty field says what it is for`() {
        show()

        compose.onNode(hasText("Search channels")).assertExists()
    }

    @Test
    fun `what is typed reaches the holder as it is typed`() {
        show()

        field.performTextInput("bein")

        assertEquals(listOf("bein"), typed)
        assertEquals("bein", text)
    }

    @Test
    fun `what was typed stays in the field exactly as typed`() {
        show()

        field.performTextInput("  BeIN  Sports ")

        // The field is the viewer's. Folding the words is the query's business, not the text's.
        assertEquals("  BeIN  Sports ", text)
        compose.onNode(hasText("  BeIN  Sports ")).assertExists()
        compose.onNode(hasText("Search channels")).assertDoesNotExist()
    }

    @Test
    fun `emptying the field says so, and the hint comes back`() {
        show()
        field.performTextInput("bein")

        field.performTextClearance()

        assertEquals("", text)
        compose.onNode(hasText("Search channels")).assertExists()
    }

    @Test
    fun `the field reports when it has the remote and when it gives it up`() {
        show()

        field.requestFocus()
        compose.waitForIdle()
        assertTrue(focusReports.last())

        focus!!.moveFocus(FocusDirection.Down)
        compose.waitForIdle()

        compose.onNodeWithTag(ROW).assertIsFocused()
        assertFalse(focusReports.last())
    }

    @Test
    fun `the remote leaves the field the way it leaves any row`() {
        show()
        field.requestFocus()

        focus!!.moveFocus(FocusDirection.Down)
        compose.waitForIdle()
        compose.onNodeWithTag(ROW).assertIsFocused()

        focus!!.moveFocus(FocusDirection.Up)
        compose.waitForIdle()
        field.assertIsFocused()
    }

    @Test
    fun `the keyboard's search key leaves the field focused, with its text, and does not move on`() {
        show()
        field.performTextInput("bein")
        field.requestFocus()

        field.performImeAction()
        compose.waitForIdle()

        field.assertIsFocused()
        compose.onNodeWithTag(ROW).assertIsNotFocused()
        assertEquals("bein", text)
    }

    /** The list's first-focus rule, with the field's focus as the one thing that changes. */
    private fun showList(fieldHasIt: () -> Boolean, rows: () -> Boolean) {
        compose.setContent {
            CastivioTheme {
                val first = remember { FocusRequester() }
                val m = channelsMetricsFor(tv = true, width = 960.dp, height = 540.dp)
                focus = LocalFocusManager.current
                Column {
                    HeaderSearchField(text = "", m = m, onType = {})
                    if (rows()) {
                        Box(Modifier.size(40.dp).testTag(ROW).focusRequester(first).focusable())
                        FirstRowFocus(hasContent = true, fieldHasIt = fieldHasIt(), first = first)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the list takes the remote when it first has rows`() {
        var rows by mutableStateOf(false)
        showList(fieldHasIt = { false }, rows = { rows })

        rows = true
        compose.waitForIdle()

        compose.onNodeWithTag(ROW).assertIsFocused()
    }

    @Test
    fun `the list does not take the remote from a field being typed in when rows come back`() {
        var rows by mutableStateOf(false)
        var typing by mutableStateOf(false)
        showList(fieldHasIt = { typing }, rows = { rows })
        compose.onNode(hasSetTextAction()).requestFocus()
        typing = true
        compose.waitForIdle()

        // A word with no match and then one with: the list is empty, and then it is not.
        rows = true
        compose.waitForIdle()

        compose.onNodeWithTag(ROW).assertIsNotFocused()
        compose.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun `a search that matched nothing names what was asked and how to leave it`() {
        var cleared = 0
        compose.setContent {
            CastivioTheme {
                ChannelListEmpty(loading = false, search = "zzz", onClearSearch = { cleared++ }, onRefresh = {})
            }
        }

        compose.onNode(hasText("Nothing matched “zzz”")).assertExists()
        compose.onNode(hasText("Clear")).performClick()

        assertEquals(1, cleared)
    }

    @Test
    fun `an empty category is not a search that matched nothing`() {
        compose.setContent {
            CastivioTheme {
                ChannelListEmpty(loading = false, search = "", onClearSearch = {}, onRefresh = {})
            }
        }

        compose.onNode(hasText("No channels here")).assertExists()
    }

    private companion object {
        const val ROW = "test.row"
    }
}
