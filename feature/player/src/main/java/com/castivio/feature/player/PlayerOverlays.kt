package com.castivio.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.icons.CastivioIcons
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing
import com.castivio.playback.api.EngineId
import com.castivio.playback.api.AspectMode
import com.castivio.playback.api.PlaybackDiagnosis
import com.castivio.playback.api.PlaybackError
import com.castivio.playback.api.Track
import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * The title on the loading screen, and nothing beside it.
 *
 * Composed from [PlayerRequest.title], which the opener already had. No artwork, no
 * programme, no channel logo — each of those is a fetch, and a fetch here is a fetch in
 * front of the picture.
 */
@Composable
internal fun OpeningTitle(state: PlayerState, modifier: Modifier = Modifier) {
    Text(
        text = state.request.title,
        style = CastivioType.titleMedium,
        color = CastivioTheme.colors.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.testTag(PlayerTags.TITLE),
    )
}

@Composable
internal fun OpeningSpinner(state: PlayerState, modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        CircularProgressIndicator(
            color = colors.focusRing,
            trackColor = colors.glassFillStrong,
            modifier = Modifier.size(SPINNER),
        )
        Text(
            // "Switching to the backup" while it is happening, "Opening…" otherwise. One
            // sentence either way: the user did not ask for the switch and cannot act on
            // it, so it is reported and then gone.
            text = stringResource(
                if (state.switching) R.string.player_switching else R.string.player_opening,
            ),
            style = CastivioType.labelMedium,
            color = colors.onBackgroundVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(PlayerTags.TOAST),
        )
    }
}

/**
 * Everything that appears over a picture that already exists.
 *
 * Separated from the opening overlay because the difference is whether there is a frame
 * behind it: buffering dims the film and keeps it, opening has nothing to dim.
 */
@Composable
internal fun BoxScope.Transients(state: PlayerState, actions: PlayerActions, inset: Dp) {
    when (val picture = state.picture) {
        is Picture.Buffering -> CentredSpinner(stringResource(R.string.player_buffering))

        is Picture.Reconnecting -> CentredSpinner(
            stringResource(R.string.player_reconnecting) + "  ·  " +
                stringResource(R.string.player_attempt, picture.attempt, picture.of),
        )

        is Picture.Failed -> FailureCard(picture, state.diagnosis, actions)

        else -> Unit
    }

    JumpMark(state)
}

/**
 * "+10 s", for about a second, in the middle of the picture.
 *
 * ## Why a jump needs to announce itself and a pause does not
 *
 * Pressing pause changes the picture into a still one, and there is nothing to explain. A
 * ten-second jump inside a long take changes almost nothing on screen and moves the head on
 * the bar by a hair, so a viewer who presses it and looks at the picture — which is where
 * they are looking — has no way to tell whether anything happened. That is most of what
 * "the buttons do nothing" felt like on a device even where the seek was being performed.
 *
 * Keyed on [PlayerState.seekRequests] rather than on the distance, so pressing the same
 * control twice shows the mark twice. Keyed on the counter and not on a time, because a
 * time would make this composable ask what the clock says, and nothing in a composition may.
 *
 * It draws over the picture and takes no touches: a mark that could be pressed would be a
 * mark that swallows the next press of the control that raised it.
 */
@Composable
private fun BoxScope.JumpMark(state: PlayerState) {
    val distance = state.lastJumpMs ?: return
    val colors = CastivioTheme.colors

    var shown by remember(state.seekRequests) { mutableStateOf(true) }
    LaunchedEffect(state.seekRequests) {
        delay(JUMP_MARK_MS)
        shown = false
    }
    if (!shown) return

    Text(
        text = stringResource(
            if (distance >= 0) R.string.player_jump_ahead else R.string.player_jump_back,
            jumpDistance(abs(distance)),
        ),
        style = CastivioType.titleMedium,
        color = colors.onBackground,
        modifier = Modifier
            .align(Alignment.Center)
            .clip(RoundedCornerShape(Radius.pill))
            .background(colors.overVideo)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            .testTag(PlayerTags.JUMP_MARK),
    )
}

