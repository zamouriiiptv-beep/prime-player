package com.castivio.feature.home

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Sizing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * The band over the board while it arranges: which end its word is at and which end its buttons are, and how tall the buttons are.
 *
 * In English the band is left to right, the word at the left and the buttons at the right; in Arabic it follows the reader, the
 * word at the right and the buttons at the left, [ModeBandArabicTest]. The buttons are compact in both: 34dp under a thumb, and a
 * television's own floor under a remote. Drawn in `design/mockups/channels-mode-bar-fix-ar.html`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
open class ModeBandDirectionTest {

    @get:Rule
    val compose = createComposeRule()

    /** Whether this run is in Arabic: the band follows the reader's direction. */
    protected open val arabic = false

    private var word = ""
    private var cancel = ""
    private var save = ""
    private var tv = false

    private fun show() {
        val m = channelsMetricsFor(tv = false, width = 833.dp, height = 385.dp)
        compose.setContent {
            CastivioTheme {
                word = stringResource(R.string.reorder_entry)
                cancel = stringResource(R.string.reorder_cancel)
                save = stringResource(R.string.reorder_save)
                tv = CastivioTheme.device.isTv
                ReorderBar(
                    mode = Arranging(bouquets = listOf("sport", "news"), initial = listOf("sport", "news"), channelsOf = null),
                    m = m,
                    onSave = {},
                    onCancel = {},
                )
            }
        }
        compose.waitForIdle()
    }

    private val wordAt get() = compose.onNode(hasText(word)).getUnclippedBoundsInRoot()
    private val cancelAt get() = compose.onNode(hasClickAction() and hasText(cancel)).getUnclippedBoundsInRoot()
    private val saveAt get() = compose.onNode(hasClickAction() and hasText(save)).getUnclippedBoundsInRoot()

    @Test
    fun `the word and the buttons are at the ends the language puts them`() {
        show()

        if (arabic) {
            // The word at the right, then the line, then Cancel and Save at the left, Save last.
            assertTrue("save ${saveAt.right}, cancel ${cancelAt.left}", saveAt.right <= cancelAt.left)
            assertTrue("cancel ${cancelAt.right}, word ${wordAt.left}", cancelAt.right <= wordAt.left)
        } else {
            assertTrue("word ${wordAt.right}, cancel ${cancelAt.left}", wordAt.right <= cancelAt.left)
            assertTrue("cancel ${cancelAt.right}, save ${saveAt.left}", cancelAt.right <= saveAt.left)
        }
    }

    @Test
    fun `the buttons are compact, and at a television's floor under a remote`() {
        show()

        val expected = Sizing.compactTarget(tv).value
        val cancelHeight = (cancelAt.bottom - cancelAt.top).value
        val saveHeight = (saveAt.bottom - saveAt.top).value
        assertTrue("cancel is $cancelHeight high, expected $expected", abs(cancelHeight - expected) <= 1f)
        assertEquals("both buttons are the same height", cancelHeight, saveHeight, 1f)
        if (!tv) assertTrue("under a thumb's 48dp", cancelHeight < Sizing.minTouchTarget.value)
    }
}

/** The same band in Arabic, whose own direction is right to left: the band follows it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ar-ldrtl-w960dp-h540dp-land")
class ModeBandArabicTest : ModeBandDirectionTest() {
    override val arabic = true
}
