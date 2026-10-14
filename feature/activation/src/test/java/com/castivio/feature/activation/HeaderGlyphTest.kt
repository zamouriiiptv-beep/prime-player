package com.castivio.feature.activation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.DeviceClass
import com.castivio.core.design.theme.LocalDeviceClass
import com.castivio.domain.ProviderSource
import com.castivio.domain.SourceKind
import com.castivio.domain.activation.ActivationForm
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The mark a chooser card carries onto the screen it opens.
 *
 * Four screens are opened by a card -- Xtream, the playlist, the portal and the saved
 * subscriptions -- and each wears that card's glyph beside its name. What can go wrong
 * with it is not whether it is drawn but *where*: the header row is pinned left to
 * right, and a pair that inherited the reader's direction would put the mark after an
 * Arabic name and before an English one. Both look deliberate in the language they
 * were checked in. So the side is asserted in both directions, on a handset and a
 * television, along with the two things the mark must not cost: the name staying
 * between the lockup and Back, and the pair staying on the header's own line.
 *
 * The chooser itself draws no mark -- it is where the marks come from -- and that is
 * asserted too, so a default that leaked one onto it would fail here rather than ship.
 *
 * Robolectric lays no text out (every `Text` is 35dp tall), so nothing here measures a
 * name's width or a glyph against a line of type: the claims are relative, which a
 * harness with the wrong text metrics still gets right.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-land")
class HeaderGlyphTest {

    @get:Rule
    val compose = createComposeRule()

    private data class Frame(val width: Dp, val height: Dp, val device: DeviceClass)

    private data class Shown(val frame: Frame, val direction: LayoutDirection, val screen: Screen)

    private enum class Screen(val heading: String, val back: String) {
        Xtream(ActivationTags.XTREAM_HEADING, ActivationTags.XTREAM_BACK),
        Playlist(ActivationTags.PLAYLIST_HEADING, ActivationTags.PLAYLIST_BACK),
        Portal(ActivationTags.PORTAL_HEADING, ActivationTags.PORTAL_BACK),
        Saved(ActivationTags.SAVED_TITLE, ActivationTags.SAVED_BACK),
    }

    @Test
    fun `each screen a card opens wears its mark left of its name, in both directions`() {
        var shown by mutableStateOf(Shown(HANDSET, LayoutDirection.Rtl, Screen.Xtream))
        compose.setContent {
            val s = shown
            Stage(s.frame, s.direction) { s.screen.Draw() }
        }

        for (frame in listOf(HANDSET, TELEVISION)) for (direction in LayoutDirection.entries) {
            for (screen in Screen.entries) {
                shown = Shown(frame, direction, screen)
                compose.waitForIdle()

                val pass = "${screen.name} at ${frame.width}x${frame.height} $direction"
                val glyph = compose.bounds(ActivationTags.HEADER_GLYPH)
                val pair = compose.bounds(screen.heading)
                val mark = compose.bounds(ActivationTags.HEADER_MARK)
                val back = compose.bounds(screen.back)

                // The mark leads the pair on the left edge, whatever the reading order.
                assertTrue(
                    "$pass: the mark ${glyph.left}..${glyph.right} does not start the pair " +
                        "${pair.left}..${pair.right}",
                    abs((glyph.left - pair.left).value) <= 1f,
                )
                assertTrue(
                    "$pass: there is no name to the right of the mark — the pair ends at " +
                        "${pair.right}, the mark at ${glyph.right}",
                    pair.right > glyph.right,
                )
                // On the pair's own line: centred on it, not riding above the name.
                val glyphMiddle = (glyph.top + glyph.bottom) / 2
                val pairMiddle = (pair.top + pair.bottom) / 2
                assertTrue(
                    "$pass: the mark sits at $glyphMiddle, the pair's line at $pairMiddle",
                    abs((glyphMiddle - pairMiddle).value) <= 1f,
                )
                // And what it must not cost: the name still between the lockup and Back.
                assertTrue(
                    "$pass: the pair ${pair.left}..${pair.right} is not between the lockup " +
                        "(ends ${mark.right}) and Back (starts ${back.left})",
                    pair.left >= mark.right && pair.right <= back.left,
                )
            }
        }
    }

    @Test
    fun `the chooser draws no mark of its own`() {
        compose.setContent {
            Stage(TELEVISION, LayoutDirection.Rtl) {
                SourceChoiceScreen(onXtream = {}, onPlaylist = {}, onPortal = {}, onSavedSources = {}, onBack = {})
            }
        }
        // The heading first, so the absence below is an absence on a drawn screen.
        compose.onNodeWithTag(ActivationTags.SOURCE_HEADING).assertExists("the chooser's heading is missing")
        compose.onNodeWithTag(ActivationTags.HEADER_GLYPH, useUnmergedTree = true).assertDoesNotExist()
    }

    /* ---------------------------------------------------------------------------- */

    @Composable
    private fun Screen.Draw() = when (this) {
        Screen.Xtream -> XtreamFormScreen(
            form = ActivationForm.Xtream(),
            enabled = true,
            canSubmit = false,
            onName = {},
            onServerUrl = {},
            onUsername = {},
            onPassword = {},
            onSubmit = {},
            onBack = {},
        )
        Screen.Playlist -> M3uFormScreen(
            ActivationForm.Playlist(),
            enabled = true,
            canSubmit = false,
            onName = {},
            onUrl = {},
            onSubmit = {},
            onBack = {},
        )
        Screen.Portal -> PortalFormScreen(
            ActivationForm.Portal(),
            enabled = true,
            canSubmit = false,
            onName = {},
            onUrl = {},
            onSubmit = {},
            onBack = {},
        )
        Screen.Saved -> SavedSourcesScreen(
            state = SavedSourcesState.Ready(saved = listOf(SAVED), activeId = SAVED.id),
            onChoose = {},
            onRename = { _, _ -> },
            onDelete = {},
            onBack = {},
        )
    }

    /** `requiredSize`, so the frame is imposed rather than negotiated. */
    @Composable
    private fun Stage(frame: Frame, direction: LayoutDirection, content: @Composable () -> Unit) {
        CastivioTheme {
            CompositionLocalProvider(
                LocalDeviceClass provides frame.device,
                LocalLayoutDirection provides direction,
            ) {
                Box(Modifier.requiredSize(frame.width, frame.height)) { content() }
            }
        }
    }

    private fun ComposeContentTestRule.bounds(tag: String): DpRect =
        onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private companion object {
        /** The reporter's handset as the layout receives it: 873dp less the cutout. */
        val HANDSET = Frame(827.dp, 393.dp, DeviceClass.Expanded)
        val TELEVISION = Frame(960.dp, 540.dp, DeviceClass.Television)

        val SAVED = ProviderSource(
            id = "a",
            kind = SourceKind.M3U_URL,
            label = "Playlist 1",
            url = "http://example.com/get.php",
        )
    }
}
