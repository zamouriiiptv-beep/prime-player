package com.castivio.core.design.components

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Radius
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * A picture a provider shipped, drawn where the design reserves room for one.
 *
 * ## Why this is the only file in Castivio that may see the image loader
 *
 * `UI_ARCHITECTURE.md` and `CLAUDE.md` say the same thing about every platform
 * dependency: it sits behind something the features call, not something they import.
 * Room, OkHttp and Media3 already work that way. An image loader is exactly the same
 * kind of dependency — Android-only, replaceable, and the sort of thing a second
 * platform would answer differently — so it gets the same treatment: the library is an
 * `implementation` dependency of `:core:design` alone, which means a feature module
 * that tries to import Coil does not compile. The seam is held by the build, not by
 * somebody remembering.
 *
 * ## What it draws, and what it deliberately does not
 *
 * A picture, or a tile that says there is no picture. Providers are inconsistent about
 * artwork — the same subscription ships logos for one category and none for the next —
 * so "no logo" is a *common* state rather than an error, and an empty slot in a column of
 * pictures reads as something that failed to load. The tile ([NoLogoTile]) says the
 * opposite: nothing is coming, and nothing is wrong.
 *
 * It is the generic picture glyph in a tile, in one of the palette's four hues chosen from
 * [seed], and it is larger than a crossed-out outline was. There is still no generated
 * stand-in: a coloured square with the channel's initials in it is not the channel's logo —
 * it is a different picture wearing its place, and it was removed from this list for that
 * reason. The glyph is the same on every channel and claims nothing.
 *
 * It is drawn in two cases: the provider shipped no url, and the url failed. While a
 * request is in flight the slot is *empty*, deliberately — a tile that appeared for a
 * moment and was then replaced by the picture would flicker down a list a remote is
 * scrolling, and the slot keeps its exact size either way, so nothing moves.
 *
 * The tile is a *square*, and a logo's slot is usually a wide, short box: so it is drawn
 * larger than the slot's height -- see [tileSized] -- and centred on the slot, which keeps
 * its size: nothing around it moves.
 *
 * [LogoTile] still exists and is still right where a large surface would otherwise be
 * blank — the preview plate. It is wrong at 54×40 in a list, which is why this is a
 * separate thing and not a parameter on that one.
 *
 * ## A light plate under a logo too dark for the ground
 *
 * Some logos are drawn in near-black -- France 2 is a coloured dot and a black digit -- and on this product's near-black ground
 * the digit is lost and the logo reads a third the size of its neighbours'. Once a picture has decoded, a small sample of it is
 * judged by [ArtworkInk] off the main thread, and where it is too dark it is drawn on a light plate the size of its slot: the
 * slot, the row and every other logo are as they were. The verdict is kept for the address ([verdicts]), so a list that scrolls
 * back finds it already; until it is known there is no plate, and a plate that appears a moment after its logo is the only
 * movement it makes. Drawn first in `design/mockups/channels-dark-logos-ar.html`.
 *
 * ## Fit, not crop
 *
 * A channel logo is a piece of artwork with its own aspect ratio, usually wider than
 * it is tall and usually with transparent margins. [ContentScale.Fit] keeps all of it
 * inside the slot; cropping would cut the mark. The slot is a *box* the logo sits in,
 * not a frame it fills.
 *
 * @param url the provider's own `tvg-logo` / `stream_icon`, as imported.
 * @param seed the channel's name, which picks the tile's hue so that a channel keeps its colour wherever it is drawn. Null is the
 *   quiet grey tile.
 * @param description what the picture is, for a screen reader. Null where the name is
 *   already drawn beside it and reading the logo too would say everything twice.
 * @param imageScale how much larger than its fitted size the *picture* is drawn, about the slot's
 *   centre and in both directions alike, so its proportions do not change. It is a draw-time
 *   scale: the slot, and so everything laid out around it, keeps its size. The "no picture" mark
 *   is not scaled by it. 1 is the picture as fitted.
 */
