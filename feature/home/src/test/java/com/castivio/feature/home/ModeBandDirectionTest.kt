package com.castivio.feature.home

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing
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

    /** The label's own height, in the tree the button's semantics are not merged into. */
    private fun labelHeight(text: String): Float {
        val box = compose.onNode(hasText(text), useUnmergedTree = true).getUnclippedBoundsInRoot()
        return (box.bottom - box.top).value
    }

    /**
     * A button is as tall as its label and its padding, and no shorter than its floor; this one's padding is the compact one and its
     * floor is [Sizing.compactTarget] -- 34dp under a thumb, a television's own under a remote.
     *
     * The label is read and not assumed: Robolectric lays every `Text` out at 35dp whatever its style, so on a phone the button is its
     * label and its padding there (43dp) and the floor on a device (34dp). The rule is the same in both -- the taller of the two --
     * and it is the rule that tells a compact button from a regular one, whose padding is three times as much and whose floor is 48dp.
     */
    @Test
    fun `the buttons are compact, and at a television's floor under a remote`() {
        show()

        val floor = Sizing.compactTarget(tv).value
        val padding = 2 * Spacing.xs.value
        val cancelHeight = (cancelAt.bottom - cancelAt.top).value
        val saveHeight = (saveAt.bottom - saveAt.top).value
        val cancelExpected = maxOf(floor, labelHeight(cancel) + padding)
        val saveExpected = maxOf(floor, labelHeight(save) + padding)
        assertTrue("cancel is $cancelHeight high, expected $cancelExpected", abs(cancelHeight - cancelExpected) <= 1f)
        assertTrue("save is $saveHeight high, expected $saveExpected", abs(saveHeight - saveExpected) <= 1f)
        assertEquals("both buttons are the same height", cancelHeight, saveHeight, 1f)
        if (!tv) {
            // Not a regular button: its padding alone would make it this much taller than its label. (On a television the floor
            // is the same for both, so there is nothing to tell them apart by.)
            assertTrue("cancel is $cancelHeight high", cancelHeight < labelHeight(cancel) + 2 * Spacing.md.value)
            assertTrue("under a thumb's 48dp", cancelHeight < Sizing.minTouchTarget.value)
        }
    }
}

/** The same band in Arabic, whose own direction is right to left: the band follows it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ar-ldrtl-w960dp-h540dp-land")
class ModeBandArabicTest : ModeBandDirectionTest() {
    override val arabic = true
}
