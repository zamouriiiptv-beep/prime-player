package com.castivio.core.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.castivio.core.design.theme.CastivioTheme

/**
 * The wide picture a provider sent of a show, drawn behind the top of its page and let go into the ground.
 *
 * Cropped to fill the box it is given, then faded out towards the start edge -- where the poster, the name and the
 * facts stand over it -- and towards the foot, where the page goes on, so the words are read against the application's
 * own ground and never against a still. Like [PosterArtwork] this file is where the loader is seen: a feature hands
 * over a url and does not import Coil.
 *
 * Nothing is drawn while the picture is on its way, or when it failed: the page's own ground is under it, so a
 * backdrop that never arrives leaves the page as it would have been, and no stand-in claims to be the show's.
 *
 * It follows the page's direction, as the page does: the start edge it fades towards is the left one in English and the
 * right one in Arabic, where the poster and the words stand.
 */
@Composable
fun ShowBackdrop(url: String, modifier: Modifier = Modifier) {
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) return
    val ground = CastivioTheme.colors.background
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(modifier) {
        AsyncImage(
            // A wide picture, hundreds of kilobytes: asked for as a picture, and given a picture's limit and not a logo's.
            model = ImageRequest.Builder(LocalContext.current)
                .data(url)
                .addHeader(ArtworkPolicy.KIND_HEADER, ArtworkPolicy.KIND_PICTURE)
                .crossfade(false)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onError = { failed = true },
            modifier = Modifier.fillMaxSize(),
        )
        // The whole picture is dimmed: the plot runs across it, and a still is bright where the words are.
        Box(Modifier.fillMaxSize().background(ground.copy(alpha = DIM)))
        Box(
            Modifier
                .fillMaxSize()
                // The gradient runs from the left edge, so where the start edge is the right one it is turned round.
                .then(if (rtl) Modifier.graphicsLayer(scaleX = -1f) else Modifier)
                .background(
                    Brush.horizontalGradient(
                        0f to ground,
                        START_SOLID to ground.copy(alpha = START_ALPHA),
                        END_CLEAR to Color.Transparent,
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(FOOT_BEGINS to Color.Transparent, 1f to ground)),
        )
    }
}

/** Where along the width the ground stops being solid, and how much of it is left there. */
private const val START_SOLID = 0.4f
private const val START_ALPHA = 0.86f

/** How much of the ground lies over the whole picture, so that words across it read. */
private const val DIM = 0.42f

/** Where the picture is clear again, and where the foot's fade begins. */
private const val END_CLEAR = 0.95f
private const val FOOT_BEGINS = 0.6f
