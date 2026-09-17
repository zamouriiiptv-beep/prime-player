package com.castivio.core.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest

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
 * A url, or nothing. There is no generated stand-in here: the caller has already
 * reserved the space, and a coloured square with initials in it is not the channel's
 * logo — it is a different picture wearing its place. While the request is in flight,
 * and for a channel whose provider shipped no logo, and for a url that 404s, this
 * composable draws an empty box of exactly the size it was given. Nothing moves in any
 * of those cases, which is the property that matters on a list a remote is scrolling.
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
    if (url.isNullOrBlank()) {
        // The reserved slot, empty. Not an early return with nothing drawn: the caller
        // sized this box, and a row whose logo is missing has to be the same height as
        // a row whose logo is there.
        Box(modifier)
        return
    }
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(url)
            // Cross-fade is off on purpose. Twelve rows animating their logos in as a
            // list settles is movement nobody asked for, on the screen where a viewer
            // is trying to read names.
            .crossfade(false)
            .build(),
        contentDescription = description,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}
