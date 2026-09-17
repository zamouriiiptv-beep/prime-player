package com.castivio.core.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ImageNotSupported
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.castivio.core.design.theme.CastivioTheme

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
 * A picture, or a mark that says there is no picture. Providers are inconsistent about
 * artwork — the same subscription ships logos for one category and none for the next —
 * so "no logo" is a *common* state rather than an error, and an empty slot in a column of
 * pictures reads as something that failed to load. The crossed-out frame says the
 * opposite: nothing is coming, and nothing is wrong.
 *
 * There is still no generated stand-in. A coloured square with the channel's initials in
 * it is not the channel's logo — it is a different picture wearing its place, and it was
 * removed from this list for that reason. A crossed-out frame claims nothing.
 *
 * It is drawn in two cases: the provider shipped no url, and the url failed. While a
 * request is in flight the slot is *empty*, deliberately — a mark that appeared for a
 * moment and was then replaced by the picture would flicker down a list a remote is
 * scrolling, and the slot keeps its exact size either way, so nothing moves.
 *
 * [LogoTile] still exists and is still right where a large surface would otherwise be
 * blank — the preview plate. It is wrong at 54×40 in a list, which is why this is a
 * separate thing and not a parameter on that one.
 *
 * ## Fit, not crop
 *
 * A channel logo is a piece of artwork with its own aspect ratio, usually wider than
 * it is tall and usually with transparent margins. [ContentScale.Fit] keeps all of it
 * inside the slot; cropping would cut the mark. The slot is a *box* the logo sits in,
 * not a frame it fills.
 *
 * @param url the provider's own `tvg-logo` / `stream_icon`, as imported.
 * @param description what the picture is, for a screen reader. Null where the name is
 *   already drawn beside it and reading the logo too would say everything twice.
 */
@Composable
fun ProviderArtwork(
    url: String?,
    description: String?,
    modifier: Modifier = Modifier,
) {
    // Keyed to the url, because a row in a `LazyColumn` is recycled: without the key a
    // channel whose logo failed would hand its failure to whichever channel scrolled
    // into its slot next.
    var failed by remember(url) { mutableStateOf(false) }

    Box(modifier, contentAlignment = Alignment.Center) {
        if (url.isNullOrBlank() || failed) {
            Icon(
                imageVector = Icons.Rounded.ImageNotSupported,
                // The mark states a fact about the picture, and the name beside it is
                // already the row's label; announcing "no image" on every second row
                // would make a screen reader unusable on this list.
                contentDescription = null,
                tint = CastivioTheme.colors.onBackgroundMuted,
                // Square, and sized from the slot's height: an `Icon` is 24dp
                // unless it is told otherwise, which on this row is nearly the
                // whole slot on a phone and a third of it on a television.
                modifier = Modifier.fillMaxHeight(MARK_OF_SLOT).aspectRatio(1f),
            )
        } else {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    // Cross-fade is off on purpose. Twelve rows animating their logos in
                    // as a list settles is movement nobody asked for, on the screen where
                    // a viewer is trying to read names.
                    .crossfade(false)
                    .build(),
                contentDescription = description,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * How much of the slot the "no picture" mark fills.
 *
 * Smaller than a logo would be. It is a note about an absence, and a note drawn at the
 * size of the thing it stands in for competes with the names down the column — which are
 * what the list is actually for.
 */
private const val MARK_OF_SLOT = 0.62f