/** Long enough to read at a glance, short enough not to sit over the film. */
private const val JUMP_MARK_MS = 900L

/**
 * How far a run of presses has moved, written the way a person would say it.
 *
 * Seconds while it is seconds, and minutes once it is minutes: "+90 s" is a figure a reader
 * has to divide, and by the fourth press of a control that is what the mark had become.
 * Under a minute the unit is spelled out because a bare number beside a plus sign could be
 * anything; over one, `m:ss` is the form the timeline beside it already uses.
 */
@Composable
private fun jumpDistance(distanceMs: Long): String {
    val seconds = distanceMs / 1000L
    return if (seconds < SECONDS_IN_MINUTE) {
        stringResource(R.string.player_jump_seconds, seconds.toInt())
    } else {
        clock(distanceMs)
    }
}

private const val SECONDS_IN_MINUTE = 60L

@Composable
private fun BoxScope.CentredSpinner(label: String) {
    val colors = CastivioTheme.colors
    Column(
        Modifier.align(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        CircularProgressIndicator(
            color = colors.focusRing,
            trackColor = colors.glassFillStrong,
            modifier = Modifier.size(SPINNER),
        )
        Text(label, style = CastivioType.labelMedium, color = colors.onBackgroundVariant)
    }
}

/**
 * The three failures, as one card with different buttons.
 *
 * ## What differs, and why it is not cosmetic
 *
 * The shape is identical. What changes is **what can be done about it**, and that is the
 * whole design:
 *
 * - A codec the main engine refused is worth handing to the other one. Two buttons.
 * - A codec *neither* engine can read is not. One button.
 * - Protected content is neither engine's to fix — the device lacks the keys, and a
 *   different decoder on the same device lacks them identically. One button.
 *
 * Offering "try the other player" where it cannot help is worse than offering nothing: it
 * spends the user's time on a second failure and teaches them the button is a lie.
 *
 * The decision is not made here. [Picture.Failed.canTryBackup] arrives already computed by
 * `FallbackPolicy`, which is the same function the automatic fallback consults — so the
 * card and the machine can never disagree about whether the backup is worth trying.
 */
@Composable
private fun BoxScope.FailureCard(failure: Picture.Failed, diagnosis: PlaybackDiagnosis?, actions: PlayerActions) {
    val colors = CastivioTheme.colors
    val copy = failureCopy(failure.reason)

    Column(
        Modifier
            .align(Alignment.Center)
            .fillMaxWidth(CARD_WIDTH)
            .clip(RoundedCornerShape(Radius.xl))
            .background(colors.overVideo)
            .border(HAIRLINE, colors.glassBorder, RoundedCornerShape(Radius.xl))
            .padding(horizontal = Spacing.xl, vertical = Spacing.lg)
            .testTag(PlayerTags.ERROR_CARD),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = stringResource(copy.title),
            style = CastivioType.titleMedium,
            color = colors.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(copy.detail),
            style = CastivioType.bodySmall,
            color = colors.onBackgroundVariant,
            textAlign = TextAlign.Center,
        )
        Row(
            Modifier.padding(top = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (failure.canTryBackup) {
                CardButton(
                    icon = CastivioIcons.Engine,
                    text = stringResource(R.string.player_error_backup),
                    tag = PlayerTags.ERROR_BACKUP,
                    onClick = actions.onTryBackup,
                )
            }
            CardButton(
                icon = CastivioIcons.Retry,
                text = stringResource(R.string.player_error_retry),
                tag = PlayerTags.ERROR_RETRY,
                onClick = actions.onRetry,
            )
        }

        DiagnosisBlock(diagnosis)
    }
}

/**
 * The evidence, under the card, in a debug build only.
 *
 * ## Why it is on the card rather than behind a menu
 *
 * Because the person who needs it is looking at the card. A diagnostic two taps away is a
 * diagnostic that gets described from memory instead of pasted, and the last round of this
 * bug was lost to exactly that: the information existed in `logcat`, `logcat` needed a
 * cable, and what came back was a screenshot of a sentence the app had made up.
 *
 * Compiled out of a release build entirely — `BuildConfig.DEBUG` is a constant the compiler
 * folds, so this is absent rather than merely hidden.
 */
@Composable
private fun ColumnScope.DiagnosisBlock(diagnosis: PlaybackDiagnosis?) {
    if (!BuildConfig.DEBUG || diagnosis == null) return
    val colors = CastivioTheme.colors
    val clipboard = LocalClipboardManager.current
    val text = remember(diagnosis) { diagnosis.render() }

    Text(
        text = text,
        style = CastivioType.codeSmall,
        color = colors.onBackgroundVariant,
        modifier = Modifier
            .padding(top = Spacing.sm)
            .fillMaxWidth()
            .heightIn(max = REPORT_HEIGHT)
            .verticalScroll(rememberScrollState())
            .testTag(PlayerTags.DIAGNOSIS),
    )
    CardButton(
        icon = CastivioIcons.Engine,
        text = stringResource(R.string.player_copy_report),
        tag = PlayerTags.DIAGNOSIS_COPY,
        onClick = { clipboard.setText(AnnotatedString(text)) },
    )
}

/** Tall enough for a decoder failure with its chain, short enough to leave the card a card. */
private val REPORT_HEIGHT = 180.dp

/** Which sentences the card carries. One place, so the three stay distinguishable. */
private data class FailureCopy(val title: Int, val detail: Int)

/**
 * One sentence per reason, and no reason borrowing another's.
 *
 * Several reasons share a card where the user's next move is the same — a permission and a
 * source failure are both "this file could not be opened" to somebody holding a phone — but
 * none of them borrows the *codec* card. That sharing is what produced a report saying a
 * perfectly ordinary MP4 was an unsupported format, and the diagnosis under the card keeps
 * the distinction the copy compresses.
 */
private fun failureCopy(reason: PlaybackError): FailureCopy = when (reason) {
    PlaybackError.UNSUPPORTED_FORMAT ->
        FailureCopy(R.string.player_unsupported_title, R.string.player_unsupported_detail)
    PlaybackError.DRM ->
        FailureCopy(R.string.player_drm_title, R.string.player_drm_detail)
    PlaybackError.NETWORK ->
        FailureCopy(R.string.player_network_title, R.string.player_network_detail)
    PlaybackError.NOT_FOUND ->
        FailureCopy(R.string.player_not_found_title, R.string.player_not_found_detail)
    PlaybackError.PERMISSION, PlaybackError.SOURCE ->
        FailureCopy(R.string.player_source_title, R.string.player_source_detail)
    PlaybackError.TIMEOUT ->
        FailureCopy(R.string.player_timeout_title, R.string.player_timeout_detail)
    PlaybackError.DECODER_INIT, PlaybackError.DECODING, PlaybackError.CONTAINER ->
        FailureCopy(R.string.player_decoder_title, R.string.player_decoder_detail)
    PlaybackError.UNKNOWN ->
        FailureCopy(R.string.player_error_title, R.string.player_error_detail)
}

@Composable
private fun CardButton(icon: ImageVector, text: String, tag: String, onClick: () -> Unit) {
    val colors = CastivioTheme.colors
    val target = Sizing.minTarget(CastivioTheme.device.isTv)
    Row(
        Modifier
            .defaultMinSize(minWidth = target, minHeight = target)
            .clip(RoundedCornerShape(Radius.pill))
            .background(colors.glassFillStrong)
            .border(HAIRLINE, colors.glassBorder, RoundedCornerShape(Radius.pill))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.lg)
            .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.onBackground, modifier = Modifier.size(Sizing.iconMd))
        Text(text, style = CastivioType.labelMedium, color = colors.onBackground, maxLines = 1)
    }
}

