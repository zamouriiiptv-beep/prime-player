package com.castivio.feature.activation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.castivio.core.design.components.CastivioArtwork
import com.castivio.core.design.components.GlassCard
import com.castivio.core.design.components.InteractiveGlassCard
import com.castivio.core.design.components.rememberThumbnail
import com.castivio.core.design.icons.CastivioIcons
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Spacing

/**
 * Every video on this device.
 *
 * A wall of 16:9 tiles that keeps its tile *size* and changes how many fit: a
 * television gets three at 240dp and a handset four at 150dp from one figure, with no
 * breakpoint table to keep in step with a frame. Reading order is the grid's own, so
 * a D-pad moves along a row and then down between rows in either direction.
 *
 * [videos] is supplied rather than fetched. The query behind it is `MediaStore.Video`
 * and it belongs to the slice after this one; what this file owns is how a video looks
 * once something hands one over — a question that can be settled now, and one a screen
 * that also owned a cursor would answer worse.
 */
@Composable
internal fun VideoLibraryScreen(
    videos: List<MediaTile>,
    onPlay: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onNearEnd: () -> Unit = {},
    permission: MediaPermission = MediaPermission.Granted,
) {
    MediaScaffold(
        title = stringResource(R.string.media_video_library_title),
        onBack = onBack,
        backTag = ActivationTags.LIBRARY_BACK,
        containerTag = ActivationTags.LIBRARY_CONTAINER,
        headingTag = ActivationTags.LIBRARY_HEADING,
        modifier = modifier,
    ) { m ->
        if (videos.isEmpty()) {
            EmptyBand(m, emptyMessage(permission, R.string.media_video_library_empty))
            return@MediaScaffold
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = BrowseTileMin),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .fadeAtBottom(BrowseFade)
                .testTag(ActivationTags.LIBRARY_GRID),
            horizontalArrangement = Arrangement.spacedBy(BrowseTileGap),
            verticalArrangement = Arrangement.spacedBy(BrowseTileGap),
        ) {
            items(count = videos.size) { index ->
                // The next page is asked for from the item that is nearing the end rather
                // than from a scroll listener, so the trigger is "the user can see the
                // bottom" instead of a pixel offset that means different things on a
                // handset and a television.
                if (index >= videos.size - PREFETCH) NearEnd(onNearEnd)
                VideoTile(m, videos[index], index) { onPlay(index) }
            }
        }
    }
}

/**
 * One tile: artwork with the duration on it, the name beneath.
 *
 * The duration rides the picture rather than taking a line of its own. That is the
 * universal idiom, and it buys the tile 20dp — on the shortest frame the difference
 * between one row of tiles and a second row peeking over the fold.
 *
 * The focus ring is on the picture because the picture is the target: the name is a
 * caption, and a ring around both would be a rectangle with a ragged bottom the day a
 * title wraps. `InteractiveGlassCard` draws it, so this tile and a card on the source
 * choice light up identically under a remote.
 */
