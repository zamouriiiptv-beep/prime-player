package com.castivio.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.castivio.core.design.icons.CastivioIcons
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing

/* ============================================================================
 * The three bands.
 *
 * Live and on-demand are one screen, and that is a product decision rather than an
 * economy: a user who watches both should not have to learn the player twice. Live swaps
 * three things and adds one — the timeline becomes a live position, previous/next step
 * channels rather than chapters, the title gains a number and a LIVE pill, and a programme
 * strip appears above the timeline.
 * ========================================================================= */

/**
 * Title, and the three controls that are not about playback.
 *
 * The title is the item's own, passed in when the player opened. There is no lookup behind
 * it and no way to perform one — see [PlayerRequest].
 */
@Composable
internal fun TopBar(state: PlayerState, actions: PlayerActions) {
    val colors = CastivioTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(PlayerTags.TOP),
        horizontalArrangement = Arrangement.spacedBy(barGap()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerButton(
            icon = CastivioIcons.ArrowBack,
            label = stringResource(R.string.player_back),
            tag = PlayerTags.BACK,
            onClick = actions.onBack,
            mirror = true,
        )

        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = Spacing.xs)
                .testTag(PlayerTags.TITLE),
        ) {
            Text(
                text = state.request.title,
                style = CastivioType.titleMedium,
                color = colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            SubtitleRow(state)
        }

        PlayerButton(
            icon = if (state.locked) CastivioIcons.Unlock else CastivioIcons.Lock,
            label = stringResource(
                if (state.locked) R.string.player_unlock else R.string.player_lock,
            ),
            tag = PlayerTags.LOCK,
            onClick = { actions.onLock(!state.locked) },
        )
        PlayerButton(
            icon = CastivioIcons.Cast,
            label = stringResource(R.string.player_cast),
            tag = PlayerTags.CAST,
            onClick = actions.onCast,
        )
        PlayerButton(
            icon = CastivioIcons.More,
            label = stringResource(R.string.player_more),
            tag = PlayerTags.MORE,
            onClick = { actions.onSheet(Sheet.Settings) },
        )
    }
}

/**
 * What is under the title: the LIVE pill and a channel number, or the item's own subtitle.
 *
 * The engine badge is here and nowhere else. A switch the user did not ask for and cannot
 * act on is a fact to report, not a decision to present — so the second engine is a small
 * word a curious user can find, and nobody else will notice.
 */
@Composable
private fun SubtitleRow(state: PlayerState) {
    val colors = CastivioTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.request.isLive) {
            Pill(
                text = stringResource(R.string.player_live),
                fill = colors.live,
                ink = colors.onBackground,
            )
        }
        state.request.channelNumber?.let {
            Pill(text = it, fill = colors.glassFillStrong, ink = colors.onBackground)
        }
        state.request.subtitle?.let {
            Text(
                text = it,
                style = CastivioType.bodySmall,
                color = colors.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.engine == com.castivio.playback.api.EngineId.BACKUP) {
            Pill(
                text = stringResource(R.string.player_engine_backup),
                fill = colors.glassFill,
                ink = colors.onBackgroundVariant,
                tag = PlayerTags.ENGINE_BADGE,
            )
        }
    }
}

@Composable
private fun Pill(text: String, fill: Color, ink: Color, tag: String? = null) {
    Box(
        Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(fill)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xxs)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    ) {
        Text(text = text, style = CastivioType.labelSmall, color = ink, maxLines = 1)
    }
}

/**
 * The centre: step, jump ten, play, jump ten, step.
 *
 * The ten-second jumps stay on live as well as on a film, and that is deliberate — a live
 * stream that is behind is exactly where a viewer wants them, and removing them on live
 * would mean the control moved depending on what you were watching.
 *
 * The play control is [Sizing.minTarget] plus a margin rather than the same size as its
 * neighbours: it is the one control a thumb finds without looking.
 */
@Composable
internal fun CentreCluster(state: PlayerState, actions: PlayerActions) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(PlayerTags.CENTRE),
        horizontalArrangement = Arrangement.spacedBy(barGapLarge(), Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerButton(
            icon = CastivioIcons.Previous,
            label = stringResource(R.string.player_previous),
            tag = PlayerTags.PREVIOUS,
            onClick = actions.onPrevious,
            mirror = true,
        )
        PlayerButton(
            icon = CastivioIcons.Replay10,
            label = stringResource(R.string.player_replay_10),
            tag = PlayerTags.REPLAY,
            onClick = { actions.onSeekBy(-JUMP_MS) },
        )
        PlayControl(state, actions)
        PlayerButton(
            icon = CastivioIcons.Forward10,
            label = stringResource(R.string.player_forward_10),
            tag = PlayerTags.FORWARD,
            onClick = { actions.onSeekBy(JUMP_MS) },
        )
        PlayerButton(
            icon = CastivioIcons.Next,
            label = stringResource(R.string.player_next),
            tag = PlayerTags.NEXT,
            onClick = actions.onNext,
            mirror = true,
        )
    }
}

