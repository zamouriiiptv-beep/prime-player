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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
 * Laid out left to right, as the page that uses it is, because the words in it are the provider's: the start edge it
 * fades towards is the left one.
 */
@Composable
fun ShowBackdrop(url: String, modifier: Modifier = Modifier) {
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) return
    val ground = CastivioTheme.colors.background
    Box(modifier) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(false).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onError = { failed = true },
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
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
private const val START_SOLID = 0.28f
private const val START_ALPHA = 0.82f

/** Where the picture is clear again, and where the foot's fade begins. */
private const val END_CLEAR = 0.78f
private const val FOOT_BEGINS = 0.6f
