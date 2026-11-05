package com.castivio.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The band over the four boards, and the way back that ends it.
 *
 * The arrow is at the band's right end in every language -- the band is drawn left to right in all of them -- and it is the
 * Back key, pressed with a finger or with the remote. The spaces between what the band holds are equal. What is drawn is
 * `design/mockups/channels-band-back-ar.html`; the arithmetic of the spaces is `BandTailPlanTest`'s, and this is the half
 * a viewer sees: where the pieces land, and what the arrow does.
 *
 * Run twice, in English and in Arabic ([BoardBandArabicTest]): the point of the arrow's place is that it is the same.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
open class BoardBandTest {

    @get:Rule
    val compose = createComposeRule()

    private var backs = 0
    private var focus: FocusManager? = null
    private var backLabel = ""
    private var wordmark = ""
    private var metrics: ChannelsMetrics? = null

    /** The band, over a `BackHandler` that counts what the Back key would have done. */
    private fun show(withBack: Boolean = true, withOrder: Boolean = false) {
        compose.setContent {
            CastivioTheme {
                val m = channelsMetricsFor(tv = true, width = 960.dp, height = 540.dp)
                metrics = m
                focus = LocalFocusManager.current
                backLabel = stringResource(R.string.show_back)
                wordmark = stringResource(R.string.gate_wordmark)
                BackHandler { backs++ }
                BoardBand(
                    section = "Movies",
                    list = "All movies",
                    m = m,
                    searchText = "",
                    onSearch = {},
                    onSearchFocus = {},
                    trailing = if (withOrder) {
                        { Box(Modifier.size(ORDER_WIDTH, ORDER_HEIGHT).testTag(ORDER).focusable()) }
                    } else {
                        null
                    },
                    withBack = withBack,
                )
            }
        }
        compose.waitForIdle()
    }

    private val arrow get() = compose.onNode(hasContentDescription(backLabel))
    private val field get() = compose.onNode(hasSetTextAction())
    private val time = SemanticsMatcher("the time of day") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { Regex("\\d{2}:\\d{2}").containsMatchIn(it.text) } == true
    }

    @Test
    fun `the band ends in the way back`() {
        show()

        arrow.assertExists()
    }

    @Test
    fun `a band without the way back has none`() {
        show(withBack = false)

        compose.onAllNodes(hasContentDescription(backLabel)).assertCountEquals(0)
    }

    @Test
    fun `the arrow is the last piece at the right, the clock before it and the field before that`() {
        show()

        val arrowAt = arrow.getUnclippedBoundsInRoot()
        val clockAt = compose.onNode(time).getUnclippedBoundsInRoot()
        val fieldAt = field.getUnclippedBoundsInRoot()

        assertTrue("the clock ends at ${clockAt.right}, the arrow starts at ${arrowAt.left}", clockAt.right <= arrowAt.left)
        assertTrue("the field ends at ${fieldAt.right}, the clock starts at ${clockAt.left}", fieldAt.right <= clockAt.left)
        // First from the right edge, standing in from it as the other end of the band stands in from its own.
        assertEquals((960.dp - bandInset(metrics!!)).value, arrowAt.right.value, 1f)
    }

    @Test
    fun `the spaces between the pieces are equal`() {
        show(withOrder = true)

        val fieldAt = field.getUnclippedBoundsInRoot()
        val orderAt = compose.onNodeWithTag(ORDER).getUnclippedBoundsInRoot()
        val clockAt = compose.onNode(time).getUnclippedBoundsInRoot()
        val arrowAt = arrow.getUnclippedBoundsInRoot()

        // The clock's text stands at its cell's right end, so the space after it is the arrow's own. The field node is the
        // text box inside the pill, which ends [FIELD_INSET] short of the pill's own end -- the end the space is from.
        val fieldToOrder = (orderAt.left - (fieldAt.right + FIELD_INSET)).value
        val clockToArrow = (arrowAt.left - clockAt.right).value
        assertTrue("the spaces are $fieldToOrder and $clockToArrow", fieldToOrder > 0f && clockToArrow > 0f)
        assertEquals(fieldToOrder, clockToArrow, 1.5f)
    }

    @Test
    fun `the mark and the name stand in the middle of the space before the section's name`() {
        show()

        val mark = compose.onNodeWithTag(BAND_MARK_TAG).getUnclippedBoundsInRoot()
        val name = compose.onNode(hasText(wordmark)).getUnclippedBoundsInRoot()
        val section = compose.onNode(hasText("Movies")).getUnclippedBoundsInRoot()

        // From the frame's edge -- the band's own start -- to the mark, and from the name to where the section's name begins.
        val before = mark.left.value
        val after = (section.left - name.right).value
        assertTrue("the pair has $before before it and $after after it", before > 0f && after > 0f)
        assertEquals(before, after, 1.5f)
    }

    @Test
    fun `the mark is a little larger than the band's old share of it`() {
        show()

        val mark = compose.onNodeWithTag(BAND_MARK_TAG).getUnclippedBoundsInRoot()

        // 0.8 of the band, where it was 0.7: about 14% taller, and still inside the band.
        assertTrue("the share is $MARK_OF_HEADER", MARK_OF_HEADER > 0.7f)
        assertEquals((metrics!!.header * MARK_OF_HEADER).value, mark.height.value, 1f)
        assertTrue(mark.height <= metrics!!.header)
    }

    @Test
    fun `the arrow presses what the Back key presses`() {
        show()

        arrow.performClick()

        assertEquals(1, backs)
    }

    @Test
    fun `OK on the remote presses it`() {
        show()
        arrow.requestFocus()
        compose.waitForIdle()

        arrow.assertIsFocused()
        arrow.performKeyInput { pressKey(Key.DirectionCenter) }

        assertEquals(1, backs)
    }

    @Test
    fun `Right from the field lands on the arrow, and Left leaves it`() {
        show()
        field.requestFocus()
        compose.waitForIdle()

        focus!!.moveFocus(FocusDirection.Right)
        compose.waitForIdle()
        arrow.assertIsFocused()

        focus!!.moveFocus(FocusDirection.Left)
        compose.waitForIdle()
        field.assertIsFocused()
    }

    private companion object {
        const val ORDER = "order"
        val ORDER_WIDTH = 120.dp
        val ORDER_HEIGHT = 28.dp
    }
}

/** The same band in Arabic, whose own direction is right to left: the arrow is where it was, at the right. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ar-ldrtl-w960dp-h540dp-land")
class BoardBandArabicTest : BoardBandTest()