/**
 * Locked: the picture, and the one way out of it.
 *
 * Everything else is gone — not dimmed, not disabled, gone. A lock that leaves the controls
 * visible is a lock that gets pressed against.
 */
@Composable
internal fun BoxScope.LockPill(state: PlayerState, actions: PlayerActions, inset: Dp) {
    val colors = CastivioTheme.colors
    Row(
        Modifier
            .align(Alignment.BottomCenter)
            .padding(inset)
            .defaultMinSize(minHeight = Sizing.minTarget(CastivioTheme.device.isTv))
            .clip(RoundedCornerShape(Radius.pill))
            .background(colors.overVideoSoft)
            .border(HAIRLINE, colors.glassBorder, RoundedCornerShape(Radius.pill))
            .clickable(role = Role.Button) { actions.onLock(false) }
            .padding(horizontal = Spacing.lg)
            .testTag(PlayerTags.LOCK_PILL),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            CastivioIcons.Lock,
            contentDescription = null,
            tint = colors.onBackground,
            modifier = Modifier.size(Sizing.iconMd),
        )
        Text(
            stringResource(R.string.player_locked),
            style = CastivioType.labelMedium,
            color = colors.onBackground,
            maxLines = 1,
        )
    }
}

/**
 * Statistics, as a panel and not a sheet.
 *
 * It is read *while* watching, and a sheet would cover the picture it is describing. It
 * sits at the end edge — which mirrors, so in Arabic it is on the left and the title stays
 * readable, and in English it is on the right for the same reason. That correction came out
 * of the drawing: at the start edge it covered the title in RTL.
 *
 * ## The rule this panel is the test of
 *
 * Nothing here is computed until it is composed. There is no bitrate being tracked, no
 * dropped-frame counter running, no codec string cached in case somebody asks. The view
 * model starts a one-second sampler when this opens and cancels it when it closes, and
 * [PlaybackEngine.sample] reads fields the engine already holds.
 */
