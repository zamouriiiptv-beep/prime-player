package com.castivio.feature.player

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.playback.api.VideoOutput

/**
 * The player.
 *
 * ## The one rule this screen does not share with any other
 *
 * Every screen before it is a composition inside a glass container. This one has no
 * container at all: the picture is the screen, edge to edge, and everything else is drawn
 * *over* it and disappears. A container here would be a frame around a film.
 *
 * That changes what the design system means. `glassFill` is 7.8% white, which is invisible
 * over a bright frame and a grey smear over a dark one, so the bars take a scrim from
 * `:core:design` instead and only the controls keep the glass.
 *
 * ## Three bands, and nothing between them
 *
 * Top, centre, bottom, inside one safe-area box. `SpaceBetween` rather than absolute
 * positions, so the bands find their own places on a 360dp handset and a 540dp television
 * from the same composition — and so the gate can assert they do not overlap rather than
 * assert three magic numbers.
 *
 * ## What is composed before the first frame
 *
 * The surface, the title and a spinner. That is the whole of [Picture.Opening], and it is
 * the reason the loading state is a separate branch rather than the ordinary chrome with
 * things hidden: chrome that is composed and then hidden is chrome that was measured, laid
 * out and skipped — cheap, but not free, and the point of the rule is that nothing which is
 * not needed happens at all.
 */
@Composable
fun PlayerScreen(
    state: PlayerState,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val inset = CastivioTheme.device.screenPadding

    Box(
        modifier
            .fillMaxSize()
            .background(colors.background)
            .testTag(PlayerTags.ROOT),
    ) {
        VideoSurface(actions)

        // Locked hides everything but the way out of it, and it does so before the
        // opening branch: a screen that is locked while a channel opens must not draw a
        // spinner the user cannot dismiss.
        when {
            state.locked -> LockPill(state, actions, inset)

            state.picture is Picture.Opening -> OpeningOverlay(state, inset)

            else -> {
                if (state.controls) {
                    Scrims()
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(inset)
                            .testTag(PlayerTags.SAFE),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TopBar(state, actions)
                        CentreCluster(state, actions)
                        BottomBar(state, actions)
                    }
                }

                // Over the chrome, because they are about the picture rather than part of
                // the frame: a buffering spinner belongs in the middle of what is stalling.
                Transients(state, actions, inset)
            }
        }

        // The sheet reaches the screen edge by design — it is a surface, not a control —
        // so it sits outside the safe-area column rather than inside it.
        state.sheet?.let { PlayerSheet(it, state, actions) }

        if (state.statistics) StatisticsPanel(state, actions, inset)
    }
}

/**
 * Where the picture goes.
 *
 * A bare `SurfaceView`, not `PlayerView` from `media3-ui`. That library brings its own
 * control layout, its own timeline and its own idea of what a player looks like, all of
 * which would be composed and hidden underneath Castivio's — a second set of views laid
 * out on every frame to render nothing. It is also, on this screen, a dependency added to
 * throw away.
 *
 * `SurfaceView` and not `TextureView`: a surface goes straight to the compositor and is
 * the cheaper of the two by a wide margin on the low-memory boxes this product is sized
 * for. The cost is that it cannot be animated or transformed, which this screen never does
 * to it.
 *
 * The view is created once and remembered. Recreating it on recomposition would detach the
 * decoder from its output every time the chrome appeared.
 */
@Composable
private fun VideoSurface(actions: PlayerActions) {
    AndroidView(
        factory = { context -> SurfaceView(context) },
        modifier = Modifier
            .fillMaxSize()
            .testTag(PlayerTags.VIDEO),
        onRelease = { actions.setOutput(null) },
        update = { view -> actions.setOutput(VideoOutput.Platform(view)) },
    )

    // Detaching on the way out is not tidiness: a surface that is destroyed while the
    // engine still holds it is a crash on some devices and a black frame on the rest.
    DisposableEffect(Unit) {
        onDispose { actions.setOutput(null) }
    }
}

/**
 * The two gradients, and only where a control sits.
 *
 * A full-screen veil would dim the whole film for the sake of two rows of type, which is
 * the mistake most players make. The bottom is deeper than the top because it carries the
 * timeline, the tools row and, on live, the programme strip.
 */
@Composable
private fun Scrims() {
    val colors = CastivioTheme.colors
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(TOP_SCRIM)
                .background(colors.videoScrimTop),
        )
        Box(Modifier.fillMaxWidth().weight(MIDDLE_CLEAR))
        Box(
            Modifier
                .fillMaxWidth()
                .weight(BOTTOM_SCRIM)
                .background(colors.videoScrimBottom),
        )
    }
}

/**
 * The loading state, in full.
 *
 * A title, a spinner, and nothing else — which is the design constraint made visible. Any
 * element added here has to be defensible as something that needs no work to draw, and
 * almost nothing is: a poster is a fetch, a programme is a query, a track count is a parse.
 */
@Composable
private fun OpeningOverlay(state: PlayerState, inset: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(inset)
            .testTag(PlayerTags.OVERLAY),
    ) {
        OpeningTitle(state, Modifier.align(Alignment.TopStart))
        OpeningSpinner(state, Modifier.align(Alignment.Center))
    }
}

/** The band weights, read off the drawing. 38% and 46%, with the film between them. */
private const val TOP_SCRIM = 38f
private const val MIDDLE_CLEAR = 16f
private const val BOTTOM_SCRIM = 46f

/**
 * Everything the screen can do, gathered so the composition can be rendered without a
 * view model.
 *
 * That is the whole reason for the type. The layout gates compose this screen 30 times
 * across three frames and two directions, and a screen that reached for `hiltViewModel()`
 * would need a Hilt graph, an ExoPlayer and a decoder to answer a question about where a
 * button is.
 */
data class PlayerActions(
    val onBack: () -> Unit = {},
    val onPlayPause: () -> Unit = {},
    val onSeekBy: (Long) -> Unit = {},
    val onSeekTo: (Long) -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onToggleControls: () -> Unit = {},
    val onLock: (Boolean) -> Unit = {},
    val onSheet: (Sheet?) -> Unit = {},
    val onStatistics: (Boolean) -> Unit = {},
    val onSpeed: (Float) -> Unit = {},
    val onSelectTrack: (com.castivio.playback.api.Track) -> Unit = {},
    val onReturnToLive: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onTryBackup: () -> Unit = {},
    val onFullscreen: () -> Unit = {},
    val onPictureInPicture: () -> Unit = {},
    val onCast: () -> Unit = {},
    val onGuide: () -> Unit = {},
    val onChannels: () -> Unit = {},
    val setOutput: (VideoOutput?) -> Unit = {},
)
