package com.castivio.core.design.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.Disposable
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.posterPlaceholderBrush
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * A poster a provider shipped, drawn to fill the card it is in.
 *
 * The sibling of [ProviderArtwork] for the one place a picture is meant to *fill* its slot: a
 * poster is cropped to the card's 2:3, where a channel logo is fitted inside a box because cropping
 * would cut the mark. Like it, this file is where the image loader is seen -- a feature composes a
 * card and hands it a url, and does not import Coil.
 *
 * Nothing is drawn while the picture is on its way, or when it failed, or when there is no url:
 * the card's own placeholder is under it and stays the answer, so a poster that never arrives
 * leaves a card, not a hole -- and no stand-in claims to be the film's artwork.
 */
@Composable
internal fun PosterArtwork(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    /** Called once the picture is on screen, so the card can take its placeholder away. */
    onLoaded: () -> Unit = {},
) {
    // Keyed to the url, because a cell of a lazy grid is recycled: a poster that failed must not hand
    // its failure to whichever title scrolls into its place next.
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) return
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(url)
            .crossfade(false)
            .build(),
        contentDescription = null,
        contentScale = contentScale,
        onSuccess = { onLoaded() },
        onError = { failed = true },
        modifier = modifier,
    )
}

/**
 * A poster's own colours, as the ground it stands on: the same picture asked for at a few dozen pixels and
 * drawn to fill the frame, so the loader hands back a small bitmap and the enlargement is what blurs it.
 *
 * Where a poster is fitted whole into a frame wider than itself, the bare ground beside it is a hole in the
 * card. This fills it with the film's own colours, the way a cinema's lobby card would, and the poster
 * stands on it uncropped. It is asked for **after** the poster itself is on screen -- the caller draws it
 * then -- so the original is already in the disk cache and this is a decode of a file, not a second
 * download of the same picture.
 *
 * Not the platform's blur: `Modifier.blur` does nothing under Android 12, which is most television boxes,
 * and costs a render pass per card where this costs a bitmap the size of a thumbnail. Nothing is drawn when
 * the picture fails; the card's own backdrop is under it.
 */
@Composable
internal fun PosterBackdrop(
    url: String,
    modifier: Modifier = Modifier,
    /** Called once the small picture is on screen, so the card can bring the backdrop in. */
    onLoaded: () -> Unit = {},
) {
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) return
    AsyncImage(
        model = backdropRequest(LocalContext.current, url),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        onSuccess = { onLoaded() },
        onError = { failed = true },
        modifier = modifier,
    )
}

/** The one request for a backdrop, so the card and [PosterPrefetch] ask for it in the same words and share an answer. */
private fun backdropRequest(context: Context, url: String): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .size(BACKDROP_WIDTH, BACKDROP_HEIGHT)
        .precision(Precision.EXACT)
        .scale(Scale.FILL)
        .crossfade(false)
        .build()

/**
 * Asks for the posters just below a wall before it scrolls to them, so they are on the device when the cards arrive.
 *
 * Only while the wall is still: a scroll is the moment the cards on screen need the connection, and a request made
 * for a row the thumb is about to pass would stand in front of theirs. When a scroll starts, what is still on its way
 * is cancelled; when the wall settles for [PREFETCH_SETTLE_MS], the [ahead] posters after the last one drawn are asked
 * for again, and any that already arrived are answered from the cache. The request is the backdrop's, which is the
 * small one, but it brings the whole picture into the disk cache on the way, and the poster is then read from there.
 *
 * The feature hands over its list as two lookups and does not see the loader. [itemCount] bounds the lookup, which is
 * how many items the list holds now; [urlAt] answers the poster's address without loading anything.
 */
@Composable
fun PosterPrefetch(
    state: LazyGridState,
    ahead: Int,
    itemCount: () -> Int,
    urlAt: (Int) -> String?,
) {
    val context = LocalContext.current
    val count by rememberUpdatedState(itemCount)
    val lookup by rememberUpdatedState(urlAt)
    LaunchedEffect(state, ahead) {
        val loader = context.imageLoader
        val pending = ArrayList<Disposable>()
        fun cancel() {
            pending.forEach { it.dispose() }
            pending.clear()
        }
        try {
            snapshotFlow { state.isScrollInProgress to (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) }
                .distinctUntilChanged()
                .collectLatest { (scrolling, last) ->
                    cancel()
                    if (scrolling) return@collectLatest
                    delay(PREFETCH_SETTLE_MS)
                    for (index in postersAhead(last, ahead, count())) {
                        val url = lookup(index) ?: continue
                        pending += loader.enqueue(backdropRequest(context, url))
                    }
                }
        } finally {
            cancel()
        }
    }
}

/**
 * The positions of the posters to ask for: the [ahead] after [lastShown], and none past the end of a list of [count].
 *
 * Empty when nothing is shown yet -- [lastShown] is -1 -- because there is no "after" to measure from, and the cards
 * on screen ask for themselves.
 */
internal fun postersAhead(lastShown: Int, ahead: Int, count: Int): IntRange =
    if (lastShown < 0 || ahead <= 0 || count <= 0) IntRange.EMPTY
    else (lastShown + 1)..minOf(lastShown + ahead, count - 1)

/** How long a wall must be still before the posters below it are asked for, in milliseconds. */
private const val PREFETCH_SETTLE_MS = 250L

/**
 * The size the backdrop is asked for, in pixels, at the poster's own 2:3. Small enough that enlarging it to a
 * card reads as a blur and not as a picture: at 16 the shapes of a poster's lettering were still legible
 * in the preview, at 24 they are soft colour, and 32 is the most that still reads as one.
 */
private const val BACKDROP_WIDTH = 24
private const val BACKDROP_HEIGHT = 36

/**
 * A poster at any size, framed: the placeholder, the provider's picture over it, and the card's own edge.
 *
 * What a film's page draws its one large poster with -- the same picture, ratio and edge as the card in
 * the grid it was opened from, so the page reads as that card come closer. The caller sets the size
 * and the ratio; [seed] picks the placeholder, as the card's position does.
 */
@Composable
fun PosterFrame(url: String?, seed: Int, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Radius.sm)
    Box(
        modifier
            .clip(shape)
            .background(posterPlaceholderBrush(seed))
            .border(1.dp, CastivioTheme.colors.glassBorderSoft, shape),
    ) {
        if (!url.isNullOrBlank()) PosterArtwork(url, Modifier.fillMaxSize())
    }
}

/**
 * One short fact on a poster's lower edge: a running time, a rating.
 *
 * Over a picture of any brightness, so it carries its own dim ground -- the scrim, held back a
 * little from black -- and nothing else. Drawn where the card says to, and not at all when the
 * provider shipped no fact to put in it.
 */
@Composable
internal fun PosterTag(text: String, modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    Text(
        text = text,
        style = CastivioType.labelSmall,
        color = colors.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(colors.scrim.copy(alpha = TAG_GROUND))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** How dark the tag's ground is: enough to read over a white poster, and still a tint, not a plate. */
private const val TAG_GROUND = 0.78f