@Composable
internal fun BoxScope.StatisticsPanel(state: PlayerState, actions: PlayerActions, inset: Dp) {
    val colors = CastivioTheme.colors
    val sample = state.sample
    val unknown = stringResource(R.string.stat_unknown)

    Column(
        Modifier
            .align(Alignment.TopEnd)
            .padding(inset)
            .fillMaxWidth(STATS_WIDTH)
            .clip(RoundedCornerShape(Radius.lg))
            .background(colors.overVideo)
            .border(HAIRLINE, colors.glassBorder, RoundedCornerShape(Radius.lg))
            .padding(Spacing.md)
            .testTag(PlayerTags.STATISTICS),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                CastivioIcons.Quality,
                contentDescription = null,
                tint = colors.onBackground,
                modifier = Modifier.size(Sizing.iconSm),
            )
            Text(
                stringResource(R.string.player_statistics),
                style = CastivioType.labelMedium,
                color = colors.onBackground,
                modifier = Modifier.weight(1f),
            )
            Icon(
                CastivioIcons.Close,
                contentDescription = stringResource(R.string.player_close),
                tint = colors.onBackgroundVariant,
                modifier = Modifier
                    .size(Sizing.iconMd)
                    .clickable(role = Role.Button) { actions.onStatistics(false) },
            )
        }

        StatRow(R.string.stat_resolution, sample?.let { s ->
            if (s.width != null && s.height != null) "${s.width}×${s.height}" else null
        } ?: unknown)
        StatRow(R.string.stat_frame_rate, sample?.frameRate?.let { "%.0f f/s".format(it) } ?: unknown)
        StatRow(
            R.string.stat_codec,
            listOfNotNull(sample?.videoCodec, sample?.audioCodec)
                .map { it.substringAfterLast('/').uppercase() }
                .joinToString(" · ")
                .ifEmpty { unknown },
        )
        StatRow(R.string.stat_bitrate, sample?.bitrateBps?.let { "%.1f Mb/s".format(it / 1_000_000f) } ?: unknown)
        StatRow(R.string.stat_buffer, sample?.let { "%.1f s".format(it.bufferedMs / 1000f) } ?: unknown)
        StatRow(R.string.stat_dropped, sample?.droppedFrames?.toString() ?: unknown)
        StatRow(R.string.stat_startup, sample?.startupMs?.let { "%.1f s".format(it / 1000f) } ?: unknown)
        StatRow(
            R.string.stat_engine,
            if (state.engine == EngineId.BACKUP) {
                stringResource(R.string.player_engine_backup)
            } else {
                stringResource(R.string.stat_engine_main)
            },
        )

        // The two rows that make a jump control diagnosable from a photograph.
        //
        // It can do nothing for three unrelated reasons and they look identical on a
        // device: the press never reaching the state holder, the source refusing to be
        // sought, or the engine taking a position and staying where it was. The count is
        // taken before every check, so a zero here after pressing the control rules out the
        // last two outright — which is the difference between reading the code and guessing
        // at it.
        StatRow(
            R.string.stat_seek,
            stringResource(
                if (state.seekable) R.string.stat_seek_allowed else R.string.stat_seek_refused,
            ),
        )
        StatRow(
            R.string.stat_seek_asked,
            state.lastSeekMs
                ?.let { "${state.seekRequests} · %.1f s".format(it / 1000f) }
                ?: state.seekRequests.toString(),
        )
    }
}