@Composable
fun ProviderArtwork(
    url: String?,
    description: String?,
    modifier: Modifier = Modifier,
    imageScale: Float = 1f,
    seed: String? = null,
) {
    // Keyed to the url, because a row in a `LazyColumn` is recycled: without the key a
    // channel whose logo failed would hand its failure to whichever channel scrolled
    // into its slot next.
    var failed by remember(url) { mutableStateOf(false) }
    var onPlate by remember(url) { mutableStateOf(url?.let { verdicts.get(it) } == true) }
    val scope = rememberCoroutineScope()
    val colors = CastivioTheme.colors

    Box(
        modifier.then(if (onPlate) Modifier.background(colors.artworkPlate, RoundedCornerShape(Radius.xs)) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank() || failed) {
            // Decorative: the name beside it is already the row's label, and announcing "no image" on every second row would make
            // a screen reader unusable on this list.
            NoLogoTile(seed, Modifier.tileSized())
        } else {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    // Cross-fade is off on purpose. Twelve rows animating their logos in
                    // as a list settles is movement nobody asked for, on the screen where
                    // a viewer is trying to read names.
                    .crossfade(false)
                    // Software bitmaps: the picture is sampled once to judge its ink, and a hardware bitmap can be neither read nor
                    // drawn into a software canvas. These are logos of a few kilobytes.
                    .allowHardware(false)
                    .build(),
                contentDescription = description,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                onSuccess = { state ->
                    // Judged once per address, off the main thread, and kept: a verdict that is already known was applied when the
                    // slot was composed.
                    if (verdicts.get(url) == null) {
                        val drawable = state.result.drawable
                        scope.launch {
                            val dark = withContext(Dispatchers.Default) { needsPlate(drawable) }
                            verdicts.put(url, dark)
                            onPlate = dark
                        }
                    }
                },
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = imageScale
                    scaleY = imageScale
                },
            )
        }
    }
}

/** What [ArtworkInk] said of each address already judged: the most recent few hundred, which is more than a list shows. */
private val verdicts = LruCache<String, Boolean>(VERDICTS)

private const val VERDICTS = 512

/** The decoded picture, sampled into a small square with its transparency kept, and judged. */
private fun needsPlate(drawable: Drawable): Boolean {
    val sample = drawable.toBitmap(ArtworkInk.SAMPLE, ArtworkInk.SAMPLE, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(ArtworkInk.SAMPLE * ArtworkInk.SAMPLE)
    sample.getPixels(pixels, 0, ArtworkInk.SAMPLE, 0, 0, ArtworkInk.SAMPLE, ArtworkInk.SAMPLE)
    return ArtworkInk.needsPlate(pixels)
}

/**
 * Sizes the tile of a channel with no logo: the largest square that fits the slot, and where the slot is a wide, short box -- a logo's
 * -- larger than its height, up to [TILE_OF_WIDTH] of its width or [TILE_OF_HEIGHT] of its height, whichever is less, centred on it.
 * So in a 27 by 11dp slot it is a 21dp square standing a little over the slot's top and bottom -- inside the row, which is taller -- and in
 * a square slot it fills it. The slot reports its own size either way: nothing around it moves.
 */
private fun Modifier.tileSized(): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val fitting = minOf(width, height)
    val grown = minOf(width * TILE_OF_WIDTH, height * TILE_OF_HEIGHT).roundToInt()
    val side = maxOf(fitting, grown)
    val placeable = measurable.measure(Constraints.fixed(side, side))
    layout(width, height) { placeable.place((width - side) / 2, (height - side) / 2) }
}

/** How much of a wide slot's width the tile takes, and how many times its height it may stand: the mockup's 21dp in 27 by 11. */
private const val TILE_OF_WIDTH = 0.8f
private const val TILE_OF_HEIGHT = 1.9f