@Composable
private fun PlayControl(state: PlayerState, actions: PlayerActions) {
    val colors = CastivioTheme.colors
    val playing = state.picture is Picture.Playing
    Box(
        Modifier
            .size(playSize())
            .clip(CircleShape)
            .background(colors.glassFillStrong)
            .border(HAIRLINE, colors.glassBorder, CircleShape)
            .clickable(role = Role.Button, onClick = actions.onPlayPause)
            .testTag(PlayerTags.PLAY),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (playing) CastivioIcons.Pause else CastivioIcons.Play,
            contentDescription = stringResource(
                if (playing) R.string.player_pause else R.string.player_play,
            ),
            tint = colors.onBackground,
            modifier = Modifier.size(Sizing.iconXl),
        )
    }
}

/**
 * The bottom: programme strip, timeline, tools.
 *
 * The strip is first because it belongs to the channel rather than to the playback, and
 * because putting it above the timeline is what lets it change height without moving the
 * controls — which it never does, but the arrangement is what makes that true rather than
 * lucky.
 */
@Composable
internal fun BottomBar(state: PlayerState, actions: PlayerActions) {
    Column(
        Modifier
            .fillMaxWidth()
            .testTag(PlayerTags.BOTTOM),
        verticalArrangement = Arrangement.spacedBy(barGap()),
    ) {
        if (state.request.isLive) ProgrammeStrip(state)
        Timeline(state, actions)
        ToolsRow(state, actions)
    }
}

/**
 * What is on now — and the height it takes whether or not anybody knows yet.
 *
 * ## The single most important thing in this file
 *
 * The strip is composed at a **fixed height**, and the guide fills words into it later. It
 * is not composed when the guide arrives; it is there from the first frame, as a skeleton.
 *
 * That is what makes the EPG genuinely off the critical path rather than nominally off it.
 * A strip that appeared when the data did would push the timeline and the tools row down
 * by 44dp a second after the channel opened — the user's thumb already moving toward a
 * control that is no longer where they saw it. Reserving the height costs 44dp of picture
 * and buys the guarantee that nothing moves.
 *
 * `PlayerEpgTest` measures this element in both states and fails if the two differ.
 */
@Composable
private fun ProgrammeStrip(state: PlayerState) {
    val colors = CastivioTheme.colors
    val programme = state.programme

    Row(
        Modifier
            .fillMaxWidth()
            .height(stripHeight())
            .clip(RoundedCornerShape(Radius.md))
            .background(colors.glassFill)
            .border(HAIRLINE, colors.glassBorder, RoundedCornerShape(Radius.md))
            .padding(horizontal = Spacing.md)
            .testTag(PlayerTags.EPG),
        horizontalArrangement = Arrangement.spacedBy(barGapLarge()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            SkeletonText(
                text = programme?.now,
                style = CastivioType.bodyLarge,
                colour = colors.onBackground,
                width = SKELETON_TITLE,
            )
            SkeletonText(
                text = programme?.window,
                style = CastivioType.bodySmall,
                colour = colors.onBackgroundVariant,
                width = SKELETON_WINDOW,
            )
        }

        // The progress bar is drawn either way and is simply empty when unknown. A bar
        // that appears with the words would be a second thing moving.
        Box(
            Modifier
                .width(PROGRESS_WIDTH)
                .height(PROGRESS_HEIGHT)
                .clip(RoundedCornerShape(Radius.pill))
                .background(colors.glassFillStrong),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(programme?.progress ?: 0f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(colors.focusRing),
            )
        }

        programme?.next?.let {
            Text(
                text = it,
                style = CastivioType.bodySmall,
                color = colors.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(NEXT_WEIGHT),
            )
        }
    }
}

/**
 * A line of type, or the space it will take.
 *
 * The skeleton is the same height as the text because it is the same text node with a
 * transparent ink and a filled background — not a `Box` sized by hand to something that
 * looks about right. Two ways of computing one height is how the reserved height stops
 * being reserved.
 */
@Composable
private fun SkeletonText(
    text: String?,
    style: androidx.compose.ui.text.TextStyle,
    colour: Color,
    width: Dp,
) {
    val colors = CastivioTheme.colors
    Text(
        text = text ?: " ",
        style = style,
        color = if (text != null) colour else Color.Transparent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = if (text != null) {
            Modifier
        } else {
            Modifier
                .width(width)
                .clip(RoundedCornerShape(Radius.xs))
                .background(colors.glassFillStrong)
        },
    )
}