@Composable
private fun StatRow(label: Int, value: String) {
    val colors = CastivioTheme.colors
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            stringResource(label),
            style = CastivioType.bodySmall,
            color = colors.onBackgroundVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text(value, style = CastivioType.labelSmall, color = colors.onBackground, maxLines = 1)
    }
}

/**
 * The panels that slide in from the end edge.
 *
 * A panel rather than a dialog in the middle, because the film keeps playing and the thumb
 * that opened it is already on that side.
 *
 * ## What is in them, and when it was found out
 *
 * The track lists are whatever the container has already declared — read from the engine's
 * `tracks` flow, which is filled by the player's own `onTracksChanged`. Nothing here asks
 * for tracks, and nothing asked for them before the first frame. A stream with one audio
 * track and no subtitles shows a sentence saying so, which is the honest answer and not an
 * empty list.
 */
@Composable
internal fun BoxScope.PlayerSheet(sheet: Sheet, state: PlayerState, actions: PlayerActions) {
    val colors = CastivioTheme.colors
    val inset = CastivioTheme.device.screenPadding

    Column(
        Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .fillMaxWidth(sheetWidth())
            .background(colors.overVideo)
            .testTag(PlayerTags.SHEET),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = inset, end = inset, top = inset, bottom = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(sheetTitle(sheet)),
                style = CastivioType.titleMedium,
                color = colors.onBackground,
                modifier = Modifier.weight(1f),
            )
            Icon(
                CastivioIcons.Close,
                contentDescription = stringResource(R.string.player_close),
                tint = colors.onBackground,
                modifier = Modifier
                    .size(Sizing.minTarget(CastivioTheme.device.isTv))
                    .clip(RoundedCornerShape(Radius.pill))
                    .clickable(role = Role.Button) { actions.onSheet(null) }
                    .padding(Spacing.md)
                    .testTag(PlayerTags.SHEET_CLOSE),
            )
        }

        val rows = sheetRows(sheet, state)
        if (rows.isEmpty()) {
            Text(
                text = stringResource(R.string.player_no_tracks),
                style = CastivioType.bodySmall,
                color = colors.onBackgroundVariant,
                modifier = Modifier.padding(horizontal = inset),
            )
        } else {
            LazyColumn(
                Modifier
                    .weight(1f)
                    .padding(horizontal = inset),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                items(count = rows.size) { index ->
                    val row = rows[index]
                    SheetOption(row) { row.onPick(actions) }
                }
            }
        }
    }
}

