package com.castivio.feature.activation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.castivio.domain.LocalFolder
import com.castivio.domain.LocalMediaKind
import com.castivio.domain.LocalTrack
import com.castivio.domain.LocalVideo

/**
 * The device's media, made ready for a screen to draw.
 *
 * One host for the four screens, because they are four views of two queries. It owns three
 * things none of them should:
 *
 * **The permission.** Asked for once, on first open, and re-checked whenever the screen
 * resumes — so a user who grants it in system settings and comes back finds a full library
 * rather than the same empty one.
 *
 * **The paging.** Sixty at a time, the next page fetched when a list nears its end.
 *
 * **The mapping.** `MediaStore` rows become the tiles and rows the screens already take,
 * so not one of the four screens changed shape to get real data — which is the point. They
 * were drawn and measured against a design; swapping the source of their contents is not
 * licence to redraw them.
 */
@Composable
internal fun LocalMediaHost(
    kind: LocalMediaKind,
    content: @Composable (LocalMediaState, LocalMediaHostActions) -> Unit,
) {
    val model: LocalMediaViewModel = hiltViewModel()
    val state by model.state.collectAsStateWithLifecycle()

    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { model.refreshPermission() }

    LaunchedEffect(kind) { model.open(kind) }

    // Asked once, and only when it has not already been answered. Re-asking on every
    // composition would put the system dialog in front of a user who declined it, on every
    // single visit, which is how an application teaches people to press Deny by reflex.
    val permissions = remember(model) { model.requiredPermissions() }
    LaunchedEffect(state.granted) {
        if (!state.granted && !MediaPermissions.asked) {
            MediaPermissions.asked = true
            request.launch(permissions)
        }
    }

    // Granting happens in Settings as often as in the dialog, and the app is stopped while
    // it does. Resuming is the only moment we can notice.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.refreshPermission()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    val actions = remember(model, state.granted) {
        LocalMediaHostActions(
            loadMore = model::loadMore,
            openFolder = model::openFolder,
            requestPermission = {
                MediaPermissions.asked = true
                request.launch(permissions)
            },
            permission = when {
                state.granted -> MediaPermission.Granted
                state.loadedOnce -> MediaPermission.Denied
                else -> MediaPermission.Unknown
            },
        )
    }

    content(state, actions)
}

/** What a screen can ask the host to do, and what it needs to know about the permission. */
internal data class LocalMediaHostActions(
    val loadMore: () -> Unit,
    val openFolder: (String?) -> Unit,
    val requestPermission: () -> Unit,
    val permission: MediaPermission,
)

/**
 * Three states, not two.
 *
 * [Unknown] is the moment before the first answer, and it exists so that no screen flashes
 * "Castivio needs permission" during the half second it takes to find out that it already
 * has it.
 */
internal enum class MediaPermission { Unknown, Granted, Denied }

/**
 * Whether the dialog has been put in front of the user in this session.
 *
 * Deliberately process-wide rather than per screen. All four screens read the same two
 * permissions, so a user who declines on the video library must not be asked again the
 * moment they open the audio one — that is the same question, and asking it four times is
 * how an application gets denied permanently.
 */
internal object MediaPermissions {
    var asked: Boolean = false
}

/* ------------------------------------------------------------------- the mapping */

/**
 * A video row, as the tile the grid already draws.
 *
 * The name is the file's own and is present from the first frame of the list — there is no
 * second read behind it. That is the whole difference from the reference players that show
 * `loading…` in this position: the picture is what arrives late, never the name.
 */
internal fun LocalVideo.asTile(): MediaTile = MediaTile(
    name = name,
    duration = formatDuration(durationMs),
    uri = uri,
)

internal fun LocalTrack.asTile(): MediaTile = MediaTile(
    name = name,
    duration = formatDuration(durationMs),
    uri = uri,
    albumId = albumId,
)

internal fun LocalVideo.asSelection() = LocalMediaSelection(uri = uri, title = name, isVideo = true)

internal fun LocalTrack.asSelection() = LocalMediaSelection(
    uri = uri,
    // The artist where the tags carry one, because "Fairuz — Kifak Inta" is what somebody
    // recognises and the filename frequently is not. The filename is the fallback, never a
    // blank.
    title = artist?.let { "$it — $name" } ?: name,
    isVideo = false,
)

/**
 * A picker's listing: the way up, then the folders, then the files.
 *
 * Folders first because a picker is for walking somewhere, and a file list that buries its
 * folders under two hundred videos is a file list nobody can navigate.
 */
internal fun LocalMediaState.entries(parentLabel: String): List<PickerEntry> = buildList {
    if (folder != null) add(PickerEntry(parentLabel, "", PickerEntry.EntryKind.Parent))
    if (folder == null) {
        folders.forEach { add(it.asEntry()) }
    }
    videos.forEach { add(PickerEntry(it.name, formatDuration(it.durationMs), PickerEntry.EntryKind.File)) }
    tracks.forEach { add(PickerEntry(it.name, formatDuration(it.durationMs), PickerEntry.EntryKind.File)) }
}

private fun LocalFolder.asEntry() = PickerEntry(name, count.toString(), PickerEntry.EntryKind.Folder)

/**
 * What pressing row [index] in a picker means.
 *
 * The listing is three concatenated groups, so the index has to be resolved back through
 * them. Done here rather than in the screen because the screen's job is to draw a list of
 * rows, and a screen that knew a row's index was an offset into three collections would be
 * a screen that breaks when the order changes.
 */
internal fun LocalMediaState.open(
    index: Int,
    host: LocalMediaHostActions,
    onPlay: (LocalMediaSelection) -> Unit,
) {
    var cursor = index
    if (folder != null) {
        if (cursor == 0) {
            host.openFolder(null)
            return
        }
        cursor--
    } else {
        if (cursor < folders.size) {
            host.openFolder(folders[cursor].name)
            return
        }
        cursor -= folders.size
    }
    videos.getOrNull(cursor)?.let { onPlay(it.asSelection()); return }
    tracks.getOrNull(cursor)?.let { onPlay(it.asSelection()) }
}

/**
 * A duration, as digits.
 *
 * Hours only when there are hours: `0:04:12` on a four-minute track reads as a bug, and a
 * library is mostly minutes.
 */
internal fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * What the activation flow hands upward when a file is chosen.
 *
 * Three fields, and every one of them already known: nothing here needs a lookup, which is
 * what lets the player draw a title before it has a frame.
 */
data class LocalMediaSelection(
    val uri: String,
    val title: String,
    val isVideo: Boolean,
)