@Composable
private fun VideoTile(m: SourceMetrics, tile: MediaTile, index: Int, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        InteractiveGlassCard(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(WIDESCREEN)
                .testTag(ActivationTags.BROWSE_TILE)
                .semantics(mergeDescendants = true) {
                    contentDescription = "${tile.name}. ${tile.duration}"
                },
            shape = RoundedCornerShape(m.radius),
            // The drawn placeholder is what the tile is *until* the platform produces a
            // frame, and for the files it never produces one for. It is a fill rather than
            // a grey box with a broken-image glyph because "no thumbnail yet" is the
            // ordinary state for the first second of a cold library, not a fault.
            fill = CastivioArtwork.placeholder(index),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                // The real frame, when it arrives. Drawn over the placeholder rather than
                // instead of it, so the tile never flickers through a blank on the swap.
                val frame by rememberThumbnail(tile.uri, BrowseTileMin, BrowseTileMin)
                frame?.let { picture ->
                    Image(
                        bitmap = picture,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                DurationPill(m, tile.duration)
            }
        }
        Text(
            text = tile.name,
            style = CastivioType.labelLarge,
            color = CastivioTheme.colors.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The duration, over the corner of the picture.
 *
 * A round `GlassCard` rather than a coloured chip: the artwork underneath is
 * a different colour on every tile, and the surface that stays readable over all of
 * them is the one the rest of the product already uses for exactly this.
 */
@Composable
private fun DurationPill(m: SourceMetrics, duration: String) {
    GlassCard(
        modifier = Modifier.padding(Spacing.xs),
        // A pill, so half its own height whatever that height is -- the frame's
        // `radius` is for the rectangles on the stage, not for a shape that is round
        // by definition.
        shape = RoundedCornerShape(percent = 50),
    ) {
        Text(
            text = duration,
            style = CastivioType.labelSmall,
            color = CastivioTheme.colors.onBackground,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs / 2),
        )
    }
}

/**
 * Every audio file on this device.
 *
 * A list rather than a grid, because audio has no picture worth a 16:9 hole: a note, a
 * name and a length is the whole of what tells one track from another, and a row of
 * that reaches four times as many items into the same band. Row height is
 * `Sizing.minTarget`, so each one is a target before it is a line.
 *
 * The glyph is chosen here rather than by the caller — a screen that is handed its
 * icons is a screen whose icon can be wrong.
 */
@Composable
internal fun AudioLibraryScreen(
    tracks: List<MediaTile>,
    onPlay: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onNearEnd: () -> Unit = {},
    permission: MediaPermission = MediaPermission.Granted,
) {
    MediaScaffold(
        title = stringResource(R.string.media_audio_library_title),
        onBack = onBack,
        backTag = ActivationTags.LIBRARY_BACK,
        containerTag = ActivationTags.LIBRARY_CONTAINER,
        headingTag = ActivationTags.LIBRARY_HEADING,
        modifier = modifier,
    ) { m ->
        if (tracks.isEmpty()) {
            EmptyBand(m, emptyMessage(permission, R.string.media_audio_library_empty))
            return@MediaScaffold
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .fadeAtBottom(BrowseFade)
                .testTag(ActivationTags.LIBRARY_LIST),
            verticalArrangement = Arrangement.spacedBy(BrowseItemGap),
        ) {
            items(count = tracks.size) { index ->
                if (index >= tracks.size - PREFETCH) NearEnd(onNearEnd)
                val track = tracks[index]
                MediaListRow(
                    row = MediaRow(
                        name = track.name,
                        detail = track.duration,
                        icon = CastivioIcons.AudioLibrary,
                        uri = track.uri,
                        albumId = track.albumId,
                    ),
                    onClick = { onPlay(index) },
                )
            }
        }
    }
}

/**
 * Nothing to show, said in the user's terms.
 *
 * Not an error and not a spinner: a device with no videos on it is an ordinary device,
 * and the sentence says what is true rather than apologising for it. It takes the band
 * the list would have taken, so the container keeps its shape and Back does not float
 * up to meet the title.
 */
@Composable
private fun ColumnScope.EmptyBand(m: SourceMetrics, message: String) {
    Box(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(m.cardPad),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = CastivioType.bodyLarge,
            color = CastivioTheme.colors.onBackgroundVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 16:9, the shape a video is, written once. */
private const val WIDESCREEN = 16f / 9f

/**
 * How many rows from the end the next page is asked for.
 *
 * Half a page: enough that the fetch has landed before the user reaches the fold, few
 * enough that a fast scroll does not queue four pages it will never look at.
 */
private const val PREFETCH = 30

/**
 * A side effect that fires once when a particular item is composed.
 *
 * A composable rather than a call inside the item body, because the item body runs on every
 * recomposition and the fetch must not. `LaunchedEffect(Unit)` inside the item scope is
 * keyed to that item's lifetime, which is exactly "the user has scrolled this far".
 */
@Composable
private fun NearEnd(onReached: () -> Unit) {
    LaunchedEffect(Unit) { onReached() }
}

/**
 * What an empty list says, which depends on why it is empty.
 *
 * Three different sentences for three different situations, because "no videos on this
 * device" in front of a user who simply declined a permission is the application blaming
 * the device for its own unanswered question.
 */
@Composable
private fun emptyMessage(permission: MediaPermission, whenEmpty: Int): String = when (permission) {
    MediaPermission.Granted -> stringResource(whenEmpty)
    MediaPermission.Denied -> stringResource(R.string.media_permission_denied)
    MediaPermission.Unknown -> stringResource(R.string.media_reading)
}
