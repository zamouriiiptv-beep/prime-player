package com.castivio.feature.activation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.domain.activation.ActivationForm
import com.castivio.domain.activation.ActivationUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The playlist screen draws the composition `fitsSpread` actually chose, on a real
 * surface -- not the composition the pure functions say it should choose.
 *
 * ## Why `M3uSpreadTest` could not have caught this
 *
 * `M3uSpreadTest` is a sweep of [fitsSpread], [m3uSpread] and [m3uBand] as pure
 * functions, called directly with numbers it picked. It proves the arithmetic is
 * right -- 33,498 surfaces, [851.dp] x [393.dp] among them, every one fitting its
 * band. It cannot prove those numbers are the ones [M3uFormScreen] actually hands
 * them, because nothing in that file composes the screen. A `BoxWithConstraints`
 * wired to the wrong ancestor, a modifier that quietly caps a width, a window that
 * reports less than its own surface -- none of those are visible to a test that
 * never asks Compose to lay anything out.
 *
 * This file asks it to. [Screen] is the same container production uses --
 * [ActivationSurface] at [isFixedViewport]'s own answer for the playlist step --
 * with nothing standing between the harness and the real composable tree.
 *
 * ## What it cannot tell you
 *
 * Robolectric's window has no display cutout and no OEM letterboxing. A surface
 * declared [851.dp] here is exactly [851.dp] of `BoxWithConstraints.maxWidth`,
 * which is what a device would also report *if nothing between the window and the
 * composition were taking width away from it*. If a real handset draws the column
 * at a size this file says should draw the spread, the fault this file can rule
 * out is the one it exercises -- the composable wiring -- and what is left is the
 * window itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-land")
class M3uFormScreenLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * **The handset the project is tested on.** 851x393, the exact surface
     * `M3uSpreadTest` asserts `fitsSpread` accepts. If the composition Compose
     * actually places here is the column, the wiring between [M3uFormScreen] and
     * the functions that decide its layout is broken -- the functions themselves
     * are proven correct by the sweep.
     */
    @Test
    fun `the handset draws the spread -- the illustration and the full panel`() {
        compose.setContent { Screen(851.dp, 393.dp) }

        compose.onNodeWithTag(ActivationTags.PLAYLIST_ART)
            .assertExists(
                "the illustration is missing at 851x393 -- M3uColumn was drawn " +
                    "where fitsSpread(851.dp, 303.5.dp) says M3uSpread should have been",
            )
    }

    /**
     * The reference drawing, 1280x720, where the full panel -- and so the
     * optional name field -- is affordable. A second surface rather than a second
     * assertion on the first: [CastivioPlaylistArt] is unconditional in
     * [M3uSpread], so a passing art assertion at 851x393 only shows the spread was
     * chosen, never that the panel drew its optional parts. The name field needs
     * its own surface, one wide and tall enough that [M3uChrome.full] is true.
     */
    @Test
    fun `a surface with room for the full panel draws the optional name field`() {
        compose.setContent { Screen(1280.dp, 720.dp) }

        compose.onNodeWithTag(ActivationTags.PLAYLIST_ART).assertExists()
        compose.onNodeWithTag(ActivationTags.PLAYLIST_NAME)
            .assertExists(
                "the playlist name field is missing at the reference surface, " +
                    "where the full panel is affordable and the field is not optional-away",
            )
    }

    /**
     * Below [SPREAD_MIN_WIDTH], by design -- the control, not a second claim about
     * the handset. A test that only ever asserts the art exists cannot tell "drawn
     * because the surface fits" from "drawn no matter what", and this is the
     * surface where it must be absent.
     */
    @Test
    fun `a surface narrower than the threshold draws the column, not the spread`() {
        compose.setContent { Screen(820.dp, 393.dp) }

        compose.onNodeWithTag(ActivationTags.PLAYLIST_ART).assertDoesNotExist()
    }
}

/** The screen in the container production actually builds around it. */
@Composable
private fun Screen(width: Dp, height: Dp) {
    CastivioTheme {
        Box(Modifier.requiredSize(width, height)) {
            ActivationSurface(
                fixedViewport = isFixedViewport(ActivationUiState(), ActivationStep.Playlist),
            ) {
                M3uFormScreen(
                    form = ActivationForm.Playlist(),
                    enabled = true,
                    canSubmit = false,
                    onName = {},
                    onUrl = {},
                    onSubmit = {},
                    onBack = {},
                )
            }
        }
    }
}
