package com.castivio.feature.activation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.castivio.core.design.components.castivioBodyStyle
import com.castivio.core.design.components.castivioDescriptionColor
import com.castivio.core.design.icons.CastivioIcons
import com.castivio.core.design.theme.CastivioTheme

/**
 * What the picker is looking for.
 *
 * Two screens or one is the whole question this type answers, and it is one: a video
 * picker and an MP3 picker differ in a title, a glyph and a caption. Two files would
 * be two chances for the row height to drift.
 *
 * The MIME filters the system picker will be given — every video type, and `audio/mpeg`
 * for an MP3 — are not here and not in the interface. They are how this will be built;
 * what the user is choosing is a video or a song.
 */
internal enum class PickerKind { Video, Audio }

/**
 * One thing in a folder: another folder, or a file that can be played.
 *
 * The parent row is a folder with no detail, which is why [detail] is allowed to be
 * empty rather than made nullable — an empty figure draws nothing and a null one would
 * need a branch at every call site.
 */
internal data class PickerEntry(
    val name: String,
    val detail: String,
    val kind: EntryKind,
) {
    internal enum class EntryKind { Parent, Folder, File }
}

/**
 * Choosing one file from the device, in Castivio's own hand.
 *
 * The system's document picker is a different application appearing over this one:
 * its own type scale, its own dark grey, its own idea of where Back is, and on a
 * television frequently no D-pad story at all. So the browsing is drawn here and the
 * platform is asked only for the file — which is also why nothing in this file knows
 * what a `Uri` is.
 *
 * Three bands: where you are, what is here, and the way back. The path is a caption
 * rather than a control in this pass; walking *up* is the first row of the list, which
 * is where a remote's focus already is.
 */
@Composable
internal fun FilePickerScreen(
    kind: PickerKind,
    path: String,
    entries: List<PickerEntry>,
    onOpen: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onNearEnd: () -> Unit = {},
    permission: MediaPermission = MediaPermission.Granted,
) {
    val title = when (kind) {
        PickerKind.Video -> stringResource(R.string.media_video_pick_title)
        PickerKind.Audio -> stringResource(R.string.media_mp3_pick_title)
    }
    val filter = when (kind) {
        PickerKind.Video -> stringResource(R.string.media_picker_filter_video)
        PickerKind.Audio -> stringResource(R.string.media_picker_filter_audio)
    }

    MediaScaffold(
        title = title,
        onBack = onBack,
        backTag = ActivationTags.PICKER_BACK,
        containerTag = ActivationTags.PICKER_CONTAINER,
        headingTag = ActivationTags.PICKER_HEADING,
        modifier = modifier,
    ) { m ->
        PathBand(m = m, path = path, filter = filter)
        Spacer(Modifier.height(BrowseItemGap))

        if (entries.isEmpty()) {
            EmptyFolder(
                m,
                when (permission) {
                    MediaPermission.Granted -> stringResource(R.string.media_picker_empty)
                    MediaPermission.Denied -> stringResource(R.string.media_permission_denied)
                    MediaPermission.Unknown -> stringResource(R.string.media_reading)
                },
            )
            return@MediaScaffold
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .fadeAtBottom(BrowseFade)
                .testTag(ActivationTags.PICKER_LIST),
            verticalArrangement = Arrangement.spacedBy(BrowseItemGap),
        ) {
            items(count = entries.size) { index ->
                if (index >= entries.size - PICKER_PREFETCH) LaunchedEffect(Unit) { onNearEnd() }
                val entry = entries[index]
                MediaListRow(
                    m = m,
                    row = MediaRow(
                        name = entry.name,
                        detail = entry.detail,
                        icon = glyphFor(entry.kind, kind),
                        isPlace = entry.kind != PickerEntry.EntryKind.File,
                    ),
                    onClick = { onOpen(index) },
                )
            }
        }
    }
}

/**
 * Where you are, and what you are being shown.
 *
 * Two things on one line because they answer the same question from opposite ends: the
 * path says which folder, the caption says which of its files are listed. The caption
 * is in the user's words — "video files only" — because a MIME filter is a fact about
 * the implementation and not about the choice being made.
 */
@Composable
private fun PathBand(m: SourceMetrics, path: String, filter: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = m.cardPad)
            .testTag(ActivationTags.PICKER_PATH),
        horizontalArrangement = Arrangement.spacedBy(m.cardGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = path,
            // The size token but not the colour: a path says *where you are*, which is
            // a fact rather than an explanation, and it shares this line with the
            // caption beside it -- two sizes on one line would read as a mistake.
            style = castivioBodyStyle(m.fsDetail),
            color = CastivioTheme.colors.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = filter,
            style = castivioBodyStyle(m.fsDetail),
            color = castivioDescriptionColor,
            maxLines = 1,
        )
    }
}

/** An empty folder is a fact, not a failure. */
@Composable
private fun ColumnScope.EmptyFolder(m: SourceMetrics, message: String) {
    Box(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(m.cardPad),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = castivioBodyStyle(m.fsDetail),
            color = CastivioTheme.colors.onBackgroundVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The glyph for a row.
 *
 * A file takes the picker's own kind — the sheet with a play mark for video, the sheet
 * with a note for audio — so the list says what it is filtering for without a word.
 * The parent row is a folder with an arrow out of it rather than a chevron: it is a
 * place you go to, and every other place in the list is drawn as a folder.
 */
/** Half a page, the same figure and the same reasoning as the libraries. */
private const val PICKER_PREFETCH = 30

private fun glyphFor(entry: PickerEntry.EntryKind, kind: PickerKind) = when (entry) {
    PickerEntry.EntryKind.Parent -> CastivioIcons.FolderUp
    PickerEntry.EntryKind.Folder -> CastivioIcons.Folder
    PickerEntry.EntryKind.File -> when (kind) {
        PickerKind.Video -> CastivioIcons.VideoFile
        PickerKind.Audio -> CastivioIcons.AudioFile
    }
}
