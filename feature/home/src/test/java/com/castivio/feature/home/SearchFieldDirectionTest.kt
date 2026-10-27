package com.castivio.feature.home

import androidx.compose.ui.text.style.TextDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which way the header search field's words run, as a decision the field makes once.
 *
 * The board is drawn left to right in every language, so the field cannot ask its layout: it is told whether the
 * language runs right to left. An empty field in such a language must wait with its caret at the right end of the
 * box, which is what "starts from the right" is; in any other language it is the left.
 */
class SearchFieldDirectionTest {

    @Test
    fun `a right-to-left language starts its words at the right`() {
        assertEquals(TextDirection.ContentOrRtl, searchTextDirection(rtlLanguage = true))
    }

    @Test
    fun `any other language starts its words at the left`() {
        assertEquals(TextDirection.ContentOrLtr, searchTextDirection(rtlLanguage = false))
    }
}