/**
 * Position, bar, duration.
 *
 * Live has no future to scrub into, so the bar is full and the head sits at the end — and
 * the left figure says "live now" or how far behind the edge you are rather than a
 * timestamp, because a timestamp on a live stream is a number about the provider's window
 * and not about the viewer.
 */
@Composable
private fun Timeline(state: PlayerState, actions: PlayerActions) {
    val colors = CastivioTheme.colors
    val live = state.request.isLive
    val duration = state.durationMs

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(barGapLarge()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when {
                live && state.isTimeshifted ->
                    stringResource(R.string.player_behind_live, clock(state.behindLiveMs))
                live -> stringResource(R.string.player_live_now)
                else -> clock(state.positionMs)
            },
            style = CastivioType.labelMedium,
            color = colors.onBackground,
            maxLines = 1,
            modifier = Modifier.testTag(PlayerTags.POSITION),
        )

        Box(
            Modifier
                .weight(1f)
                .height(TRACK_HEIGHT)
                .clip(RoundedCornerShape(Radius.pill))
                .background(colors.glassFillStrong)
                .testTag(PlayerTags.TIMELINE),
        ) {
            val played = when {
                live -> 1f
                duration != null && duration > 0 ->
                    (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
                else -> 0f
            }
            val buffered = when {
                duration != null && duration > 0 ->
                    ((state.positionMs + state.bufferedMs).toFloat() / duration).coerceIn(0f, 1f)
                else -> played
            }
            Box(
                Modifier
                    .fillMaxWidth(buffered)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(colors.glassBorder),
            )
            Box(
                Modifier
                    .fillMaxWidth(played)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(colors.primaryBrush),
            )
        }

        if (!live) {
            Text(
                text = duration?.let(::clock) ?: stringResource(R.string.stat_unknown),
                style = CastivioType.labelMedium,
                color = colors.onBackground,
                maxLines = 1,
                modifier = Modifier.testTag(PlayerTags.DURATION),
            )
        }
    }
}

/**
 * The one row whose contents vary by state.
 *
 * Live adds a guide and a channel list; a stream that has fallen behind adds the way back
 * to the edge. On a television at 956dp with Arabic labels this ran past the safe area, so
 * the row is allowed to **shrink its items rather than push them out**: the labels
 * ellipsis, the targets never do. That is the whole of the fix, and it is why the wide
 * buttons carry `weight` with a floor rather than a fixed width.
 *
 * "Back to live" is in this row and not in the time row above it. It was drawn inline there
 * first, at 32dp, which measured as the only control in the player under the touch floor.
 */
@Composable
private fun ToolsRow(state: PlayerState, actions: PlayerActions) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(PlayerTags.TOOLS),
        horizontalArrangement = Arrangement.spacedBy(barGap()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerButton(
            icon = CastivioIcons.Speed,
            label = stringResource(R.string.player_speed),
            tag = PlayerTags.SPEED,
            onClick = { actions.onSheet(Sheet.Settings) },
            text = speedLabel(state.speed),
            shrinkable = true,
        )
        PlayerButton(
            icon = CastivioIcons.Subtitles,
            label = stringResource(R.string.player_sheet_subtitles),
            tag = PlayerTags.SUBTITLES,
            onClick = { actions.onSheet(Sheet.Subtitles) },
        )
        PlayerButton(
            icon = CastivioIcons.AudioTrack,
            label = stringResource(R.string.player_sheet_audio),
            tag = PlayerTags.AUDIO,
            onClick = { actions.onSheet(Sheet.Audio) },
        )
        // One press, one change, and the button says which fit is on — the same shape as
        // the speed control beside it. It used to open the settings sheet onto a row that
        // printed "Fit" and did nothing, so the picture could not be corrected from
        // anywhere in the player.
        PlayerButton(
            icon = CastivioIcons.Aspect,
            label = stringResource(R.string.player_aspect),
            tag = PlayerTags.ASPECT,
            onClick = { actions.onAspect(nextAspect(state.aspect)) },
            text = aspectLabel(state.aspect),
            shrinkable = true,
        )

        if (state.request.isLive) {
            PlayerButton(
                icon = CastivioIcons.Guide,
                label = stringResource(R.string.player_guide),
                tag = PlayerTags.GUIDE,
                onClick = actions.onGuide,
            )
            PlayerButton(
                icon = CastivioIcons.Channels,
                label = stringResource(R.string.player_channels),
                tag = PlayerTags.CHANNELS,
                onClick = actions.onChannels,
            )
        }

        // Only when there is something to return *from*. Castivio never draws a disabled
        // control, so a stream at the live edge does not carry this at all.
        if (state.request.isLive && state.isTimeshifted) {
            PlayerButton(
                icon = CastivioIcons.Live,
                label = stringResource(R.string.player_to_live),
                tag = PlayerTags.TO_LIVE,
                onClick = actions.onReturnToLive,
                text = stringResource(R.string.player_to_live),
                shrinkable = true,
            )
        }

        Box(Modifier.weight(1f))

        PlayerButton(
            icon = CastivioIcons.Quality,
            label = stringResource(R.string.player_sheet_quality),
            tag = PlayerTags.QUALITY,
            onClick = { actions.onSheet(Sheet.Quality) },
        )
        PlayerButton(
            icon = CastivioIcons.Fullscreen,
            label = stringResource(R.string.player_fullscreen),
            tag = PlayerTags.FULLSCREEN,
            onClick = actions.onFullscreen,
        )
    }
}