/** One line of a sheet: an icon, a name, a figure, and a tick when it is the current one. */
@Composable
private fun SheetOption(row: SheetRow, onClick: () -> Unit) {
    val colors = CastivioTheme.colors
    if (row.heading) {
        Text(
            text = row.name,
            style = CastivioType.overline,
            color = colors.onBackgroundVariant,
            modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xxs),
        )
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Sizing.minTarget(CastivioTheme.device.isTv))
            .clip(RoundedCornerShape(Radius.md))
            .background(if (row.selected) colors.glassFillStrong else colors.background.copy(alpha = 0f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(row.icon, contentDescription = null, tint = colors.onBackground, modifier = Modifier.size(Sizing.iconMd))
        Text(
            row.name,
            style = CastivioType.bodyLarge,
            color = colors.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        row.detail?.let {
            Text(it, style = CastivioType.bodySmall, color = colors.onBackgroundVariant, maxLines = 1)
        }
        if (row.selected) {
            Icon(
                CastivioIcons.Tick,
                contentDescription = null,
                tint = colors.focusRing,
                modifier = Modifier.size(Sizing.iconSm),
            )
        }
    }
}

private data class SheetRow(
    val icon: ImageVector,
    val name: String,
    val detail: String? = null,
    val selected: Boolean = false,
    /**
     * A group's name rather than a choice in it.
     *
     * The look sheet asks four questions and each has its own answers, so the rows have to
     * be told apart by something more than spacing. A heading is drawn quieter, carries no
     * icon and takes no press — the last of those matters most, because a heading that
     * looked pressable in a list of pressable things is a control that does nothing.
     */
    val heading: Boolean = false,
    val onPick: (PlayerActions) -> Unit = {},
)

private fun sheetTitle(sheet: Sheet): Int = when (sheet) {
    Sheet.Subtitles -> R.string.player_sheet_subtitles
    Sheet.Audio -> R.string.player_sheet_audio
    Sheet.Settings -> R.string.player_sheet_settings
    Sheet.Quality -> R.string.player_sheet_quality
    Sheet.SubtitleLook -> R.string.player_subtitle_look
}

/**
 * What a sheet lists, built from the stream rather than from a fixture.
 *
 * The quality sheet lists the video renditions the container declared — an HLS ladder has
 * several, a plain transport stream has one — plus "Automatic", which is what the selector
 * does when nothing is overridden. A player that invented 1080p/720p/480p rows regardless
 * of what the stream carries would offer three buttons where two do nothing.
 */
@Composable
private fun sheetRows(sheet: Sheet, state: PlayerState): List<SheetRow> = when (sheet) {
    Sheet.Subtitles -> buildList {
        add(
            SheetRow(
                icon = CastivioIcons.Subtitles,
                name = stringResource(R.string.player_subtitles_off),
                selected = state.subtitleTracks.none { it.selected },
            ),
        )
        state.subtitleTracks.forEach { track -> add(trackRow(CastivioIcons.Subtitles, track)) }

        // Last, because it is the one row that is not a track. Choosing what to read comes
        // before choosing how it looks, and a viewer opening this sheet almost always wants
        // the first of those.
        add(
            SheetRow(
                icon = CastivioIcons.Quality,
                name = stringResource(R.string.player_subtitle_look),
                onPick = { it.onSheet(Sheet.SubtitleLook) },
            ),
        )
    }

    // Four questions, each with its answers on one line. A viewer adjusting captions is
    // asking "can I read that from here", and four steps they can try in a second answer it
    // better than a slider they have to nudge while a film plays.
    Sheet.SubtitleLook -> buildList {
        val look = state.subtitleStyle
        add(heading(R.string.player_subtitle_size))
        SubtitleSize.entries.forEach { size ->
            add(
                SheetRow(
                    icon = CastivioIcons.Subtitles,
                    name = stringResource(size.label()),
                    selected = look.size == size,
                    onPick = { it.onSubtitleStyle(look.copy(size = size)) },
                ),
            )
        }
        add(heading(R.string.player_subtitle_colour))
        SubtitleInk.entries.forEach { ink ->
            add(
                SheetRow(
                    icon = CastivioIcons.Subtitles,
                    name = stringResource(ink.label()),
                    selected = look.ink == ink,
                    onPick = { it.onSubtitleStyle(look.copy(ink = ink)) },
                ),
            )
        }
        add(heading(R.string.player_subtitle_backdrop))
        SubtitleBackdrop.entries.forEach { backdrop ->
            add(
                SheetRow(
                    icon = CastivioIcons.Subtitles,
                    name = stringResource(backdrop.label()),
                    selected = look.backdrop == backdrop,
                    onPick = { it.onSubtitleStyle(look.copy(backdrop = backdrop)) },
                ),
            )
        }
        add(heading(R.string.player_subtitle_place))
        SubtitlePlace.entries.forEach { place ->
            add(
                SheetRow(
                    icon = CastivioIcons.Subtitles,
                    name = stringResource(place.label()),
                    selected = look.place == place,
                    onPick = { it.onSubtitleStyle(look.copy(place = place)) },
                ),
            )
        }
    }

    Sheet.Audio -> state.audioTracks.map { track ->
        trackRow(CastivioIcons.AudioTrack, track)
    }

    Sheet.Quality -> buildList {
        add(
            SheetRow(
                icon = CastivioIcons.Quality,
                name = stringResource(R.string.player_quality_auto),
                selected = state.videoTracks.none { it.selected },
            ),
        )
        state.videoTracks.forEach { track -> add(trackRow(CastivioIcons.Quality, track)) }
    }

    Sheet.Settings -> listOf(
        SheetRow(
            icon = CastivioIcons.Speed,
            name = stringResource(R.string.player_speed),
            detail = speedLabel(state.speed),
            onPick = { it.onSpeed(nextSpeed(state.speed)) },
        ),
        SheetRow(
            icon = CastivioIcons.Subtitles,
            name = stringResource(R.string.player_sheet_subtitles),
            onPick = { it.onSheet(Sheet.Subtitles) },
        ),
        SheetRow(
            icon = CastivioIcons.AudioTrack,
            name = stringResource(R.string.player_sheet_audio),
            onPick = { it.onSheet(Sheet.Audio) },
        ),
        SheetRow(
            icon = CastivioIcons.Aspect,
            name = stringResource(R.string.player_aspect),
            // Reads the mode and sets the next one, exactly as the speed row above does.
            // It used to print "Fit" whatever the state was and had no `onPick` at all —
            // an affordance the design had drawn and the code had never connected, which
            // is why a stretched picture could not be corrected from inside the player.
            detail = aspectLabel(state.aspect),
            onPick = { it.onAspect(nextAspect(state.aspect)) },
        ),
        SheetRow(
            icon = CastivioIcons.PictureInPicture,
            name = stringResource(R.string.player_pip),
            onPick = { it.onPictureInPicture() },
        ),
        SheetRow(
            icon = CastivioIcons.Quality,
            name = stringResource(R.string.player_statistics),
            onPick = {
                it.onSheet(null)
                it.onStatistics(true)
            },
        ),
        SheetRow(
            icon = CastivioIcons.Share,
            name = stringResource(R.string.player_share),
            onPick = {
                it.onSheet(null)
                it.onShare()
            },
        ),
    )
}

/**
 * A line of a sheet that names a group rather than offering a choice.
 *
 * [SheetRow.onPick] is left at its default, which does nothing, so a heading pressed by
 * accident is a press that changed nothing rather than one that changed something the
 * viewer cannot see.
 */
@Composable
private fun heading(label: Int): SheetRow = SheetRow(
    icon = CastivioIcons.Quality,
    name = stringResource(label),
    heading = true,
)

private fun SubtitleSize.label(): Int = when (this) {
    SubtitleSize.Small -> R.string.player_subtitle_size_small
    SubtitleSize.Medium -> R.string.player_subtitle_size_medium
    SubtitleSize.Large -> R.string.player_subtitle_size_large
    SubtitleSize.Huge -> R.string.player_subtitle_size_huge
}

private fun SubtitleInk.label(): Int = when (this) {
    SubtitleInk.White -> R.string.player_subtitle_white
    SubtitleInk.Amber -> R.string.player_subtitle_amber
}

private fun SubtitleBackdrop.label(): Int = when (this) {
    SubtitleBackdrop.None -> R.string.player_subtitle_backdrop_none
    SubtitleBackdrop.Shadow -> R.string.player_subtitle_backdrop_shadow
    SubtitleBackdrop.Soft -> R.string.player_subtitle_backdrop_soft
    SubtitleBackdrop.Solid -> R.string.player_subtitle_backdrop_solid
}

private fun SubtitlePlace.label(): Int = when (this) {
    SubtitlePlace.Bottom -> R.string.player_subtitle_place_bottom
    SubtitlePlace.Raised -> R.string.player_subtitle_place_raised
    SubtitlePlace.Top -> R.string.player_subtitle_place_top
}

private fun trackRow(icon: ImageVector, track: Track): SheetRow = SheetRow(
    icon = icon,
    name = track.label,
    detail = track.channels?.let { "$it ch" },
    selected = track.selected,
    onPick = { it.onSelectTrack(track) },
)

/**
 * The speeds, cycled rather than listed.
 *
 * A row that steps through the five useful values is one target; a sub-sheet of five rows
 * is six. On a remote the cycle is the faster of the two by a wide margin, and the label
 * says where you are.
 */
private fun nextSpeed(current: Float): Float {
    val ladder = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    val index = ladder.indexOfFirst { it > current - SPEED_EPSILON && it < current + SPEED_EPSILON }
    return ladder[(index + 1).mod(ladder.size)]
}

private const val SPEED_EPSILON = 0.01f

private val SPINNER = 52.dp
private const val CARD_WIDTH = 0.62f
private const val STATS_WIDTH = 0.42f

@Composable
private fun sheetWidth(): Float = if (CastivioTheme.device.isTv) 0.40f else 0.52f

/**
 * The four fits offered, in the order the row steps through them.
 *
 * [AspectMode.ZOOM] is absent on purpose: cropping needs the surface drawn larger than the
 * frame and clipped, and a `SurfaceView` is composited by the system rather than drawn by
 * Compose, so the clip would not reliably hold. Four modes that work beat five that
 * mostly do.
 */
internal val ASPECT_CYCLE = listOf(
    AspectMode.FIT,
    AspectMode.RATIO_16_9,
    AspectMode.RATIO_4_3,
    AspectMode.FILL,
)

internal fun nextAspect(current: AspectMode): AspectMode {
    val at = ASPECT_CYCLE.indexOf(current)
    return ASPECT_CYCLE[(at + 1) % ASPECT_CYCLE.size]
}

@Composable
internal fun aspectLabel(mode: AspectMode): String = stringResource(
    when (mode) {
        AspectMode.FIT -> R.string.player_aspect_fit
        AspectMode.FILL -> R.string.player_aspect_fill
        AspectMode.RATIO_16_9 -> R.string.player_aspect_16_9
        AspectMode.RATIO_4_3 -> R.string.player_aspect_4_3
        AspectMode.ZOOM -> R.string.player_aspect_zoom
    },
)
