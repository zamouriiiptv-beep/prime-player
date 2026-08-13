package com.castivio.tv.player

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.castivio.feature.player.PlayerRequest
import com.castivio.feature.player.PlayerRoute
import com.castivio.playback.api.MediaKind

/**
 * Where the player is opened from, and the only place in the application that knows both
 * the activation flow and the player exist.
 *
 * ## Why the four browse screens were not touched
 *
 * They already hoist the presses. `onLocalVideo`, `onVideoLibrary`, `onAudioLibrary` and
 * `onPickAudio` have been `() -> Unit` seams since they were written, defaulted to nothing
 * precisely so that the line wiring them could land here — beside the player — rather than
 * being invented inside a feature that has no business owning playback. Connecting them
 * needed no edit to any of the four.
 *
 * ## What a press actually opens
 *
 * The system document picker, and then the player on whatever file comes back.
 *
 * That is the honest wiring today. `MediaStore` is not implemented — it was deferred
 * deliberately — so the tiles in the two library screens are the debug fixture and have no
 * file behind them. The picker is the one route to a real file on the device, so all four
 * seams take it: a press means "play something local", and the chooser is what turns that
 * into a URI. When `MediaStore` lands, the two library seams start carrying an item and
 * this file is where that change goes.
 *
 * The title comes from the picker's own display name, which the platform returns with the
 * URI. That is not a lookup: it is one column of the result already in hand, and the
 * alternative — opening the file to read a container title — would be a read in front of
 * the first frame.
 */
@Composable
internal fun PlayerHost(
    modifier: Modifier = Modifier,
    content: @Composable (openVideo: () -> Unit, openAudio: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var request by remember { mutableStateOf<PlayerRequest?>(null) }

    val pickVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> request = uri?.let { context.toRequest(it, MediaKind.VOD) } }

    val pickAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> request = uri?.let { context.toRequest(it, MediaKind.VOD) } }

    val open = request
    if (open != null) {
        PlayerRoute(
            request = open,
            onLeave = { request = null },
            modifier = modifier,
        )
    } else {
        content(
            { pickVideo.launch(VIDEO_TYPES) },
            { pickAudio.launch(AUDIO_TYPES) },
        )
    }
}

/**
 * A picked document, as something the player can open.
 *
 * The read permission is taken persistently where the provider grants it, because a URI
 * that works now and fails after a rotation is the classic document-picker bug. Where it
 * is not granted the URI still works for this session, which is the whole of what the
 * player needs.
 */
private fun android.content.Context.toRequest(uri: Uri, kind: MediaKind): PlayerRequest {
    runCatching {
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }
    return PlayerRequest(url = uri.toString(), title = displayName(uri), kind = kind)
}

/**
 * The file's name, from the column the picker already returned.
 *
 * Falls back to the last path segment, which is what a `file://` URI or an unhelpful
 * provider leaves. Never null and never empty: the loading screen shows exactly one piece
 * of text and a blank one would read as a broken player.
 */
private fun android.content.Context.displayName(uri: Uri): String {
    val fromProvider = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()
    return fromProvider ?: uri.lastPathSegment ?: uri.toString()
}

/**
 * What the chooser will accept.
 *
 * Wide on purpose. A provider that reports `application/octet-stream` for an `.mkv` — and
 * many do — would have the file greyed out under a strict filter, and a picker that hides
 * the file the user is looking at is worse than one that shows a few it cannot play. The
 * player's error card is the honest answer for the rest.
 */
private val VIDEO_TYPES = arrayOf("video/*", "application/octet-stream", "*/*")
private val AUDIO_TYPES = arrayOf("audio/*", "application/ogg", "*/*")