/* ------------------------------------------------------------------- the control */

/**
 * One control, and the floor that makes it one.
 *
 * Every interactive thing on this screen is this composable, so "every target is at least
 * the frame's floor" is a property of one function rather than of thirty call sites. The
 * floor is [Sizing.minTarget], which is 48dp under a thumb and 56dp under a remote — the
 * distinction that was missed twice elsewhere in this product and found both times by eye
 * on a photograph.
 *
 * [shrinkable] is the tools-row concession: the *label* may be squeezed and ellipsised, the
 * target may not. `widthIn(min = …)` rather than `width(…)`, so the minimum survives the
 * squeeze.
 */
@Composable
internal fun RowScope.PlayerButton(
    icon: ImageVector,
    label: String,
    tag: String,
    onClick: () -> Unit,
    text: String? = null,
    mirror: Boolean = false,
    shrinkable: Boolean = false,
) {
    val colors = CastivioTheme.colors
    val target = Sizing.minTarget(CastivioTheme.device.isTv)
    val mirrored = mirror && LocalLayoutDirection.current == LayoutDirection.Rtl

    Row(
        modifier = Modifier
            .then(if (shrinkable) Modifier.weight(1f, fill = false) else Modifier)
            .defaultMinSize(minWidth = target, minHeight = target)
            .height(target)
            .clip(RoundedCornerShape(Radius.pill))
            .background(colors.glassFillStrong)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = if (text == null) Spacing.xs else Spacing.md)
            .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = colors.onBackground,
            modifier = Modifier
                .size(Sizing.iconMd)
                .scale(scaleX = if (mirrored) -1f else 1f, scaleY = 1f),
        )
        if (text != null) {
            Text(
                text = text,
                style = CastivioType.labelMedium,
                color = colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/* ---------------------------------------------------------------------- measures */

/** A hairline is one device-independent pixel, everywhere in the product. */
internal val HAIRLINE = 1.dp

/** Ten seconds, in both directions. The figure the icons draw. */
private const val JUMP_MS = 10_000L

private val TRACK_HEIGHT = 4.dp
private val PROGRESS_WIDTH = 96.dp
private val PROGRESS_HEIGHT = 3.dp
private val SKELETON_TITLE = 148.dp
private val SKELETON_WINDOW = 92.dp
private const val NEXT_WEIGHT = 0.9f

@Composable
@ReadOnlyComposable
internal fun barGap(): Dp = if (CastivioTheme.device.isTv) Spacing.lg else Spacing.sm

@Composable
@ReadOnlyComposable
internal fun barGapLarge(): Dp = if (CastivioTheme.device.isTv) Spacing.xl else Spacing.lg

/**
 * The play control, larger than its neighbours on purpose.
 *
 * The drawing puts it at 64dp on a handset and 80 on a television — the one control a thumb
 * finds without looking, and the one a remote lands on first.
 */
@Composable
@ReadOnlyComposable
private fun playSize(): Dp = if (CastivioTheme.device.isTv) 80.dp else 64.dp

/** The strip's reserved height. A constant, because "reserved" means it cannot vary. */
@Composable
@ReadOnlyComposable
private fun stripHeight(): Dp = if (CastivioTheme.device.isTv) 56.dp else 44.dp

/**
 * A duration as digits.
 *
 * Formatted here and not in composition for the reason `CLAUDE.md` gives, and hours are
 * dropped when there are none — "0:04:12" on a four-minute delay reads as a bug.
 */
internal fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** "1.0×" — a figure, so it is built rather than translated. */
internal fun speedLabel(speed: Float): String = "%.1f×".format(speed)
