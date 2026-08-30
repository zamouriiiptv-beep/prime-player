package com.castivio.feature.player

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.viewinterop.AndroidView
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.playback.api.AspectMode
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

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(colors.background)
            .testTag(PlayerTags.ROOT),
        contentAlignment = Alignment.Center,
    ) {
        // Where the film actually is. Computed once, here, and used twice: the surface is
        // drawn at this size and the chrome is laid out inside it.
        val picture = surfaceSize(maxWidth, maxHeight, state)

        VideoSurface(state, actions, picture)

        // Locked hides everything but the way out of it, and it does so before the
        // opening branch: a screen that is locked while a channel opens must not draw a
        // spinner the user cannot dismiss.
        when {
            state.locked -> LockPill(state, actions, inset)

            state.picture is Picture.Opening -> OpeningOverlay(state, inset)

            else -> {
                if (state.controls) {
                    // The chrome belongs to the film, not to the window.
                    //
                    // It filled the whole screen, so on a 21:9 phone showing a 16:9 film
                    // the clock at each end of the timeline sat in the black pillar beside
                    // the picture — text floating in the letterbox, pointing at nothing.
                    // The scrim was worse: a gradient over black, shading a bar that has
                    // no image in it.
                    //
                    // Sized to the picture and centred with it, everything reads as part
                    // of the film. When there are no bars — fill, or a source whose shape
                    // matches the screen — this is the whole frame and nothing moves.
                    Box(Modifier.size(picture)) {
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
private fun VideoSurface(state: PlayerState, actions: PlayerActions, picture: DpSize) {
    val taps = remember { MutableInteractionSource() }
    val covered = state.statistics || state.sheet != null
    val label = stringResource(
        if (covered) R.string.player_close else R.string.player_reveal_controls,
    )

    Box(
        Modifier
            .fillMaxSize()
            .testTag(PlayerTags.VIDEO)
            // A tap on the picture is how the controls come back, and there was no such
            // tap: `onToggleControls` existed on the contract and nothing on this screen
            // ever called it, so once the chrome auto-hid after four seconds there was no
            // way left to reach play/pause at all.
            //
            // What that tap means depends on what is over the picture, and the ladder is
            // deliberately the same one `PlayerRoute` gives the back button: a panel first,
            // then a sheet, then the chrome. A sheet used to have exactly one way out — the
            // close icon — because a tap on the film beside it toggled the chrome behind it
            // and left the sheet exactly where it was. Innermost first, and nothing on this
            // ladder touches playback: dismissing a panel must never pause a film.
            //
            // Not while locked — the lock pill is the only way out of a lock, and a tap
            // that dismissed it would make the lock protect nothing.
            //
            // `clickable` and not a raw gesture, so the same press works from a remote
            // and from a screen reader. No indication: a ripple across a film is noise.
            .then(
                if (state.locked) {
                    Modifier
                } else {
                    Modifier.clickable(
                        interactionSource = taps,
                        indication = null,
                        onClickLabel = label,
                        onClick = {
                            when {
                                state.statistics -> actions.onStatistics(false)
                                state.sheet != null -> actions.onSheet(null)
                                else -> actions.onToggleControls()
                            }
                        },
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { context -> SurfaceView(context) },
            modifier = Modifier
                .size(picture)
                .testTag(PlayerTags.SURFACE),
            onRelease = { actions.setOutput(null) },
            update = { view -> actions.setOutput(VideoOutput.Platform(view)) },
        )
    }

    // Detaching on the way out is not tidiness: a surface that is destroyed while the
    // engine still holds it is a crash on some devices and a black frame on the rest.
    DisposableEffect(Unit) {
        onDispose { actions.setOutput(null) }
    }
}

/**
 * How big to make the drawing surface inside the frame.
 *
 * This is where the aspect ratio is actually applied, and it has to be here. A
 * `SurfaceView` scales whatever the decoder writes into it to whatever size the view is,
 * so a surface told to fill a 21:9 phone shows a 16:9 film stretched across it — which is
 * what "zoomed and cropped" looked like, and no amount of setting a mode on the engine
 * could have changed it. Sizing the view *is* sizing the surface; the letterbox is the
 * part of the region the surface does not cover.
 *
 * [AspectMode.FIT] is relative to the source and needs [PlayerState.videoAspectRatio];
 * the fixed ratios are not, and answer the same whatever arrives. A null ratio — a sound
 * file, or a frame that has not been decoded — falls through to the frame, because there
 * is nothing yet to letterbox against and black bars around nothing would be a defect of
 * their own.
 *
 * [AspectMode.ZOOM] is deliberately not offered. Cropping means drawing the surface larger
 * than the frame and clipping it, and a `SurfaceView` is composited by the system rather
 * than drawn by Compose — the clip would not reliably hold. A mode that works on some
 * devices is worse than a mode that is not in the list.
 */
private fun surfaceSize(frameWidth: Dp, frameHeight: Dp, state: PlayerState): DpSize {
    val target = when (state.aspect) {
        AspectMode.FILL, AspectMode.ZOOM -> null
        AspectMode.RATIO_16_9 -> WIDE
        AspectMode.RATIO_4_3 -> ACADEMY
        AspectMode.FIT -> state.videoAspectRatio
    }
    if (target == null || target <= 0f || frameHeight.value <= 0f) {
        return DpSize(frameWidth, frameHeight)
    }

    // A frame wider than the picture runs out of height first, so the height is the one
    // to keep and the width is whatever the ratio makes of it. The other way round when
    // the frame is the taller of the two.
    return if (frameWidth / frameHeight > target) {
        DpSize(frameHeight * target, frameHeight)
    } else {
        DpSize(frameWidth, frameWidth / target)
    }
}

private const val WIDE = 16f / 9f
private const val ACADEMY = 4f / 3f

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
    val onAspect: (AspectMode) -> Unit = {},
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
    val onShare: () -> Unit = {},
    val onGuide: () -> Unit = {},
    val onChannels: () -> Unit = {},
    val setOutput: (VideoOutput?) -> Unit = {},
)
