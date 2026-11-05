package com.castivio.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.castivio.core.design.components.NO_LOGO_TILE_TAG
import com.castivio.core.design.components.ProviderArtwork
import com.castivio.core.design.theme.CastivioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The tile a channel without a logo is drawn with, in the slots it is drawn in: a square, larger than a wide short slot is tall, and
 * centred on it -- with the slot itself, and so the row, left exactly the size it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class NoLogoTileTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(width: Int, height: Int, url: String? = null, seed: String? = "ES| DAZN 3 FHD") {
        compose.setContent {
            CastivioTheme {
                Box(Modifier.size(width.dp, height.dp).testTag(SLOT)) {
                    ProviderArtwork(url = url, description = null, modifier = Modifier.size(width.dp, height.dp), seed = seed)
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `in a wide short slot the tile is a square taller than the slot, inside the row`() {
        show(width = 27, height = 11)

        val tile = compose.onNodeWithTag(NO_LOGO_TILE_TAG).getUnclippedBoundsInRoot()

        assertEquals("a square", tile.width.value, tile.height.value, TOLERANCE)
        assertTrue("taller than the slot, which is 11dp: ${tile.height}", tile.height.value > 11 + MARGIN)
        assertTrue("and not wider than four fifths of it: ${tile.width}", tile.width.value <= 27 * 0.8f + TOLERANCE)
    }

    @Test
    fun `the slot keeps its own size, whatever the tile is`() {
        show(width = 27, height = 11)

        val slot = compose.onNodeWithTag(SLOT).getUnclippedBoundsInRoot()

        assertEquals(27f, slot.width.value, TOLERANCE)
        assertEquals(11f, slot.height.value, TOLERANCE)
    }

    @Test
    fun `in a square slot the tile fills it`() {
        show(width = 20, height = 20)

        val tile = compose.onNodeWithTag(NO_LOGO_TILE_TAG).getUnclippedBoundsInRoot()

        assertEquals(20f, tile.width.value, TOLERANCE)
        assertEquals(20f, tile.height.value, TOLERANCE)
    }

    @Test
    fun `a channel whose logo address is blank is drawn the same, with or without a name`() {
        show(width = 27, height = 11, url = " ", seed = null)

        compose.onNodeWithTag(NO_LOGO_TILE_TAG).getUnclippedBoundsInRoot().let {
            assertTrue(it.height.value > 11 + MARGIN)
        }
    }

    private companion object {
        const val SLOT = "test.slot"
        const val TOLERANCE = 0.8f
        const val MARGIN = 4f
    }
}
